package com.darkrockstudios.texteditor.input

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.darkrockstudios.texteditor.state.TextEditorState

/** No handwriting service to hand a stroke to: a stylus draws as a pointer does. */
internal actual fun Modifier.stylusHandwriting(
	state: TextEditorState,
	enabled: Boolean,
	onStroke: (start: Offset, focused: Boolean) -> Unit,
): Modifier = this
