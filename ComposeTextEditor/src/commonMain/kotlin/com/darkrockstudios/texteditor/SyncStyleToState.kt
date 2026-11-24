package com.darkrockstudios.texteditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * A [SideEffect] so the style and density land before this frame's layout and draw, not a
 * frame late, and before any effect's first pass: [RelayoutBlocksOnHeightChange] reads the
 * density but is not rerun when it is set.
 */
@Composable
internal fun SyncStyleToState(state: TextEditorState, style: TextEditorStyle) {
	val density = LocalDensity.current
	SideEffect {
		state.density = density
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
