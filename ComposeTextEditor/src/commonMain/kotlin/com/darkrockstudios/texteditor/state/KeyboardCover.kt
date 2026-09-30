package com.darkrockstudios.texteditor.state

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import kotlin.math.roundToInt

/**
 * Measures how far a soft keyboard [keyboardHeight] pixels tall, drawn over the bottom of
 * the window, covers the focused editor's canvas, and records it with
 * [onObscuredBottomChange]. Nothing is covered when the host already keeps the editor
 * above the keyboard, with `imePadding` or a window that resizes, or when the editor is
 * not focused: the keyboard is someone else's then.
 *
 * The keyboard's inset is taken from the bottom of the root, which is the window's
 * bottom for an editor in the main content; inside a dialog or popup that does not
 * reach the bottom of the window the measure is off.
 */
internal fun TextEditorState.updateKeyboardCover(keyboardHeight: Int) {
	val canvas = canvasLayoutCoordinates?.takeIf { it.isAttached } ?: return
	val covered = if (isFocused) {
		keyboardCover(
			canvasBottomInRoot = canvas.localToRoot(Offset(0f, canvas.size.height.toFloat())).y,
			canvasHeight = canvas.size.height,
			rootHeight = canvas.findRootCoordinates().size.height,
			keyboardHeight = keyboardHeight,
		)
	} else {
		0
	}
	onObscuredBottomChange(covered)
}

/**
 * How many pixels at the bottom of a canvas a keyboard [keyboardHeight] pixels tall
 * covers, when the keyboard rises from the bottom of a root [rootHeight] pixels tall.
 * No keyboard covers nothing, even where the canvas runs past the root's bottom.
 */
internal fun keyboardCover(canvasBottomInRoot: Float, canvasHeight: Int, rootHeight: Int, keyboardHeight: Int): Int {
	if (keyboardHeight <= 0) return 0
	val keyboardTop = rootHeight - keyboardHeight
	return (canvasBottomInRoot - keyboardTop).roundToInt().coerceIn(0, canvasHeight)
}

/**
 * Records that the bottom [px] pixels of the viewport are covered. When the cover grows
 * over the caret of a focused editor, as when the keyboard comes up, the caret scrolls
 * above it.
 */
internal fun TextEditorState.onObscuredBottomChange(px: Int) {
	val grew = px > scrollManager.obscuredBottomPx
	scrollManager.obscuredBottomPx = px
	if (grew && isFocused) scrollManager.ensureCursorVisible()
}

/**
 * The caret's row in canvas coordinates, where the editor keeps it: pulled above the
 * covered bottom of the viewport, since the editor scrolls it there, unless that leaves
 * no room for the row. This is the rect a platform keeps above its soft keyboard; it is
 * a pixel wide, as an empty rect is visible nowhere and would be ignored.
 */
internal fun TextEditorState.caretFocusRect(): Rect? {
	if (lineOffsets.isEmpty()) return null
	val caret = getPositionForOffset(cursorPosition, cursor.affinity)
	val height = caret.height
	val uncovered = viewportSize.height - scrollManager.obscuredBottomPx
	val top = if (uncovered >= height) caret.position.y.coerceIn(0f, uncovered - height) else caret.position.y
	return Rect(caret.position.x, top, caret.position.x + 1f, top + height)
}
