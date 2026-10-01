package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.EditorLineLimits
import com.darkrockstudios.texteditor.RichSpanClick
import com.darkrockstudios.texteditor.RichSpanClickEventListener
import com.darkrockstudios.texteditor.RichSpanClickListener
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.contextmenu.ContextMenuItem
import com.darkrockstudios.texteditor.contextmenu.ContextMenuStrings
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.focusBorder
import com.darkrockstudios.texteditor.input.KeyBindings
import com.darkrockstudios.texteditor.input.LocalKeyBindings
import com.darkrockstudios.texteditor.rememberTextEditorStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.diagnostics.DiagnosticStyle
import com.darkrockstudios.texteditor.spellcheck.diagnostics.TextDiagnosticsState
import com.darkrockstudios.texteditor.spellcheck.utils.debounceUntilQuiescent
import com.darkrockstudios.texteditor.spellcheck.utils.debounceUntilQuiescentWithBatch
import com.darkrockstudios.texteditor.spellcheck.utils.endWhenInsertedAt
import com.darkrockstudios.texteditor.state.SpanClickType
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.WordSegment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private val DefaultContentPadding = PaddingValues(start = 8.dp)

/**
 * A drop-in text editor composable with integrated spell checking.
 *
 * Wraps [BasicTextEditor] and overlays spell-check decorations driven by a [SpellCheckState].
 * Edits are observed reactively: affected spans are invalidated immediately and re-checked once
 * typing goes quiet, and replacing the document wholesale re-checks all of it. Secondary
 * clicks and taps on a flagged span open a context menu of
 * [Suggestion][com.darkrockstudios.texteditor.spellcheck.api.Suggestion]s for the
 * misspelled word or sentence-level [Correction], as do Shift+F10 and the Menu key with the
 * caret in one.
 *
 * @param spellChecker The [EditorSpellChecker] backing spell checks; used to build a default
 *   [state] when none is provided. May be `null` to disable checking.
 * @param state The [SpellCheckState] coordinating spell checking over the underlying
 *   [TextEditorState]. Defaults to a remembered state built from [spellChecker].
 * @param modifier The [Modifier] applied to the editor surface.
 * @param contentPadding Padding applied around the editor content.
 * @param enabled Whether the editor accepts input and focus. A disabled editor offers no
 *   spell check menu.
 * @param readOnly Shows the caret for navigation and selection but takes no edits; see
 *   [BasicTextEditor]. The menu on a flagged span offers Ignore, Add to dictionary and
 *   [spellCheckMenuItems], but no corrections or fixes.
 * @param lineLimits Fills the height given, grows with the text between a minimum and
 *   maximum number of lines, or keeps it to one row that scrolls sideways; see
 *   [BasicTextEditor].
 * @param autoFocus Whether the editor requests focus on first composition.
 * @param style The [TextEditorStyle] controlling appearance.
 * @param contextMenuStrings Localized strings for the built-in context menu.
 * @param spellCheckStrings Localized strings for the spell check menu.
 * @param onAddToDictionary Adds a word to the host's dictionary. When set, the menu on a flagged
 *   word offers "Add to dictionary", which calls this and stops flagging the word for the session,
 *   so it clears at once however the host's dictionary reaches the checker. It receives the word
 *   as flagged, capitals included; the session matches it across case as
 *   [SpellCheckState.ignoreWord] does.
 * @param spellCheckMenuItems Host items for the context menu opened on a flagged span, rendered
 *   after the built-in "Ignore" and "Add to dictionary" in a group below the suggestions. For a misspelled
 *   word they appear together with the suggestions once those have loaded. Not consulted while
 *   the editor is disabled. Read when the menu opens, so it may close over changing state.
 * @param diagnostics Underlines from a checker other than spelling, such as grammar, kept up to date
 *   as the text changes. Its [TextDiagnosticsState.textState] must be [state]'s. A secondary click
 *   or tap on one, or Shift+F10 or the Menu key with the caret in it, opens a menu of its
 *   message and fixes.
 * @param onRichSpanClick Optional listener for clicks on other rich spans; spell-check and
 *   diagnostic spans are handled internally.
 * @param onRichSpanClickEvent The same clicks as [onRichSpanClick], with the modifier keys
 *   that were held.
 * @param onLinkClick Opens a link's URL on Ctrl+click, or Cmd+click under the macOS
 *   [keyBindings]; see [BasicTextEditor].
 * @param keyBindings Chord-to-command mapping, defaulting to [LocalKeyBindings].
 * @param contentDescription The editor's label for accessibility services; see
 *   [BasicTextEditor].
 * @param softWrap Whether lines wrap at the editor's width; with `false` a line stays one
 *   row and the editor scrolls sideways. See [BasicTextEditor].
 */
