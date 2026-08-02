package e2e.torture

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import utils.assertBlockState
import utils.assertRichSpanInvariants
import utils.editorUiTest
import utils.selectChars
import utils.undoAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Undo/redo under sustained abuse: long streaks, interleaved bursts, the history
 * cap, and spans that must survive the round trips.
 */
class UndoStormE2eTest {

	@Test
	fun `type 50 undo 50 redo 50 is exact at both endpoints`() = editorUiTest {
		val typed = "x".repeat(50)
		typeText(typed)
		assertEquals(typed, text)

		repeat(50) { press(Key.Z, ctrl = true) }
		assertEquals("", text)
		assertFalse(state.canUndo)

		repeat(50) { press(Key.Y, ctrl = true) }
		assertEquals(typed, text)
		assertEquals(50, cursorIndex)
		assertFalse(state.canRedo)
	}

	@Test
	fun `exactly 100 edits then undoAll restores the initial document`() = editorUiTest(
		initialText = AnnotatedString("seed"),
	) {
		press(Key.MoveEnd)
		typeText("y".repeat(100))

		undoAll()

		assertEquals("seed", text)
	}

	@Test
	fun `101 edits then undoAll restores the initial document`() = editorUiTest(
		initialText = AnnotatedString("seed"),
	) {
		press(Key.MoveEnd)
		typeText("y".repeat(101))

		undoAll()

		// Typing coalesces into wordwise history entries, so a keystroke count far
		// beyond the old per-keystroke cap still undoes back to the origin.
		assertEquals("seed", text)
	}

	@Test
	fun `undo removes the last word not the last letter`() = editorUiTest {
		typeText("alpha beta")

		press(Key.Z, ctrl = true)
		assertEquals("alpha ", text)

		press(Key.Z, ctrl = true)
		assertEquals("", text)
	}

	@Test
	fun `redo restores a whole coalesced word`() = editorUiTest {
		typeText("alpha beta")

		press(Key.Z, ctrl = true)
		assertEquals("alpha ", text)

		press(Key.Y, ctrl = true)
		assertEquals("alpha beta", text)
		assertEquals(10, cursorIndex, "the caret lands at the end of the redone word")
	}

	@Test
	fun `undo restores a backspaced word in one step`() = editorUiTest(
		initialText = AnnotatedString("alpha beta"),
	) {
		press(Key.MoveEnd)
		repeat(4) { press(Key.Backspace) }
		assertEquals("alpha ", text)

		press(Key.Z, ctrl = true)

		assertEquals("alpha beta", text)
	}

	@Test
	fun `undo separates typing from the paste it followed`() = editorUiTest {
		setPlainClipboardText("pasted")
		press(Key.V, ctrl = true)
		typeText("x")

		press(Key.Z, ctrl = true)
		assertEquals("pasted", text, "the typed character undoes without the paste")

		press(Key.Z, ctrl = true)
		assertEquals("", text)
	}

	@Test
	fun `enter breaks a typing run into separate undo steps`() = editorUiTest {
		typeText("ab\ncd")

		press(Key.Z, ctrl = true)
		assertEquals(listOf("ab", ""), lines)

		press(Key.Z, ctrl = true)
		assertEquals(listOf("ab"), lines)

		press(Key.Z, ctrl = true)
		assertEquals("", text)
	}

	@Test
	fun `clicking elsewhere breaks the typing run`() = editorUiTest(
		initialText = AnnotatedString("stub "),
	) {
		press(Key.MoveEnd)
		typeText("tail")
		clickAtCharacter(0)
		typeText("x")
		assertEquals("xstub tail", text)

		press(Key.Z, ctrl = true)

		assertEquals("stub tail", text, "only the character typed after the click undoes")
	}

