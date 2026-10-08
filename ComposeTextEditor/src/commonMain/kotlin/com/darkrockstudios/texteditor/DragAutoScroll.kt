package com.darkrockstudios.texteditor

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import com.darkrockstudios.texteditor.state.TextEditorScrollState
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto-scroll for a drag that selects: while the pointer is above or below the
 * viewport, or with wrapping off left or right of it, the editor keeps scrolling that
 * way, even with the pointer held still, at a speed proportional to how far outside it is.
 * Sideways, the dragged point is kept inside the viewport. [onDrag] receives the point being dragged, the
 * pointer plus [targetOffset], kept on a row wholly inside the viewport; it gets it on
 * every move and on every frame that scrolls, so the selection follows the text as it
 * moves under the pointer. A handle drag passes the offset from the finger to the text
 * it moves; the scroll still starts at the finger, where the user can reach. A drag that
 * starts at [startAt] past an edge, on a handle hanging past it, scrolls that way only once
 * the pointer is further out than it started.
 */
internal class DragAutoScroll(
	private val state: TextEditorState,
	private val scope: CoroutineScope,
	private val targetOffset: Offset = Offset.Zero,
	private val startAt: Offset? = null,
	private val onDrag: (Offset) -> Unit,
) {
	private var pointer = Offset.Zero
	private var ticker: Job? = null

	private val vertical = Axis(state.scrollState)
	private val sideways = Axis(state.horizontalScrollState)

	/** One direction's scroll, which a held pointer moves at a speed proportional to how far out it is. */
	private class Axis(private val scroll: TextEditorScrollState) {
		// The scroll position is whole pixels; a slow scroll moves less than one per frame.
		var fraction = 0f

		/** Scrolls for [seconds] with the pointer [overflow] pixels out, returning the pixels scrolled. */
		fun step(overflow: Float, seconds: Float): Float {
			if (overflow == 0f) return 0f
			val wanted = fraction + overflow * SPEED_PER_PX_OUTSIDE * seconds
			val whole = wanted.toInt()
			fraction = wanted - whole
			return if (whole != 0) scroll.scrollBy(whole.toFloat()) else 0f
		}
	}

	fun update(position: Offset) {
		pointer = position
		// Back inside, the editor's scroll into view is the drag's again, so it is
		// handed back before this move places the caret.
		if (!overflows(position)) stop()
		onDrag(clampToViewport(position + targetOffset))
		if (overflows(position) && ticker == null) {
			// This owns the scroll now: the editor's scroll into view would restart
			// against it every frame for a caret a line drag leaves off screen.
			state.scrollManager.stopScrolling()
			state.scrollManager.cursorScrollSuppressed = true
			ticker = scope.launch { tick() }
		}
	}

	/** Ends the scroll, and reveals the caret the drag may have left off screen meanwhile. */
	fun stop() {
		val wasScrolling = ticker != null
		ticker?.cancel()
		ticker = null
		vertical.fraction = 0f
		sideways.fraction = 0f
		if (wasScrolling) {
			state.scrollManager.cursorScrollSuppressed = false
			state.scrollManager.ensureCursorVisible()
		}
	}

	private suspend fun tick() {
		var lastFrame = withFrameNanos { it }
		while (currentCoroutineContext().isActive) {
			withFrameNanos { frame ->
				// A stalled frame (a GC, a hidden window) must not turn into a jump of pages.
				val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(MAX_FRAME_SECONDS)
				lastFrame = frame
				val scrolled = vertical.step(pointerOverflow(pointer), seconds) + sideways.step(pointerOverflowX(pointer), seconds)
				if (scrolled != 0f) onDrag(clampToViewport(pointer + targetOffset))
			}
		}
	}

	/** How far [position] is above (negative) or below (positive) the viewport. */
	private fun overflow(position: Offset): Float = overflow(position, top = 0f, bottom = state.viewportSize.height)

	/** How far the pointer at [position] is past the edges that scroll: the viewport's, or [startAt] past them. */
	private fun pointerOverflow(position: Offset): Float = edgeOverflow(position.y, startAt?.y, state.viewportSize.height)

	/** [at]'s distance before 0 (negative) or past [extent], or past [start] where the drag began beyond an edge. */
	private fun edgeOverflow(at: Float, start: Float?, extent: Float): Float =
		if (start == null) overflow(at, 0f, extent) else overflow(at, minOf(0f, start), maxOf(extent, start))

	private fun overflow(position: Offset, top: Float, bottom: Float): Float = overflow(position.y, top, bottom)

	private fun overflow(at: Float, from: Float, to: Float): Float = when {
		at < from -> at - from
		at > to -> at - to
		else -> 0f
	}

	/** Whether the pointer at [position] is past an edge that scrolls. */
	private fun overflows(position: Offset): Boolean = pointerOverflow(position) != 0f || pointerOverflowX(position) != 0f

	/**
	 * How far the pointer at [position] is left (negative) or right (positive) of the
	 * viewport, or of [startAt] past its sides; zero where the content cannot scroll that
	 * way, so a drag through the side padding at the content's edge scrolls nothing.
	 */
	private fun pointerOverflowX(position: Offset): Float {
		val scroll = state.horizontalScrollState
		val overflow = edgeOverflow(position.x, startAt?.x, state.viewportSize.width)
		val canScroll = if (overflow < 0f) scroll.value > 0 else scroll.value < scroll.maxValue
		return if (canScroll) overflow else 0f
	}

	/**
	 * [position] held inside the viewport sideways while there is a sideways range, short
	 * of the right edge by the room the editor keeps for a caret there, so the caret the
	 * drag places is in view and nothing scrolls to reveal it once the drag stops.
	 */
	private fun clampSideways(position: Offset): Offset {
		if (state.horizontalScrollState.maxValue == 0) return position
		val right = (state.viewportSize.width - state.lineBreakWidth - 1f).coerceAtLeast(0f)
		return position.copy(x = position.x.coerceIn(0f, right))
	}

	/**
	 * [position] moved onto the edge row the drag has reached when it is outside the
	 * viewport. The row must be wholly visible: a caret on a partly visible one would start
	 * the editor's own scroll to reveal it, which fights this one frame by frame. Once the
	 * first or last row is in view, past that edge means the document's start or end,
	 * inside the viewport (its padding, the space under a short document) or out of it.
	 */
	private fun clampToViewport(position: Offset): Offset {
		val overflow = overflow(position)
		if (overflow == 0f) return pastDocumentEdge(position) ?: clampSideways(position)
		val top = state.scrollState.value.toFloat()
		val bottom = top + state.viewportSize.height
		val rows = state.lineOffsets
		// Rows run top to bottom, so both searches are binary.
		val row = if (overflow > 0f) {
			val last = rows.lastOrNull() ?: return position
			if (last.bandBottom <= bottom) {
				// With the last row in view, far right of it is the document's end.
				return Offset(DOCUMENT_EDGE_X, last.bandMiddle - top)
			}
			val pastBottom = rows.firstRowWhere { it.bandBottom > bottom }
			// A row taller than the viewport is never wholly inside it; take the one at the edge.
			rows.getOrNull(pastBottom - 1)?.takeIf { it.bandTop >= top } ?: rows.getOrNull(pastBottom)
		} else {
			val firstRow = rows.firstOrNull() ?: return position
			if (firstRow.bandTop >= top) {
				// With the first row in view, far left of it is the document's start.
				return Offset(-DOCUMENT_EDGE_X, firstRow.bandMiddle - top)
			}
			val firstBelowTop = rows.firstRowWhere { it.bandTop >= top }
			val first = rows.getOrNull(firstBelowTop)
			first?.takeIf { it.bandBottom <= bottom } ?: rows.getOrNull(firstBelowTop - 1)
		}
		val y = row?.let { it.bandMiddle - top }
			?: position.y.coerceIn(0f, (state.viewportSize.height - 1f).coerceAtLeast(0f))
		return clampSideways(position.copy(y = y))
	}

	/** The document's start above its first row, its end below its last, else null. */
	private fun pastDocumentEdge(position: Offset): Offset? {
		val rows = state.lineOffsets
		val first = rows.firstOrNull() ?: return null
		val last = rows.last()
		val top = state.scrollState.value.toFloat()
		val y = position.y + top
		return when {
			y < first.bandTop -> Offset(-DOCUMENT_EDGE_X, first.bandMiddle - top)
			y >= last.bandBottom ->
				Offset(DOCUMENT_EDGE_X, last.bandMiddle - top)
			else -> null
		}
	}

	private companion object {
		/** Pixels scrolled per second for each pixel the pointer is outside the viewport. */
		const val SPEED_PER_PX_OUTSIDE = 10f

		const val MAX_FRAME_SECONDS = 0.05f

		const val DOCUMENT_EDGE_X = 1_000_000f
	}
}

/** The middle of a row's band (see [bandTop]). */
private val LineWrap.bandMiddle: Float get() = (bandTop + bandBottom) / 2f
