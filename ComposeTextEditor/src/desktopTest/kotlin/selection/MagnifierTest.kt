package selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the magnifier looks while a touch handle is dragged. Only Android draws one, so
 * the desktop suite checks the point it is given.
 */
class MagnifierTest {

	private val document = AnnotatedString("alpha beta gamma delta\nsecond line")

	@Test
	fun `dragging a selection handle magnifies the dragged end's row`() = editorUiTest(initialText = document) {
		longPressAtCharacter(8)
		val grab = handleCenter(isStart = false)
		val delta = positionOfCharacter(16) - positionOfCharacter(10)

		touch {
			down(grab)
			moveTo(grab + delta)
		}

		val center = assertNotNull(state.selector.magnifierCenter)
		assertEquals(positionOfCharacter(16).y, center.y, 0.5f)
		assertEquals(positionOfCharacter(16).x, center.x, 1f)

		touch { up() }
		assertNull(state.selector.magnifierCenter)
	}

	@Test
	fun `dragging the caret handle magnifies the caret's row`() = editorUiTest(initialText = document) {
		tapAtCharacter(8)
		val grab = caretHandleCenter()

		touch {
			down(grab)
			moveTo(grab + Offset(0f, positionOfCharacter(26).y - positionOfCharacter(8).y))
		}

		val center = assertNotNull(state.selector.magnifierCenter)
		assertEquals(positionOfCharacter(26).y, center.y, 0.5f)

		touch { up() }
		assertNull(state.selector.magnifierCenter)
	}

	@Test
	fun `the magnifier shows as soon as a handle is grabbed`() = editorUiTest(initialText = document) {
		longPressAtCharacter(8)

		touch { down(handleCenter(isStart = false)) }

		assertNotNull(state.selector.magnifierCenter)
		touch { up() }
	}

	@Test
	fun `the magnifier stays within the row's text`() = editorUiTest(initialText = document) {
		longPressAtCharacter(8)
		val grab = handleCenter(isStart = false)

		touch {
			down(grab)
			moveTo(grab + Offset(2_000f, 0f))
		}

		val center = assertNotNull(state.selector.magnifierCenter)
		assertTrue(center.x <= positionOfCharacter(22).x + 1f, "past the row's end: ${center.x}")
		touch { up() }
	}

	@Test
	fun `a mouse drag shows no magnifier`() = editorUiTest(initialText = document) {
		mouse {
			moveTo(positionOfCharacter(2))
			press()
			moveTo(positionOfCharacter(12))
		}

		assertNull(state.selector.magnifierCenter)
		mouse(fresh = false) { release() }
	}
}
