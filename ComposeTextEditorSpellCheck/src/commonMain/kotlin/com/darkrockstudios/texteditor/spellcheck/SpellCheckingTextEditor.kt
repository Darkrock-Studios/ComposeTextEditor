package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
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
import com.darkrockstudios.texteditor.state.SpanClickType
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.WordSegment
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private val DefaultContentPadding = PaddingValues(start = 8.dp)

/**
 * A drop-in text editor composable with integrated spell checking.
 *
 * Wraps [BasicTextEditor] and overlays spell-check decorations driven by a [SpellCheckState].
 * Edits are observed reactively: affected spans are invalidated immediately and re-checked once
 * typing goes quiet, and replacing the document wholesale re-checks all of it. Secondary clicks and taps on a flagged span open a context menu of
 * [Suggestion][com.darkrockstudios.texteditor.spellcheck.api.Suggestion]s for the
 * misspelled word or sentence-level [Correction].
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
 * @param lineLimits Fills the height given, or grows with the text between a minimum and
 *   maximum number of lines; see [BasicTextEditor].
 * @param autoFocus Whether the editor requests focus on first composition.
 * @param style The [TextEditorStyle] controlling appearance.
 * @param contextMenuStrings Localized strings for the built-in context menu.
 * @param spellCheckStrings Localized strings for the spell check menu.
 * @param onAddToDictionary Adds a word to the host's dictionary. When set, the menu on a flagged
 *   word offers "Add to dictionary", which calls this and stops flagging the word for the session,
 *   so it clears at once however the host's dictionary reaches the checker.
 * @param spellCheckMenuItems Host items for the context menu opened on a flagged span, rendered
 *   after the built-in "Ignore" and "Add to dictionary" in a group below the suggestions. For a misspelled
 *   word they appear together with the suggestions once those have loaded. Not consulted while
 *   the editor is disabled. Read at click time, so it may close over changing state.
 * @param diagnostics Underlines from a checker other than spelling, such as grammar, kept up to date
 *   as the text changes. Its [TextDiagnosticsState.textState] must be [state]'s. A secondary click
 *   or tap on one opens a menu of its message and fixes.
 * @param onRichSpanClick Optional listener for clicks on other rich spans; spell-check and
 *   diagnostic spans are handled internally.
 * @param onRichSpanClickEvent The same clicks as [onRichSpanClick], with the modifier keys
 *   that were held.
 * @param onLinkClick Opens a link's URL on Ctrl+click, or Cmd+click under the macOS
 *   [keyBindings]; see [BasicTextEditor].
 * @param keyBindings Chord-to-command mapping, defaulting to [LocalKeyBindings].
 * @param contentDescription The editor's label for accessibility services; see
 *   [BasicTextEditor].
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
		state.textState.editOperations
			.collect { operation ->
				state.invalidateSpellCheckSpans(operation)
			}
	}

	LaunchedEffect(state) {
		state.textState.editOperations.debounceUntilQuiescentWithBatch(500.milliseconds)
			.collect { operations ->
				val rangesToCheck = computeAffectedRanges(operations, state.textState)
				val computedAgainst = state.textState.textLines
				rangesToCheck.forEach { range ->
					state.runPartialSpellCheck(range, computedAgainst)
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
			diagnostics.textState.editOperations.collect(diagnostics::invalidate)
		}
		LaunchedEffect(diagnostics) {
			diagnostics.textState.editOperations.debounceUntilQuiescent(500.milliseconds).collect { diagnostics.refresh() }
		}
	}

	fun showDiagnosticMenu(offset: Offset, span: RichSpan, style: DiagnosticStyle) {
		suggestionJob.value?.cancel()
		suggestionJob.value = null
		val message = ContextMenuItem(label = style.message, enabled = false, onClick = {})
		val fixes = if (!editable) emptyList() else style.fixes.map { fix ->
			ContextMenuItem(label = fix.label, enabled = true, onClick = { if (canEdit) diagnostics?.applyFix(span, fix.replacement) })
		}
		contextMenuState.showMenuAtText(below(offset), listOf(message) + fixes)
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

	fun showContextMenu(offset: Offset, spellCheckItem: SpellCheckItem?) {
		val menuPos = below(offset)
		suggestionJob.value?.cancel()
		suggestionJob.value = null

		if (spellCheckItem == null) {
			contextMenuState.showMenuAtText(menuPos)
			return
		}

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
			contextMenuState.showMenuAtText(menuPos, emptyList(), hostItems)
			return
		}
		when (spellCheckItem) {
			is SpellCheckItem.MisspelledWord -> {
				val placeholder = listOf(ContextMenuItem(label = spellCheckStrings.loading, enabled = false, onClick = {}))
				contextMenuState.showMenuAtText(menuPos, placeholder)
				// Host items arrive with the suggestions: shown under the placeholder, they
				// would move under the pointer when it is replaced.
				suggestionJob.value = coroutineScope.launch {
					val suggestions = state.getSuggestions(spellCheckItem.segment.text)
					// Dismissed, or showing other items, while the lookup ran. The position is no
					// guide: the editor re-anchors the menu to the pointer after this handler returns.
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
				contextMenuState.showMenuAtText(menuPos, items, hostItems)
			}
		}
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
		val flag = if (hostSpan) state.textState.flagAt(state.textState.getOffsetAtPosition(offset)) else span
		val diagnostic = flag?.style as? DiagnosticStyle
		if (diagnostic != null && diagnostics != null && enabled) {
			showDiagnosticMenu(offset, flag, diagnostic)
			return true
		}

		// A disabled editor must not offer corrections it cannot apply.
		val spellCheckItem: SpellCheckItem? = if (!enabled || flag == null) null else when (val clickResult = state.handleSpanClick(flag)) {
			is WordSegment -> SpellCheckItem.MisspelledWord(clickResult)
			is Correction -> SpellCheckItem.SentenceIssue(clickResult)
			else -> null
		}

		// A right-click always offers a menu, falling back to the standard one. A tap
		// only opens one on a flag: tapping a correctly spelled word means "put the
		// caret here", so declining leaves the tap to focus the editor and raise the
		// keyboard.
		if (spellCheckItem != null) {
			showContextMenu(offset, spellCheckItem)
			return true
		}
		if (type == SpanClickType.SECONDARY_CLICK) showContextMenu(offset, null)
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
		)
	}
}

private fun RichSpan.isFlag(): Boolean = style is SpellCheckStyle || style is DiagnosticStyle

/** The flag covering [position], its ends included: a spelling one first, then a diagnostic. */
private fun TextEditorState.flagAt(position: CharLineOffset): RichSpan? {
	val spans = richSpanManager.getSpansInRange(TextEditorRange(position, position))
	return spans.firstOrNull { it.style is SpellCheckStyle } ?: spans.firstOrNull { it.style is DiagnosticStyle }
}

@Composable
private fun dpToPx(dp: Dp): Float {
	val density = LocalDensity.current.density
	return dp.value * density
}

private fun computeAffectedRanges(
	operations: List<TextEditOperation>,
	state: TextEditorState
): List<TextEditorRange> {
	return operations.fold(mutableListOf<TextEditorRange>()) { ranges, op ->
		val opRange = when (op) {
			is TextEditOperation.Insert -> TextEditorRange(
				op.position,
				op.position.copy(char = op.position.char + op.text.length)
			)

			is TextEditOperation.Delete -> op.range
			is TextEditOperation.Replace -> op.range
			else -> null
		}
		opRange?.let { newRange ->
			val touching = ranges.filter { it.adjoins(newRange) }
			ranges.removeAll(touching)
			val mergedRange = touching.fold(newRange) { acc, r -> acc.merge(r) }
			ranges.add(mergedRange)
		}
		ranges
	}
}

/**
 * Overlapping, or butting up end to start. A typed word arrives as one insert per
 * character, each range starting where the last ended; [TextEditorRange.intersects]
 * is exclusive at the ends and would re-check the same word once per keystroke.
 */
private fun TextEditorRange.adjoins(other: TextEditorRange): Boolean =
	intersects(other) || end == other.start || other.end == start
