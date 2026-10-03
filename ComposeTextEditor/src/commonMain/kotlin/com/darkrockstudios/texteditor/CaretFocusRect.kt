package com.darkrockstudios.texteditor

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.caretFocusRect

/**
 * Reports the caret row as the focused editor's focus rect, instead of the whole editor.
 * iOS keeps the focus rect above its soft keyboard by moving all of the window's
 * content, so with the editor's full bounds a tall editor was pushed up until its top
 * met the screen's, hiding whatever sat above it (roadmap 4.24). The caret row, where
 * the editor scrolls it, moves the window only when the editor cannot show it itself.
 *
 * [modifier] goes right before the focus target it describes. Remember one per editor,
 * so the coordinates it records survive recomposition.
 */
internal class CaretFocusRect(private val state: TextEditorState) {
	private var coordinates: LayoutCoordinates? = null

	val modifier: Modifier = Modifier
		.onGloballyPositioned { coordinates = it }
		.focusProperties { rect()?.let { focusRect = it } }

	/**
	 * [caretFocusRect] in the focus target's coordinates, while the editor is focused.
	 * Read unobserved: the rect is asked for when the keyboard moves, and observing the
	 * caret and scroll here would invalidate the focus properties on every keystroke.
	 */
	private fun rect(): Rect? = Snapshot.withoutReadObservation { computeRect() }

	private fun computeRect(): Rect? {
		if (!state.isFocused) return null
		val target = coordinates?.takeIf { it.isAttached } ?: return null
		val canvas = state.canvasLayoutCoordinates?.takeIf { it.isAttached } ?: return null
		val caret = state.caretFocusRect() ?: return null
		return caret.translate(target.localPositionOf(canvas, Offset.Zero))
	}
}
