package blocks

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.OrderedListStartSpanStyle
import com.darkrockstudios.texteditor.richstyle.nestListItems
import com.darkrockstudios.texteditor.richstyle.unnestListItems
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.orderedListStart
import com.darkrockstudios.texteditor.state.setOrderedListStart
import com.darkrockstudios.texteditor.state.textEditorStateSaver
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleOrderedList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.blockLines
import utils.setBlockLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An ordered list's start number: kept on the list's first item, honored by the
 * numbering, and carried through the edits that split, join and undo lists.
 */
class OrderedListStartTest {

	private fun TestScope.editor(blockLines: String): TextEditorState {
		val state = TextEditorState(
			scope = this,
			measurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr),
		)
		state.onViewportSizeChange(Size(800f, 800f))
		state.setBlockLines(blockLines)
		return state
	}

	/** The number each line's ordered item shows, null off an ordered item. */
	private fun TextEditorState.numbers(): List<Int?> =
		lineOffsets.filter { it.virtualLineIndex == 0 }.map { it.orderedListNumber }

	/** Each line holding a list start, and the start. */
	private fun TextEditorState.starts(): Map<Int, Int> =
		richSpanManager.getAllRichSpans()
			.mapNotNull { span -> (span.style as? OrderedListStartSpanStyle)?.let { span.range.start.line to it.start } }
			.toMap()

	@Test
	fun `a list counts on from its start`() = runTest {
		val state = editor("1. a\n1. b\n1. c")
		assertEquals(listOf(1, 2, 3), state.numbers())
		assertEquals(1, state.orderedListStart(2))

		assertTrue(state.setOrderedListStart(2, 3))
		assertEquals(listOf(3, 4, 5), state.numbers())
		assertEquals(mapOf(0 to 3), state.starts(), "the start is kept on the list's first item")
		assertEquals(3, state.orderedListStart(1))
	}

	@Test
	fun `a list can start at zero, and null or 1 takes the start off`() = runTest {
		val state = editor("1. a\n1. b")
		assertTrue(state.setOrderedListStart(0, 0))
		assertEquals(listOf(0, 1), state.numbers())

		assertTrue(state.setOrderedListStart(1, null))
		assertEquals(listOf(1, 2), state.numbers())
		assertEquals(emptyMap(), state.starts())

		state.setOrderedListStart(0, 5)
		state.setOrderedListStart(0, 1)
		assertEquals(emptyMap(), state.starts())
	}

	@Test
	fun `a start out of range or off an ordered item is refused`() = runTest {
		val state = editor("1. a\n- b\nplain")
		assertFalse(state.setOrderedListStart(0, -1))
		assertFalse(state.setOrderedListStart(0, OrderedListStartSpanStyle.MAX_START + 1))
		assertFalse(state.setOrderedListStart(1, 3))
		assertFalse(state.setOrderedListStart(2, 3))
		assertNull(state.orderedListStart(1))
		assertEquals(emptyMap(), state.starts())

		assertTrue(state.setOrderedListStart(0, OrderedListStartSpanStyle.MAX_START))
		assertEquals(OrderedListStartSpanStyle.MAX_START, state.numbers()[0])
	}

	@Test
	fun `each nesting level has its own start`() = runTest {
		val state = editor("1. a\n  1. x\n  1. y\n1. b\n  1. z")
		state.setOrderedListStart(2, 7)
		state.setOrderedListStart(3, 4)
		assertEquals(listOf(4, 7, 8, 5, 1), state.numbers())
		assertEquals(mapOf(0 to 4, 1 to 7), state.starts())
		assertEquals(1, state.orderedListStart(4), "a nested list under the next item is a list of its own")
	}

	@Test
	fun `setting a start is one undo step`() = runTest {
		val state = editor("1. a\n1. b")
		state.setOrderedListStart(0, 3)
		state.setOrderedListStart(0, 8)
		assertEquals(listOf(8, 9), state.numbers())

		state.undo()
		assertEquals(listOf(3, 4), state.numbers())
		state.undo()
		assertEquals(listOf(1, 2), state.numbers())
		assertEquals(emptyMap(), state.starts())
		state.redo()
		state.redo()
		assertEquals(listOf(8, 9), state.numbers())
	}

	@Test
	fun `enter at the end of the first item continues the list from its start`() = runTest {
		val state = editor("1. a\n1. b")
		state.setOrderedListStart(0, 3)
		state.cursor.updatePosition(CharLineOffset(0, 1))
		state.insertNewlineAtCursor()
		assertEquals(listOf(3, 4, 5), state.numbers())
		assertEquals(mapOf(0 to 3), state.starts())
	}

	@Test
	fun `enter at the start of the first item keeps the list's start on its new first item`() = runTest {
		val state = editor("1. a\n1. b")
		state.setOrderedListStart(0, 3)
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertNewlineAtCursor()
		assertEquals("1. \n1. a\n1. b", state.blockLines())
		assertEquals(listOf(3, 4, 5), state.numbers())

		state.undo()
		assertEquals(listOf(3, 4), state.numbers())
		assertEquals(mapOf(0 to 3), state.starts())
	}

	@Test
	fun `a line break typed at the first item's start keeps the list's start on its new first item`() = runTest {
		val state = editor("1. a\n1. b")
		state.setOrderedListStart(0, 3)
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertStringAtCursor("\n")
		assertEquals("1. \n1. a\n1. b", state.blockLines())
		assertEquals(listOf(3, 4, 5), state.numbers())
		assertEquals(mapOf(0 to 3), state.starts())
	}

	@Test
	fun `splitting a list starts the second part at 1, and undo joins it back`() = runTest {
		val state = editor("1. a\n1. b\n1. c")
		state.setOrderedListStart(0, 3)
		state.toggleOrderedList(1..1)
		assertEquals(listOf(3, null, 1), state.numbers())

		state.undo()
		assertEquals(listOf(3, 4, 5), state.numbers())
	}

	@Test
	fun `joining two lists continues the first, and keeps the second's start for a split or an undo`() = runTest {
		val state = editor("1. a\n\n1. b\n1. c")
		state.setOrderedListStart(0, 3)
		state.setOrderedListStart(2, 7)
		assertEquals(listOf(3, null, 7, 8), state.numbers())

		// Backspace on the blank line between them joins the lists.
		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()
		assertEquals("1. a\n1. b\n1. c", state.blockLines())
		assertEquals(listOf(3, 4, 5), state.numbers())

		state.undo()
		assertEquals(listOf(3, null, 7, 8), state.numbers())

		state.redo()
		assertEquals(listOf(3, 4, 5), state.numbers())
		// Splitting the joined list where the second began gives it its start back.
		state.toggleOrderedList(0..0)
		assertEquals(listOf(null, 7, 8), state.numbers())
	}

	@Test
	fun `deleting the first item keeps the list's start on the item now first`() = runTest {
		val state = editor("1. a\n1. b\n1. c")
		state.setOrderedListStart(0, 3)
		state.delete(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 0)))
		assertEquals("1. b\n1. c", state.blockLines())
		assertEquals(listOf(3, 4), state.numbers())

		state.undo()
		assertEquals("1. a\n1. b\n1. c", state.blockLines())
		assertEquals(listOf(3, 4, 5), state.numbers())
		assertEquals(mapOf(0 to 3), state.starts())

		state.redo()
		assertEquals(listOf(3, 4), state.numbers())
	}

	@Test
	fun `deleting a whole list leaves the list after it its own numbering`() = runTest {
		val state = editor("1. a\nplain\n1. b")
		state.setOrderedListStart(0, 3)
		state.delete(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 0)))
		assertEquals("plain\n1. b", state.blockLines())
		assertEquals(listOf(null, 1), state.numbers())
		assertEquals(emptyMap(), state.starts())
	}

	@Test
	fun `typing over the first item and the line before keeps the list's start`() = runTest {
		val state = editor("intro\n1. a\n1. b")
		state.setOrderedListStart(1, 3)
		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(1, 1)), "x")
		assertEquals("inx\n1. b", state.blockLines())
		assertEquals(listOf(null, 3), state.numbers())

		state.undo()
		assertEquals(listOf(null, 3, 4), state.numbers())
		assertEquals(mapOf(1 to 3), state.starts())
	}

	@Test
	fun `taking the first item out of the list leaves the list its start`() = runTest {
		val state = editor("1. a\n1. b\n1. c")
		state.setOrderedListStart(0, 3)
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.backspaceAtCursor()
		assertEquals("a\n1. b\n1. c", state.blockLines())
		assertEquals(listOf(null, 3, 4), state.numbers())
		assertEquals(mapOf(1 to 3), state.starts())

		state.undo()
		assertEquals(listOf(3, 4, 5), state.numbers())
		assertEquals(mapOf(0 to 3), state.starts())
		state.redo()
		assertEquals(listOf(null, 3, 4), state.numbers())
	}

	@Test
	fun `leaving an empty first item with Enter leaves the list its start`() = runTest {
		val state = editor("1. \n1. b")
		state.setOrderedListStart(0, 5)
		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertNewlineAtCursor()
		assertEquals("\n1. b", state.blockLines())
		assertEquals(listOf(null, 5), state.numbers())
	}

	@Test
	fun `making the first item a bullet leaves the rest of the list its start`() = runTest {
		val state = editor("1. a\n1. b\n1. c")
		state.setOrderedListStart(0, 3)
		state.toggleBulletList(0..0)
		assertEquals(listOf(null, 3, 4), state.numbers())

		state.undo()
		assertEquals(listOf(3, 4, 5), state.numbers())
		assertEquals(mapOf(0 to 3), state.starts())
	}

	@Test
	fun `nesting a list's first item leaves the list its start`() = runTest {
		val state = editor("- p\n1. a\n1. b")
		state.setOrderedListStart(1, 4)
		state.nestListItems(1..1)
		assertEquals("- p\n  1. a\n1. b", state.blockLines())
		assertEquals(listOf(null, 1, 4), state.numbers())

		state.undo()
		assertEquals(listOf(null, 4, 5), state.numbers())
	}

	@Test
	fun `un-nesting a nested list's first item leaves the nested list its start`() = runTest {
		val state = editor("1. a\n  1. x\n  1. y")
		state.setOrderedListStart(1, 7)
		assertEquals(listOf(1, 7, 8), state.numbers())
		state.unnestListItems(1..1)
		assertEquals("1. a\n1. x\n  1. y", state.blockLines())
		assertEquals(listOf(1, 2, 7), state.numbers())

		state.undo()
		assertEquals(listOf(1, 7, 8), state.numbers())
	}

	@Test
	fun `making a list bullets drops its start, and undo restores it`() = runTest {
		val state = editor("1. a\n1. b")
		state.setOrderedListStart(0, 3)
		state.toggleBulletList(0..1)
		state.toggleOrderedList(0..1)
		assertEquals(listOf(1, 2), state.numbers())

		state.undo()
		state.undo()
		assertEquals(listOf(3, 4), state.numbers())
	}

	@Test
	fun `the saved state keeps a list's start`() = runTest {
		val state = editor("1. a\n1. b")
		state.setOrderedListStart(0, 0)
		val saver = textEditorStateSaver(this, state.textMeasurer, richSpanStyleSaver = null)
		val saved = with(saver) { SaverScope { true }.save(state) }!!
		val restored = saver.restore(saved)!!
		assertEquals(0, restored.orderedListStart(1))
	}

	@Test
	fun `taking the first items out of the list together leaves the list its start`() = runTest {
		val off = editor("1. a\n1. b\n1. c\n1. d")
		off.setOrderedListStart(0, 3)
		off.toggleOrderedList(0..1)
		assertEquals(listOf(null, null, 3, 4), off.numbers())
		assertEquals(mapOf(2 to 3), off.starts())
		off.undo()
		assertEquals(listOf(3, 4, 5, 6), off.numbers())

		val bullets = editor("1. a\n1. b\n1. c\n1. d")
		bullets.setOrderedListStart(0, 3)
		bullets.toggleBulletList(0..1)
		assertEquals(listOf(null, null, 3, 4), bullets.numbers())
	}
}
