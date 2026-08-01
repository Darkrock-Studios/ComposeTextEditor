package markdown

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BULLET_LIST_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BulletListSerializationTest {

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

	private fun MarkdownExtension.bulletLines(): List<Int> =
		editorState.richSpanManager.getAllRichSpans()
			.filter { it.style === BulletListSpanStyle }
			.map { it.range.start.line }
			.sorted()

	@Test
	fun `import attaches bullet span on dash line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n- item\nafter")

		assertEquals(listOf(1), extension.bulletLines())
		// The `- ` prefix is stripped so the underlying text is the item body.
		assertEquals("before\nitem\nafter", extension.editorState.getAllText().text)
	}

	@Test
	fun `import recognises asterisk bullet marker`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("* item")
		assertEquals(listOf(0), extension.bulletLines())
		assertEquals("item", extension.editorState.getAllText().text)
	}

	@Test
	fun `import recognises plus bullet marker`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("+ item")
		assertEquals(listOf(0), extension.bulletLines())
		assertEquals("item", extension.editorState.getAllText().text)
	}

	@Test
	fun `import preserves item body inline markdown`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- a **bold** item")

		assertEquals(listOf(0), extension.bulletLines())
		assertEquals("a bold item", extension.editorState.getAllText().text)
	}

	@Test
	fun `import handles consecutive bullet lines as separate spans`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- one\n- two\n- three")

		assertEquals(listOf(0, 1, 2), extension.bulletLines())
	}

	@Test
	fun `import does not treat lone dash inside paragraph as bullet`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("a - b")
		assertTrue(extension.bulletLines().isEmpty())
	}

	@Test
	fun `import does not treat dash without space as bullet`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("-not-a-bullet")
		assertTrue(extension.bulletLines().isEmpty())
	}

	@Test
	fun `import attaches paragraph indent style on bullet line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- item")

		val line = extension.editorState.textLines[0]
		val hasIndent = line.paragraphStyles.any { it.item == BULLET_LIST_PARAGRAPH_STYLE }
		assertTrue(hasIndent, "bullet line should carry the indent paragraph style")
	}

	@Test
	fun `export emits dash prefix for bullet line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n- item\nafter")
		assertEquals("before\n- item\nafter", extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves single bullet list`() = runTest {
		val extension = createMarkdownExtension()
		val original = "Intro.\n\n- One\n- Two\n- Three\n\nFollowup."
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip normalises asterisk and plus markers to dash`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("* one\n+ two\n- three")
		// Export normalises every bullet to `- ` since we don't track which marker was used.
		assertEquals("- one\n- two\n- three", extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves bullet alongside HR`() = runTest {
		val extension = createMarkdownExtension()
		val original = "- item\n---\nbody"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves bullet alongside blockquote`() = runTest {
		val extension = createMarkdownExtension()
		val original = "> quoted\n- item"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `import peels a bullet marker stacked inside a blockquote`() = runTest {
		// Quote stacks with a list, so `> - item` carries two markers; both peel,
		// leaving the body as the document text.
		val extension = createMarkdownExtension()
		extension.importMarkdown("> - quoted item")

		assertEquals("quoted item", extension.editorState.getAllText().text)
		assertEquals(listOf(0), extension.bulletLines())
	}

	@Test
	fun `roundtrip preserves a bullet stacked inside a blockquote`() = runTest {
		val extension = createMarkdownExtension()
		val original = "> - quoted item"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `import keeps a nested blockquote marker as body text`() = runTest {
		// Each style peels at most once; a second quote level is not representable,
		// so its marker stays in the body.
		val extension = createMarkdownExtension()
		extension.importMarkdown("> > quoted")

		assertEquals("> quoted", extension.editorState.getAllText().text)
		assertTrue(extension.bulletLines().isEmpty())
	}

	@Test
	fun `import keeps an ordered lookalike inside a bullet body`() = runTest {
		// The two list styles are mutually exclusive, so after the bullet peels,
		// a numeral-period lead is the author's text, not a second marker.
		val extension = createMarkdownExtension()
		extension.importMarkdown("- 1990. The year everything changed")

		assertEquals("1990. The year everything changed", extension.editorState.getAllText().text)
		assertEquals(listOf(0), extension.bulletLines())
	}

	@Test
	fun `roundtrip preserves an ordered lookalike inside a bullet body`() = runTest {
		// Export escapes the numeral-period lead (`- 1990\. ...`) so no parser can
		// read it as a second marker; the text and the bullet survive unchanged and
		// the serialized form is stable from the first save.
		val extension = createMarkdownExtension()
		extension.importMarkdown("- 1990. The year everything changed")
		val exported = extension.exportAsMarkdown()

		extension.importMarkdown(exported)

		assertEquals("1990. The year everything changed", extension.editorState.getAllText().text)
		assertEquals(listOf(0), extension.bulletLines())
		assertEquals(exported, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves bold inside bullet`() = runTest {
		val extension = createMarkdownExtension()
		val original = "- **bold** in item"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `re-import after export keeps paragraph indent on bullet lines`() = runTest {
		// Regression: setText didn't clear stale rich spans, so on a re-import the
		// applyLineBlock guard hit a leftover span and skipped the paragraph-style
		// re-application, leaving the bullet glyph on top of the first character.
		val extension = createMarkdownExtension()
		extension.importMarkdown("- one\n- two\n- three")

		val markdown = extension.exportAsMarkdown()
		extension.importMarkdown(markdown)

		assertEquals(listOf(0, 1, 2), extension.bulletLines())
		extension.editorState.textLines.forEachIndexed { index, line ->
			val hasIndent = line.paragraphStyles.any { it.item == BULLET_LIST_PARAGRAPH_STYLE }
			assertTrue(hasIndent, "line $index lost its bullet indent paragraph style on re-import")
		}
	}

	@Test
	fun `toggleBulletList adds span to a line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("plain text")
		assertTrue(extension.bulletLines().isEmpty())

		extension.toggleBulletList(0..0)
		assertEquals(listOf(0), extension.bulletLines())
		assertEquals("- plain text", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleBulletList removes span on second invocation`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- item")
		assertEquals(listOf(0), extension.bulletLines())

		extension.toggleBulletList(0..0)
		assertTrue(extension.bulletLines().isEmpty())
		assertEquals("item", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleBulletList across mixed range turns all on`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- one\nplain")

		extension.toggleBulletList(0..1)
		assertEquals(listOf(0, 1), extension.bulletLines())
	}

	@Test
	fun `delete inside bullet line preserves paragraph indent and span`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- hello world")

		val state = extension.editorState
		state.delete(
			TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 6))
		)

		assertEquals("helloworld", state.textLines[0].text)
		assertEquals(listOf(0), extension.bulletLines())
		val hasIndent = state.textLines[0].paragraphStyles
			.any { it.item == BULLET_LIST_PARAGRAPH_STYLE }
		assertTrue(hasIndent, "indent paragraph style must survive in-line deletes")
	}

	@Test
	fun `insert inside bullet line preserves paragraph indent`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- hello")

		val state = extension.editorState
		state.cursor.updatePosition(CharLineOffset(0, 3))
		state.insertStringAtCursor("!")

		assertEquals("hel!lo", state.textLines[0].text)
		val hasIndent = state.textLines[0].paragraphStyles
			.any { it.item == BULLET_LIST_PARAGRAPH_STYLE }
		assertTrue(hasIndent, "indent paragraph style must survive in-line inserts")
	}

	@Test
	fun `append at end of bullet line keeps paragraph covering whole line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- hello")

		val state = extension.editorState
		// Cursor at the very end of the line (position 5 in "hello").
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertStringAtCursor("X")

		val line = state.textLines[0]
		assertEquals("helloX", line.text)
		// The paragraph style must extend to cover the appended character — otherwise
		// Compose renders the trailing chars as a separate paragraph (visual newline).
		val indents = line.paragraphStyles.filter { it.item == BULLET_LIST_PARAGRAPH_STYLE }
		assertEquals(1, indents.size, "exactly one bullet indent paragraph should remain")
		assertEquals(0, indents[0].start)
		assertEquals(line.length, indents[0].end, "paragraph must cover the appended char")
	}

	@Test
	fun `prepend at start of bullet line keeps paragraph covering whole line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- hello")

		val state = extension.editorState
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertStringAtCursor("X")

		val line = state.textLines[0]
		assertEquals("Xhello", line.text)
		val indents = line.paragraphStyles.filter { it.item == BULLET_LIST_PARAGRAPH_STYLE }
		assertEquals(1, indents.size)
		assertEquals(0, indents[0].start, "paragraph must still start at index 0")
		assertEquals(line.length, indents[0].end)
	}

	@Test
	fun `backspace at column 0 of bullet line demotes instead of merging`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n- item")
		val state = extension.editorState

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()

		assertTrue(extension.bulletLines().isEmpty(), "bullet span should be removed")
		val hasIndent = state.textLines[1].paragraphStyles
			.any { it.item == BULLET_LIST_PARAGRAPH_STYLE }
		assertFalse(hasIndent, "indent paragraph style should be removed")
		assertEquals("before\nitem", state.getAllText().text)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)
	}

	@Test
	fun `backspace after demote merges with previous line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n- item")
		val state = extension.editorState

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()  // demote
		state.backspaceAtCursor()  // merge with previous line

		assertEquals("beforeitem", state.getAllText().text)
	}

	@Test
	fun `backspace at column 0 of bullet with bullet above merges with previous item`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- first\n- second")
		val state = extension.editorState
		assertEquals(listOf(0, 1), extension.bulletLines())

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()

		// Same-style line above: backspace merges directly, no demote-first dance.
		assertEquals(1, state.textLines.size)
		assertEquals("firstsecond", state.textLines[0].text)
		assertEquals(listOf(0), extension.bulletLines())
		val hasIndent = state.textLines[0].paragraphStyles
			.any { it.item == BULLET_LIST_PARAGRAPH_STYLE }
		assertTrue(hasIndent, "indent paragraph style must survive the merge")
	}

	@Test
	fun `backspace at column 0 of bullet on first line demotes`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- only line")
		val state = extension.editorState

		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.backspaceAtCursor()

		assertTrue(extension.bulletLines().isEmpty())
		assertEquals("only line", state.getAllText().text)
	}

	@Test
	fun `merging two bullet items coalesces the indent into a single paragraph run`() = runTest {
		// Regression: backspace at the start of a bullet item joins it onto the
		// bullet above. Both lines carry a BULLET_LIST_PARAGRAPH_STYLE indent, and
		// the merge used to leave two contiguous runs ([0,n] + [n,m]) on the joined
		// line. Compose renders each ParagraphStyle run as its own paragraph, so the
		// joined line displayed as two stacked paragraphs and the merge looked like
		// it never happened. The runs must coalesce into one span over the whole line.
		val extension = createMarkdownExtension()
		extension.importMarkdown("- First item\n- Second item")
		val state = extension.editorState
		assertEquals(listOf(0, 1), extension.bulletLines())

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()

		assertEquals(1, state.textLines.size)
		val merged = state.textLines[0]
		assertEquals("First itemSecond item", merged.text)
		assertEquals(listOf(0), extension.bulletLines())

		val indents = merged.paragraphStyles.filter { it.item == BULLET_LIST_PARAGRAPH_STYLE }
		assertEquals(1, indents.size, "merged bullet line must have exactly one indent run")
		assertEquals(0, indents[0].start)
		assertEquals(merged.length, indents[0].end, "indent must span the whole joined line")
	}

	@Test
	fun `backspace at start of empty line below bullet preserves bullet on the merged line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- third item\n\nbody")
		val state = extension.editorState

		// Cursor at start of the empty line (line 1) — backspace merges that line
		// into line 0 (the bullet item).
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()

		// The bullet line content is unchanged...
		assertEquals("third item", state.textLines[0].text)
		// ...and the bullet rich span + indent paragraph style must survive the merge.
		assertEquals(listOf(0), extension.bulletLines())
		val hasIndent = state.textLines[0].paragraphStyles
			.any { it.item == BULLET_LIST_PARAGRAPH_STYLE }
		assertTrue(hasIndent, "indent paragraph style must survive a multi-line merge")
	}

	@Test
	fun `enter at end of bullet line continues the list`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- first")
		val state = extension.editorState

		state.cursor.updatePosition(CharLineOffset(0, 5))  // end of "first"
		state.insertNewlineAtCursor()

		assertEquals(2, state.textLines.size)
		assertEquals("first", state.textLines[0].text)
		assertEquals("", state.textLines[1].text)
		assertEquals(listOf(0, 1), extension.bulletLines())
		assertTrue(
			state.textLines[1].paragraphStyles.any { it.item == BULLET_LIST_PARAGRAPH_STYLE },
			"new line below must carry the bullet indent paragraph style",
		)
		assertEquals(CharLineOffset(1, 0), state.cursorPosition)
	}

	@Test
	fun `enter mid-bullet splits into two bullet items`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- helloworld")
		val state = extension.editorState

		state.cursor.updatePosition(CharLineOffset(0, 5))  // between "hello" and "world"
		state.insertNewlineAtCursor()

		assertEquals(listOf("hello", "world"), state.textLines.map { it.text })
		assertEquals(listOf(0, 1), extension.bulletLines())
		assertTrue(state.textLines[0].paragraphStyles.any { it.item == BULLET_LIST_PARAGRAPH_STYLE })
		assertTrue(state.textLines[1].paragraphStyles.any { it.item == BULLET_LIST_PARAGRAPH_STYLE })
	}

	@Test
	fun `enter at start of bullet keeps both lines bulleted`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- first")
		val state = extension.editorState

		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertNewlineAtCursor()

		assertEquals(listOf("", "first"), state.textLines.map { it.text })
		assertEquals(listOf(0, 1), extension.bulletLines())
	}

	@Test
	fun `typing into a continued empty bullet line keeps the bullet attached`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- first")
		val state = extension.editorState

		state.cursor.updatePosition(CharLineOffset(0, 5))  // end of "first"
		state.insertNewlineAtCursor()
		// We're now on the new empty bullet line at (1, 0).
		assertEquals(listOf(0, 1), extension.bulletLines())

		state.insertStringAtCursor("a")

		assertEquals("a", state.textLines[1].text)
		// The bullet span must still anchor at column 0 of line 1 — otherwise
		// the visible bullet glyph disappears the moment the user types. Without
		// sticky-at-start, transformOffset shifts the span to (1, 1)-(1, 2),
		// past the line content, and intersectsWith reports no overlap.
		val bulletSpanOnLine1 = state.richSpanManager.getAllRichSpans()
			.singleOrNull { it.style === BulletListSpanStyle && it.range.start.line == 1 }
		assertNotNull(bulletSpanOnLine1, "bullet rich span on line 1 should still exist")
		assertEquals(0, bulletSpanOnLine1.range.start.char, "bullet span must stay anchored at column 0")
		assertTrue(
			bulletSpanOnLine1.range.end.char > 0,
			"bullet span must cover at least the first character of the typed text",
		)
		assertTrue(
			state.textLines[1].paragraphStyles.any { it.item == BULLET_LIST_PARAGRAPH_STYLE },
			"indent paragraph style must survive the first keystroke",
		)
	}

	@Test
	fun `enter on empty bullet item exits the list`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- first\n- ")
		val state = extension.editorState
		// Sanity check: line 1 is the empty bullet item.
		assertEquals("", state.textLines[1].text)
		assertEquals(listOf(0, 1), extension.bulletLines())

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.insertNewlineAtCursor()

		// Empty bullet + Enter demotes the line; no newline inserted.
		assertEquals(2, state.textLines.size)
		assertEquals(listOf(0), extension.bulletLines())
		assertFalse(
			state.textLines[1].paragraphStyles.any { it.item == BULLET_LIST_PARAGRAPH_STYLE },
			"demoted line must drop the indent paragraph style",
		)
	}

	@Test
	fun `removing bullet drops paragraph style`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- with indent")

		extension.toggleBulletList(0..0)
		val line = extension.editorState.textLines[0]
		val hasIndent = line.paragraphStyles.any { it.item == BULLET_LIST_PARAGRAPH_STYLE }
		assertFalse(hasIndent, "removing bullet should clear the indent paragraph style")
	}
}
