package state

import com.darkrockstudios.texteditor.state.LayoutUpdate
import com.darkrockstudios.texteditor.state.mergedWith
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [mergedWith] may only produce a Partial when neither operand can have shifted the
 * other's line coordinates; every ambiguous composition must degrade to Full, because
 * an under-scoped merge silently reuses stale layouts instead of failing visibly.
 */
class LayoutUpdateMergeTest {

	private fun partial(first: Int, last: Int, delta: Int = 0) =
		LayoutUpdate.Partial(first, last, delta)

	@Test
	fun `full absorbs everything`() {
		assertEquals(LayoutUpdate.Full, LayoutUpdate.Full.mergedWith(partial(1, 2)))
		assertEquals(LayoutUpdate.Full, partial(1, 2).mergedWith(LayoutUpdate.Full))
	}

	@Test
	fun `spans only keeps the partial's shaping and respans every line`() {
		val p = partial(3, 7, 2)
		val merged = LayoutUpdate.Partial(3, 7, 2, spansFirst = 0, spansLast = LayoutUpdate.ALL_LINES)
		assertEquals(merged, LayoutUpdate.SpansOnly.mergedWith(p))
		assertEquals(merged, p.mergedWith(LayoutUpdate.SpansOnly))
	}

	@Test
	fun `a spans range joins a stable partial`() {
		assertEquals(
			LayoutUpdate.Partial(2, 4, 0, spansFirst = 7, spansLast = 9),
			partial(2, 4).mergedWith(LayoutUpdate.Spans(7, 9)),
		)
		assertEquals(LayoutUpdate.Spans(2, 9), LayoutUpdate.Spans(2, 4).mergedWith(LayoutUpdate.Spans(7, 9)))
	}

	@Test
	fun `a spans range below the shift point degrades to full`() {
		assertEquals(LayoutUpdate.Full, LayoutUpdate.Spans(12, 13).mergedWith(partial(5, 10, 5)))
		assertEquals(LayoutUpdate.Full, partial(5, 10, 5).mergedWith(LayoutUpdate.Spans(6, 6)))
	}

	@Test
	fun `a spans range above the shift point is kept`() {
		assertEquals(
			LayoutUpdate.Partial(5, 10, 5, spansFirst = 2, spansLast = 3),
			LayoutUpdate.Spans(2, 3).mergedWith(partial(5, 10, 5)),
		)
	}

	@Test
	fun `two stable partials union`() {
		assertEquals(partial(2, 9), partial(2, 4).mergedWith(partial(7, 9)))
	}

	@Test
	fun `two structural partials degrade to full`() {
		assertEquals(LayoutUpdate.Full, partial(2, 4, 1).mergedWith(partial(7, 9, -1)))
	}

	@Test
	fun `stable partial above the shift point degrades to full`() {
		// The stable range was posted before the insert below it could shift its
		// lines; reusing them by raw index would resurrect pre-edit layouts.
		assertEquals(LayoutUpdate.Full, partial(12, 13).mergedWith(partial(5, 10, 5)))
		assertEquals(LayoutUpdate.Full, partial(5, 10, 5).mergedWith(partial(12, 13)))
	}

	@Test
	fun `stable partial overlapping the shift point degrades to full`() {
		assertEquals(LayoutUpdate.Full, partial(6, 6).mergedWith(partial(5, 10, 5)))
	}

	@Test
	fun `stable partial fully below the shift point unions`() {
		assertEquals(
			partial(2, 10, 5),
			partial(2, 3).mergedWith(partial(5, 10, 5)),
		)
	}
}
