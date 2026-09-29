package com.darkrockstudios.texteditor

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto-scroll for a drag that selects: while the pointer is above or below the
 * viewport the editor keeps scrolling, even with the pointer held still, at a speed
 * proportional to how far outside it is. [onDrag] receives the pointer moved onto the
 * nearest row wholly inside the viewport, on every move and on every scrolled frame,
 * so the selection follows the text as it moves under the pointer.
 */
internal class DragAutoScroll(
	private val state: TextEditorState,
	private val scope: CoroutineScope,
	private val onDrag: (Offset) -> Unit,
) {
	private var pointer = Offset.Zero
	private var ticker: Job? = null

	// The scroll position is whole pixels; a slow scroll moves less than one per frame.
	private var fraction = 0f

	fun update(position: Offset) {
		pointer = position
		onDrag(clampToViewport(position))
		if (overflow(position) == 0f) {
			stop()
		} else if (ticker == null) {
			ticker = scope.launch { tick() }
		}
	}

	fun stop() {
		ticker?.cancel()
		ticker = null
	}

	private suspend fun tick() {
		var lastFrame = withFrameNanos { it }
		while (scope.isActive) {
			withFrameNanos { frame ->
				val seconds = (frame - lastFrame) / 1_000_000_000f
				lastFrame = frame
				val overflow = overflow(pointer)
				if (overflow != 0f) {
					val wanted = fraction + overflow * SPEED_PER_PX_OUTSIDE * seconds
					val whole = wanted.toInt()
					fraction = wanted - whole
					if (whole != 0) state.scrollState.scrollBy(whole.toFloat())
					onDrag(clampToViewport(pointer))
				}
			}
		}
	}

	/** How far [position] is above (negative) or below (positive) the viewport. */
	private fun overflow(position: Offset): Float {
		val height = state.viewportSize.height
		return when {
			position.y < 0f -> position.y
			position.y > height -> position.y - height
			else -> 0f
		}
	}

	/**
	 * [position] moved onto the edge row the drag has reached. The row must be wholly
	 * visible: a caret on a partly visible one would start the editor's own scroll to
	 * reveal it, which fights this one frame by frame.
	 */
	private fun clampToViewport(position: Offset): Offset {
		val overflow = overflow(position)
		if (overflow == 0f) return position
		val top = state.scrollState.value.toFloat()
		val bottom = top + state.viewportSize.height
		val rows = state.lineOffsets
		// Rows run top to bottom, so both searches are binary; neither comparison returns 0,
		// so each result is -(first row past the boundary) - 1.
		val row = if (overflow > 0f) {
			val pastBottom = -rows.binarySearch { if (it.offset.y + it.effectiveHeight <= bottom) -1 else 1 } - 1
			rows.getOrNull(pastBottom - 1)
		} else {
			val firstBelowTop = -rows.binarySearch { if (it.offset.y < top) -1 else 1 } - 1
			rows.getOrNull(firstBelowTop)
		}
		val y = row?.let { it.offset.y + it.effectiveHeight / 2f - top }
			?: position.y.coerceIn(0f, (state.viewportSize.height - 1f).coerceAtLeast(0f))
		return position.copy(y = y)
	}

	private companion object {
		/** Pixels scrolled per second for each pixel the pointer is outside the viewport. */
		const val SPEED_PER_PX_OUTSIDE = 10f
	}
}
