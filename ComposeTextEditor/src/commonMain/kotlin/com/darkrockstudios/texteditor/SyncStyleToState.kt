package com.darkrockstudios.texteditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.darkrockstudios.texteditor.state.TextEditorState

/** A [SideEffect] so the style lands before this frame's layout and draw, not a frame late. */
@Composable
internal fun SyncStyleToState(state: TextEditorState, style: TextEditorStyle) {
	SideEffect {
		state.textStyle = style.textStyle
		state.paragraphSpacing = style.paragraphSpacing
		state.textColor = style.textColor
		state.bulletColor = style.bulletColor
		state.blockquoteBarColor = style.blockquoteBarColor
		state.blockquoteBackgroundColor = style.blockquoteBackgroundColor
		state.orderedListMarkerColor = style.orderedListMarkerColor
		state.codeFenceBackgroundColor = style.codeFenceBackgroundColor
		state.codeFenceBorderColor = style.codeFenceBorderColor
		state.tableBorderColor = style.tableBorderColor
		state.tableHeaderBackgroundColor = style.tableHeaderBackgroundColor
		state.imageSize = style.imageSize
	}
}
