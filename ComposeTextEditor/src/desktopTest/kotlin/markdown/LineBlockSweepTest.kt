package markdown

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Multi-line block toggles act on every selected line that can carry the style:
 * blank lines are included (an empty list item, as in Word and Google Docs) and
 * placeholder lines (rules, images) are excluded for every style except
 * blockquote, the one style that stacks on them. The toggle direction is decided
 * from the same set of lines, so both directions always reach every line they
 * could change.
 */
class LineBlockSweepTest {

	private fun TestScope.createMarkdownExtension(): MarkdownExtension {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = null as AnnotatedString?,
		)
		return MarkdownExtension(state, MarkdownConfiguration.DEFAULT)
	}

	private fun MarkdownExtension.selectAll(): IntRange = 0..editorState.textLines.lastIndex

	private fun MarkdownExtension.linesWith(style: RichSpanStyle): List<Int> =
		editorState.richSpanManager.getAllRichSpans()
			.filter { it.style === style }
			.map { it.range.start.line }
			.sorted()

	@Test
	fun `select-all bullet includes blank separator lines as empty items`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("Chapter One\n\nShe walked in.")

		extension.toggleBulletList(extension.selectAll())

		assertEquals(listOf(0, 1, 2), extension.linesWith(BulletListSpanStyle))
		assertEquals("- Chapter One\n- \n- She walked in.", extension.exportAsMarkdown())
	}

	@Test
	fun `select-all bullet twice returns to the original document`() = runTest {
		val extension = createMarkdownExtension()
		val original = "Chapter One\n\nShe walked in."
		extension.importMarkdown(original)

		extension.toggleBulletList(extension.selectAll())
		extension.toggleBulletList(extension.selectAll())

		assertTrue(extension.linesWith(BulletListSpanStyle).isEmpty())
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `select-all clear removes a bullet from a blank line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("- one\n- \n- two")
		assertEquals(listOf(0, 1, 2), extension.linesWith(BulletListSpanStyle))

		extension.toggleBulletList(extension.selectAll())

		assertTrue(extension.linesWith(BulletListSpanStyle).isEmpty())
		assertEquals("one\n\ntwo", extension.exportAsMarkdown())
	}

	@Test
	fun `select-all ordered list numbers contiguously across blank lines`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("First\n\nSecond\n\nThird")

		extension.toggleOrderedList(extension.selectAll())

		assertEquals(
			"1. First\n2. \n3. Second\n4. \n5. Third",
			extension.exportAsMarkdown(),
		)
	}

	@Test
	fun `select-all fence keeps a snippet with blank lines as one fence`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("fun a() {}\n\nfun b() {}")

		extension.toggleCodeFence(extension.selectAll())

		assertEquals(listOf(0, 1, 2), extension.linesWith(CodeFenceSpanStyle))
		assertEquals("```\nfun a() {}\n\nfun b() {}\n```", extension.exportAsMarkdown())
	}

	@Test
	fun `select-all bullet leaves a horizontal rule untouched`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n---\nafter")

		extension.toggleBulletList(extension.selectAll())

		assertEquals(listOf(0, 2), extension.linesWith(BulletListSpanStyle))
		assertEquals(listOf(1), extension.linesWith(HorizontalRuleSpanStyle))
		assertEquals("- before\n---\n- after", extension.exportAsMarkdown())
	}

	@Test
	fun `select-all bullet across a rule survives a save and reload`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n---\nafter")
		extension.toggleBulletList(extension.selectAll())
		val saved = extension.exportAsMarkdown()

		extension.importMarkdown(saved)

		assertEquals(listOf(1), extension.linesWith(HorizontalRuleSpanStyle))
		assertEquals(saved, extension.exportAsMarkdown())
	}

	@Test
	fun `select-all bullet twice across a rule clears every bullet`() = runTest {
		val extension = createMarkdownExtension()
		val original = "before\n---\nafter"
		extension.importMarkdown(original)

		extension.toggleBulletList(extension.selectAll())
		extension.toggleBulletList(extension.selectAll())

		assertTrue(extension.linesWith(BulletListSpanStyle).isEmpty())
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `select-all quote includes the rule line`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("before\n---\nafter")

		extension.toggleBlockquote(extension.selectAll())

		assertEquals(listOf(0, 1, 2), extension.linesWith(BlockquoteSpanStyle))
		assertEquals(listOf(1), extension.linesWith(HorizontalRuleSpanStyle))
		assertEquals("> before\n> ---\n> after", extension.exportAsMarkdown())
	}

	@Test
	fun `bullet sweep over only rule lines is a no-op`() = runTest {
		val extension = createMarkdownExtension()
		val original = "before\n---\n---\nafter"
		extension.importMarkdown(original)

		extension.toggleBulletList(1..2)

		assertTrue(extension.linesWith(BulletListSpanStyle).isEmpty())
		assertEquals(original, extension.exportAsMarkdown())
	}

	@Test
	fun `sweep past the end of the document acts on the lines that exist`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("one\ntwo")

		extension.toggleBulletList(0..9)

		assertEquals(listOf(0, 1), extension.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `single-line toggle on a blank line makes an empty item`() = runTest {
		val extension = createMarkdownExtension()
		extension.importMarkdown("one\n\ntwo")

		extension.toggleBulletList(1..1)

		assertEquals(listOf(1), extension.linesWith(BulletListSpanStyle))
	}

	@Test
	fun `undo restores the document after a select-all sweep`() = runTest {
		val extension = createMarkdownExtension()
		val original = "Chapter One\n\nShe walked in."
		extension.importMarkdown(original)

		extension.toggleBulletList(extension.selectAll())
		extension.editorState.undo()

		assertTrue(extension.linesWith(BulletListSpanStyle).isEmpty())
		assertEquals(original, extension.exportAsMarkdown())
	}
}
