package input

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.TextEditorKeyCommandHandler
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Windows synthesizes AltGr as left-Ctrl + right-Alt, so layouts that reach common
 * characters through AltGr (Hungarian AltGr+V is `@`, Polish AltGr+Z is `ż`) must not
 * trip the Ctrl shortcuts. Whether AWT reports the chord as Alt or as AltGraph varies
 * by JDK; Compose folds both into `isAltPressed`, which is what these chords set.
 */
@OptIn(InternalComposeUiApi::class)
class AltGrChordTest {

	private lateinit var state: TextEditorState
	private lateinit var handler: TextEditorKeyCommandHandler
	private lateinit var clipboard: Clipboard
	private lateinit var scope: TestScope

	@BeforeTest
	fun setup() {
		scope = TestScope()
		state = TextEditorState(
			scope = scope,
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(""),
		)
		handler = TextEditorKeyCommandHandler(CtrlKeyBindings)
		clipboard = mockk(relaxed = true)
	}

	private fun chord(key: Key, ctrl: Boolean = false, alt: Boolean = false) = KeyEvent(
		key = key,
		type = KeyEventType.KeyDown,
		isCtrlPressed = ctrl,
		isAltPressed = alt,
	)

	/** The composed character, delivered as its own event after the chord. */
	private fun typed(char: Char, ctrl: Boolean = false, alt: Boolean = false) = KeyEvent(
		key = Key.Unknown,
		type = KeyEventType.Unknown,
		codePoint = char.code,
		isCtrlPressed = ctrl,
		isAltPressed = alt,
	)

	private fun handleKey(event: KeyEvent) = handler.handleKeyEvent(event, state, clipboard, scope)

	private fun selectAll() {
		state.selector.updateSelection(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, state.textLines[0].length),
		)
	}

	@Test
	fun `AltGr+X does not cut`() {
		state.setText("hello")
		selectAll()

		assertFalse(handleKey(chord(Key.X, ctrl = true, alt = true)))

		assertEquals("hello", state.getAllText().text, "The selection must survive an AltGr chord")
		assertNotNull(state.selector.selection)
	}

	@Test
	fun `AltGr+V does not paste`() {
		state.setText("hello")

		assertFalse(handleKey(chord(Key.V, ctrl = true, alt = true)))
	}

	@Test
	fun `AltGr+C does not copy`() {
		state.setText("hello")
		selectAll()

		assertFalse(handleKey(chord(Key.C, ctrl = true, alt = true)))
	}

	@Test
	fun `AltGr+Z does not undo`() {
		state.setText("hello")
		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertStringAtCursor(" world")

		assertFalse(handleKey(chord(Key.Z, ctrl = true, alt = true)))

		assertEquals("hello world", state.getAllText().text, "The last edit must not be reverted")
	}

	@Test
	fun `AltGr+A does not select all`() {
		state.setText("hello")

		assertFalse(handleKey(chord(Key.A, ctrl = true, alt = true)))

		assertNull(state.selector.selection)
	}

	@Test
	fun `AltGr+Y does not redo`() {
		state.setText("hello")
		state.insertStringAtCursor(" world")
		state.undo()

		assertFalse(handleKey(chord(Key.Y, ctrl = true, alt = true)))

		assertEquals("hello", state.getAllText().text)
	}

	@Test
	fun `AltGr composed character is typed`() {
		state.setText("mail")
		state.cursor.updatePosition(CharLineOffset(0, 4))

		assertTrue(handler.handleCharacterInput(typed('@', ctrl = true, alt = true), state))

		assertEquals("mail@", state.getAllText().text)
	}

	@Test
	fun `Ctrl+A still selects all`() {
		state.setText("hello")

		assertTrue(handleKey(chord(Key.A, ctrl = true)))

		assertNotNull(state.selector.selection)
	}

	@Test
	fun `Ctrl+Z still undoes`() {
		state.setText("hello")
		state.insertStringAtCursor(" world")

		assertTrue(handleKey(chord(Key.Z, ctrl = true)))

		assertEquals("hello", state.getAllText().text)
	}

	@Test
	fun `Ctrl+X is still cut`() {
		state.setText("hello")
		selectAll()

		assertTrue(handleKey(chord(Key.X, ctrl = true)))

		assertEquals("", state.getAllText().text)
	}

	@Test
	fun `Ctrl chord does not insert a literal character`() {
		state.setText("hello")

		assertFalse(handler.handleCharacterInput(typed(0x18.toChar(), ctrl = true), state))

		assertEquals("hello", state.getAllText().text)
	}
}
