package html

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ZzVerifyTest {

	@Test
	fun `partial heading line export and reimport`() = runTest {
		val config = MarkdownConfiguration.DEFAULT
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = null,
		)
		val ext = HtmlExtension(state, config)
		ext.importHtml("<h2>Title</h2>")
		println("AFTER IMPORT lines=" + state.textLines.size + " text=" + state.getAllText().text)
		println("spans=" + state.textLines[0].spanStyles)

		// type " more" at end of the heading line
		state.cursor.updatePosition(
			com.darkrockstudios.texteditor.CharLineOffset(0, state.textLines[0].length)
		)
		state.insertStringAtCursor(" more")

		println("AFTER TYPE text=[" + state.getAllText().text + "]")
		println("spans=" + state.textLines[0].spanStyles)

		val html = ext.exportAsHtml()
		println("EXPORT=[" + html + "]")

		ext.importHtml(html)
		println("REIMPORT lines=" + state.textLines.size + " text=[" + state.getAllText().text + "]")
	}

	@Test
	fun `join heading line with following paragraph`() = runTest {
		val config = MarkdownConfiguration.DEFAULT
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = null,
		)
		val ext = HtmlExtension(state, config)
		ext.importHtml("<h2>Title</h2><p>more</p>")
		println("J AFTER IMPORT lines=" + state.textLines.size + " text=[" + state.getAllText().text + "]")

		// Cursor at start of line 1, backspace to join
		state.cursor.updatePosition(com.darkrockstudios.texteditor.CharLineOffset(1, 0))
		state.backspaceAtCursor()

		println("J AFTER JOIN lines=" + state.textLines.size + " text=[" + state.getAllText().text + "]")
		println("J spans=" + state.textLines[0].spanStyles.map { it.start to it.end })

		val html = ext.exportAsHtml()
		println("J EXPORT=[" + html + "]")

		ext.importHtml(html)
		println("J REIMPORT lines=" + state.textLines.size + " text=[" + state.getAllText().text.replace("\n", "\\n") + "]")
	}
}
