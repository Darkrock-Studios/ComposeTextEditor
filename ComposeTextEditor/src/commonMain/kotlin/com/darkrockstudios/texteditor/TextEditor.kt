package com.darkrockstudios.texteditor

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 * @param contentPadding Padding between the surface edge and the text.
 * @param enabled When `false`, the editor is read-only and cannot take focus.
 * @param autoFocus Requests focus once when first composed.
 * @param style Colors and text style for the editor and its gutter markers.
 * @param onRichSpanClick Invoked when a rich span (link, list, blockquote, code
 *   block, …) is tapped or right-clicked; see [RichSpanClickListener] for what
 *   the return value does (and does not do).
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
) {
	Surface(modifier = modifier.focusBorder(state.isFocused && enabled, style)) {
		BasicTextEditor(
			state = state,
			modifier = Modifier,
			contentPadding = contentPadding,
			enabled = enabled,
			autoFocus = autoFocus,
			style = style,
			onRichSpanClick = onRichSpanClick,
		)
	}
}
