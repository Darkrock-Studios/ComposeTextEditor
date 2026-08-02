package markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MarkdownExtensionEqualityTest {

	private fun TestScope.createEditorState(initialText: String?) =
		TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = initialText?.let { AnnotatedString(it) },
		)

	private fun TestScope.createMarkdownExtension(
		initialText: String?,
		configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
	): MarkdownExtension {
		val state = createEditorState(initialText)
		return MarkdownExtension(state, configuration)
	}

	@Test
	fun `equals is reference identity`() = runTest {
		val extension = createMarkdownExtension("# Header")
		assertEquals(extension, extension)
	}

	@Test
	fun `distinct instances with same content are not equal`() = runTest {
		val text = "# Header\nSome content"
		val extension1 = createMarkdownExtension(text)
		val extension2 = createMarkdownExtension(text)

		assertNotEquals(extension1, extension2)
	}

	@Test
	fun `hashCode is stable across edits and configuration changes`() = runTest {
		val extension = createMarkdownExtension("# Header")
		val before = extension.hashCode()

		extension.editorState.setText("entirely new content")
		extension.markdownConfiguration = MarkdownConfiguration.DEFAULT.copy(
			header1Style = MarkdownConfiguration.DEFAULT.header1Style.copy(color = Color.Red)
		)

		assertEquals(before, extension.hashCode())
	}
}
