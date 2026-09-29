package input

import android.view.View
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Android [TextEditorInputConnection] reaches [EditBehavior.onTextInput] when text is committed, never while composing. */
class TextEditorInputConnectionTextInputTest {

	private val state = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(""),
	)
	private val connection = TextEditorInputConnection(state, mockk<View>(relaxed = true))
	private val offered = mutableListOf<String>()

	init {
		state.editBehaviors += object : EditBehavior {
			override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
				offered += text
				return false
			}
		}
	}

	@Test
	fun `composing updates are not offered, the commit is`() {
		connection.setComposingText("h", 1)
		connection.setComposingText("he", 1)
		assertEquals(emptyList(), offered)

		connection.commitText("hey", 1)

		assertEquals(listOf("hey"), offered)
		assertEquals("hey", state.getAllText().text)
	}

	@Test
	fun `finishing a typed composition offers it`() {
		connection.setComposingText("word", 1)

		connection.finishComposingText()

		assertEquals(listOf("word"), offered)
	}

	@Test
	fun `a plain commit is offered`() {
		connection.commitText("a", 1)
		connection.commitText("b", 1)

		assertEquals(listOf("a", "b"), offered)
	}
}
