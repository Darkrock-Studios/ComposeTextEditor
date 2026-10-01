package markdown

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.CodeFenceLanguageSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.codeFenceLanguage
import com.darkrockstudios.texteditor.state.setCodeFenceLanguage
import com.darkrockstudios.texteditor.state.toggleCodeFence
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A fence's info string (` ```kotlin `) is carried by a
 * [CodeFenceLanguageSpanStyle] span on every line of the run and written back
 * on export from the run's first line.
 */
class CodeFenceLanguageTest {

	private fun TestScope.extension(): MarkdownExtension =
		MarkdownExtension(TextEditorState(scope = this, measurer = mockk(relaxed = true)))

	private fun MarkdownExtension.languageLines(): List<Pair<Int, String>> =
		editorState.richSpanManager.getAllRichSpans()
			.mapNotNull { span ->
				(span.style as? CodeFenceLanguageSpanStyle)?.let { span.range.start.line to it.language }
			}
			.sortedBy { it.first }

	@Test
	fun `import puts the language tag on every line of the run`() = runTest {
		val e = extension()
		e.importMarkdown("```kotlin\nfun foo() {}\nval x = 1\n```")

		assertEquals(listOf(0 to "kotlin", 1 to "kotlin"), e.languageLines())
		assertEquals("kotlin", e.editorState.codeFenceLanguage(0))
		assertEquals("kotlin", e.editorState.codeFenceLanguage(1))
		assertEquals("fun foo() {}\nval x = 1", e.editorState.getAllText().text)
	}

	@Test
	fun `a plain line has no language`() = runTest {
		val e = extension()
		e.importMarkdown("plain\n```\ncode\n```")
		assertNull(e.editorState.codeFenceLanguage(0))
		assertNull(e.editorState.codeFenceLanguage(1))
	}

	@Test
	fun `round trip keeps the language tag`() = runTest {
		val e = extension()
		val markdown = "```kotlin\nfun greet() {}\n```"
		e.importMarkdown(markdown)
		assertEquals(markdown, e.exportAsMarkdown())
	}

	@Test
	fun `each fence keeps its own info string, verbatim`() = runTest {
		val e = extension()
		val markdown = "```kotlin\na\n```\n\ntext\n\n```sh title=run\nb\nc\n```"
		e.importMarkdown(markdown)
		assertEquals(listOf(0 to "kotlin", 2 to "sh title=run", 3 to "sh title=run"), e.languageLines())
		assertEquals(markdown, e.exportAsMarkdown())
	}

	@Test
	fun `a tilde fence imports and exports as a backtick fence`() = runTest {
		val e = extension()
		e.importMarkdown("~~~python\nprint(1)\n~~~")
		assertEquals(listOf(0 to "python"), e.languageLines())
		assertEquals("```python\nprint(1)\n```", e.exportAsMarkdown())
	}

	@Test
	fun `setCodeFenceLanguage sets, replaces and clears the run in undoable steps`() = runTest {
		val e = extension()
		e.importMarkdown("```\nfun foo() {}\nval x = 1\n```")
		val state = e.editorState

		e.editorState.setCodeFenceLanguage(1, "kotlin")
		assertEquals("```kotlin\nfun foo() {}\nval x = 1\n```", e.exportAsMarkdown())
		assertEquals(listOf(0 to "kotlin", 1 to "kotlin"), e.languageLines())

		e.editorState.setCodeFenceLanguage(0, "java")
		assertEquals(listOf(0 to "java", 1 to "java"), e.languageLines())

		e.editorState.setCodeFenceLanguage(1, null)
		assertEquals(emptyList(), e.languageLines())

		state.undo()
		assertEquals(listOf(0 to "java", 1 to "java"), e.languageLines())
		state.undo()
		assertEquals(listOf(0 to "kotlin", 1 to "kotlin"), e.languageLines())
		state.undo()
		assertEquals(emptyList(), e.languageLines())
	}

	@Test
	fun `setCodeFenceLanguage on a plain line does nothing`() = runTest {
		val e = extension()
		e.importMarkdown("plain")
		e.editorState.setCodeFenceLanguage(0, "kotlin")
		assertEquals(emptyList(), e.languageLines())
		assertEquals("plain", e.exportAsMarkdown())
	}

	@Test
	fun `toggling the fence off drops the language and undo restores it`() = runTest {
		val e = extension()
		e.importMarkdown("```kotlin\ncode\n```")

		e.editorState.toggleCodeFence(0..0)
		assertEquals(emptyList(), e.languageLines())
		assertEquals("code", e.exportAsMarkdown())

		e.editorState.undo()
		assertEquals(listOf(0 to "kotlin"), e.languageLines())
		assertEquals("```kotlin\ncode\n```", e.exportAsMarkdown())
	}

