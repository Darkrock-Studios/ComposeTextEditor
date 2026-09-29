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
	private val getCursorAffinity: () -> CaretAffinity = { CaretAffinity.Downstream },
	val scrollState: TextEditorScrollState
) {
	private var scrollJob: Job? = null

	var totalContentHeight by mutableStateOf(0)
		private set

	/** Height of the laid-out rows, which unlike [totalContentHeight] may be under the viewport's. */
	private var contentHeight = 0

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

	/**
	 * The furthest scroll: the last row and the bottom padding at the viewport's bottom,
	 * or no scrolling at all when the content and its padding fit. Native editors add no
	 * room past the last line; the bottom content padding is that room when wanted.
	 */
	private val maxScroll: Int
		get() = maxOf(-topContentPaddingPx, contentHeight + bottomContentPaddingPx - viewportHeight)

	private fun applyScrollRange() {
		scrollState.viewportHeight = viewportHeight
		scrollState.minValue = -topContentPaddingPx
		scrollState.maxValue = maxScroll
	}

	fun updateContentHeight(height: Int) {
		contentHeight = height
		totalContentHeight = maxOf(height, viewportHeight)
		applyScrollRange()
	}

	/**
	 * Set while a drag auto-scroll runs: it owns the scroll then, and a caret it puts off
	 * screen (a line drag's, at the paragraph end) must not start a scroll against it.
	 * Every scroll to the caret goes through [ensureCursorVisible], which honours it.
	 */
	internal var cursorScrollSuppressed = false

	/** Stops a scroll animation in progress where it is. */
	internal fun stopScrolling() {
		scrollJob?.cancel()
		scrollJob = null
	}

	fun scrollToTop() {
		stopScrolling()
		scrollJob = scope.launch {
			scrollState.animateScrollTo(scrollState.minValue)
		}
	}

	fun scrollToBottom() {
		stopScrolling()
		scrollJob = scope.launch {
			scrollState.animateScrollTo(maxScroll)
		}
	}

	fun scrollToPosition(position: Int, animated: Boolean = true) {
		stopScrolling()
		scrollJob = scope.launch {
			val scrollToY = position.coerceIn(scrollState.minValue, maxScroll)
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
	fun scrollToPosition(offset: CharLineOffset, top: Boolean = false, animated: Boolean = true) =
		scrollToPosition(offset, CaretAffinity.Downstream, top, animated)

	private fun scrollToPosition(offset: CharLineOffset, affinity: CaretAffinity, top: Boolean, animated: Boolean) {
		if (offset.line >= getLines().size) return

		if (top) {
			val targetTop = calculateOffsetYPosition(offset, affinity).toInt()
			val minScroll = scrollState.minValue
			scrollToPosition(targetTop.coerceIn(minScroll, maxScroll), animated = animated)
			return
		}

		val cursorTop = calculateOffsetYPosition(offset, affinity).toInt()
		val cursorHeight = calculateLineHeight(offset, affinity)
		val viewportTop = scrollState.value
		val minScroll = scrollState.minValue

		// Just far enough to show the caret's whole row, as native editors scroll.
		val targetScroll = if (cursorTop < viewportTop) {
			cursorTop.coerceIn(minScroll, maxScroll)
		} else if (cursorTop + cursorHeight > viewportTop + viewportHeight) {
			(cursorTop + cursorHeight - viewportHeight).coerceIn(minScroll, maxScroll)
		} else {
			viewportTop
		}

		if(targetScroll != viewportTop) {
			stopScrolling()
			scrollJob = scope.launch {
				scrollState.animateScrollTo(targetScroll)
			}
		}
	}

	/** Scrolls to the row the caret is drawn on. */
	fun scrollToCursor() {
		scrollToPosition(getCursorPosition(), getCursorAffinity(), top = false, animated = true)
	}

	fun ensureCursorVisible() {
		if (cursorScrollSuppressed) return
		if (!isOffsetVisible(getCursorPosition(), getCursorAffinity())) {
			scrollToCursor()
		}
	}

	fun isOffsetVisible(offset: CharLineOffset): Boolean = isOffsetVisible(offset, CaretAffinity.Downstream)

	private fun isOffsetVisible(offset: CharLineOffset, affinity: CaretAffinity): Boolean {
		val cursorTop = calculateOffsetYPosition(offset, affinity).toInt()
		val cursorHeight = calculateLineHeight(offset, affinity)
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
	fun calculateOffsetYPosition(offset: CharLineOffset): Float = calculateOffsetYPosition(offset, CaretAffinity.Downstream)

	private fun calculateOffsetYPosition(offset: CharLineOffset, affinity: CaretAffinity): Float {
		return getLineOffsets().getWrapForDrawing(offset, affinity)?.offset?.y ?: 0f
	}

	@VisibleForTesting
	internal fun calculateLineHeight(offset: CharLineOffset): Int = calculateLineHeight(offset, CaretAffinity.Downstream)

	private fun calculateLineHeight(offset: CharLineOffset, affinity: CaretAffinity): Int {
		val wrap = getLineOffsets().getWrapForDrawing(offset, affinity) ?: return 1
		return wrap.effectiveHeight.toInt().coerceAtLeast(1)
	}
}