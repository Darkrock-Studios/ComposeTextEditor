package e2e.torture

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import utils.assertBlockState
import utils.assertRichSpanInvariants
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Block styles (bullet, ordered, quote, fence) under hostile realistic editing:
 * toggles interleaved with typing, splitting, demoting, and undo.
 */
class BlockToggleTortureE2eTest {

	@Test
	fun `typing at the end of a bulleted item extends it`() = editorUiTest(
		initialText = AnnotatedString("item"),
	) {
		markdown.toggleBulletList(0..0)
		press(Key.MoveEnd)
		typeText(" grows")

		assertEquals("item grows", text)
		assertBlockState(0, bullet = true)
		assertRichSpanInvariants()
	}

	@Test
	fun `enter mid item splits into two bulleted items`() = editorUiTest(
		initialText = AnnotatedString("itemtail"),
	) {
		markdown.toggleBulletList(0..0)
		press(Key.MoveHome)
		repeat(4) { press(Key.DirectionRight) }
		press(Key.Enter)

		assertEquals(listOf("item", "tail"), lines)
		assertBlockState(0, bullet = true)
		assertBlockState(1, bullet = true)
		assertRichSpanInvariants()
	}

	@Test
	fun `enter on an empty list item exits the list`() = editorUiTest {
		markdown.toggleBulletList(0..0)
		typeText("item")
		press(Key.Enter)
		assertBlockState(1, bullet = true)

		press(Key.Enter)

		assertEquals(listOf("item", ""), lines, "the exit keystroke is eaten, not inserted")
		assertBlockState(0, bullet = true)
		assertBlockState(1)
		assertRichSpanInvariants()
	}

	@Test
	fun `backspace at column 0 demotes first then merges`() = editorUiTest(
		initialText = AnnotatedString("plain\nitem"),
	) {
		markdown.toggleBulletList(1..1)
		press(Key.MoveEnd, ctrl = true)
		press(Key.MoveHome)

		press(Key.Backspace)
		assertEquals(listOf("plain", "item"), lines, "first backspace only demotes")
		assertBlockState(1)

		press(Key.Backspace)
		assertEquals(listOf("plainitem"), lines, "second backspace merges the lines")
		assertRichSpanInvariants()
	}

