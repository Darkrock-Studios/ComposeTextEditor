package com.darkrockstudios.texteditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Applied effects, not launched ones, so the style and density land before this frame's
 * layout and draw, not a frame late, and before any launched effect's first pass:
 * [RelayoutBlocksOnHeightChange] reads the density but is not rerun when it is set.
 */
@Composable
internal fun SyncStyleToState(state: TextEditorState, style: TextEditorStyle) {
	val density = LocalDensity.current
	// Keyed, not a SideEffect: two hosts of one state under different densities would
	// otherwise overwrite each other, reshaping the document, on every recomposition.
	DisposableEffect(state, density) {
		state.density = density
		onDispose {}
	}
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
