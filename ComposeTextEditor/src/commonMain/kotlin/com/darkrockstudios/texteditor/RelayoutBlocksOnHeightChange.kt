package com.darkrockstudios.texteditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.state.LayoutUpdate
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Lays the rows out again when a [BlockSpanStyle]'s height changes outside an edit: an
 * image finishing its load, or the editor's image bounds changing. The layout pass reads
 * block heights but is not itself snapshot-tracked, so without this a freshly loaded image
 * would leave stale row offsets, the paragraphs below it drawn where the placeholder ended.
 */
@Composable
internal fun RelayoutBlocksOnHeightChange(state: TextEditorState) {
	LaunchedEffect(state) {
		snapshotFlow {
			// The spans are not snapshot state: the revision is read so a block an edit
			// adds is watched from then on, not only those present at the last change.
			state.revision
			val d = state.density ?: return@snapshotFlow emptyList<Float>()
			state.richSpanManager.getAllRichSpans().mapNotNull { span ->
				(span.style as? BlockSpanStyle)?.blockHeight(d, state.viewportSize, state.imageSize)
			}
		}
			.distinctUntilChanged()
			// A block height change moves rows but no line content, so the pass skips shaping.
			.collect { state.updateBookKeeping(LayoutUpdate.SpansOnly) }
	}
}