	@Test
	fun `a language on one line of a run spreads to the run`() = runTest {
		val e = extension()
		e.importMarkdown("```\na\nb\n```")
		e.editorState.addRichSpan(
			TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 1)),
			CodeFenceLanguageSpanStyle("kotlin"),
		)
		assertEquals(listOf(0 to "kotlin", 1 to "kotlin"), e.languageLines())
		assertEquals("```kotlin\na\nb\n```", e.exportAsMarkdown())
	}

	@Test
	fun `a language span off a fence is dropped`() = runTest {
		val e = extension()
		e.importMarkdown("plain")
		e.editorState.addRichSpan(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)),
			CodeFenceLanguageSpanStyle("kotlin"),
		)
		assertEquals(emptyList(), e.languageLines())
	}

	@Test
	fun `splitting a run keeps the language on both halves`() = runTest {
		val e = extension()
		e.importMarkdown("```kotlin\na\nb\nc\n```")
		e.editorState.toggleCodeFence(1..1)
		assertEquals("```kotlin\na\n```\n\nb\n\n```kotlin\nc\n```", e.exportAsMarkdown())
	}

	@Test
	fun `a language that would break the fence is refused, and blanks clear`() = runTest {
		val e = extension()
		e.editorState.setText(AnnotatedString("code"))
		e.editorState.toggleCodeFence(0..0)
		e.editorState.setCodeFenceLanguage(0, "a`b")
		assertNull(e.editorState.codeFenceLanguage(0))
		e.editorState.setCodeFenceLanguage(0, "  kotlin ")
		assertEquals("kotlin", e.editorState.codeFenceLanguage(0))
		e.editorState.setCodeFenceLanguage(0, "   ")
		assertNull(e.editorState.codeFenceLanguage(0))
		assertEquals("```\ncode\n```", e.exportAsMarkdown())
	}

	@Test
	fun `enter at the start of the first fenced line keeps the language on the run`() = runTest {
		val e = extension()
		e.importMarkdown("```kotlin\ncode\n```")
		e.editorState.cursor.updatePosition(CharLineOffset(0, 0))
		e.editorState.insertNewlineAtCursor()
		assertEquals("```kotlin\n\ncode\n```", e.exportAsMarkdown())
		e.editorState.undo()
		assertEquals("```kotlin\ncode\n```", e.exportAsMarkdown())
	}

	@Test
	fun `fencing the line above a run gives it the run's language, and undo takes it back`() = runTest {
		val e = extension()
		e.importMarkdown("intro\n\n\n```kotlin\ncode\n```")
		e.editorState.toggleCodeFence(1..1)
		assertEquals("intro\n\n```kotlin\n\ncode\n```", e.exportAsMarkdown())
		assertEquals(listOf(1 to "kotlin", 2 to "kotlin"), e.languageLines())
		e.editorState.undo()
		assertEquals("intro\n\n\n```kotlin\ncode\n```", e.exportAsMarkdown())
		assertEquals(listOf(2 to "kotlin"), e.languageLines())
	}

	@Test
	fun `un-fencing the first line of a run keeps the language on the rest, and back on undo`() = runTest {
		val e = extension()
		e.importMarkdown("```kotlin\na\nb\n```")
		e.editorState.toggleCodeFence(0..0)
		assertEquals("a\n\n```kotlin\nb\n```", e.exportAsMarkdown())
		assertEquals(listOf(1 to "kotlin"), e.languageLines())
		e.editorState.undo()
		assertEquals("```kotlin\na\nb\n```", e.exportAsMarkdown())
		e.editorState.redo()
		assertEquals("a\n\n```kotlin\nb\n```", e.exportAsMarkdown())
	}

	@Test
	fun `joining two runs writes the first run's language, and undo gives the second its own back`() = runTest {
		val e = extension()
		e.importMarkdown("```kotlin\na\n```\n\n\n```java\nb\n```")
		assertEquals(listOf(0 to "kotlin", 2 to "java"), e.languageLines())
		// Delete the blank line between the runs: the caret at its start, forward delete.
		e.editorState.cursor.updatePosition(CharLineOffset(1, 0))
		e.editorState.deleteAtCursor()
		assertEquals("```kotlin\na\nb\n```", e.exportAsMarkdown())
		assertEquals("kotlin", e.editorState.codeFenceLanguage(1))

		e.editorState.undo()
		assertEquals("```kotlin\na\n```\n\n\n```java\nb\n```", e.exportAsMarkdown())
		assertEquals("java", e.editorState.codeFenceLanguage(2))
	}

	@Test
	fun `un-fencing a joined run's first lines makes the second run's language the run's`() = runTest {
		val e = extension()
		e.importMarkdown("```kotlin\na\n```\n\n\n```java\nb\n```")
		e.editorState.cursor.updatePosition(CharLineOffset(1, 0))
		e.editorState.deleteAtCursor()
		e.editorState.toggleCodeFence(0..0)
		assertEquals("a\n\n```java\nb\n```", e.exportAsMarkdown())
	}

	@Test
	fun `an unwritable language is dropped even when the run has no other`() = runTest {
		val e = extension()
		e.importMarkdown("~~~a`b\ncode\n~~~")
		assertEquals(emptyList(), e.languageLines())
		assertEquals("```\ncode\n```", e.exportAsMarkdown())
	}
}
