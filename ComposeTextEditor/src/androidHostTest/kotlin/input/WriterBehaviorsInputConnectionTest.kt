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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
	fun `a composition the closing connection ends is substituted`() {
		state.editBehaviors += SmartPunctuation()
		connection.setComposingText("don't", 1)

		connection.closeConnection()

		assertEquals("don\u2019t", text())
		assertNull(state.composingRange)
		state.undo()
		assertEquals("don't", text())
	}

	@Test
	fun `a composition open through a batch when focus is lost is substituted by the close`() {
		state.editBehaviors += SmartPunctuation()
		connection.beginBatchEdit()
		connection.setComposingText("don't", 1)

		state.updateFocus(false)
		assertEquals("don't", text())
		connection.closeConnection()

		assertEquals("don\u2019t", text())
		assertNull(state.composingRange)
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
	fun `a batch's later commands address the text the keyboard committed`() {
		state.editBehaviors += SmartPunctuation()
		connection.commitText("a", 1)

		// The keyboard's mirror holds "a--" throughout the batch: the dash the
		// behavior makes of "--" must not be there yet when it deletes those two.
		connection.beginBatchEdit()
		connection.commitText("--", 1)
		assertEquals("a--", text(), "No behavior runs while the batch is open")
		connection.deleteSurroundingText(2, 0)
		connection.endBatchEdit()

		assertEquals("a", text())
	}

	@Test
	fun `a composition set over the commit in the same batch replaces what the keyboard meant`() {
		state.editBehaviors += SmartPunctuation()
		connection.commitText("a", 1)

		connection.beginBatchEdit()
		connection.commitText("...", 1)
		// Mirror "a...": the keyboard re-marks the last two dots and retypes them.
		connection.setComposingRegion(2, 4)
		connection.setComposingText("x", 1)
		connection.endBatchEdit()

		assertEquals("a.x", text())
	}

	@Test
	fun `text landed in a batch is offered once it ends, where it then stands, and undoes as typed`() {
		state.editBehaviors += SmartPunctuation()
		connection.commitText("a", 1)
		val generation = state.imeResyncGeneration

		connection.beginBatchEdit()
		connection.commitText("--", 1)
		connection.commitText(" ", 1)
		connection.commitText("\"", 1)
		assertTrue(state.imeResyncGeneration == generation, "No resync while the batch is open")
		connection.endBatchEdit()

		assertEquals("a\u2014 \u201C", text())
		assertTrue(state.imeResyncGeneration > generation)
		assertEquals(CharLineOffset(0, 4), state.cursorPosition)

		state.undo()
		assertEquals("a\u2014 \"", text())
		state.undo()
		assertEquals("a-- \"", text())
	}

	@Test
	fun `a composition the batch opened after the commit outlives the substitution`() {
		state.editBehaviors += SmartPunctuation()
		connection.commitText("a", 1)

		connection.beginBatchEdit()
		connection.commitText("--", 1)
		connection.setComposingText("x", 1)
		connection.endBatchEdit()
		assertEquals("a\u2014x", text())
		assertEquals(CharLineOffset(0, 2), state.composingRange?.start)

		connection.setComposingText("xy", 1)
		connection.commitText("xy", 1)
		assertEquals("a\u2014xy", text())
	}

	@Test
	fun `a batch the keyboard never closes keeps its text and offers nothing`() {
		state.editBehaviors += SmartPunctuation()

		connection.beginBatchEdit()
		connection.commitText("a--", 1)
		connection.closeConnection()

		assertEquals("a--", text())
		assertFalse(state.platformExtensions.isInBatchEdit)
		// The next connection's commits are offered as usual.
		val next = TextEditorInputConnection(state, mockk<View>(relaxed = true))
		next.commitText("\"", 1)
		assertEquals("a--\u201C", text())
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
