package texteditor.state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TextEditorStateEqualityTest {

	private fun TestScope.createState(initialText: String?) =
		TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = initialText?.let { AnnotatedString(it) },
		)

	@Test
	fun `equals is reference identity`() = runTest {
		val state = createState("content")
		assertEquals(state, state)
	}

	@Test
	fun `distinct instances with same content are not equal`() = runTest {
		val state1 = createState("Line 1\nLine 2")
		val state2 = createState("Line 1\nLine 2")

		assertNotEquals(state1, state2)
	}

	@Test
	fun `hashCode is stable across edits`() = runTest {
		val state = createState("Line 1")
		val before = state.hashCode()

		state.setText("completely different content\nwith more lines")

		assertEquals(before, state.hashCode())
	}

	@Test
	fun `instances are usable as hash keys`() = runTest {
		val state1 = createState("same")
		val state2 = createState("same")

		val map = HashMap<TextEditorState, String>()
		map[state1] = "first"
		map[state2] = "second"

		assertEquals(2, map.size)
		assertEquals("first", map[state1])
		assertEquals("second", map[state2])

		state1.setText("edited")
		assertEquals("first", map[state1])
	}

	@Test
	fun `contentEquals same instance`() = runTest {
		val state = createState("content")
		assertTrue(state.contentEquals(state))
	}

	@Test
	fun `contentEquals same content`() = runTest {
		val state1 = createState("Line 1\nLine 2")
		val state2 = createState("Line 1\nLine 2")

		assertTrue(state1.contentEquals(state2))
		assertTrue(state2.contentEquals(state1))
	}

	@Test
	fun `contentEquals different content`() = runTest {
		val state1 = createState("Line 1")
		val state2 = createState("Different line")

		assertFalse(state1.contentEquals(state2))
	}

	@Test
	fun `contentEquals different number of lines`() = runTest {
		val state1 = createState("Line 1")
		val state2 = createState("Line 1\nLine 2")

		assertFalse(state1.contentEquals(state2))
	}

	@Test
	fun `contentEquals diverges after an edit`() = runTest {
		val state1 = createState("shared")
		val state2 = createState("shared")
		assertTrue(state1.contentEquals(state2))

		state1.setText("changed")

		assertFalse(state1.contentEquals(state2))
	}
}
