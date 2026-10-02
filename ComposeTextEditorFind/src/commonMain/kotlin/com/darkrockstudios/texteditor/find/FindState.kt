package com.darkrockstudios.texteditor.find

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.decorations
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * State management for the Find feature.
 *
 * @param textState The TextEditorState to search within
 * @param scope CoroutineScope for handling text change reactions
 */
@OptIn(FlowPreview::class)
class FindState(
	val textState: TextEditorState,
	private val scope: CoroutineScope
) {
	// Search configuration
	var query: String by mutableStateOf("")
		private set

	var caseSensitive: Boolean by mutableStateOf(false)
		private set

	/** Whether a match must stand as a whole word. */
	var wholeWord: Boolean by mutableStateOf(false)
		private set

	/** Whether [query] is a regular expression. */
	var useRegex: Boolean by mutableStateOf(false)
		private set

	/**
	 * Whether matches are limited to the selection captured by [toggleInSelection]. The scope
	 * follows edits; if its text is deleted or the document replaced, this turns off. An undo
	 * that brings back text the scope covered brings the scope back with it, and turns this
	 * back on if the deletion had turned it off.
	 */
	var inSelection: Boolean by mutableStateOf(false)
		private set

	/** Whether [query] is a regular expression that does not compile; it then finds nothing. */
	val isInvalidPattern: Boolean by derivedStateOf {
		useRegex && query.isNotEmpty() && !isValidFindPattern(query)
	}

	// Search results
	private val _matches = mutableStateListOf<TextEditorRange>()
	val matches: List<TextEditorRange> get() = _matches

	var currentMatchIndex: Int by mutableIntStateOf(-1)
		private set

	val matchCount: Int get() = _matches.size

	/**
	 * The decoration layer this find draws its match highlights and its in-selection scope on,
	 * apart from every other owner's (see `docs/design/decorations.md`).
	 */
	internal val layer = DecorationLayer("find")

	private val matchStyle = FindMatchStyle(DefaultFindMatchColor, layer)
	private val currentMatchStyle = FindCurrentMatchStyle(DefaultFindCurrentMatchColor, layer)
	private val scopeStyle = FindScopeStyle(layer)

	/**
	 * The [TextEditorState.textLines] the highlights were last laid in, null once cleared, and
	 * the match then current: while the text is still that, they lie where [matches] say.
	 */
	private var highlightedLines: List<AnnotatedString>? = null
	private var highlightedIndex = -1

	/**
	 * The user's last own selection this session, as it stood before a search or a replacement
	 * moved the selection onto a match. Clearing the selection keeps it. Find's own replacements
	 * carry it along; any other edit drops it (see [selectionBeforeSearchLines]).
	 */
	private var selectionBeforeSearch: TextEditorRange? = null

	/** The [TextEditorState.textLines] [selectionBeforeSearch] was taken in or carried to. */
	private var selectionBeforeSearchLines: List<AnnotatedString>? = null

	/**
	 * The selection as this session last left it: on a match, or none after [clearSearch]. The
	 * selector keeps its range object until the selection changes, so an identity check still
	 * recognises it once a query with no results empties [matches], and a user who selects the
	 * same range again after selecting something else makes a new object.
	 */
	private var sessionSelection: TextEditorRange? = null

	/**
	 * The scope, in flat character indices, as it stood in each document text seen while
	 * [inSelection] was on, newest last, keyed by the text. Undo restores no decoration, so the
	 * scope span is gone or moved once an undo brings back text it covered; the text identifies
	 * the state to restore it from. Edits are not tied to history entries, so the text is the
	 * one key an undo and the edits it reverts share.
	 */
	private val scopeHistory = LinkedHashMap<ScopeKey, Pair<Int, Int>>()

	/** The [TextEditorState.documentGeneration] [scopeHistory] was recorded in. */
	private var scopeHistoryGeneration = textState.documentGeneration.value

	/** Whether an edit deleted the scope's text while [inSelection] was on. */
	private var scopeLostToEdit = false

	// Job for debounced search on text changes
	private var searchUpdateJob: Job? = null

	/** The [TextEditorState.documentGeneration] [matches] were found in. */
	private var matchesGeneration = textState.documentGeneration.value

	init {
		searchUpdateJob = scope.launch {
			// Listen for text changes and update search results
			launch {
				textState.editOperations
					.debounce(300.milliseconds)
					.collect {
						if (query.isNotEmpty()) {
							refreshSearch()
						}
					}
			}
			launch {
				textState.editOperations.collect {
					if (syncScope()) refreshSearch()
				}
			}
			// A whole-document replacement (setText, setDocument) emits no edit, so
			// without this the matches would keep describing the old document.
			textState.documentGeneration.collect { generation ->
				if (generation != matchesGeneration && query.isNotEmpty()) {
					refreshSearch()
				}
			}
		}
	}

	/**
	 * Execute a search with the given query.
	 * Updates highlights and jumps to the nearest match.
	 */
	fun search(newQuery: String) {
		rememberOwnSelection()
		query = newQuery

		if (newQuery.isEmpty()) {
			clearSearch()
			return
		}

		// Find all matches
		val results = findMatches()
		matchesGeneration = textState.documentGeneration.value
		_matches.clear()
		_matches.addAll(results)

		if (results.isEmpty()) {
			currentMatchIndex = -1
			clearHighlights()
			return
		}

		// Nearest the selection (the current match, while typing extends a query), else the cursor
		val origin = textState.selector.selection?.start ?: textState.cursor.position
		currentMatchIndex = findNearestMatchIndex(results, origin)

		// Add highlights
		updateHighlights()

		// Navigate to current match
		goToCurrentMatch()
	}

	/**
	 * Toggle case sensitivity and re-run search if there's an active query.
	 */
	fun toggleCaseSensitive(sensitive: Boolean) {
		if (caseSensitive != sensitive) {
			caseSensitive = sensitive
			rerunSearch()
		}
	}

	/**
	 * Toggle whole-word matching and re-run search if there's an active query.
	 */
	fun toggleWholeWord(enabled: Boolean) {
		if (wholeWord != enabled) {
			wholeWord = enabled
			rerunSearch()
		}
	}

	/**
	 * Toggle regular expression matching and re-run search if there's an active query.
	 * See [isInvalidPattern].
	 */
	fun toggleRegex(enabled: Boolean) {
		if (useRegex != enabled) {
			useRegex = enabled
			rerunSearch()
		}
	}

	/**
	 * Limit matches to the current selection, or search the whole document again. While the
	 * selection is a match this session selected, or there is none, the user's last own selection
	 * is used instead, as long as the only edits since were this session's replacements. Enabling
	 * does nothing when there is no such selection.
	 */
	fun toggleInSelection(enabled: Boolean) {
		if (inSelection == enabled) return
		if (enabled) {
			val selection = textState.selector.selection
			val scope = if (selection == null || isSessionSelection(selection)) {
				currentSelectionBeforeSearch()
			} else {
				selection
			}
			if (scope == null || scope.start == scope.end) return
			// Kept whole, not a piece per line as setDecorations would keep it, so it follows
			// edits as one range.
			textState.addRichSpan(scope, scopeStyle)
		} else {
			removeScope()
		}
		forgetScopeHistory()
		inSelection = enabled
		syncScope()
		rerunSearch()
	}

	/**
	 * The query to seed a new search with: the selected text when it is a single line, escaped
	 * when [useRegex] is on so it still finds itself.
	 */
	internal fun selectionSeed(): String? {
		val selection = textState.selector.selection ?: return null
		if (!selection.isSingleLine() || selection.start == selection.end) return null
		val text = textState.selector.getSelectedText().text
		return if (useRegex) text.replace(REGEX_METACHARACTER) { "\\" + it.value } else text
	}

	private fun rememberOwnSelection() {
		val selection = textState.selector.selection ?: return
		if (!isSessionSelection(selection) && selection.start != selection.end) {
			rememberSelectionBeforeSearch(selection)
		}
	}

	private fun rememberSelectionBeforeSearch(selection: TextEditorRange?) {
		selectionBeforeSearch = selection
		selectionBeforeSearchLines = if (selection != null) textState.textLines else null
	}

	/**
	 * The line list changes identity with every text edit and keeps it through span changes. A
	 * formatting change or a block normalization replaces it too, which drops the range needlessly
	 * but never keeps a stale one.
	 */
	private fun currentSelectionBeforeSearch(): TextEditorRange? =
		selectionBeforeSearch?.takeIf { selectionBeforeSearchLines === textState.textLines }

	private fun scopeSpans(): List<RichSpan> =
		textState.decorations(layer).filter { it.style === scopeStyle }

	private fun scopeRange(): TextEditorRange? = scopeSpans().firstOrNull()?.range

	private fun removeScope() {
		scopeSpans().forEach { textState.removeRichSpan(it) }
	}

	/**
	 * Records the scope against the current text or, after an undo, lays it back where this text
	 * last had it. Returns whether it laid the scope back.
	 */
	private fun syncScope(): Boolean {
		val generation = textState.documentGeneration.value
		if (generation != scopeHistoryGeneration) {
			scopeHistoryGeneration = generation
			forgetScopeHistory()
		}
		if (!inSelection && !scopeLostToEdit) return false
		val key = documentKey()
		val spans = scopeSpans()
		val current = spans.firstOrNull()?.range?.let { textState.getCharacterIndex(it.start) to textState.getCharacterIndex(it.end) }
		val recorded = scopeHistory[key]
		// Only an undo leaves something to redo; a new edit that happens to bring back an
		// earlier text is the user's own, and keeps the scope where it now is.
		if (recorded != null && recorded != current && textState.canRedo && recorded.second <= key.length) {
			val restored = TextEditorRange(
				textState.getOffsetAtCharacter(recorded.first),
				textState.getOffsetAtCharacter(recorded.second),
			)
			textState.updateRichSpans(remove = spans, add = listOf(RichSpan(restored, scopeStyle)))
			inSelection = true
			scopeLostToEdit = false
			return true
		}
		if (current != null) {
			scopeHistory.remove(key)
			scopeHistory[key] = current
			if (scopeHistory.size > SCOPE_HISTORY_LIMIT) scopeHistory.remove(scopeHistory.keys.first())
		}
		return false
	}

	private fun forgetScopeHistory() {
		scopeHistory.clear()
		scopeLostToEdit = false
	}

	/** The document text's length and 64-bit FNV-1a hash, read off the lines without joining them. */
	private fun documentKey(): ScopeKey {
		var hash = FNV_OFFSET_BASIS
		var length = 0
		textState.textLines.forEachIndexed { index, line ->
			if (index > 0) {
				hash = (hash xor '\n'.code.toLong()) * FNV_PRIME
				length++
			}
			val text = line.text
			for (char in text) hash = (hash xor char.code.toLong()) * FNV_PRIME
			length += text.length
		}
		return ScopeKey(length, hash)
	}

	private fun rerunSearch() {
		if (query.isNotEmpty()) {
			search(query)
		}
	}

	/**
	 * Navigate to the next match.
	 */
	fun findNext() {
		if (_matches.isEmpty()) return

		currentMatchIndex = if (currentMatchIndex < _matches.lastIndex) {
			currentMatchIndex + 1
		} else {
			0 // Wrap around
		}

		moveCurrentHighlight()
		goToCurrentMatch()
	}

	/**
	 * Navigate to the previous match.
	 */
	fun findPrevious() {
		if (_matches.isEmpty()) return

		currentMatchIndex = if (currentMatchIndex > 0) {
			currentMatchIndex - 1
		} else {
			_matches.lastIndex // Wrap around
		}

		moveCurrentHighlight()
		goToCurrentMatch()
	}

	/**
	 * Jump to a specific match by index.
	 */
	fun goToMatch(index: Int) {
		if (index < 0 || index >= _matches.size) return

		currentMatchIndex = index
		moveCurrentHighlight()
		goToCurrentMatch()
	}

	/**
	 * Clear the search - remove all highlights and reset state.
	 */
	fun clearSearch() {
		query = ""
		clearHighlights()
		_matches.clear()
		currentMatchIndex = -1
		textState.selector.clearSelection()
		noteSessionSelection()
	}

	/**
	 * End the find session: remove all highlights and reset the query and results, but keep the
	 * selection so the last match found stays selected in the editor.
	 */
	fun close() {
		query = ""
		removeScope()
		inSelection = false
		forgetScopeHistory()
		rememberSelectionBeforeSearch(null)
		sessionSelection = null
		clearHighlights()
		_matches.clear()
		currentMatchIndex = -1
	}

	/**
	 * Replace the current match with the given text and move to the next match.
	 * The replacement takes the styling at the start of the text it replaces.
	 * @param replaceText The text to replace with. With [useRegex], `$1`, `${name}` and the
	 * other group references of Kotlin's `Regex.replace` are expanded, and `\n` and `\t`
	 * insert a line break and a tab; see the module docs.
	 * @return true if a replacement was made, false if there is no current match or the editor's
	 * input filter refused the replacement, which leaves the match current
	 */
	fun replaceCurrent(replaceText: String): Boolean {
		if (currentMatchIndex < 0 || currentMatchIndex >= _matches.size) return false

		// An edit since the last search can have moved the match. Its highlight moved with it, so
		// replace what the user sees highlighted, and only while that is still a match.
		val highlighted = textState.decorations(layer)
			.firstOrNull { it.style === currentMatchStyle }?.range
			?: _matches[currentMatchIndex]
		val current = findMatches()
		val match = highlighted.takeIf { it in current }
		if (match == null) {
			refreshSearch()
			return false
		}

		// Clear highlights before replacement
		clearHighlights()

		if (replaceRanges(listOf(match), replaceText).isNotEmpty()) {
			// The input filter refused it, so the document is as it was: the match stays current.
			_matches.clear()
			_matches.addAll(current)
			currentMatchIndex = current.indexOf(match)
			updateHighlights()
			return false
		}

		// Re-run the search to update matches
		// The debounced search will also run, but we do it immediately for responsiveness
		val results = findMatches()
		_matches.clear()
		_matches.addAll(results)

		// The first match after the replacement (the cursor's spot), which can itself contain the query
		val afterReplacement = textState.cursor.position
		currentMatchIndex = if (_matches.isEmpty()) {
			-1
		} else {
			_matches.indexOfFirst { it.start >= afterReplacement }.takeIf { it >= 0 } ?: 0
		}

		// Update highlights and navigate
		updateHighlights()
		if (_matches.isNotEmpty()) {
			goToCurrentMatch()
		}

		return true
	}

	/**
	 * Replace all matches with the given text, each taking the styling at the start of the text
	 * it replaces. Matches are found afresh in the current text; where matches overlap, only the
	 * first is replaced.
	 * @param replaceText The text to replace with, group references expanded as in [replaceCurrent]
	 * @return The number of replacements made. Matches the editor's input filter refused are not
	 * counted and stay as the matches, the first of them current.
	 */
	fun replaceAll(replaceText: String): Int {
		if (query.isEmpty()) return 0
		val targets = findMatches().withoutOverlaps()
		if (targets.isEmpty()) {
			refreshSearch()
			return 0
		}

		clearHighlights()

		val refused = replaceRanges(targets, replaceText)

		// What the input filter refused stays a match, as long as the other replacements left it one.
		val stillMatching = if (refused.isEmpty()) emptyList() else refused.intersect(findMatches().toSet()).toList()
		_matches.clear()
		_matches.addAll(stillMatching)
		currentMatchIndex = if (stillMatching.isEmpty()) -1 else 0
		updateHighlights()
		goToCurrentMatch()

		return targets.size - refused.size
	}

	/**
	 * Replaces [targets], in document order and not overlapping, last to first so each
	 * replacement leaves the earlier ranges where they were, as one undo step. With [useRegex],
	 * group references in [replaceText] are expanded for each match. An edit at the edge of the
	 * find in selection scope would shrink it, so the scope is re-laid over what it covered, and
	 * the selection from before the search is carried along the same way. Returns the targets the
	 * input filter refused, in document order, where they are once the rest have landed.
	 */
	private fun replaceRanges(targets: List<TextEditorRange>, replaceText: String): List<TextEditorRange> {
		val replacements = if (useRegex) {
			textState.regexReplacements(targets, query, caseSensitive, wholeWord, replaceText)
		} else {
			targets.map { replaceText }
		}
		rememberOwnSelection()
		val before = currentSelectionBeforeSearch()?.let(::flatRange)
		val refused = textState.editGroup { replaceInGroup(targets.zip(replacements), before) }
		rememberSelectionBeforeSearch(before?.toRange())
		return refused.mapNotNull { it.toRange() }
	}

	/** Returns the matches the input filter refused, where they are once the rest have landed. */
	private fun replaceInGroup(replacements: List<Pair<TextEditorRange, String>>, before: FlatRange?): List<FlatRange> {
		val scope = scopeRange()?.let(::flatRange)
		val refused = mutableListOf<FlatRange>()
		replacements.asReversed().forEach { (match, replacement) ->
			val matchStart = textState.getCharacterIndex(match.start)
			val matchEnd = textState.getCharacterIndex(match.end)
			// What landed, which line ending normalization or the input filter can make differ
			// from the replacement; a refused one leaves the match.
			val landedRange = textState.replace(match, styledReplacement(match, replacement))
			val landed = landedRange?.let { textState.getCharacterIndex(it.end) - matchStart } ?: (matchEnd - matchStart)
			refused.forEach { it.follow(matchStart, matchEnd, landed) }
			if (landedRange == null) refused += FlatRange(matchStart, matchEnd)
			scope?.follow(matchStart, matchEnd, landed)
			before?.follow(matchStart, matchEnd, landed)
		}
		if (scope != null) {
			textState.updateRichSpans(
				remove = scopeSpans(),
				add = listOfNotNull(scope.toRange()?.let { RichSpan(it, scopeStyle) }),
			)
		}
		return refused.asReversed()
	}

	private fun flatRange(range: TextEditorRange) =
		FlatRange(textState.getCharacterIndex(range.start), textState.getCharacterIndex(range.end))

	private fun FlatRange.toRange(): TextEditorRange? =
		if (start < end) TextEditorRange(textState.getOffsetAtCharacter(start), textState.getOffsetAtCharacter(end)) else null

	/**
	 * A range in flat character indices, carried across replacements made last to first. A
	 * replacement inside it stays inside; one across an edge is left out, since not all of the
	 * text it replaced was in the range.
	 */
	private class FlatRange(var start: Int, var end: Int) {
		fun follow(matchStart: Int, matchEnd: Int, replacementLength: Int) {
			val delta = replacementLength - (matchEnd - matchStart)
			when {
				matchStart >= end -> Unit
				matchEnd <= start -> {
					start += delta
					end += delta
				}

				else -> {
					val replacementEnd = matchStart + replacementLength
					if (matchStart < start) start = replacementEnd
					end = if (matchEnd > end) maxOf(start, matchStart) else end + delta
				}
			}
		}
	}

	private fun noteSessionSelection() {
		sessionSelection = textState.selector.selection
	}

	private fun isSessionSelection(selection: TextEditorRange?): Boolean = selection === sessionSelection

	/** [replaceText] styled like the character at the start of [range]. */
	private fun styledReplacement(range: TextEditorRange, replaceText: String): AnnotatedString {
		val line = textState.textLines[range.start.line]
		val char = range.start.char
		return buildAnnotatedString {
			append(replaceText)
			line.spanStyles
				.filter { it.start <= char && char < it.end }
				.forEach { addStyle(it.item, 0, replaceText.length) }
		}
	}

	private fun findMatches(): List<TextEditorRange> {
		val all = textState.findAll(query, caseSensitive, wholeWord, useRegex)
		syncScope()
		if (!inSelection) return all
		val scope = scopeRange()
		if (scope == null) {
			inSelection = false
			scopeLostToEdit = scopeHistory.isNotEmpty()
			return all
		}
		return all.filter { it.start >= scope.start && it.end <= scope.end }
	}

	/**
	 * Cancel any ongoing operations. Call this when done with FindState.
	 */
	fun dispose() {
		searchUpdateJob?.cancel()
		close()
		clearSearch()
	}

	/**
	 * Refresh search results (e.g., after text changes).
	 */
	private fun refreshSearch() {
		if (query.isEmpty()) return

		// Save current match position for continuity
		val previousMatchStart = if (currentMatchIndex >= 0 && currentMatchIndex < _matches.size) {
			_matches[currentMatchIndex].start
		} else null

		// Re-search
		val results = findMatches()
		matchesGeneration = textState.documentGeneration.value
		_matches.clear()
		_matches.addAll(results)

		if (results.isEmpty()) {
			currentMatchIndex = -1
			clearHighlights()
			return
		}

		// Try to stay at the same position, or find nearest
		currentMatchIndex = if (previousMatchStart != null) {
			findNearestMatchIndex(results, previousMatchStart)
		} else {
			0
		}

		updateHighlights()
	}

	/**
	 * Lays a highlight on every match, the current one in its own style, in one redraw. Only
	 * the lines whose highlights differ from the matches' are laid again, each with its
	 * highlights in match order.
	 */
	private fun updateHighlights() {
		val existing = highlightSpans().groupBy { it.range.start.line }
		val wanted = HashMap<Int, MutableList<RichSpan>>()
		_matches.indices.forEach { index -> wanted.getOrPut(_matches[index].start.line) { ArrayList() } += highlight(index) }
		val remove = ArrayList<RichSpan>()
		val add = ArrayList<RichSpan>()
		for (line in existing.keys + wanted.keys) {
			val have = existing[line].orEmpty()
			val want = wanted[line].orEmpty()
			if (have != want) {
				remove += have
				add += want
			}
		}
		textState.updateRichSpans(remove = remove, add = add)
		highlightedLines = textState.textLines
		highlightedIndex = currentMatchIndex
	}

	/**
	 * Moves the current highlight to [currentMatchIndex] after a step through [matches]. While
	 * the text is the one the highlights were laid in, only the lines it leaves and reaches
	 * change; after an edit every highlight is laid where [matches] say, as by a search.
	 */
	private fun moveCurrentHighlight() {
		val lineCount = textState.textLines.size
		val from = _matches.getOrNull(highlightedIndex)?.takeIf { it.start.line < lineCount }
		val to = _matches.getOrNull(currentMatchIndex)?.takeIf { it.start.line < lineCount }
		// A match past the last line, found before an edit shortened the text, was clamped
		// onto the last line when laid; only laying them all again finds it there.
		if (highlightedLines !== textState.textLines || from == null || to == null) {
			updateHighlights()
			return
		}
		val remove = ArrayList<RichSpan>()
		val add = ArrayList<RichSpan>()
		for (line in setOf(from.start.line, to.start.line)) {
			remove += textState.decorations(layer, line..line).filter { it.isHighlight() }
			add += highlightsOn(line)
		}
		textState.updateRichSpans(remove = remove, add = add)
		highlightedIndex = currentMatchIndex
	}

	/** The highlights of the matches on [line], in match order. */
	private fun highlightsOn(line: Int): List<RichSpan> {
		var low = 0
		var high = _matches.size
		while (low < high) {
			val mid = (low + high) ushr 1
			if (_matches[mid].start.line < line) low = mid + 1 else high = mid
		}
		val spans = ArrayList<RichSpan>()
		var index = low
		while (index < _matches.size && _matches[index].start.line == line) spans += highlight(index++)
		return spans
	}

	private fun highlight(index: Int) =
		RichSpan(_matches[index], if (index == currentMatchIndex) currentMatchStyle else matchStyle)

	/**
	 * Remove all find-related highlights.
	 */
	private fun clearHighlights() {
		textState.updateRichSpans(remove = highlightSpans(), add = emptyList())
		highlightedLines = null
	}

	/** The highlights as edits have moved them, read off [layer] so removal stays correct after an edit. */
	private fun highlightSpans(): List<RichSpan> = textState.decorations(layer).filter { it.isHighlight() }

	private fun RichSpan.isHighlight(): Boolean = style === matchStyle || style === currentMatchStyle

	/**
	 * Navigate to the current match - scroll and select.
	 */
	private fun goToCurrentMatch() {
		if (currentMatchIndex < 0 || currentMatchIndex >= _matches.size) return

		val match = _matches[currentMatchIndex]

		textState.selector.updateSelection(match.start, match.end)
		noteSessionSelection()

		// Scroll to make it visible
		textState.scrollManager.scrollToPosition(match.start)
	}
}

private val REGEX_METACHARACTER = Regex("""[\\^$.|?*+()\[\]{}]""")

/** A document text's identity for [FindState]'s scope history: its length and hash. */
private data class ScopeKey(val length: Int, val hash: Long)

/**
 * Texts the scope history keeps, one per edit seen (a typed character is one): an undo back to
 * a text older than this does not bring the scope back.
 */
private const val SCOPE_HISTORY_LIMIT = 1000

private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
