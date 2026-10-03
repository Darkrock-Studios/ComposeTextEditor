package markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.setParagraphFormat
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Markdown has no form for a paragraph format, so export writes the text alone. */
class ParagraphFormatExportTest {

	@Test
	fun `a paragraph format is not exported`() = runTest {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString((0 until 5).joinToString("\n") { "line $it" }),
		)
		state.setParagraphFormat(
			1..2,
			ParagraphFormatSpanStyle(spaceBefore = 8.dp, spaceAfter = 4.dp, textAlign = TextAlign.End, indent = 12.sp, lineHeight = 1.5.em),
		)

		assertEquals("line 0\n\nline 1\n\nline 2\n\nline 3\n\nline 4", MarkdownExtension(state).exportAsMarkdown())
	}
}
