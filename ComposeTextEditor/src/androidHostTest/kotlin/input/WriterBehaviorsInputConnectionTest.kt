package input

import android.view.View
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.behaviors.AutoLink
import com.darkrockstudios.texteditor.behaviors.SmartPunctuation
import com.darkrockstudios.texteditor.state.linkAt
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** The writer behaviors reached through the Android [TextEditorInputConnection]. */
class WriterBehaviorsInputConnectionTest {

	private val state = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(""),
	)
	private val connection = TextEditorInputConnection(state, mockk<View>(relaxed = true))

	private fun text() = state.getAllText().text

	@Test
	fun `committed punctuation is substituted`() {
		state.editBehaviors += SmartPunctuation()

		connection.commitText("a", 1)
		connection.commitText("-", 1)
		connection.commitText("-", 1)
		connection.commitText(" ", 1)
		connection.setComposingText("it's", 1)
		connection.finishComposingText()

		assertEquals("a\u2014 it\u2019s", text())
	}

	@Test
	fun `a batch commit is substituted`() {
		state.editBehaviors += SmartPunctuation()

		connection.beginBatchEdit()
		connection.commitText("\"wait...\"", 1)
		connection.endBatchEdit()

		assertEquals("\u201Cwait\u2026\u201D", text())
	}

	@Test
	fun `a committed URL links on the space, and on Enter`() {
		state.editBehaviors.add(0, AutoLink())

		connection.setComposingText("https://a.com", 1)
		connection.commitText("https://a.com", 1)
		connection.commitText(" ", 1)
		connection.commitText("www.b.org", 1)
		connection.commitText("\n", 1)

		assertEquals("https://a.com www.b.org\n", text())
		assertEquals("https://a.com", state.linkAt(CharLineOffset(0, 0)))
		assertEquals("https://www.b.org", state.linkAt(CharLineOffset(0, 14)))
	}
}