	@Test
	fun `backspace at column 0 merges adjacent same-block items directly`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
	) {
		markdown.toggleBulletList(0..1)
		press(Key.MoveEnd, ctrl = true)
		press(Key.MoveHome)

		press(Key.Backspace)

		assertEquals(listOf("onetwo"), lines, "same-block neighbours merge on the first backspace")
		assertBlockState(0, bullet = true)
		assertRichSpanInvariants()
	}

	@Test
	fun `bullet toggle over an ordered line swaps the list type`() = editorUiTest(
		initialText = AnnotatedString("a\nb\nc"),
	) {
		markdown.toggleOrderedList(0..2)
		markdown.toggleBulletList(1..1)

		assertBlockState(0, ordered = true)
		assertBlockState(1, bullet = true)
		assertBlockState(2, ordered = true)
		assertEquals(
			"1. a\n- b\n1. c",
			markdown.exportAsMarkdown(),
			"the ordered run restarts numbering after the interruption",
		)
		assertRichSpanInvariants()
	}

	@Test
	fun `code fence toggle strips stacked quote and bullet`() = editorUiTest(
		initialText = AnnotatedString("code"),
	) {
		markdown.toggleBulletList(0..0)
		markdown.toggleBlockquote(0..0)
		assertBlockState(0, bullet = true, quote = true)

		markdown.toggleCodeFence(0..0)

		assertBlockState(0, fence = true)
		assertRichSpanInvariants()
	}

	@Test
	fun `quote stacks with bullet and both survive typing`() = editorUiTest(
		initialText = AnnotatedString("item"),
	) {
		markdown.toggleBulletList(0..0)
		markdown.toggleBlockquote(0..0)
		press(Key.MoveEnd)
		typeText(" and more")

		assertEquals("item and more", text)
		assertBlockState(0, bullet = true, quote = true)
		assertRichSpanInvariants()
	}

	@Test
	fun `block toggle after select all does not inject literal markers`() = editorUiTest(
		initialText = AnnotatedString("alpha\nbravo\ncharlie"),
	) {
		press(Key.A, ctrl = true)
		markdown.toggleBulletList(0..2)

		assertEquals(
			listOf("alpha", "bravo", "charlie"),
			lines,
			"toggling must attach gutter spans, never literal '- ' text",
		)
		for (line in 0..2) assertBlockState(line, bullet = true)

		val exported = markdown.exportAsMarkdown()
		assertEquals("- alpha\n- bravo\n- charlie", exported)
		assertRichSpanInvariants()
	}

	@Test
	fun `toggle undo redo cycle is lossless`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
	) {
		markdown.toggleBlockquote(0..1)
		markdown.toggleBulletList(0..1)
		assertBlockState(0, quote = true, bullet = true)
		assertBlockState(1, quote = true, bullet = true)

		press(Key.Z, ctrl = true)
		assertBlockState(0, quote = true)
		assertBlockState(1, quote = true)

		press(Key.Z, ctrl = true)
		assertBlockState(0)
		assertBlockState(1)

		press(Key.Y, ctrl = true)
		press(Key.Y, ctrl = true)
		assertBlockState(0, quote = true, bullet = true)
		assertBlockState(1, quote = true, bullet = true)
		assertRichSpanInvariants()
	}

	@Test
	fun `deleting a whole bulleted line leaves no orphan span`() = editorUiTest(
		initialText = AnnotatedString("aa\nbb\ncc"),
	) {
		markdown.toggleBulletList(0..2)

		press(Key.MoveHome, ctrl = true)
		press(Key.DirectionDown)
		press(Key.MoveEnd, shift = true)
		press(Key.DirectionRight, shift = true)
		press(Key.Delete)

		assertEquals(listOf("aa", "cc"), lines)
		assertBlockState(0, bullet = true)
		assertBlockState(1, bullet = true)
		assertRichSpanInvariants()
	}

	@Test
	fun `inserting an item renumbers an ordered list on export`() = editorUiTest(
		initialText = AnnotatedString("a\nb\nc"),
	) {
		markdown.toggleOrderedList(0..2)
		press(Key.MoveHome, ctrl = true)
		press(Key.MoveEnd)
		press(Key.Enter)
		typeText("new")

		assertEquals(listOf("a", "new", "b", "c"), lines)
		assertEquals("1. a\n2. new\n3. b\n4. c", markdown.exportAsMarkdown())
		assertRichSpanInvariants()
	}

	@Test
	fun `a smart backspace demotion is undoable`() = editorUiTest(
		initialText = AnnotatedString("plain\nitem"),
	) {
		markdown.toggleBulletList(1..1)
		press(Key.MoveEnd, ctrl = true)
		press(Key.MoveHome)

		press(Key.Backspace)
		assertBlockState(1)

		press(Key.Z, ctrl = true)

		assertEquals(listOf("plain", "item"), lines, "undo must not fall through to an older edit")
		assertBlockState(1, bullet = true)
	}

	@Test
	fun `a smart enter exit from a list is undoable`() = editorUiTest {
		markdown.toggleBulletList(0..0)
		typeText("item")
		press(Key.Enter)
		press(Key.Enter)
		assertBlockState(1)

		press(Key.Z, ctrl = true)

		assertEquals(listOf("item", ""), lines, "undo must not fall through to an older edit")
		assertBlockState(1, bullet = true)
	}

	@Test
	fun `fencing 50 lines and unfencing is symmetric`() = editorUiTest(
		initialText = AnnotatedString((0 until 50).joinToString("\n") { "line$it" }),
	) {
		markdown.toggleCodeFence(0..49)
		for (line in 0 until 50) assertBlockState(line, fence = true)

		markdown.toggleCodeFence(0..49)
		assertTrue(
			state.richSpanManager.getAllRichSpans().isEmpty(),
			"unfencing must leave zero rich spans",
		)

		press(Key.Z, ctrl = true)
		for (line in 0 until 50) assertBlockState(line, fence = true)

		press(Key.Z, ctrl = true)
		assertTrue(state.richSpanManager.getAllRichSpans().isEmpty())
		assertRichSpanInvariants()
	}
}
