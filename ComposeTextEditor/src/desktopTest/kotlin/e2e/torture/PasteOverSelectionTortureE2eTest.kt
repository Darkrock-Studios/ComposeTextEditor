package e2e.torture

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.clipboard.AnnotatedStringTransferable
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import utils.assertRichSpanInvariants
import utils.blockFlags
import utils.editorUiTest
import utils.linesWith
import utils.pasteHtml
import utils.selectChars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Paste-over-selection is the replace path (RichSpanManager.handleReplace), the
 * least defended span-adjustment route: spans wholly inside the replaced range,
 * sticky line anchors, and the copy buffer all get abused here.
 */
class PasteOverSelectionTortureE2eTest {

	@Test
	fun `pasting plain text over a mid word selection keeps the bullet`() = editorUiTest {
		markdown.importMarkdown("- item here")

		selectChars(2, 4)
		setPlainClipboardText("XX")
		press(Key.V, ctrl = true)

		assertEquals("itXX here", text)
		assertEquals(setOf("bullet"), blockFlags(0))
		assertRichSpanInvariants()
	}

	@Test
	fun `pasting an html list over a selected plain line bullets only the pasted lines`() = editorUiTest(
		initialText = AnnotatedString("intro\nmiddle\nend"),
	) {
		selectChars(6, 12)
		pasteHtml("<ul><li>a</li><li>b</li></ul>")

		assertEquals(listOf("intro", "a", "b", "end"), lines)
		assertEquals(listOf(1, 2), state.linesWith(BulletListSpanStyle))
		assertEquals(setOf<String>(), blockFlags(0))
		assertEquals(setOf<String>(), blockFlags(3))
		assertRichSpanInvariants()
	}

	@Test
	fun `pasting an html ordered list into a bullet list keeps list types exclusive`() = editorUiTest {
		markdown.importMarkdown("- one\n- two")

		selectChars(2, 6)
		pasteHtml("<ol><li>x</li><li>y</li></ol>")

		for (line in lines.indices) {
			val flags = blockFlags(line)
			assertTrue(
				!("bullet" in flags && "ordered" in flags),
				"line $line carries both list types: $flags",
			)
		}
		assertRichSpanInvariants()
	}

	@Test
	fun `typing over a fully selected bulleted item keeps the bullet`() = editorUiTest {
		markdown.importMarkdown("- item")

		selectChars(0, 4)
		typeText("X")

		assertEquals("X", text)
		assertEquals(setOf("bullet"), blockFlags(0), "replacing an item's text is not removing the item")
		assertRichSpanInvariants()
	}

	@Test
	fun `a partial copy of a list item pastes as plain text`() = editorUiTest {
		markdown.importMarkdown("- item\n> quoted")

		selectChars(1, 3)
		press(Key.C, ctrl = true)
		press(Key.MoveEnd, ctrl = true)
		press(Key.V, ctrl = true)

		assertEquals(listOf("item", "quotedte"), lines)
		assertEquals(
			setOf("quote"),
			blockFlags(1),
			"a fragment of an item's text must not drag its list marker along",
		)
		assertRichSpanInvariants()
	}

	@Test
	fun `copy then an intervening edit then paste applies no stale spans`() = editorUiTest {
		markdown.importMarkdown("- item\nplain")

		selectChars(0, 4)
		press(Key.C, ctrl = true)
		press(Key.MoveEnd, ctrl = true)
		typeText("z")
		press(Key.V, ctrl = true)

		assertEquals(listOf("item", "plainzitem"), lines)
		assertEquals(setOf<String>(), blockFlags(1), "the intervening edit must invalidate the span buffer")
		assertRichSpanInvariants()
	}

