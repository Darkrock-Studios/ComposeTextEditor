package com.darkrockstudios.texteditor.state

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import kotlin.math.roundToInt

/** The soft keyboard's inset the editor measures its cover by: `WindowInsets.ime`, unless a test stands one in. */
internal val LocalImeInsets = staticCompositionLocalOf<WindowInsets?> { null }

/**
 * Measures how far a soft keyboard [keyboardHeight] pixels tall, drawn over the bottom of
 * the window, covers the focused editor's canvas, and records it with
 * [onObscuredBottomChange]. Nothing is covered when the host already keeps the editor
 * above the keyboard, with `imePadding` or a window that resizes, or when the editor is
 * not focused: the keyboard is someone else's then.
 *
 * The keyboard's inset is taken from the window's bottom ([TextEditorState.windowBottomInRoot]):
 * on Android the bottom of the window the view is in, so a `ComposeView` embedded in
 * views measures it right; elsewhere the bottom of the root, which is off inside a root
 * that does not reach the window's bottom.
 */
internal fun TextEditorState.updateKeyboardCover(keyboardHeight: Int) {
	keyboardCoverFor(keyboardHeight)?.let(::onObscuredBottomChange)
}

/** The cover [updateKeyboardCover] records, or null before the canvas is attached. */
private fun TextEditorState.keyboardCoverFor(keyboardHeight: Int): Int? {
	val canvas = canvasLayoutCoordinates?.takeIf { it.isAttached } ?: return null
	if (!isFocused || keyboardHeight <= 0) return 0
	// Where the canvas rests: a rubber band lifting it must not shrink the cover, or the
	// scroll range with it, and leave the scroll short of the end once it lets go.
	val frame = canvasFrameCoordinates?.takeIf { it.isAttached } ?: canvas
	return keyboardCover(
		canvasBottomInRoot = frame.localToRoot(Offset(0f, frame.size.height.toFloat())).y,
		canvasHeight = frame.size.height,
		windowBottomInRoot = windowBottomInRoot?.invoke() ?: canvas.findRootCoordinates().size.height.toFloat(),
		keyboardHeight = keyboardHeight,
	)
}

/**
 * Measures the cover (see [updateKeyboardCover]) on the canvas's frame, which this
 * modifies: the editor's box outside its overscroll effect. Measured whenever the
 * keyboard's [insets] or the focus change, once the frame is placed for that change. Both
 * are read in the placement block, so a change re-places the frame after its ancestors
 * have been laid out: under a host's `imePadding` the inset grows before the padding shrinks the
 * editor, and a measure taken any earlier reads the strip the padding is about to take
 * away as covered.
 */
internal fun Modifier.measuresKeyboardCover(state: TextEditorState, insets: () -> WindowInsets): Modifier =
	this then KeyboardCoverElement(state, insets)

private data class KeyboardCoverElement(
	val state: TextEditorState,
	val insets: () -> WindowInsets,
) : ModifierNodeElement<KeyboardCoverNode>() {
	override fun create() = KeyboardCoverNode(state, insets)

	override fun update(node: KeyboardCoverNode) {
		node.state = state
		node.insets = insets
		node.invalidatePlacement()
	}
}

private class KeyboardCoverNode(
	state: TextEditorState,
	var insets: () -> WindowInsets,
) : Modifier.Node(), LayoutModifierNode {
	var state: TextEditorState = state
		set(value) {
			if (value === field) return
			if (isAttached) release(field)
			field = value
			if (isAttached) value.currentKeyboardHeight = keyboardHeightNow
		}

	private val keyboardHeightNow = { if (isAttached) insets().getBottom(requireDensity()) else 0 }

	override fun onAttach() {
		state.currentKeyboardHeight = keyboardHeightNow
	}

	override fun onDetach() {
		release(state)
	}

	private fun release(state: TextEditorState) {
		if (state.currentKeyboardHeight === keyboardHeightNow) {
			state.currentKeyboardHeight = null
			state.canvasFrameCoordinates = null
		}
	}

	override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
		val placeable = measurable.measure(constraints)
		return layout(placeable.width, placeable.height) {
			placeable.place(0, 0)
			// A lookahead pass places before the canvas has its new bounds.
			if (isLookingAhead) return@layout
			state.canvasFrameCoordinates = coordinates
			val keyboardHeight = insets().getBottom(this@measure)
			// Read here, so a focus change re-places as an inset change does.
			val focused = state.isFocused
			// What the measure itself reads (the scroll, the rows) must not re-place.
			Snapshot.withoutReadObservation { state.updateKeyboardCover(if (focused) keyboardHeight else 0) }
		}
	}
}

/**
 * How many pixels at the bottom of a canvas a keyboard [keyboardHeight] pixels tall
 * covers, when the keyboard rises from [windowBottomInRoot]. No keyboard covers nothing,
 * even where the canvas runs past the window's bottom.
 */
internal fun keyboardCover(canvasBottomInRoot: Float, canvasHeight: Int, windowBottomInRoot: Float, keyboardHeight: Int): Int {
	if (keyboardHeight <= 0) return 0
	val keyboardTop = windowBottomInRoot - keyboardHeight
	return (canvasBottomInRoot - keyboardTop).roundToInt().coerceIn(0, canvasHeight)
}

/**
 * Records that the bottom [px] pixels of the viewport are covered. When the cover grows
 * over the caret of a focused editor, as when the keyboard comes up, the caret scrolls
 * above it. A caret already out of view stays there: the reader scrolled away from it,
 * and the cover is measured again, a little larger, as iOS settles the view after a fling.
 */
internal fun TextEditorState.onObscuredBottomChange(px: Int) {
	val grew = px > scrollManager.obscuredBottomPx
	// Against the cover as it was; a scroll to the caret still under way counts as in view.
	val caretShown = grew && isFocused && scrollManager.isCursorInViewOrScrolling()
	scrollManager.obscuredBottomPx = px
	if (caretShown) scrollManager.ensureCursorVisible()
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
	// The platform asks while the keyboard slides, before the canvas is placed for its
	// new height, so the cover measured then can trail it; this frame's placement scrolls
	// the caret above the keyboard as it is now.
	// Under a host's imePadding the keyboard is measured before the padding shrinks the
	// canvas and overstates the cover; if that leaves no room, the last placement's holds.
	val coverNow = currentKeyboardHeight?.let { keyboardCoverFor(it()) } ?: 0
	val measured = viewportSize.height - scrollManager.obscuredBottomPx
	val uncovered = (viewportSize.height - max(scrollManager.obscuredBottomPx, coverNow))
		.takeIf { it >= height } ?: measured
	val top = if (uncovered >= height) caret.position.y.coerceIn(0f, uncovered - height) else caret.position.y
	return Rect(caret.position.x, top, caret.position.x + 1f, top + height)
}
