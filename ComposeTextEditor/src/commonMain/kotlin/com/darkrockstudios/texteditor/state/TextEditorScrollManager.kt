package com.darkrockstudios.texteditor.state

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.cursor.getWrapForDrawing
import com.darkrockstudios.texteditor.effectiveHeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TextEditorScrollManager(
	private val scope: CoroutineScope,
	private val getLines: () -> List<AnnotatedString>,
	private val getLineOffsets: () -> List<LineWrap>,
	private val getViewportSize: () -> Size,
	private val getCursorPosition: () -> CharLineOffset,
	val scrollState: TextEditorScrollState
) {
	private var scrollJob: Job? = null

	var totalContentHeight by mutableStateOf(0)
		private set

	var topContentPaddingPx: Int = 0
		set(value) {
			if (field != value) {
				field = value
				applyScrollRange()
			}
		}

	var bottomContentPaddingPx: Int = 0
		set(value) {
			if (field != value) {
				field = value
				applyScrollRange()
			}
		}

	val viewportHeight: Int
		get() = getViewportSize().height.toInt()

	private fun applyScrollRange() {
		scrollState.minValue = -topContentPaddingPx
		scrollState.maxValue = totalContentHeight - viewportHeight + bottomContentPaddingPx
	}

	fun updateContentHeight(height: Int) {
		totalContentHeight = maxOf(height, viewportHeight)
		applyScrollRange()
	}

	fun scrollToTop() {
		scrollJob?.cancel()
		scrollJob = scope.launch {
			scrollState.animateScrollTo(scrollState.minValue)
		}
	}

	fun scrollToBottom() {
		scrollJob?.cancel()
		scrollJob = scope.launch {
			scrollState.animateScrollTo(totalContentHeight - viewportHeight + bottomContentPaddingPx)
		}
	}

	fun scrollToPosition(position: Int, animated: Boolean = true) {
		scrollJob?.cancel()
		scrollJob = scope.launch {
			val scrollToY = position.coerceIn(scrollState.minValue, totalContentHeight)
			if (animated) {
				scrollState.animateScrollTo(scrollToY)
			} else {
				scrollState.scrollTo(scrollToY)
			}
		}
	}

	/**
	 * The [CharLineOffset] currently at the top of the viewport, or the closest
	 * offset before it if the top falls inside a wrapped line. Compose-observable:
	 * composables reading this recompose when the user scrolls.
	 */
	val firstVisibleOffset: CharLineOffset
		get() = offsetAtYPosition(scrollState.value.toFloat())

	/**
	 * The offset whose line sits at content-space [y] (absolute, not viewport-relative),
	 * or the closest offset before it. Inverse of [calculateOffsetYPosition].
	 */
	fun offsetAtYPosition(y: Float): CharLineOffset {
		val lineOffsets = getLineOffsets()
		if (lineOffsets.isEmpty()) return CharLineOffset(0, 0)

		val wrap = lineOffsets.lastOrNull { it.offset.y <= y } ?: lineOffsets.first()
		return CharLineOffset(wrap.line, wrap.wrapStartsAtIndex)
	}

	/**
	 * Scrolls so [offset] is visible. When [top] is true, instead aligns [offset]
	 * to the top of the viewport — useful for symmetric scroll-by-line sync, where
	 * [animated] should be false so the follower pane tracks the source 1:1 instead
	 * of lagging behind a spring per scroll frame.
	 */
	fun scrollToPosition(offset: CharLineOffset, top: Boolean = false, animated: Boolean = true) {
		if (offset.line >= getLines().size) return

		if (top) {
			val targetTop = calculateOffsetYPosition(offset).toInt()
			val minScroll = scrollState.minValue
			val maxScroll = maxOf(minScroll, totalContentHeight - viewportHeight + bottomContentPaddingPx)
			scrollToPosition(targetTop.coerceIn(minScroll, maxScroll), animated = animated)
			return
		}

		val cursorTop = calculateOffsetYPosition(offset).toInt()
		val cursorHeight = calculateLineHeight(offset)
		val viewportTop = scrollState.value
		val minScroll = scrollState.minValue
		val maxScroll = maxOf(minScroll, totalContentHeight - viewportHeight + bottomContentPaddingPx)

		val buffer = 10
		val targetScroll = if (cursorTop < viewportTop + buffer) {
			// Scrolling up - align cursor near top
			(cursorTop - buffer).coerceIn(minScroll, maxScroll)
		} else if (cursorTop + cursorHeight > viewportTop + viewportHeight - buffer) {
			// Scrolling down - ensure full cursor height is visible
			(cursorTop + cursorHeight - viewportHeight + buffer).coerceIn(minScroll, maxScroll)
		} else {
			// Cursor already fully visible, maintain current scroll
			viewportTop
		}

		if(targetScroll != viewportTop) {
			scrollJob?.cancel()
			scrollJob = scope.launch {
				scrollState.animateScrollTo(targetScroll)
			}
		}
	}

	fun scrollToCursor() {
		scrollToPosition(getCursorPosition())
	}

	fun ensureCursorVisible() {
		val cursorPos = getCursorPosition()
		if (!isOffsetVisible(cursorPos)) {
			scrollToCursor()
		}
	}

	fun isOffsetVisible(offset: CharLineOffset): Boolean {
		val cursorTop = calculateOffsetYPosition(offset).toInt()
		val cursorHeight = calculateLineHeight(offset)
		val cursorBottom = cursorTop + cursorHeight

		val viewPortTop = scrollState.value
		val viewPortBottom = viewPortTop + viewportHeight

		// Check if both top and bottom of cursor are within viewport
		val topVisible = cursorTop in viewPortTop..viewPortBottom
		val bottomVisible = cursorBottom in viewPortTop..viewPortBottom
		val cursorSpansViewport = cursorTop <= viewPortTop && cursorBottom >= viewPortBottom

		return (topVisible && bottomVisible) || cursorSpansViewport
	}

	/**
	 * Content-space Y position (absolute, not viewport-relative) of [offset].
	 * Inverse of [offsetAtYPosition].
	 */
	fun calculateOffsetYPosition(offset: CharLineOffset): Float {
		return getLineOffsets().getWrapForDrawing(offset)?.offset?.y ?: 0f
	}

	@VisibleForTesting
	internal fun calculateLineHeight(offset: CharLineOffset): Int {
		val wrap = getLineOffsets().getWrapForDrawing(offset) ?: return 1
		return wrap.effectiveHeight.toInt().coerceAtLeast(1)
	}
}