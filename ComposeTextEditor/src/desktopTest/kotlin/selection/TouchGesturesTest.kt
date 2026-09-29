package selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The finger gestures that select: a double tap selects a word, a long press selects a
 * word, and dragging on from either extends the selection by word, as Android's text
 * fields do.
 */
@OptIn(ExperimentalTestApi::class)
class TouchGesturesTest {

	private val document = AnnotatedString("alpha beta gamma delta")

	@Test
	fun `a double tap selects the word`() = editorUiTest(initialText = document) {
		doubleTapAtCharacter(8)

		assertEquals("beta", selectedText)
		assertTrue(state.selector.isTouchSelection, "a finger selection has handles")
	}

	@Test
	fun `a double tap focuses the editor`() = editorUiTest(initialText = document, autoFocus = false) {
		doubleTapAtCharacter(8)

		assertTrue(state.isFocused)
		assertEquals("beta", selectedText)
	}

	@Test
	fun `a double tap then drag extends by word`() = editorUiTest(initialText = document) {
		doubleTapAtCharacter(8, toChar = 13)

		assertEquals("beta gamma", selectedText)
		assertTrue(state.selector.isTouchSelection)
	}

	@Test
	fun `a double tap then drag backwards keeps the tapped word`() = editorUiTest(initialText = document) {
		doubleTapAtCharacter(13, toChar = 2)

		assertEquals("alpha beta gamma", selectedText)
	}

	@Test
	fun `two slow taps do not select`() = editorUiTest(initialText = document) {
		tapAtCharacter(8)
		test.mainClock.advanceTimeBy(1_000)
		tapAtCharacter(8)

		assertNull(state.selector.selection)
		assertEquals(8, cursorIndex)
	}

	/** A finger drifts between taps, so nearby taps still pair; far apart ones are two taps. */
	@Test
	fun `two quick taps far apart do not select`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda"),
	) {
		touch {
			down(positionOfCharacter(2))
			up()
			advanceEventTime(50)
			down(positionOfCharacter(56))
			up()
		}

		assertNull(state.selector.selection)
		assertEquals(56, cursorIndex)
	}

	@Test
	fun `a second tap a little off the first still selects`() = editorUiTest(initialText = document) {
		touch {
			down(positionOfCharacter(8))
			up()
			advanceEventTime(50)
			down(positionOfCharacter(8) + Offset(12f, 6f))
			up()
		}

		assertEquals("beta", selectedText)
	}

	/** The double-tap window runs from the first tap's lift, so a slow first tap still pairs. */
	@Test
	fun `a held first tap still pairs with a quick second`() = editorUiTest(initialText = document) {
		touch {
			down(positionOfCharacter(8))
			advanceEventTime(250)
			up()
			advanceEventTime(100)
			down(positionOfCharacter(8))
			up()
		}

		assertEquals("beta", selectedText)
	}

	@Test
	fun `a tap right after a long press is not a double tap`() = editorUiTest(initialText = document) {
		longPressAtCharacter(8)
		assertEquals("beta", selectedText)

		tapAtCharacter(16)

		assertNull(state.selector.selection)
		assertEquals(16, cursorIndex)
	}

	@Test
	fun `a double tap in an empty editor selects nothing`() = editorUiTest {
		touch {
			down(Offset(10f, 10f))
			up()
			advanceEventTime(50)
			down(Offset(10f, 10f))
			up()
		}

		assertNull(state.selector.selection)
	}

	@Test
	fun `a long press then drag extends by word`() = editorUiTest(initialText = document) {
		longPressDragToCharacter(fromChar = 8, toChar = 16)

		assertEquals("beta gamma", selectedText)
		assertTrue(state.selector.isTouchSelection)
	}

	@Test
	fun `a long press then drag backwards keeps the pressed word`() = editorUiTest(initialText = document) {
		longPressDragToCharacter(fromChar = 13, toChar = 2)

		assertEquals("alpha beta gamma", selectedText)
	}

	/** The drag past touch slop is a selection, not a pan, so it must still focus. */
	@Test
	fun `a long press then drag focuses the editor`() = editorUiTest(
		initialText = document,
		autoFocus = false,
	) {
		longPressDragToCharacter(fromChar = 8, toChar = 16)

		assertTrue(state.isFocused)
		assertEquals("beta gamma", selectedText)
	}

	/** Once the long press has selected, the finger extends the selection rather than panning. */
	@Test
	fun `a long press then drag does not scroll the editor`() = editorUiTest(
		initialText = AnnotatedString((0 until 200).joinToString("\n") { "line $it" }),
	) {
		state.scrollState.scrollTo(400)
		waitForIdle()
		val scrollBefore = state.scrollState.value
		// A row below the first, which the scroll may have cut in half.
		val row = state.scrollManager.firstVisibleOffset.line + 2
		val from = positionOfCharacter(state.getCharacterIndex(CharLineOffset(row, 2)))

		longPressAt(from)
		touch {
			moveTo(from + Offset(0f, 40f))
			moveTo(from + Offset(0f, 80f))
			up()
		}

		assertEquals(scrollBefore, state.scrollState.value)
		assertTrue(selectedText.contains("\n"), "expected the drag to select across rows: $selectedText")
	}

	@Test
	fun `a long press then drag held below the viewport keeps scrolling`() = editorUiTest(
		initialText = AnnotatedString((0 until 200).joinToString("\n") { "line $it" }),
	) {
		val from = positionOfCharacter(2)
		longPressAt(from)
		test.mainClock.autoAdvance = false
		touch { moveTo(Offset(from.x, state.viewportSize.height + 120f)) }
		val scrollBefore = state.scrollState.value
		val selectedBefore = selectedText.length

		test.mainClock.advanceTimeBy(1_000)

		assertTrue(state.scrollState.value > scrollBefore, "the held drag should keep scrolling")
		assertTrue(selectedText.length > selectedBefore, "the selection should follow the scroll")
		touch { up() }
	}

	@Test
	fun `a long press then drag shows the magnifier at the moving end`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma delta\nsecond line"),
	) {
		longPressAt(positionOfCharacter(8))

		touch { moveTo(positionOfCharacter(26)) }

		val center = state.selector.magnifierCenter
		assertTrue(center != null, "expected a magnifier during the drag")
		assertEquals(positionOfCharacter(26).y, center.y, 0.5f)
		touch { up() }
		assertNull(state.selector.magnifierCenter)
	}
}