@Composable
fun SpellCheckingTextEditor(
	spellChecker: EditorSpellChecker? = null,
	state: SpellCheckState = rememberSpellCheckState(spellChecker),
	modifier: Modifier = Modifier,
	contentPadding: PaddingValues = DefaultContentPadding,
	enabled: Boolean = true,
	autoFocus: Boolean = false,
	style: TextEditorStyle = rememberTextEditorStyle(),
	contextMenuStrings: ContextMenuStrings = ContextMenuStrings.Default,
	spellCheckStrings: SpellCheckStrings = SpellCheckStrings.Default,
	onAddToDictionary: ((String) -> Unit)? = null,
	spellCheckMenuItems: (SpellCheckItem) -> List<ContextMenuItem> = { emptyList() },
	diagnostics: TextDiagnosticsState? = null,
	onRichSpanClick: RichSpanClickListener? = null,
	onRichSpanClickEvent: RichSpanClickEventListener? = null,
	onLinkClick: ((url: String) -> Unit)? = null,
	keyBindings: KeyBindings = LocalKeyBindings.current,
	contentDescription: String? = null,
	readOnly: Boolean = false,
	lineLimits: EditorLineLimits = EditorLineLimits.Fill,
	softWrap: Boolean = true,
) {
	// Corrections and fixes edit the text, so they follow this; Ignore does not. Read when
	// an item is picked too, since a menu can outlive the editability it opened with.
	val editable = enabled && !readOnly
	val canEdit by rememberUpdatedState(editable)
	val contextMenuState = remember { TextEditorContextMenuState() }
	val wordVisibilityBuffer = dpToPx(35.dp)

	// Below the click, which is in the text's coordinates, so the menu leaves the word in view.
	fun below(offset: Offset) = Offset(offset.x, offset.y + wordVisibilityBuffer)

	val coroutineScope = rememberCoroutineScope()
	val suggestionJob = remember { mutableStateOf<Job?>(null) }

	LaunchedEffect(state) {
		state.textState.documentGeneration
			.collect { generation ->
				if (generation != state.fullCheckGeneration) state.runFullSpellCheck()
			}
	}

	LaunchedEffect(state) {
		state.textState.editOperationBursts.collect(state::invalidateSpellCheckSpans)
	}

	LaunchedEffect(state) {
		val text = state.textState
		// Each edit with the document it addressed: a batch can run from before a replacement to after it.
		text.editOperationBursts
			.transform { burst ->
				val generation = text.documentGeneration.value
				burst.forEach { emit(it to generation) }
			}
			.debounceUntilQuiescentWithBatch(500.milliseconds) { BatchText(text.textLines, text.documentGeneration.value) }
			.collect { (edits, batchText) ->
				// A document replaced since has its own full check.
				if (batchText.generation != text.documentGeneration.value) return@collect
				val operations = edits.mapNotNull { (operation, generation) -> operation.takeIf { generation == batchText.generation } }
				computeAffectedRanges(operations).forEach { range ->
					state.runPartialSpellCheck(range, batchText.lines)
				}
			}
	}

	if (diagnostics != null) {
		LaunchedEffect(diagnostics) {
			diagnostics.textState.documentGeneration.collect { generation ->
				if (generation != diagnostics.refreshGeneration) diagnostics.refresh()
			}
		}
		LaunchedEffect(diagnostics) {
			diagnostics.textState.editOperationBursts.collect { diagnostics.invalidate(it) }
		}
		LaunchedEffect(diagnostics) {
			diagnostics.textState.editOperations.debounceUntilQuiescent(500.milliseconds).collect { diagnostics.refresh() }
		}
	}

	fun showDiagnosticMenu(span: RichSpan, style: DiagnosticStyle, show: ShowMenu) {
		val message = ContextMenuItem(label = style.message, enabled = false, onClick = {})
		val fixes = if (!editable) emptyList() else style.fixes.map { fix ->
			ContextMenuItem(label = fix.label, enabled = true, onClick = { if (canEdit) diagnostics?.applyFix(span, fix.replacement) })
		}
		show(listOf(message) + fixes, emptyList())
	}

	fun createSpellSuggestionItems(
		item: SpellCheckItem,
		suggestions: List<com.darkrockstudios.texteditor.spellcheck.api.Suggestion>
	): List<ContextMenuItem> {
		return if (suggestions.isNotEmpty()) {
			suggestions.map { suggestion ->
				ContextMenuItem(
					label = suggestion.term,
					enabled = true,
					onClick = {
						if (canEdit) when (item) {
							is SpellCheckItem.MisspelledWord -> {
								state.correctSpelling(item.segment, suggestion.term)
							}

							is SpellCheckItem.SentenceIssue -> {
								state.applySentenceCorrection(item.correction, suggestion.term)
							}
						}
					}
				)
			}
		} else {
			listOf(
				ContextMenuItem(
					label = spellCheckStrings.noSuggestions,
					enabled = false,
					onClick = { }
				)
			)
		}
	}

	fun showSpellCheckMenu(spellCheckItem: SpellCheckItem, show: ShowMenu) {
		val flagged = when (spellCheckItem) {
			is SpellCheckItem.MisspelledWord -> spellCheckItem.segment.text
			is SpellCheckItem.SentenceIssue -> spellCheckItem.correction.originalText
		}
		val builtInItems = buildList {
			add(ContextMenuItem(label = spellCheckStrings.ignore) { state.ignoreWord(flagged) })
			if (onAddToDictionary != null && flagged.none(Char::isWhitespace)) {
				add(
					ContextMenuItem(label = spellCheckStrings.addToDictionary) {
						// The host's dictionary gets the spelling later lookups use.
						onAddToDictionary(flagged.forLookup())
						state.accept(flagged)
					}
				)
			}
		}
		val hostItems = builtInItems + spellCheckMenuItems(spellCheckItem)
		if (!editable) {
			show(emptyList(), hostItems)
			return
		}
		when (spellCheckItem) {
			is SpellCheckItem.MisspelledWord -> {
				val placeholder = listOf(ContextMenuItem(label = spellCheckStrings.loading, enabled = false, onClick = {}))
				show(placeholder, emptyList())
				// Host items arrive with the suggestions: shown under the placeholder, they
				// would move under the pointer when it is replaced.
				suggestionJob.value = coroutineScope.launch {
					val suggestions = state.getSuggestions(spellCheckItem.segment.text)
					// Dismissed, or showing other items, while the lookup ran. The position is read
					// back: the editor re-anchors a right-click's menu to the pointer after the click.
					val position = contextMenuState.menuPosition.value
					if (position == null || contextMenuState.extraItems.value !== placeholder) return@launch
					contextMenuState.showMenu(
						position,
						if (canEdit) createSpellSuggestionItems(spellCheckItem, suggestions) else emptyList(),
						hostItems,
					)
				}
			}

			is SpellCheckItem.SentenceIssue -> {
				val items = createSpellSuggestionItems(spellCheckItem, spellCheckItem.correction.suggestions)
				show(items, hostItems)
			}
		}
	}

	fun cancelSuggestions() {
		suggestionJob.value?.cancel()
		suggestionJob.value = null
	}

	/** Opens the menu for [flag], a spell check or diagnostic span; false when it has none. */
	fun showFlagMenu(flag: RichSpan, show: ShowMenu): Boolean {
		// A disabled editor offers nothing it cannot apply.
		if (!enabled) return false
		val diagnostic = flag.style as? DiagnosticStyle
		if (diagnostic != null) {
			if (diagnostics == null) return false
			cancelSuggestions()
			showDiagnosticMenu(flag, diagnostic, show)
			return true
		}
		val spellCheckItem = when (val flagged = state.handleSpanClick(flag)) {
			is WordSegment -> SpellCheckItem.MisspelledWord(flagged)
			is Correction -> SpellCheckItem.SentenceIssue(flagged)
			else -> return false
		}
		cancelSuggestions()
		showSpellCheckMenu(spellCheckItem, show)
		return true
	}

	// Shift+F10, the Menu key and accessibility services open the menu below the caret; a
	// flag there fills it with its own items. A selection must lie within the flag.
	val fillMenuAtCaret by rememberUpdatedState { text: TextEditorState ->
		val position = contextMenuState.menuPosition.value
		if (position != null) {
			cancelSuggestions()
			val selection = text.selector.selection
			text.flagsAt(text.cursorPosition)
				.filter { flag ->
					selection == null ||
						(flag.range.start isBeforeOrEqual selection.start && flag.range.end isAfterOrEqual selection.end)
				}
				.any { flag -> showFlagMenu(flag) { items, trailing -> contextMenuState.showMenu(position, items, trailing) } }
		}
	}
	DisposableEffect(contextMenuState) {
		contextMenuState.onOpenedAtCaret = { text -> fillMenuAtCaret(text) }
		onDispose { contextMenuState.onOpenedAtCaret = null }
	}

	fun onSpanClick(click: RichSpanClick): Boolean {
		val (span, type, offset) = click
		val hostSpan = !span.isFlag()
		// Spell check and diagnostic spans are handled here; the host hears about its own.
		fun passOn(): Boolean {
			if (!hostSpan) return false
			val heard = onRichSpanClick?.invoke(span, type, offset)
			val heardEvent = onRichSpanClickEvent?.invoke(click)
			return heard == true || heardEvent == true
		}

		if (type != SpanClickType.SECONDARY_CLICK && type != SpanClickType.TAP) return passOn()

		// A host span, such as a link, wins the hit test over a squiggle under it.
		val flags = if (hostSpan) state.textState.flagsAt(state.textState.getOffsetAtPosition(offset)) else listOf(span)
		val show: ShowMenu = { items, trailing -> contextMenuState.showMenuAtText(below(offset), items, trailing) }
		if (flags.any { showFlagMenu(it, show) }) return true
		// A right-click always offers a menu, falling back to the standard one. A tap only
		// opens one on a flag: tapping a correctly spelled word means "put the caret here",
		// so declining leaves the tap to focus the editor and raise the keyboard.
		if (type == SpanClickType.SECONDARY_CLICK) {
			cancelSuggestions()
			show(emptyList(), emptyList())
		}
		return passOn()
	}

	Surface(modifier = modifier.focusBorder(state.textState.isFocused && enabled, style)) {
		BasicTextEditor(
			state = state.textState,
			modifier = Modifier,
			contentPadding = contentPadding,
			enabled = enabled,
			autoFocus = autoFocus,
			style = style,
			contextMenuStrings = contextMenuStrings,
			contextMenuState = contextMenuState,
			onRichSpanClickEvent = ::onSpanClick,
			onLinkClick = onLinkClick,
			keyBindings = keyBindings,
			contentDescription = contentDescription,
			readOnly = readOnly,
			lineLimits = lineLimits,
			softWrap = softWrap,
		)
	}
}

