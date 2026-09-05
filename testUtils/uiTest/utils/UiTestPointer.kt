package utils

import androidx.compose.ui.geometry.Offset
import com.darkrockstudios.texteditor.state.TextEditorState

// Pointer input is injected at the tagged editor node, not onRoot(): once a
// context menu popup is open there are two roots and onRoot() refuses to pick.
internal const val EDITOR_TEST_TAG = "editor-under-test"

/**
 * Pixel position of the character at flat index [charIndex], vertically centered on its
 * line, in text-canvas coordinates. Only matches the tagged node when the editor has no
 * content padding.
 */
fun TextEditorState.positionOfCharacter(charIndex: Int): Offset {
	val metrics = getPositionForOffset(getOffsetAtCharacter(charIndex))
	return Offset(metrics.position.x, metrics.position.y + metrics.height / 2f)
}

// The editor's double/triple-click detection compares wall-clock timestamps
// (Clock.System.now, 300ms window), not the virtual test clock, so gestures
// issued back-to-back by a fast test read as multi-clicks and word-select.
fun defeatMultiClickDetection() {
	Thread.sleep(350)
}