	@Test
	fun `undo restores a highlight the paste replaced`() = editorUiTest(
		initialText = AnnotatedString("hello world"),
	) {
		val highlight = HighlightSpanStyle(Color.Yellow)
		state.addRichSpan(5, 8, highlight)

		selectChars(3, 10)
		setPlainClipboardText("REPL")
		press(Key.V, ctrl = true)
		assertEquals("helREPLd", text)

		press(Key.Z, ctrl = true)

		assertEquals("hello world", text)
		val spans = state.richSpanManager.getAllRichSpans().filter { it.style === highlight }
		assertEquals(1, spans.size, "undo of a replace must restore the span the replace swallowed")
		assertEquals(5, spans.single().range.start.char)
		assertEquals(8, spans.single().range.end.char)
		assertRichSpanInvariants()
	}

	@Test
	fun `paste over a selection anchored at the item start keeps the bullet anchored`() = editorUiTest {
		markdown.importMarkdown("- item")

		selectChars(0, 2)
		setPlainClipboardText("XY")
		press(Key.V, ctrl = true)

		assertEquals("XYem", text)
		val bullets = state.richSpanManager.getAllRichSpans().filter { it.style === BulletListSpanStyle }
		assertEquals(1, bullets.size, "the line must still be a bullet item")
		assertEquals(
			0,
			bullets.single().range.start.char,
			"the line-anchored marker must stay stuck to column 0",
		)
		assertRichSpanInvariants()
	}

	@Test
	fun `multi line paste over a multi line selection rebases the surviving halves`() = editorUiTest {
		markdown.importMarkdown("> alpha\nbeta\n- gamma")

		selectChars(2, 13)
		setPlainClipboardText("XX\nYY")
		press(Key.V, ctrl = true)

		assertEquals(listOf("alXX", "YYmma"), lines)
		assertEquals(setOf("quote"), blockFlags(0), "the quote's surviving half keeps its style")
		assertEquals(setOf("bullet"), blockFlags(1), "the bullet's surviving half keeps its style")
		assertRichSpanInvariants()
	}

	@Test
	fun `undo then redo of a multi line paste is idempotent`() = editorUiTest {
		markdown.importMarkdown("> alpha\nbeta\n- gamma")

		selectChars(2, 13)
		setPlainClipboardText("XX\nYY")
		press(Key.V, ctrl = true)

		val textAfter = text
		val flagsAfter = lines.indices.map { blockFlags(it) }

		press(Key.Z, ctrl = true)
		press(Key.Y, ctrl = true)

		assertEquals(textAfter, text)
		assertEquals(flagsAfter, lines.indices.map { blockFlags(it) })
		assertRichSpanInvariants()
	}

	@Test
	fun `a foreign paste matching the copy buffer must not resurrect spans`() = editorUiTest {
		markdown.importMarkdown("- item\nplain")

		selectChars(0, 4)
		press(Key.C, ctrl = true)
		press(Key.MoveEnd, ctrl = true)
		setPlainClipboardText("item")
		press(Key.V, ctrl = true)

		assertEquals(listOf("item", "plainitem"), lines)
		// Foreign clipboard content carries no copy id, so the span buffer must not
		// apply no matter how exactly the text matches.
		assertEquals(
			setOf<String>(),
			blockFlags(1),
			"plain text from another application must never arrive styled",
		)
		assertRichSpanInvariants()
	}

	@OptIn(ExperimentalComposeUiApi::class)
	@Test
	fun `a copy from another editor instance must not resurrect this buffer`() = editorUiTest {
		markdown.importMarkdown("- item\nplain")

		selectChars(0, 4)
		press(Key.C, ctrl = true)
		clipboard.seed(
			ClipEntry(
				AnnotatedStringTransferable(
					AnnotatedString("item"),
					state.markdownConfiguration,
					copyId = 999_999L,
				)
			)
		)
		press(Key.MoveEnd, ctrl = true)
		press(Key.V, ctrl = true)

		assertEquals(listOf("item", "plainitem"), lines)
		// The other instance's copy id does not match this buffer's, so its text
		// pastes rich via its own flavors but this editor's stale spans stay dead.
		assertEquals(setOf<String>(), blockFlags(1))
		assertRichSpanInvariants()
	}
}
