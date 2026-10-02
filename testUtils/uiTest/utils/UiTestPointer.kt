package utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.MouseInjectionScope
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

/**
 * Moves the injected event clock past the double-click timeout, so the next press is a
 * fresh single click. The editor counts clicks by event time, which the test clock
 * drives, so a fast test would otherwise issue back-to-back clicks as a multi-click.
 */
fun MouseInjectionScope.defeatMultiClickDetection() {
	advanceEventTime(1_000)
}