	@Test
	fun `alternating type and undo leaves a bold span untouched`() = editorUiTest(
		initialText = AnnotatedString("bold word"),
	) {
		val bold = SpanStyle(fontWeight = FontWeight.Bold)
		state.addStyleSpan(
			TextEditorRange(state.getOffsetAtCharacter(0), state.getOffsetAtCharacter(4)),
			bold,
		)
		press(Key.MoveEnd)

		repeat(20) {
			typeText("z")
			press(Key.Z, ctrl = true)
		}

		assertEquals("bold word", text)
		assertTrue(
			stylesAt(2).any { it.fontWeight == FontWeight.Bold },
			"the bold run must survive 20 type/undo cycles",
		)
		assertTrue(
			stylesAt(6).none { it.fontWeight == FontWeight.Bold },
			"the bold run must not grow",
		)
		assertRichSpanInvariants()
	}

	@Test
	fun `undo storm across block toggles lands on the initial document`() = editorUiTest {
		typeText("abc\ndef")
		markdown.toggleBulletList(0..1)
		press(Key.MoveEnd, ctrl = true)
		typeText("x")
		markdown.toggleBlockquote(0..1)

		undoAll()

		assertEquals("", text)
		assertTrue(state.richSpanManager.getAllRichSpans().isEmpty())
	}

	@Test
	fun `a divergent edit clears the redo stack`() = editorUiTest {
		typeText("a")
		press(Key.Z, ctrl = true)
		typeText("b")

		assertFalse(state.canRedo)
		press(Key.Y, ctrl = true)
		assertEquals("b", text)
	}

	@Test
	fun `undo of a paste insert keeps every rich span`() = editorUiTest {
		markdown.importMarkdown("- alpha\n- bravo")

		press(Key.MoveHome, ctrl = true)
		repeat(2) { press(Key.DirectionRight) }
		setPlainClipboardText("XX")
		press(Key.V, ctrl = true)
		assertEquals(listOf("alXXpha", "bravo"), lines)

		press(Key.Z, ctrl = true)

		assertEquals(listOf("alpha", "bravo"), lines)
		assertBlockState(0, bullet = true)
		assertBlockState(1, bullet = true)
		assertRichSpanInvariants()
	}

	@Test
	fun `a decoration added outside history survives undo of a real edit`() = editorUiTest(
		initialText = AnnotatedString("hello world"),
	) {
		val highlight = HighlightSpanStyle(Color.Yellow)
		state.updateRichSpans(
			remove = emptySet(),
			add = setOf(
				RichSpan(
					TextEditorRange(state.getOffsetAtCharacter(6), state.getOffsetAtCharacter(11)),
					highlight,
				)
			),
		)

		press(Key.MoveHome, ctrl = true)
		typeText("A")
		press(Key.Z, ctrl = true)

		val spans = state.richSpanManager.getAllRichSpans().filter { it.style === highlight }
		assertEquals(1, spans.size, "the highlight must survive the type/undo cycle")
		val range = spans.single().range
		assertEquals(6, range.start.char, "the highlight must rebase back to its original start")
		assertEquals(11, range.end.char)
		assertRichSpanInvariants()
	}

	@Test
	fun `interleaved undo redo bursts land where the ledger says`() = editorUiTest {
		typeText("one two three")
		val full = "one two three"

		repeat(3) {
			press(Key.Z, ctrl = true)
			press(Key.Z, ctrl = true)
			press(Key.Y, ctrl = true)
			press(Key.Z, ctrl = true)
			press(Key.Y, ctrl = true)
			press(Key.Y, ctrl = true)

			assertEquals(full, text, "a net-zero undo/redo burst must be a no-op")
		}
	}

	@Test
	fun `five selection replacements then undoAll restores text and spans`() = editorUiTest(
		initialText = AnnotatedString(
			"aaa bbb ccc ddd eee",
			spanStyles = listOf(
				AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 8, 11),
			),
		),
	) {
		for (word in listOf("aaa", "bbb", "ddd", "eee")) {
			val from = text.indexOf(word)
			selectChars(from, from + word.length)
			typeText("X")
		}
		assertEquals("X X ccc X X", text)
		undoAll()

		assertEquals("aaa bbb ccc ddd eee", text)
		assertTrue(
			stylesAt(9).any { it.fontWeight == FontWeight.Bold },
			"the bold run on 'ccc' must survive the replacement storm",
		)
		assertTrue(stylesAt(4).none { it.fontWeight == FontWeight.Bold })
		assertRichSpanInvariants()
	}
}