/** Opens the menu with these items and trailing items, where the gesture asked for it. */
/** The text a batch of edits left, and the document it is in. */
private class BatchText(val lines: List<AnnotatedString>, val generation: Int)

private typealias ShowMenu = (items: List<ContextMenuItem>, trailingItems: List<ContextMenuItem>) -> Unit

private fun RichSpan.isFlag(): Boolean = style is SpellCheckStyle || style is DiagnosticStyle

/**
 * The flags covering [position], their ends included: those it lies inside ahead of those
 * it only touches, and spelling ahead of diagnostics.
 */
private fun TextEditorState.flagsAt(position: CharLineOffset): List<RichSpan> =
	richSpanManager.getSpansInRange(TextEditorRange(position, position))
		.filter { it.isFlag() }
		.sortedWith(compareBy({ it.range.start == position || it.range.end == position }, { it.style !is SpellCheckStyle }))

@Composable
private fun dpToPx(dp: Dp): Float {
	val density = LocalDensity.current.density
	return dp.value * density
}

/**
 * The ranges the batch's [operations] left to check, as the text stands after them. Each
 * edit addresses the text as it stood when it ran, so the ranges gathered before it are
 * moved by it before its own is merged in.
 *
 * Each edit is checked over the text it wrote, or the point a deletion closed up, which
 * is where [SpellCheckState.invalidateSpellCheckSpans] strips the flags.
 */
