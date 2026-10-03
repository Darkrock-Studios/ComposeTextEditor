package com.darkrockstudios.texteditor

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto-scroll for a drag that selects: while the pointer is above or below the
 * viewport the editor keeps scrolling, even with the pointer held still, at a speed
 * proportional to how far outside it is. [onDrag] receives the point being dragged, the
 * pointer plus [targetOffset], kept on a row wholly inside the viewport; it gets it on
 * every move and on every frame that scrolls, so the selection follows the text as it
 * moves under the pointer. A handle drag passes the offset from the finger to the text
 * it moves; the scroll still starts at the finger, where the user can reach.
 */
internal class DragAutoScroll(
	private val state: TextEditorState,
	private val scope: CoroutineScope,
	private val targetOffset: Offset = Offset.Zero,
	private val onDrag: (Offset) -> Unit,
) {
	private var pointer = Offset.Zero
	private var ticker: Job? = null

	// The scroll position is whole pixels; a slow scroll moves less than one per frame.
	private var fraction = 0f

	fun update(position: Offset) {
		pointer = position
		onDrag(clampToViewport(position + targetOffset))
		if (overflow(position) == 0f) {
			stop()
		} else if (ticker == null) {
			ticker = scope.launch { tick() }
		}
	}

	fun stop() {
		ticker?.cancel()
		ticker = null
		fraction = 0f
	}

	private suspend fun tick() {
		var lastFrame = withFrameNanos { it }
		while (currentCoroutineContext().isActive) {
			withFrameNanos { frame ->
				// A stalled frame (a GC, a hidden window) must not turn into a jump of pages.
				val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(MAX_FRAME_SECONDS)
				lastFrame = frame
				val overflow = overflow(pointer)
				if (overflow != 0f) {
					val wanted = fraction + overflow * SPEED_PER_PX_OUTSIDE * seconds
					val whole = wanted.toInt()
					fraction = wanted - whole
					val scrolled = if (whole != 0) state.scrollState.scrollBy(whole.toFloat()) else 0f
					if (scrolled != 0f) onDrag(clampToViewport(pointer + targetOffset))
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
	 * [position] moved onto the edge row the drag has reached when it is outside the
	 * viewport. The row must be wholly visible: a caret on a partly visible one would start
	 * the editor's own scroll to reveal it, which fights this one frame by frame. Once the
	 * first or last row is in view, past that edge means the document's start or end.
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
			// With the last row in view, below it is where the document's end is read from.
			val last = rows.lastOrNull() ?: return position
			if (last.offset.y + last.effectiveHeight <= bottom) return position
			val pastBottom = -rows.binarySearch { if (it.offset.y + it.effectiveHeight <= bottom) -1 else 1 } - 1
			// A row taller than the viewport is never wholly inside it; take the one at the edge.
			rows.getOrNull(pastBottom - 1)?.takeIf { it.offset.y >= top } ?: rows.getOrNull(pastBottom)
		} else {
			val firstRow = rows.firstOrNull() ?: return position
			if (firstRow.offset.y >= top) {
				// With the first row in view, far left of it is the document's start.
				return Offset(-DOCUMENT_EDGE_X, firstRow.offset.y + firstRow.effectiveHeight / 2f - top)
			}
			val firstBelowTop = -rows.binarySearch { if (it.offset.y < top) -1 else 1 } - 1
			val first = rows.getOrNull(firstBelowTop)
			first?.takeIf { it.offset.y + it.effectiveHeight <= bottom } ?: rows.getOrNull(firstBelowTop - 1)
		}
		val y = row?.let { it.offset.y + it.effectiveHeight / 2f - top }
			?: position.y.coerceIn(0f, (state.viewportSize.height - 1f).coerceAtLeast(0f))
		return position.copy(y = y)
	}

	private companion object {
		/** Pixels scrolled per second for each pixel the pointer is outside the viewport. */
		const val SPEED_PER_PX_OUTSIDE = 10f

		const val MAX_FRAME_SECONDS = 0.05f

		const val DOCUMENT_EDGE_X = 1_000_000f
	}
}
