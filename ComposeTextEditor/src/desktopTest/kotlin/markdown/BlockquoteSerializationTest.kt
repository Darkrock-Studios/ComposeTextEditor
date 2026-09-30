package markdown

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BLOCKQUOTE_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockquoteSerializationTest {

	private fun TestScope.createMarkdownExtension(
		initialText: String? = null,
		configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	): MarkdownExtension {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = initialText?.let { AnnotatedString(it) },
		)
		return MarkdownExtension(state, configuration)
	}

	private fun MarkdownExtension.blockquoteLines(): List<Int> =
		editorState.richSpanManager.getAllRichSpans()
			.filter { it.style === BlockquoteSpanStyle }
			.map { it.range.start.line }
			.sorted()

	@Test
	fun `import attaches blockquote span on quoted line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n> a quote\nafter")

		assertEquals(listOf(1), extension.blockquoteLines())
		// The `> ` prefix is stripped so the underlying text is the quote body.
		assertEquals("before\na quote\nafter", extension.editorState.getAllText().text)
	}

	@Test
	fun `import preserves quote body inline markdown`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> a **bold** quote")

		assertEquals(listOf(0), extension.blockquoteLines())
		assertEquals("a bold quote", extension.editorState.getAllText().text)
	}

	@Test
	fun `import handles consecutive quote lines as separate spans`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> first\n> second\n> third")

		assertEquals(listOf(0, 1, 2), extension.blockquoteLines())
	}

	@Test
	fun `import handles empty blockquote line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown(">")

		assertEquals(listOf(0), extension.blockquoteLines())
	}

	@Test
	fun `import does not treat angle-bracket inside paragraph as blockquote`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("a > b")
		assertTrue(extension.blockquoteLines().isEmpty())
	}

	@Test
	fun `import attaches blockquote paragraph style on quoted line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> indented")

		val line = extension.editorState.textLines[0]
		val hasIndent = line.paragraphStyles.any { it.item == BLOCKQUOTE_PARAGRAPH_STYLE }
		assertTrue(hasIndent, "blockquote line should carry the indent paragraph style")
	}

	@Test
	fun `export emits gt prefix for blockquote line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n> a quote\nafter")
		assertEquals("before\n\n> a quote\n\nafter", extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves single blockquote`() = runTest {
		val extension = createMarkdownExtension()
		val original = "Intro.\n\n> Quoted text.\n\nFollowup."
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves consecutive quote lines`() = runTest {
		val extension = createMarkdownExtension()
		val original = "> first\n>\n> second\n>\n> third"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves blockquote alongside HR`() = runTest {
		val extension = createMarkdownExtension()
		val original = "> quote\n\n---\n\nbody"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves bold inside blockquote`() = runTest {
		val extension = createMarkdownExtension()
		val original = "> **bold** inside quote"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `toggleBlockquote adds span to a line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("plain text")
		assertTrue(extension.blockquoteLines().isEmpty())

		extension.toggleBlockquote(0..0)
		assertEquals(listOf(0), extension.blockquoteLines())
		assertEquals("> plain text", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleBlockquote removes span on second invocation`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> already a quote")
		assertEquals(listOf(0), extension.blockquoteLines())

		extension.toggleBlockquote(0..0)
		assertTrue(extension.blockquoteLines().isEmpty())
		assertEquals("already a quote", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleBlockquote across mixed range turns all on`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> quoted\nplain")

		extension.toggleBlockquote(0..1)
		assertEquals(listOf(0, 1), extension.blockquoteLines())
	}

	@Test
	fun `delete inside blockquote line preserves paragraph indent and span`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> hello world")

		// Delete the space between "hello" and "world".
		val state = extension.editorState
		state.delete(
			TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 6))
		)

		assertEquals("helloworld", state.textLines[0].text)
		assertEquals(listOf(0), extension.blockquoteLines())
		val hasIndent = state.textLines[0].paragraphStyles
			.any { it.item == BLOCKQUOTE_PARAGRAPH_STYLE }
		assertTrue(hasIndent, "indent paragraph style must survive in-line deletes")
	}

	@Test
	fun `insert inside blockquote line preserves paragraph indent`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> hello")

		val state = extension.editorState
		state.cursor.updatePosition(CharLineOffset(0, 3))
		state.insertStringAtCursor("!")

		assertEquals("hel!lo", state.textLines[0].text)
		val hasIndent = state.textLines[0].paragraphStyles
			.any { it.item == BLOCKQUOTE_PARAGRAPH_STYLE }
		assertTrue(hasIndent, "indent paragraph style must survive in-line inserts")
	}

	@Test
	fun `append at end of blockquote line keeps paragraph covering whole line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> hello")

		val state = extension.editorState
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertStringAtCursor("X")

		val line = state.textLines[0]
		assertEquals("helloX", line.text)
		val indents = line.paragraphStyles.filter { it.item == BLOCKQUOTE_PARAGRAPH_STYLE }
		assertEquals(1, indents.size, "exactly one blockquote indent paragraph should remain")
		assertEquals(0, indents[0].start)
		assertEquals(line.length, indents[0].end, "paragraph must cover the appended char")
	}

	@Test
	fun `prepend at start of blockquote line keeps paragraph covering whole line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> hello")

		val state = extension.editorState
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertStringAtCursor("X")

		val line = state.textLines[0]
		assertEquals("Xhello", line.text)
		val indents = line.paragraphStyles.filter { it.item == BLOCKQUOTE_PARAGRAPH_STYLE }
		assertEquals(1, indents.size)
		assertEquals(0, indents[0].start, "paragraph must still start at index 0")
		assertEquals(line.length, indents[0].end)
	}

	@Test
	fun `backspace at column 0 of blockquote demotes instead of merging`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n> a quote")
		val state = extension.editorState

		// Move cursor to start of the blockquote line.
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()

		// Blockquote demoted: span gone, indent gone, but text and line count unchanged.
		assertTrue(extension.blockquoteLines().isEmpty(), "blockquote span should be removed")
		val hasIndent = state.textLines[1].paragraphStyles
			.any { it.item == BLOCKQUOTE_PARAGRAPH_STYLE }
		assertFalse(hasIndent, "indent paragraph style should be removed")
		assertEquals("before\na quote", state.getAllText().text)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)
	}

	@Test
	fun `backspace after demote merges with previous line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n> a quote")
		val state = extension.editorState

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()  // demote
		state.backspaceAtCursor()  // merge with previous line

		assertEquals("beforea quote", state.getAllText().text)
	}

	@Test
	fun `backspace at column 0 of blockquote with blockquote above merges with previous`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> first\n> second")
		val state = extension.editorState
		assertEquals(listOf(0, 1), extension.blockquoteLines())

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()

		assertEquals(1, state.textLines.size)
		assertEquals("firstsecond", state.textLines[0].text)
		assertEquals(listOf(0), extension.blockquoteLines())
		val hasIndent = state.textLines[0].paragraphStyles
			.any { it.item == BLOCKQUOTE_PARAGRAPH_STYLE }
		assertTrue(hasIndent, "indent paragraph style must survive the merge")
	}

	@Test
	fun `backspace at column 0 of blockquote on first line demotes`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> only line")
		val state = extension.editorState

		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.backspaceAtCursor()

		assertTrue(extension.blockquoteLines().isEmpty())
		assertEquals("only line", state.getAllText().text)
	}

	@Test
	fun `backspace at start of empty line below blockquote preserves quote on the merged line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> a quote\n\n\nbody")
		val state = extension.editorState

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()

		assertEquals("a quote", state.textLines[0].text)
		assertEquals(listOf(0), extension.blockquoteLines())
		val hasIndent = state.textLines[0].paragraphStyles
			.any { it.item == BLOCKQUOTE_PARAGRAPH_STYLE }
		assertTrue(hasIndent, "indent paragraph style must survive a multi-line merge")
	}

	@Test
	fun `enter at end of blockquote line continues the quote`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> a quote")
		val state = extension.editorState

		state.cursor.updatePosition(CharLineOffset(0, 7))  // end of "a quote"
		state.insertNewlineAtCursor()

		assertEquals(2, state.textLines.size)
		assertEquals(listOf(0, 1), extension.blockquoteLines())
		assertTrue(
			state.textLines[1].paragraphStyles.any { it.item == BLOCKQUOTE_PARAGRAPH_STYLE },
		)
	}

	@Test
	fun `enter on empty blockquote line exits the quote`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> first\n>\n> ")
		val state = extension.editorState
		assertEquals("", state.textLines[1].text)

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.insertNewlineAtCursor()

		assertEquals(2, state.textLines.size)
		assertEquals(listOf(0), extension.blockquoteLines())
		assertFalse(
			state.textLines[1].paragraphStyles.any { it.item == BLOCKQUOTE_PARAGRAPH_STYLE },
		)
	}

	@Test
	fun `removing blockquote drops paragraph style`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("> with indent")

		extension.toggleBlockquote(0..0)
		val line = extension.editorState.textLines[0]
		val hasIndent = line.paragraphStyles.any { it.item == BLOCKQUOTE_PARAGRAPH_STYLE }
		assertFalse(hasIndent, "removing blockquote should clear the indent paragraph style")
	}
}
