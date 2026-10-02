package blocks

import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.richstyle.InMemoryImageProvider
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.headerLevel
import com.darkrockstudios.texteditor.state.isBlockquote
import com.darkrockstudios.texteditor.state.isBulletList
import com.darkrockstudios.texteditor.state.isCodeFence
import com.darkrockstudios.texteditor.state.isOrderedList
import com.darkrockstudios.texteditor.state.listLevel
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import utils.blockLines
import utils.imageLines
import utils.setBlockLines

/** The block lines notation the block tests build and read documents in (`testUtils/blockLines`). */
class BlockLinesTest {

	private fun TestScope.editor(blockLines: String): TextEditorState =
		TextEditorState(scope = this, measurer = mockk(relaxed = true)).apply {
			setBlockLines(blockLines, imageProvider = InMemoryImageProvider())
		}

	private val TextEditorState.lines: List<String> get() = textLines.map { it.text }

	@Test
	fun `every marker loads its block and reads back the same`() = runTest {
		val document = listOf(
			"# Title",
			"> quoted",
			"> - quoted item",
			">   1. nested in a quote",
			"- item",
			"  - nested",
			"1. first",
			"``` code",
			"---",
			"![alt](image.png)",
			"",
			"plain",
		).joinToString("\n")
		val state = editor(document)

		assertEquals(document, state.blockLines())
		assertEquals(listOf("Title", "quoted", "quoted item", "nested in a quote", "item", "nested", "first", "code"), state.lines.take(8))
		assertEquals(1, state.headerLevel(0))
		assertTrue((1..3).all { state.isBlockquote(it) })
		assertEquals(1, state.listLevel(3))
		assertTrue(state.isOrderedList(3))
		assertEquals(1, state.listLevel(5))
		assertTrue(state.isCodeFence(7))
		assertEquals(listOf(9), state.imageLines())
		assertFalse(state.isBlockquote(11))
	}

	@Test
	fun `any number reads as an ordered item and writes as 1`() = runTest {
		assertEquals("1. a\n1. b", editor("1. a\n7. b").blockLines())
	}

	@Test
	fun `text that reads as a marker is escaped`() = runTest {
		val document = "\\- not a list\n- \\# not a heading\n\\---\n\\\\backslash"
		val state = editor(document)

		assertEquals(listOf("- not a list", "# not a heading", "---", "\\backslash"), state.lines)
		assertFalse(state.isBulletList(0))
		assertEquals(document, state.blockLines())
	}

	@Test
	fun `a load leaves the styles as an importer does, unless asked for bare text`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines("- item\n\nplain")

		val body = RichTextStyles.DEFAULT.defaultTextStyle
		assertEquals(listOf(body), state.textLines[0].spanStyles.map { it.item })
		assertEquals(emptyList(), state.textLines[1].spanStyles)
		assertEquals(listOf(body), state.textLines[2].spanStyles.map { it.item })

		state.setBlockLines("- item", asImported = false)
		assertEquals(emptyList(), state.textLines[0].spanStyles)
	}

	@Test
	fun `a fence line is written with its space, even when empty`() = runTest {
		assertEquals("``` \n``` code", editor("``` \n``` code").blockLines())
	}
}