internal fun computeAffectedRanges(operations: List<TextEditOperation>): List<TextEditorRange> {
	// Each stored [lineShift] lines above where it stands: an edit on lines above every range
	// (each of a replace-all's, which goes last to first) moves them all at once.
	val ranges = mutableListOf<TextEditorRange>()
	var lineShift = 0
	var firstLine = Int.MAX_VALUE
	for (op in operations) {
		val change = op.textChange() ?: continue
		val checked = TextEditorRange(change.start, change.newEnd)
		if (change.end.line < firstLine) {
			lineShift += change.newEnd.line - change.end.line
			ranges.add(checked.shifted(-lineShift))
			firstLine = checked.start.line
			continue
		}
		for (i in ranges.indices) ranges[i] = change.move(ranges[i].shifted(lineShift))
		lineShift = 0
		val touching = ranges.filter { it.adjoins(checked) }
		ranges.removeAll(touching)
		ranges.add(touching.fold(checked) { acc, r -> acc.merge(r) })
		firstLine = ranges.minOf { it.start.line }
	}
	return ranges.map { it.shifted(lineShift) }
}

private fun TextEditorRange.shifted(lines: Int): TextEditorRange =
	if (lines == 0) this else TextEditorRange(start.copy(line = start.line + lines), end.copy(line = end.line + lines))

