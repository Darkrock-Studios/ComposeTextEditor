package state

import com.darkrockstudios.texteditor.state.backspaceStart
import com.darkrockstudios.texteditor.state.followingGraphemeBoundary
import com.darkrockstudios.texteditor.state.isGraphemeBoundary
import com.darkrockstudios.texteditor.state.precedingGraphemeBoundary
import com.darkrockstudios.texteditor.state.snapToGraphemeBoundary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextBreaksTest {
	private val grin = "😀"
	private val family = "👨‍👩‍👧"
	private val flag = "🇯🇵"
	private val thumbsUpMedium = "👍🏽"
	private val keycapOne = "1️⃣"
	private val eAcute = "é"

	@Test
	fun `clusters are stepped whole in both directions`() {
		val text = "a$family$flag${eAcute}b"
		val boundaries = generateSequence(0) { i -> text.followingGraphemeBoundary(i).takeIf { it > i } }.toList()
		assertEquals(listOf(0, 1, 9, 13, 15, 16), boundaries)
		val backwards = generateSequence(text.length) { i -> if (i == 0) null else text.precedingGraphemeBoundary(i) }.toList()
		assertEquals(listOf(16, 15, 13, 9, 1, 0), backwards)
	}

	@Test
	fun `the ends and the empty string are boundaries`() {
		assertTrue("".isGraphemeBoundary(0))
		assertEquals(0, "".precedingGraphemeBoundary(0))
		assertEquals(0, "".followingGraphemeBoundary(0))
		assertEquals(0, "ab".precedingGraphemeBoundary(0))
		assertEquals(2, "ab".followingGraphemeBoundary(2))
		assertEquals(2, "ab".followingGraphemeBoundary(5))
		// A layout that lags the text can ask past the end; that is the end.
		assertEquals(2, "ab".precedingGraphemeBoundary(5))
		assertEquals(1, "ab".precedingGraphemeBoundary(2))
	}

	@Test
	fun `a position inside a cluster is not a boundary and snaps either way`() {
		val text = "a${grin}b"
		assertFalse(text.isGraphemeBoundary(2))
		assertTrue(text.isGraphemeBoundary(1))
		assertTrue(text.isGraphemeBoundary(3))
		assertEquals(1, text.snapToGraphemeBoundary(2, forward = false))
		assertEquals(3, text.snapToGraphemeBoundary(2, forward = true))
		assertEquals(3, text.snapToGraphemeBoundary(3, forward = true))
	}

	@Test
	fun `backspace takes an emoji sequence whole`() {
		assertEquals(1, "a$grin".backspaceStart(3))
		assertEquals(1, "a$family".backspaceStart(9))
		assertEquals(1, "a$flag".backspaceStart(5))
		assertEquals(1, "a$thumbsUpMedium".backspaceStart(5))
		assertEquals(1, "a$keycapOne".backspaceStart(4))
		assertEquals(1, "a❤️".backspaceStart(3))
	}

	@Test
	fun `backspace takes a combining mark off its base on its own`() {
		assertEquals(2, "a$eAcute".backspaceStart(3))
		assertEquals(1, "a$eAcute".backspaceStart(2))
		assertEquals(2, "año".backspaceStart(3))
		// Devanagari ka + virama + ssa: one cluster, deleted a code point at a time.
		assertEquals(2, "क्ष".backspaceStart(3))
	}

	@Test
	fun `backspace on plain text and at the start`() {
		assertEquals(0, "a".backspaceStart(1))
		assertEquals(0, "a".backspaceStart(0))
		assertEquals(1, "日本".backspaceStart(2))
	}
}
