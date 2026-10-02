package e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.MacKeyBindings
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cocoa's kill buffer: Ctrl+K, Cmd+Backspace and Cmd+Fn+Delete keep what they delete,
 * a run of them with nothing between builds one kill, and Ctrl+Y yanks it back. It is
 * the editor's own, never the clipboard.
 */
class MacKillRingE2eTest {

	@Test
	fun `ctrl+y yanks back what ctrl+k killed`() = editorUiTest(
		initialText = AnnotatedString("hello world"),
		keyBindings = MacKeyBindings,
	) {
		state.cursor.updatePosition(CharLineOffset(0, 6))
		press(Key.K, ctrl = true)
		assertEquals("hello ", text)
		press(Key.Y, ctrl = true)
		assertEquals("hello world", text)
		assertEquals(11, cursorIndex, "the caret ends after the yanked text")
		press(Key.Y, ctrl = true)
		assertEquals("hello worldworld", text, "the kill stays for another yank")
	}

	@Test
	fun `consecutive kills build one kill, line breaks included`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo\nthree"),
		keyBindings = MacKeyBindings,
	) {
		state.cursor.updatePosition(CharLineOffset(0, 0))
		press(Key.K, ctrl = true)
		press(Key.K, ctrl = true)
		press(Key.K, ctrl = true)
		assertEquals("\nthree", text)
		press(Key.DirectionDown, meta = true)
		press(Key.Y, ctrl = true)
		assertEquals("\nthreeone\ntwo", text)
	}

	@Test
	fun `a motion between kills starts a new kill`() = editorUiTest(
		initialText = AnnotatedString("one two"),
		keyBindings = MacKeyBindings,
	) {
		state.cursor.updatePosition(CharLineOffset(0, 4))
		press(Key.K, ctrl = true)
		assertEquals("one ", text)
		press(Key.B, ctrl = true)
		press(Key.K, ctrl = true)
		assertEquals("one", text)
		press(Key.Y, ctrl = true)
		assertEquals("one ", text, "only the latest kill")
	}

	@Test
	fun `any other key command between kills starts a new kill`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
		keyBindings = MacKeyBindings,
	) {
		state.cursor.updatePosition(CharLineOffset(0, 1))
		press(Key.K, ctrl = true)
		press(Key.C, meta = true)
		press(Key.K, ctrl = true)
		assertEquals("otwo", text)
		press(Key.Y, ctrl = true)
		assertEquals("o\ntwo", text, "only the line break, killed after Cmd+C")
	}

	@Test
	fun `a new document leaves nothing to yank`() = editorUiTest(
		initialText = AnnotatedString("secret"),
		keyBindings = MacKeyBindings,
	) {
		state.cursor.updatePosition(CharLineOffset(0, 0))
		press(Key.K, ctrl = true)
		state.setText("other")
		waitForIdle()
		press(Key.Y, ctrl = true)
		assertEquals("other", text)
	}

	@Test
	fun `a kill backward goes in front of the kill before it`() = editorUiTest(
		initialText = AnnotatedString("abcd"),
		keyBindings = MacKeyBindings,
	) {
		state.cursor.updatePosition(CharLineOffset(0, 2))
		press(Key.K, ctrl = true)
		press(Key.Backspace, meta = true)
		assertEquals("", text)
		press(Key.Y, ctrl = true)
		assertEquals("abcd", text)
	}

	@Test
	fun `the kill is not the clipboard`() = editorUiTest(
		initialText = AnnotatedString("keep kill"),
		keyBindings = MacKeyBindings,
	) {
		setPlainClipboardText("clip")
		state.cursor.updatePosition(CharLineOffset(0, 4))
		press(Key.K, ctrl = true)
		press(Key.V, meta = true)
		assertEquals("keepclip", text)
		press(Key.Y, ctrl = true)
		assertEquals("keepclip kill", text)
	}

	@Test
	fun `a yank replaces the selection and undoes in one step`() = editorUiTest(
		initialText = AnnotatedString("alpha beta gamma"),
		keyBindings = MacKeyBindings,
	) {
		state.cursor.updatePosition(CharLineOffset(0, 11))
		press(Key.K, ctrl = true)
		assertEquals("alpha beta ", text)
		dragSelect(0, 5)
		press(Key.Y, ctrl = true)
		assertEquals("gamma beta ", text)
		press(Key.Z, meta = true)
		assertEquals("alpha beta ", text)
	}

	@Test
	fun `a yank keeps the killed styling`() = editorUiTest(
		initialText = buildAnnotatedString {
			append("a ")
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bold") }
		},
		keyBindings = MacKeyBindings,
	) {
		state.cursor.updatePosition(CharLineOffset(0, 2))
		press(Key.K, ctrl = true)
		press(Key.Y, ctrl = true)
		assertEquals("a bold", text)
		assertTrue(stylesAt(3).any { it.fontWeight == FontWeight.Bold })
	}

	@Test
	fun `nothing killed, nothing yanked`() = editorUiTest(
		initialText = AnnotatedString("abc"),
		keyBindings = MacKeyBindings,
	) {
		state.cursor.updatePosition(CharLineOffset(0, 1))
		press(Key.Y, ctrl = true)
		assertEquals("abc", text, "and no literal y")
	}
}
