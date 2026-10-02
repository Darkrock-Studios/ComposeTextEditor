package input

import com.darkrockstudios.texteditor.input.ImeExpectation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Where the keyboard's own commands leave it expecting the selection. */
class ImeExpectationTest {

	private fun at(selStart: Int, selEnd: Int = selStart, compStart: Int = -1, compEnd: Int = -1, length: Int = 10) =
		ImeExpectation().apply { reset(selStart, selEnd, compStart, compEnd, length) }

	@Test
	fun `a commit lands over the selection and places the caret`() {
		val ime = at(2, 5)

		ime.commitText(1, 1)

		assertTrue(ime.expects(3, 3, -1, -1, 8))
	}

	@Test
	fun `a commit replaces the composition, and zero or less counts from its start`() {
		val ime = at(4, 4, 1, 4)

		ime.commitText(5, 0)

		assertTrue(ime.expects(1, 1, -1, -1, 12))
	}

	@Test
	fun `composing text becomes the composing region`() {
		val ime = at(3)

		ime.setComposingText(2, 1)
		ime.setComposingText(3, 1)

		assertTrue(ime.expects(6, 6, 3, 6, 13))
	}

	@Test
	fun `a surrounding delete counts from the selection's edges and keeps it`() {
		val ime = at(4, 6)

		ime.deleteSurroundingText(2, 3)

		assertTrue(ime.expects(2, 4, -1, -1, 5))
	}

	@Test
	fun `a composing region is ordered and clamped`() {
		val ime = at(3)

		ime.setComposingRegion(8, 1)
		assertTrue(ime.expects(3, 3, 1, 8, 10))

		ime.setComposingRegion(20, 20)
		assertTrue(ime.expects(3, 3, -1, -1, 10))
	}

	@Test
	fun `a command it cannot follow leaves it unknown until the next reset`() {
		val ime = at(3)

		ime.unknown()
		assertFalse(ime.expects(3, 3, -1, -1, 10))

		ime.reset(3, 3, -1, -1, 10)
		assertTrue(ime.expects(3, 3, -1, -1, 10))
	}

	@Test
	fun `a forward delete shortens the document with the caret in place`() {
		val ime = at(5)

		ime.deleteSurroundingText(0, 1)

		assertFalse(ime.expects(5, 5, -1, -1, 10))
		assertTrue(ime.expects(5, 5, -1, -1, 9))
	}

	@Test
	fun `a key event stays unknown until it has been handled`() {
		val ime = at(3)

		ime.keySent()
		ime.reset(3, 3, -1, -1, 10)
		assertFalse(ime.expects(3, 3, -1, -1, 10))

		ime.keyHandled()
		assertFalse(ime.expects(3, 3, -1, -1, 10), "until a flush has looked at the result")
		ime.reset(3, 3, -1, -1, 10)
		assertTrue(ime.expects(3, 3, -1, -1, 10))
	}
}
