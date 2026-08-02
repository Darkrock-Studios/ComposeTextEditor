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
	fun `spans only is the identity`() {
		val p = partial(3, 7, 2)
		assertEquals(p, LayoutUpdate.SpansOnly.mergedWith(p))
		assertEquals(p, p.mergedWith(LayoutUpdate.SpansOnly))
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
