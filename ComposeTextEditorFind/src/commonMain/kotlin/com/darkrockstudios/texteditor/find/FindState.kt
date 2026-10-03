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
	 * follows edits; if its text is deleted or the document replaced, this turns off.
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

	// Styles for highlighting
	private val matchStyle = FindMatchStyle()
	private val currentMatchStyle = FindCurrentMatchStyle()
	private val scopeStyle = FindScopeStyle()

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
			textState.addRichSpan(scope, scopeStyle)
		} else {
			removeScope()
		}
		inSelection = enabled
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
		textState.richSpanManager.getAllRichSpans().filter { it.style === scopeStyle }

	private fun scopeRange(): TextEditorRange? = scopeSpans().firstOrNull()?.range

	private fun removeScope() {
		scopeSpans().forEach { textState.removeRichSpan(it) }
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

		updateHighlights()
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

		updateHighlights()
		goToCurrentMatch()
	}

	/**
	 * Jump to a specific match by index.
	 */
	fun goToMatch(index: Int) {
		if (index < 0 || index >= _matches.size) return

		currentMatchIndex = index
		updateHighlights()
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
	 * @return true if a replacement was made, false if no current match
	 */
	fun replaceCurrent(replaceText: String): Boolean {
		if (currentMatchIndex < 0 || currentMatchIndex >= _matches.size) return false

		// An edit since the last search can have moved the match. Its highlight moved with it, so
		// replace what the user sees highlighted, and only while that is still a match.
		val highlighted = textState.richSpanManager.getAllRichSpans()
			.firstOrNull { it.style === currentMatchStyle }?.range
			?: _matches[currentMatchIndex]
		val match = highlighted.takeIf { it in findMatches() }
		if (match == null) {
			refreshSearch()
			return false
		}

		// Clear highlights before replacement
		clearHighlights()

		replaceRanges(listOf(match), replaceText)

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
	 * @return The number of replacements made
	 */
	fun replaceAll(replaceText: String): Int {
		if (query.isEmpty()) return 0
		val targets = findMatches().withoutOverlaps()
		if (targets.isEmpty()) {
			refreshSearch()
			return 0
		}

		clearHighlights()

		replaceRanges(targets, replaceText)

		// Clear matches since they're all replaced
		_matches.clear()
		currentMatchIndex = -1

		return targets.size
	}

	/**
	 * Replaces [targets], in document order and not overlapping, last to first so each
	 * replacement leaves the earlier ranges where they were, as one undo step. With [useRegex],
	 * group references in [replaceText] are expanded for each match. An edit at the edge of the
	 * find in selection scope would shrink it, so the scope is re-laid over what it covered, and
	 * the selection from before the search is carried along the same way.
	 */
	private fun replaceRanges(targets: List<TextEditorRange>, replaceText: String) {
		val replacements = if (useRegex) {
			textState.regexReplacements(targets, query, caseSensitive, wholeWord, replaceText)
		} else {
			targets.map { replaceText }
		}
		rememberOwnSelection()
		val before = currentSelectionBeforeSearch()?.let(::flatRange)
		textState.editGroup { replaceInGroup(targets.zip(replacements), before) }
		rememberSelectionBeforeSearch(before?.toRange())
	}

	private fun replaceInGroup(replacements: List<Pair<TextEditorRange, String>>, before: FlatRange?) {
		val scope = scopeRange()?.let(::flatRange)
		replacements.asReversed().forEach { (match, replacement) ->
			val matchStart = textState.getCharacterIndex(match.start)
			val matchEnd = textState.getCharacterIndex(match.end)
			// What landed, which line ending normalization or the input filter can make differ
			// from the replacement; a refused one leaves the match.
			val landed = textState.replace(match, styledReplacement(match, replacement))
				?.let { textState.getCharacterIndex(it.end) - matchStart } ?: (matchEnd - matchStart)
			scope?.follow(matchStart, matchEnd, landed)
			before?.follow(matchStart, matchEnd, landed)
		}
		if (scope != null) {
			textState.updateRichSpans(
				remove = scopeSpans(),
				add = listOfNotNull(scope.toRange()?.let { RichSpan(it, scopeStyle) }),
			)
		}
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
		if (!inSelection) return all
		val scope = scopeRange()
		if (scope == null) {
			inSelection = false
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
	 * Update RichSpan highlights for all matches in a single batched relayout.
	 */
	private fun updateHighlights() {
		val newSpans = _matches.mapIndexed { index, range ->
			val style = if (index == currentMatchIndex) currentMatchStyle else matchStyle
			RichSpan(range, style)
		}
		textState.updateRichSpans(remove = currentHighlightSpans(), add = newSpans)
	}

	/**
	 * Remove all find-related highlights.
	 */
	private fun clearHighlights() {
		val existing = currentHighlightSpans()
		if (existing.isNotEmpty()) {
			textState.updateRichSpans(remove = existing, add = emptyList())
		}
	}

	/**
	 * The highlight spans currently applied by this find session, identified by
	 * the two style instances this session owns. Read live from the manager so
	 * removal stays correct even after an edit transforms span positions.
	 */
	private fun currentHighlightSpans(): List<RichSpan> =
		textState.richSpanManager.getAllRichSpans().filter {
			it.style === matchStyle || it.style === currentMatchStyle
		}

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
