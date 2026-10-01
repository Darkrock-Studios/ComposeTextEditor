package e2e.torture

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.state.toggleBlockquote
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleCodeFence
import com.darkrockstudios.texteditor.state.toggleOrderedList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import utils.assertBlockState
import utils.assertRichSpanInvariants
import utils.blockFlags
import utils.blockLines
import utils.editorUiTest
import utils.linesWith
import utils.selectChars
import utils.setBlockLines

/**
 * Deletions that cross block boundaries: merging lists, joining styled lines into
 * plain ones, and chewing through mixed-block documents one keystroke at a time.
 */
class BlockBoundaryDeletionE2eTest {

	@Test
	fun `deleting the separator line merges two bullet lists`() = editorUiTest {
		state.setBlockLines("- a\n\n- b")
		assertEquals(listOf("a", "", "b"), lines)

		press(Key.MoveHome, ctrl = true)
		press(Key.MoveEnd)
		press(Key.Delete)

		assertEquals(listOf("a", "b"), lines)
		assertBlockState(0, bullet = true)
		assertBlockState(1, bullet = true)
		assertEquals("- a\n- b", state.blockLines(), "one contiguous list")
		assertRichSpanInvariants()
	}

	@Test
	fun `forward deleting the newline before a bulleted line makes it plain`() = editorUiTest(
		initialText = AnnotatedString("plain\nitem"),
	) {
		state.toggleBulletList(1..1)

		press(Key.MoveHome, ctrl = true)
		press(Key.MoveEnd)
		press(Key.Delete)

		assertEquals(listOf("plainitem"), lines)
		// The backspace path deliberately demotes a block before merging it into a
		// plain line; the forward-delete join of the same two lines must agree.
		assertEquals(emptySet(), blockFlags(0), "the receiving plain line keeps its identity")
		assertRichSpanInvariants()
	}

	@Test
	fun `deleting across a quote to fence boundary leaves one block style`() = editorUiTest(
		initialText = AnnotatedString("quoted\ncode"),
	) {
		state.toggleBlockquote(0..0)
		state.toggleCodeFence(1..1)

		selectChars(3, 9)
		press(Key.Delete)

		assertEquals(listOf("quode"), lines)
		// Fence stacks with nothing (conflicts), so whatever the join keeps,
		// a quote+fence combination on one line is an invalid document.
		assertEquals(setOf("quote"), blockFlags(0), "joined line must keep one valid block style")
		assertRichSpanInvariants()
	}

	@Test
	fun `select all delete in a fully styled doc leaves a clean empty state`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo\nthree"),
	) {
		state.toggleBulletList(0..2)
		state.toggleBlockquote(0..2)

		press(Key.A, ctrl = true)
		press(Key.Delete)

		assertEquals("", text)
		assertTrue(
			state.richSpanManager.getAllRichSpans().isEmpty(),
			"an emptied document must carry no rich spans",
		)
	}

	@Test
	fun `deleting the last character of a one char item keeps the bullet`() = editorUiTest(
		initialText = AnnotatedString("x"),
	) {
		state.toggleBulletList(0..0)
		press(Key.MoveEnd)
		press(Key.Backspace)

		assertEquals("", text)
		assertBlockState(0, bullet = true)
		assertRichSpanInvariants()
	}

	@Test
	fun `deleting across a horizontal rule removes it cleanly`() = editorUiTest {
		state.setBlockLines("aa\n---\nbb")
		assertEquals(listOf(1), state.linesWith(HorizontalRuleSpanStyle))

		selectChars(1, 6)
		press(Key.Delete)

		assertEquals("ab", text)
		assertEquals(emptyList(), state.linesWith(HorizontalRuleSpanStyle))
		assertRichSpanInvariants()
	}

	@Test
	fun `undo of a cross block delete restores both block styles`() = editorUiTest(
		initialText = AnnotatedString("quoted\ncode"),
	) {
		state.toggleBlockquote(0..0)
		state.toggleCodeFence(1..1)

		selectChars(3, 9)
		press(Key.Delete)
		press(Key.Z, ctrl = true)

		assertEquals(listOf("quoted", "code"), lines)
		assertEquals(setOf("quote"), blockFlags(0), "line 0 restored exactly")
		assertEquals(setOf("fence"), blockFlags(1), "line 1 restored exactly")
		assertRichSpanInvariants()
	}

	@Test
	fun `word delete at the end of a bulleted word keeps the bullet`() = editorUiTest(
		initialText = AnnotatedString("word"),
	) {
		state.toggleBulletList(0..0)
		press(Key.MoveEnd)
		press(Key.Backspace, ctrl = true)

		assertEquals("", text)
		assertBlockState(0, bullet = true)
		assertRichSpanInvariants()
	}

	@Test
	fun `deleting the blank line between two fences merges them`() = editorUiTest(
		initialText = AnnotatedString("code1\n\ncode2"),
	) {
		state.toggleCodeFence(0..0)
		state.toggleCodeFence(2..2)

		press(Key.MoveHome, ctrl = true)
		press(Key.MoveEnd)
		press(Key.Delete)

		assertEquals(listOf("code1", "code2"), lines)
		assertBlockState(0, fence = true)
		assertBlockState(1, fence = true)
		assertEquals(
			"``` code1\n``` code2",
			state.blockLines(),
			"contiguous fence lines export as one fence pair",
		)
		assertRichSpanInvariants()
	}

	@Test
	fun `backspacing through an entire mixed block document ends clean`() = editorUiTest(
		initialText = AnnotatedString("qq\nbb\noo\ncc\npp"),
	) {
		state.toggleBlockquote(0..0)
		state.toggleBulletList(1..1)
		state.toggleOrderedList(2..2)
		state.toggleCodeFence(3..3)

		press(Key.MoveEnd, ctrl = true)
		var presses = 0
		while ((text.isNotEmpty() || state.richSpanManager.getAllRichSpans().isNotEmpty()) && presses < 40) {
			press(Key.Backspace)
			presses++
			if (presses % 5 == 0) assertRichSpanInvariants()
		}

		assertEquals("", text, "the document must empty within $presses backspaces")
		assertTrue(state.richSpanManager.getAllRichSpans().isEmpty())
		assertRichSpanInvariants()
	}
}
