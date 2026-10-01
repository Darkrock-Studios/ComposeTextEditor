package markdown

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Carriage returns never reach a line through markdown, as through every other path (core's `LineEndingsTest`). */
class MarkdownLineEndingsTest {

	@Test
	fun `markdown import splits on CRLF`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString(""))
		state.withMarkdown().importMarkdown("**bold**\r\nplain\r\n\r\n- item\r\n")
		assertEquals(listOf("bold", "plain", "item"), state.textLines.map { it.text })
		assertFalse(state.getAllText().text.contains('\r'), "a carriage return reached the document")
	}

	@Test
	fun `markdown parsing drops carriage returns`() {
		val parsed = "one\r\ntwo".toAnnotatedStringFromMarkdown()
		assertFalse(parsed.text.contains('\r'), parsed.text)
	}
}