/** An edit as the text from [start] to [end] replaced by text ending at [newEnd]. */
private class TextChange(val start: CharLineOffset, val end: CharLineOffset, val newEnd: CharLineOffset) {
	/** [range], in the text before the change, in the text after it. */
	fun move(range: TextEditorRange): TextEditorRange =
		TextEditorRange(move(range.start, inside = start), move(range.end, inside = newEnd))

	/** Text the change replaced goes to [inside]. */
	private fun move(offset: CharLineOffset, inside: CharLineOffset): CharLineOffset = when {
		offset <= start -> offset
		offset < end -> inside
		offset.line == end.line -> CharLineOffset(newEnd.line, newEnd.char + offset.char - end.char)
		else -> offset.copy(line = offset.line + newEnd.line - end.line)
	}
}

private fun TextEditOperation.textChange(): TextChange? = when (this) {
	is TextEditOperation.Insert -> TextChange(position, position, text.text.endWhenInsertedAt(position))
	is TextEditOperation.Delete -> TextChange(range.start, range.end, range.start)
	is TextEditOperation.Replace -> TextChange(range.start, range.end, newText.text.endWhenInsertedAt(range.start))
	else -> null
}

/**
 * Overlapping, or butting up end to start. A typed word arrives as one insert per
 * character, each range starting where the last ended; [TextEditorRange.intersects]
 * is exclusive at the ends and would re-check the same word once per keystroke.
 */
private fun TextEditorRange.adjoins(other: TextEditorRange): Boolean =
	intersects(other) || end == other.start || other.end == start
