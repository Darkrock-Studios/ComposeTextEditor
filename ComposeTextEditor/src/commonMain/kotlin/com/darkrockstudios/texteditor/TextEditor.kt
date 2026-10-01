package com.darkrockstudios.texteditor

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.contextmenu.ContextMenuStrings
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.input.KeyBindings
import com.darkrockstudios.texteditor.input.LocalKeyBindings
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState

private val DefaultContentPadding = PaddingValues(16.dp)

/**
 * A ready-to-use rich text editor wrapped in a Material [Surface] with a focus
 * border. This is the entry point most apps want.
 *
 * Hoist a [TextEditorState] with [rememberTextEditorState] if you need to read or
 * mutate the content (set text, observe edits, apply spans); otherwise the default
 * creates one for you. For an unwrapped editor with no surface or border, use
 * [BasicTextEditor]; for a non-editable rendering of the same content, use
 * [RichTextView].
 *
 * @param state Holds the document, cursor, selection, and undo history.
 * @param modifier Applied to the surface. A `Modifier.focusRequester` here focuses the
 *   editor from code.
 * @param contentPadding Padding between the surface edge and the text.
 * @param enabled When `false`, the editor is disabled: it takes no input, shows no
 *   caret, and reports itself disabled, with no edit actions, to accessibility
 *   services. It still takes focus, so its text can be selected and copied.
 * @param readOnly Shows the caret for navigation and selection but takes no edits; see
 *   [BasicTextEditor].
 * @param lineLimits Fills the height given, or grows with the text between a minimum
 *   and maximum number of lines; see [BasicTextEditor].
 * @param autoFocus Requests focus once when first composed, if [enabled].
 * @param style Colors and text style for the editor and its gutter markers.
 * @param onRichSpanClick Invoked when a rich span (link, list, blockquote, code
 *   block, …) is clicked or tapped; see [RichSpanClick] for when, and
 *   [RichSpanClickListener] for what the return value does (and does not do).
 * @param keyBindings Chord-to-command mapping, defaulting to [LocalKeyBindings].
 *   Bind chords to actions registered on [TextEditorState.actions] to add
 *   shortcuts of your own.
 * @param onRichSpanClickEvent The same clicks as [onRichSpanClick], with the
 *   modifier keys that were held.
 * @param onLinkClick Opens a link's URL on Ctrl+click, or Cmd+click under the
 *   macOS [keyBindings]; see [BasicTextEditor].
 * @param contextMenuStrings Localized labels for the built-in context menu.
 * @param contextMenuState Drives context-menu visibility; pass your own to add
 *   custom items, or leave `null` for the default.
 * @param contentDescription The editor's label for accessibility services; see
 *   [BasicTextEditor].
 * @param softWrap Whether lines wrap at the editor's width; with `false` a line stays one
 *   row and the editor scrolls sideways. See [BasicTextEditor].
 */
@Composable
fun TextEditor(
	state: TextEditorState = rememberTextEditorState(),
	modifier: Modifier = Modifier,
	contentPadding: PaddingValues = DefaultContentPadding,
	enabled: Boolean = true,
	autoFocus: Boolean = false,
	style: TextEditorStyle = rememberTextEditorStyle(),
	onRichSpanClick: RichSpanClickListener? = null,
	keyBindings: KeyBindings = LocalKeyBindings.current,
	onRichSpanClickEvent: RichSpanClickEventListener? = null,
	onLinkClick: ((url: String) -> Unit)? = null,
	contextMenuStrings: ContextMenuStrings = ContextMenuStrings.Default,
	contextMenuState: TextEditorContextMenuState? = null,
	contentDescription: String? = null,
	readOnly: Boolean = false,
	lineLimits: EditorLineLimits = EditorLineLimits.Fill,
	softWrap: Boolean = true,
) {
	Surface(modifier = modifier.focusBorder(state.hasFocus && enabled, style)) {
		BasicTextEditor(
			state = state,
			modifier = Modifier,
			contentPadding = contentPadding,
			enabled = enabled,
			autoFocus = autoFocus,
			style = style,
			onRichSpanClick = onRichSpanClick,
			keyBindings = keyBindings,
			onRichSpanClickEvent = onRichSpanClickEvent,
			onLinkClick = onLinkClick,
			contextMenuStrings = contextMenuStrings,
			contextMenuState = contextMenuState,
			contentDescription = contentDescription,
			readOnly = readOnly,
			lineLimits = lineLimits,
			softWrap = softWrap,
		)
	}
}
