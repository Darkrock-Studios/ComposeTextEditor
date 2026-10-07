package e2e

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import utils.ForeignHtmlTransferable
import utils.ForeignRichTransferable
import utils.editorUiTest
import utils.linesWith
import utils.richSpansIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)

/**
 * Paste as plain text (hammer-editor#929): Ctrl+Shift+V, Cmd+Shift+V, and the Cocoa
 * Paste and Match Style chord Cmd+Option+Shift+V. The clipboard's text lands carrying
 * the styling at the destination, as typed text would.
 */
class PlainPasteE2eTest {

	private fun boldHello() = buildAnnotatedString {
		append("Hello world")
		addStyle(BOLD, 0, 5)
	}

	@Test
	fun `ctrl+shift+v drops the copied formatting`() = editorUiTest(initialText = boldHello()) {
		dragSelect(fromChar = 0, toChar = 5)
		press(Key.C, ctrl = true)

		press(Key.MoveEnd, ctrl = true)
		press(Key.V, ctrl = true, shift = true)

		assertEquals("Hello worldHello", text)
		assertFalse(stylesAt(13).contains(BOLD), "plain paste must not carry bold, got ${stylesAt(13)}")
	}

	@Test
	fun `ctrl+v still pastes rich`() = editorUiTest(initialText = boldHello()) {
		dragSelect(fromChar = 0, toChar = 5)
		press(Key.C, ctrl = true)

		press(Key.MoveEnd, ctrl = true)
		press(Key.V, ctrl = true)

		assertTrue(stylesAt(13).contains(BOLD))
	}

	@Test
	fun `plain paste takes the styling of its destination`() = editorUiTest(initialText = boldHello()) {
		setPlainClipboardText("xy")
		clickAtCharacter(2)
		press(Key.V, ctrl = true, shift = true)

		assertEquals("Hexyllo world", text)
		assertTrue(stylesAt(3).contains(BOLD), "text pasted inside bold reads as bold, got ${stylesAt(3)}")
	}

	@Test
	fun `plain paste drops copied rich spans`() = editorUiTest(initialText = AnnotatedString("Hello world")) {
		state.addRichSpan(0, 5, HighlightSpanStyle(Color.Yellow))
		dragSelect(fromChar = 0, toChar = 5)
		press(Key.C, ctrl = true)

		press(Key.MoveEnd, ctrl = true)
		press(Key.V, ctrl = true, shift = true)

		assertEquals("Hello worldHello", text)
		assertEquals(emptySet(), richSpansIn(11, 16))
	}

	@OptIn(ExperimentalComposeUiApi::class)
	@Test
	fun `plain paste of foreign html drops its blocks and styling`() = editorUiTest {
		clipboard.seed(ClipEntry(ForeignHtmlTransferable("<ul><li><b>one</b></li><li>two</li></ul>")))
		press(Key.V, ctrl = true, shift = true)

		assertEquals("one\ntwo", text)
		assertEquals(emptyList(), state.linesWith(BulletListSpanStyle))
		assertEquals(emptyList(), stylesAt(0))
	}

	@OptIn(ExperimentalComposeUiApi::class)
	@Test
	fun `plain paste takes the source's own plain text over its markup`() = editorUiTest {
		clipboard.seed(
			ClipEntry(ForeignRichTransferable(html = "<table><tr><td><b>a</b></td><td>b</td></tr></table>", plain = "a | b")),
		)
		press(Key.V, ctrl = true, shift = true)
		assertEquals("a | b", text)
		assertEquals(emptyList(), stylesAt(0))

		press(Key.V, ctrl = true)
		assertEquals("a | ba\nb", text, "a rich paste still reads the markup, a cell a line")
	}

	@Test
	fun `plain paste over a selection is one undo step`() = editorUiTest(
		initialText = AnnotatedString("The quick brown fox"),
	) {
		setPlainClipboardText("slow")
		dragSelect(fromChar = 4, toChar = 9)
		press(Key.V, ctrl = true, shift = true)
		assertEquals("The slow brown fox", text)

		press(Key.Z, ctrl = true)
		assertEquals("The quick brown fox", text)
	}

	@Test
	fun `a read only editor refuses plain paste`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
		enabled = false,
	) {
		setPlainClipboardText("xy")
		press(Key.V, ctrl = true, shift = true)

		assertEquals("Hello", text)
	}

	@Test
	fun `cmd+shift+v and cmd+option+shift+v paste plain on macos`() {
		for (alt in listOf(false, true)) {
			editorUiTest(initialText = boldHello(), keyBindings = MacKeyBindings) {
				dragSelect(fromChar = 0, toChar = 5)
				press(Key.C, meta = true)

				press(Key.DirectionDown, meta = true)
				press(Key.V, meta = true, shift = true, alt = alt)

				assertEquals("Hello worldHello", text, "alt = $alt")
				assertFalse(stylesAt(13).contains(BOLD), "alt = $alt: got ${stylesAt(13)}")
			}
		}
	}
}
