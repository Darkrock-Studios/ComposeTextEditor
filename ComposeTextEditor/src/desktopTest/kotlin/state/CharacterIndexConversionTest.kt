package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** Flat character indices and line offsets convert into each other, clamped into the document. */
class CharacterIndexConversionTest {

	private val state = TextEditorState(TestScope(), mockk(relaxed = true), AnnotatedString("ab\ncde\n\nf"))

	@Test
	fun `indices convert to offsets and back`() {
		val text = "ab\ncde\n\nf"
		for (index in 0..text.length) {
			assertEquals(index, state.getCharacterIndex(state.getOffsetAtCharacter(index)), "index $index")
		}
		assertEquals(CharLineOffset(1, 3), state.getOffsetAtCharacter(6))
		assertEquals(CharLineOffset(2, 0), state.getOffsetAtCharacter(7))
	}

	@Test
	fun `an index before the document is its start`() {
		assertEquals(CharLineOffset(0, 0), state.getOffsetAtCharacter(-1))
		assertEquals(CharLineOffset(0, 0), state.getOffsetAtCharacter(Int.MIN_VALUE))
	}

	@Test
	fun `an index past the document is its end`() {
		assertEquals(CharLineOffset(3, 1), state.getOffsetAtCharacter(10))
		assertEquals(CharLineOffset(3, 1), state.getOffsetAtCharacter(Int.MAX_VALUE))
	}

	@Test
	fun `an offset outside the document is clamped into it`() {
		assertEquals(9, state.getCharacterIndex(CharLineOffset(7, 3)))
		assertEquals(6, state.getCharacterIndex(CharLineOffset(1, 40)))
		assertEquals(0, state.getCharacterIndex(CharLineOffset(-1, -5)))
		with(state) { assertEquals(9, CharLineOffset(7, 3).toCharacterIndex()) }
	}
}
