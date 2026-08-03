package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.RichSpanClickListener
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.contextmenu.ContextMenuItem
import com.darkrockstudios.texteditor.contextmenu.ContextMenuStrings
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.focusBorder
import com.darkrockstudios.texteditor.rememberTextEditorStyle
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.utils.debounceUntilQuiescentWithBatch
import com.darkrockstudios.texteditor.state.SpanClickType
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.WordSegment
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private val DefaultContentPadding = PaddingValues(start = 8.dp)

/**
 * A drop-in text editor composable with integrated spell checking.
 *
 * Wraps [BasicTextEditor] and overlays spell-check decorations driven by a [SpellCheckState].
 * Edits are observed reactively: affected spans are invalidated immediately and re-checked once
 * typing goes quiet. Secondary clicks and taps on a flagged span open a context menu of
 * [Suggestion][com.darkrockstudios.texteditor.spellcheck.api.Suggestion]s for the
 * misspelled word or sentence-level [Correction].
 *
 * @param spellChecker The [EditorSpellChecker] backing spell checks; used to build a default
 *   [state] when none is provided. May be `null` to disable checking.
 * @param state The [SpellCheckState] coordinating spell checking over the underlying
 *   [TextEditorState]. Defaults to a remembered state built from [spellChecker].
 * @param modifier The [Modifier] applied to the editor surface.
 * @param contentPadding Padding applied around the editor content.
 * @param enabled Whether the editor accepts input and focus.
 * @param autoFocus Whether the editor requests focus on first composition.
 * @param style The [TextEditorStyle] controlling appearance.
 * @param contextMenuStrings Localized strings for the built-in context menu.
 * @param onRichSpanClick Optional listener for clicks on non-spell-check rich spans; spell-check
 *   spans are handled internally.
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
	onRichSpanClick: RichSpanClickListener? = null,
) {
	val contextMenuState = remember { TextEditorContextMenuState() }
	val wordVisibilityBuffer = dpToPx(35.dp)
	val coroutineScope = rememberCoroutineScope()

	// Track the current spell check item for right-click context
	var currentSpellCheckItem by remember { mutableStateOf<SpellCheckItem?>(null) }

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
				rangesToCheck.forEach { range ->
					state.runPartialSpellCheck(range)
				}
			}
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
						when (item) {
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
					label = "No suggestions",
					enabled = false,
					onClick = { }
				)
			)
		}
	}

	fun showContextMenu(offset: Offset, spellCheckItem: SpellCheckItem?) {
		val menuPos = Offset(offset.x, offset.y + wordVisibilityBuffer)

		if (spellCheckItem == null) {
			// No spell check item - just show standard menu
			contextMenuState.showMenu(menuPos)
		} else {
			// Show menu immediately, then fetch suggestions async for misspelled words
			when (spellCheckItem) {
				is SpellCheckItem.MisspelledWord -> {
					// Show loading state
					contextMenuState.showMenu(
						menuPos, listOf(
							ContextMenuItem(label = "Loading...", enabled = false, onClick = {})
						)
					)
					// Fetch suggestions asynchronously
					coroutineScope.launch {
						val suggestions = state.getSuggestions(spellCheckItem.segment.text)
						val items = createSpellSuggestionItems(spellCheckItem, suggestions)
						contextMenuState.extraItems.value = items
					}
				}

				is SpellCheckItem.SentenceIssue -> {
					// Sentence issues already have suggestions
					val items = createSpellSuggestionItems(spellCheckItem, spellCheckItem.correction.suggestions)
					contextMenuState.showMenu(menuPos, items)
				}
			}
		}
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
			onRichSpanClick = { span, type, offset ->
				if (type == SpanClickType.SECONDARY_CLICK || type == SpanClickType.TAP) {
					// Check if this span is a spell check span
					val spellCheckItem: SpellCheckItem? = when (val clickResult = state.handleSpanClick(span)) {
						is WordSegment -> SpellCheckItem.MisspelledWord(clickResult)
						is Correction -> SpellCheckItem.SentenceIssue(clickResult)
						else -> null
					}
					// Store the spell check item for context menu
					currentSpellCheckItem = spellCheckItem
					// Show context menu with spell suggestions
					showContextMenu(offset, spellCheckItem)
					// Return true to indicate we handled it
					true
				} else {
					currentSpellCheckItem = null
					onRichSpanClick?.invoke(span, type, offset) ?: false
				}
			},
		)
	}
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
 * Overlapping, or butting up against each other end to start.
 *
 * A typed word arrives as one insert per character, each range starting exactly where the
 * last ended. [TextEditorRange.intersects] is exclusive at the ends and reports those as
 * disjoint, which would re-check the same word once per character typed.
 */
private fun TextEditorRange.adjoins(other: TextEditorRange): Boolean =
	intersects(other) || end == other.start || other.end == start
