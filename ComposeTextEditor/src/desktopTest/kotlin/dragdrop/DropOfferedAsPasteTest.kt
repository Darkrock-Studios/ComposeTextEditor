package dragdrop

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.behaviors.AutoLink
import com.darkrockstudios.texteditor.dragdrop.dropText
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.EditorInputFilter
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A drop is offered to the behaviors as a paste, and a host can offer its own paste (5.12). */
class DropOfferedAsPasteTest {

	private fun state(text: String): TextEditorState =
		TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true)).apply { setText(text) }

	private fun TextEditorState.links(): List<Pair<String, String>> =
		richSpanManager.getAllRichSpans()
			.filter { it.style is LinkSpanStyle }
			.map { getStringInRange(it.range) to (it.style as LinkSpanStyle).url }

	private class Recorder : EditBehavior {
		val pasted = mutableListOf<Pair<String, TextEditorRange>>()
		override fun onPaste(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
			pasted += text to range
			return false
		}
	}

	@Test
	fun `a dropped URL links and one undo takes only the link off`() {
		val state = state("see ")
		state.editBehaviors.add(0, AutoLink())
		state.dropText(AnnotatedString("https://example.com"), null, CharLineOffset(0, 4), moveFrom = null)

		assertEquals("see https://example.com", state.getAllText().text)
		assertEquals(listOf("https://example.com" to "https://example.com"), state.links())

		state.undo()
		assertEquals("see https://example.com", state.getAllText().text)
		assertEquals(emptyList(), state.links())
	}

	@Test
	fun `a drop is offered where it landed`() {
		val state = state("ab")
		val recorder = Recorder()
		state.editBehaviors += recorder
		state.dropText(AnnotatedString("x\nyz"), null, CharLineOffset(0, 1), moveFrom = null)

		assertEquals("ax\nyzb", state.getAllText().text)
		assertEquals(listOf("x\nyz" to TextEditorRange(CharLineOffset(0, 1), CharLineOffset(1, 2))), recorder.pasted)
	}

	@Test
	fun `a move within the editor is not offered`() {
		val state = state("one two")
		val recorder = Recorder()
		state.editBehaviors += recorder
		state.dropText(
			AnnotatedString("one "),
			null,
			CharLineOffset(0, 7),
			moveFrom = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 4)),
			whole = true,
		)

		assertEquals("twoone ", state.getAllText().text)
		assertEquals(emptyList(), recorder.pasted)
	}

	@Test
	fun `a refused drop is not offered`() {
		val state = state("ab")
		val recorder = Recorder()
		state.editBehaviors += recorder
		state.inputFilter = EditorInputFilter { _, _, _ -> null }
		state.dropText(AnnotatedString("x"), null, CharLineOffset(0, 1), moveFrom = null)

		assertEquals("ab", state.getAllText().text)
		assertEquals(emptyList(), recorder.pasted)
	}

	@Test
	fun `a host's own paste is offered through pasteLanded`() {
		val state = state("see ")
		state.editBehaviors.add(0, AutoLink())
		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.insertStringAtCursor("https://example.com")
		state.pasteLanded("https://example.com", TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 23)))

		assertEquals(listOf("https://example.com" to "https://example.com"), state.links())
		state.undo()
		assertEquals("see https://example.com", state.getAllText().text)
		assertEquals(emptyList(), state.links())
	}

	@Test
	fun `a host's paste outside the document is refused`() {
		val state = state("ab\ncd")
		assertFailsWith<IllegalArgumentException> {
			state.pasteLanded("x", TextEditorRange(CharLineOffset(2, 0), CharLineOffset(2, 1)))
		}
		assertFailsWith<IllegalArgumentException> {
			state.pasteLanded("x", TextEditorRange(CharLineOffset(0, 1), CharLineOffset(0, 5)))
		}
		assertFailsWith<IllegalArgumentException> {
			state.pasteLanded("x", TextEditorRange(CharLineOffset(1, 0), CharLineOffset(0, 1)))
		}
	}
}
