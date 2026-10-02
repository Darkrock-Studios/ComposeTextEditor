package markdown

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.ORDERED_LIST_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderedListSerializationTest {

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

	private fun realTextMeasurer(): TextMeasurer = TextMeasurer(
		defaultFontFamilyResolver = createFontFamilyResolver(),
		defaultDensity = Density(1f, 1f),
		defaultLayoutDirection = LayoutDirection.Ltr,
	)

	private fun MarkdownExtension.orderedLines(): List<Int> =
		editorState.richSpanManager.getAllRichSpans()
			.filter { it.style === OrderedListSpanStyle }
			.map { it.range.start.line }
			.sorted()

	@Test
	fun `import attaches ordered-list span on numbered line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n1. item\nafter")

		assertEquals(listOf(1), extension.orderedLines())
		// The `1. ` prefix is stripped so the underlying text is just the body.
		assertEquals("before\nitem\nafter", extension.editorState.getAllText().text)
	}

	@Test
	fun `import recognises multi-digit numerals`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("42. answer")
		assertEquals(listOf(0), extension.orderedLines())
		assertEquals("answer", extension.editorState.getAllText().text)
	}

	@Test
	fun `import preserves item body inline markdown`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("1. **bold** start")
		// Body becomes "bold start"; the styling is verified separately by markdown
		// span tests, here we only check the body survived the prefix strip.
		assertEquals("bold start", extension.editorState.getAllText().text)
	}

	@Test
	fun `import handles consecutive ordered-list lines as separate spans`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("1. one\n2. two\n3. three")
		assertEquals(listOf(0, 1, 2), extension.orderedLines())
	}

	@Test
	fun `import does not treat numeric prefix mid-paragraph as ordered list`() = runTest {
		val extension = createMarkdownExtension()
		// "Step 1." is mid-line text — should not match.
		extension.importMarkdown("Step 1. do this")
		assertTrue(extension.orderedLines().isEmpty())
	}

	@Test
	fun `import does not treat digit without dot as ordered list`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("1 thing")
		assertTrue(extension.orderedLines().isEmpty())
	}

	@Test
	fun `import attaches paragraph indent style on ordered-list line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("1. item")

		val line = extension.editorState.textLines[0]
		val hasIndent = line.paragraphStyles.any { it.item == ORDERED_LIST_PARAGRAPH_STYLE }
		assertTrue(hasIndent, "ordered-list line should carry the indent paragraph style")
	}

	@Test
	fun `export emits incrementing numerals starting at one`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- bullet\n1. one\n2. two\n3. three")
		assertEquals("- bullet\n1. one\n2. two\n3. three", extension.exportAsMarkdown())
	}

	@Test
	fun `export normalises any starting digit to incrementing from one`() = runTest {
		val extension = createMarkdownExtension()
		// Markdown lets you write any digit — renderers normalise. We always emit 1, 2, 3.
		extension.importMarkdown("5. five\n9. nine\n2. two")
		assertEquals("1. five\n2. nine\n3. two", extension.exportAsMarkdown())
	}

	@Test
	fun `export resets numbering across non-list lines`() = runTest {
		val extension = createMarkdownExtension()
		val original = "1. one\n2. two\n\nbreak\n\n1. next\n2. one more"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves single ordered list`() = runTest {
		val extension = createMarkdownExtension()
		val original = "Intro.\n\n1. One\n2. Two\n3. Three\n\nFollowup."
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves ordered list alongside bullet and blockquote`() = runTest {
		val extension = createMarkdownExtension()
		val original = "> quoted\n\n- bullet\n1. ordered"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `roundtrip preserves bold inside ordered-list item`() = runTest {
		val extension = createMarkdownExtension()
		val original = "1. **bold** in item"
		extension.importMarkdown(original)
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `re-import after export keeps paragraph indent on ordered-list lines`() = runTest {
		// Mirrors the bullet regression: setText must clear stale rich spans so a
		// re-import re-applies the paragraph indent.
		val extension = createMarkdownExtension()
		extension.importMarkdown("1. one\n2. two\n3. three")

		val markdown = extension.exportAsMarkdown()
		extension.importMarkdown(markdown)

		assertEquals(listOf(0, 1, 2), extension.orderedLines())
		extension.editorState.textLines.forEachIndexed { index, line ->
			val hasIndent = line.paragraphStyles.any { it.item == ORDERED_LIST_PARAGRAPH_STYLE }
			assertTrue(hasIndent, "line $index lost its ordered indent paragraph style on re-import")
		}
	}

	@Test
	fun `toggleOrderedList on a bullet line replaces bullet with ordered`() = runTest {
		// Bullets and ordered lists can't coexist on a line — Compose paragraph
		// styles can't overlap, and visually the two gutter markers stack.
		// Switching list type should swap, not stack.
		val extension = createMarkdownExtension()
		extension.importMarkdown("- item")
		extension.toggleOrderedList(0..0)

		assertEquals(listOf(0), extension.orderedLines())
		// No bullet span should remain.
		val bulletSpans = extension.editorState.richSpanManager.getAllRichSpans()
			.filter { it.style === com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle }
		assertTrue(bulletSpans.isEmpty(), "bullet span should be replaced by ordered list")
		assertEquals("1. item", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleBulletList on an ordered-list line replaces ordered with bullet`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("1. item")
		extension.toggleBulletList(0..0)

		assertTrue(extension.orderedLines().isEmpty(), "ordered span should be replaced")
		assertEquals("- item", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleOrderedList adds span to a line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("plain text")
		assertTrue(extension.orderedLines().isEmpty())

		extension.toggleOrderedList(0..0)
		assertEquals(listOf(0), extension.orderedLines())
		assertEquals("1. plain text", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleOrderedList removes span on second invocation`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("1. item")
		assertEquals(listOf(0), extension.orderedLines())

		extension.toggleOrderedList(0..0)
		assertTrue(extension.orderedLines().isEmpty())
		assertEquals("item", extension.exportAsMarkdown())
	}

	@Test
	fun `toggleOrderedList across mixed range turns all on and numbers them`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("1. one\nplain")

		extension.toggleOrderedList(0..1)
		assertEquals(listOf(0, 1), extension.orderedLines())
		assertEquals("1. one\n2. plain", extension.exportAsMarkdown())
	}

	@Test
	fun `enter at end of ordered-list line continues the list`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("1. one")

		val state = extension.editorState
		state.cursor.updatePosition(CharLineOffset(0, 3))
		state.insertNewlineAtCursor()
		state.insertStringAtCursor("two")

		// Both lines must carry the OL span; export renumbers automatically.
		assertEquals(listOf(0, 1), extension.orderedLines())
		assertEquals("1. one\n2. two", extension.exportAsMarkdown())
	}

	@Test
	fun `enter at end of ordered-list line shows the next numeral before any typing`() = runTest {
		val state = TextEditorState(scope = this, measurer = realTextMeasurer())
		state.onViewportSizeChange(Size(500f, 500f))
		val extension = MarkdownExtension(state)
		extension.importMarkdown("1. one")

		state.cursor.updatePosition(CharLineOffset(0, 3))
		state.insertNewlineAtCursor()

		// The fresh line must already render its numeral — without typing a character
		// to force a second relayout pass.
		val newLineNumber = state.lineOffsets
			.first { it.line == 1 && it.virtualLineIndex == 0 }
			.orderedListNumber
		assertEquals(2, newLineNumber)
	}

	@Test
	fun `backspace at column 0 of ordered-list with same above merges items`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("1. one\n2. two")

		val state = extension.editorState
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()

		// Same-block above falls through to merge directly (no demote-first dance).
		assertEquals(listOf(0), extension.orderedLines())
		assertEquals("1. onetwo", extension.exportAsMarkdown())
	}

	@Test
	fun `deleting a middle ordered-list item keeps following items in the list`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("1. one\n2. two\n3. three\n4. four")

		val state = extension.editorState
		// Merge item 2 into item 1 via a single-newline delete (backspace at col 0
		// of a same-block line). Items 3 and 4 must keep their spans and renumber.
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()

		assertEquals(listOf(0, 1, 2), extension.orderedLines())
		assertEquals("1. onetwo\n2. three\n3. four", extension.exportAsMarkdown())
	}

	@Test
	fun `enter on empty ordered-list item exits the list`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("1. one\n2. \n3. three")

		val state = extension.editorState
		// Cursor on the empty middle item.
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.insertNewlineAtCursor()

		// Empty item exits — that line drops the OL span and indent.
		assertEquals(listOf(0, 2), extension.orderedLines())
	}

	@Test
	fun `removing ordered-list drops paragraph style`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("1. item")

		extension.toggleOrderedList(0..0)
		val line = extension.editorState.textLines[0]
		val hasIndent = line.paragraphStyles.any { it.item == ORDERED_LIST_PARAGRAPH_STYLE }
		assertTrue(!hasIndent, "indent paragraph style should be gone after removal")
	}

}
