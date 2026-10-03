package input

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.COMBINING_ACCENT
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.DeadKeyComposer
import com.darkrockstudios.texteditor.input.TextEditorKeyCommandHandler
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hardware keyboard dead keys as Android delivers them: the accent key's character
 * carries `KeyCharacterMap.COMBINING_ACCENT`, and the editor composes it with the next
 * character. The table stands in for `KeyCharacterMap.getDeadChar`.
 */
@OptIn(InternalComposeUiApi::class)
class DeadKeyTest {

	private val acute = 0x00B4
	private val grave = 0x0060

	private val table = mapOf(
		acute to mapOf('e'.code to 'é'.code, 'a'.code to 'á'.code, 'E'.code to 'É'.code),
		grave to mapOf('e'.code to 'è'.code),
	)

	/** `KeyCharacterMap.getDeadChar`'s contract over [table]. */
	private fun deadChar(accent: Int, codePoint: Int): Int = when (codePoint) {
		accent, ' '.code -> accent
		else -> table[accent]?.get(codePoint) ?: 0
	}

	private val scope = TestScope()
	private val state = TextEditorState(
		scope = scope,
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(""),
	)
	private val handler = TextEditorKeyCommandHandler(CtrlKeyBindings, DeadKeyComposer(::deadChar))

	private fun typed(codePoint: Int) = KeyEvent(
		key = Key.Unknown,
		type = KeyEventType.Unknown,
		codePoint = codePoint,
	)

	private fun type(char: Char) = assertTrue(handler.handleCharacterInput(typed(char.code), state))
	private fun dead(accent: Int) = assertTrue(handler.handleCharacterInput(typed(accent or COMBINING_ACCENT), state))
	private fun press(key: Key) = handler.handleKeyEvent(KeyEvent(key, KeyEventType.KeyDown), state, mockk(relaxed = true), scope)

	private val text get() = state.getAllText().text
	private fun range(start: Int, end: Int) = TextEditorRange(CharLineOffset(0, start), CharLineOffset(0, end))

	@Test
	fun `a dead key shows its accent as a composition`() {
		dead(acute)

		assertEquals("´", text)
		assertEquals(range(0, 1), state.composingRange)
		assertEquals(CharLineOffset(0, 1), state.cursorPosition)
	}

	@Test
	fun `the next character composes with the accent`() {
		type('x')
		dead(acute)
		type('e')

		assertEquals("xé", text)
		assertNull(state.composingRange)
		assertEquals(CharLineOffset(0, 2), state.cursorPosition)
	}

	@Test
	fun `a composed character undoes with the typing around it`() {
		type('c')
		type('a')
		type('f')
		dead(acute)
		type('e')

		state.undo()

		assertEquals("", text)
	}

	@Test
	fun `a character that does not compose follows the accent`() {
		dead(acute)
		type('x')

		assertEquals("´x", text)
		assertNull(state.composingRange)
	}

	@Test
	fun `the accent twice or before a space types the accent`() {
		dead(acute)
		dead(acute)
		type(' ')
		dead(grave)
		type(' ')

		assertEquals("´ `", text)
		assertNull(state.composingRange)
	}

	@Test
	fun `a second accent that does not compose commits the first and waits`() {
		dead(acute)
		dead(grave)

		assertEquals("´`", text)
		assertEquals(range(1, 2), state.composingRange)

		type('e')

		assertEquals("´è", text)
	}

	@Test
	fun `a composed capital`() {
		dead(acute)
		type('E')

		assertEquals("É", text)
	}

	@Test
	fun `an arrow key commits the accent before it moves`() {
		dead(acute)

		assertTrue(press(Key.DirectionLeft))

		assertNull(state.composingRange)
		type('e')

		assertEquals("e´", text)
		assertNull(state.composingRange)
	}

	@Test
	fun `Backspace takes the pending accent away`() {
		type('a')
		dead(acute)

		assertTrue(press(Key.Backspace))
		type('e')

		assertEquals("ae", text)
	}

	@Test
	fun `a caret moved off the accent leaves it and types plainly`() {
		type('a')
		dead(acute)
		state.cursor.updatePosition(CharLineOffset(0, 0))

		type('e')

		assertEquals("ea´", text)
		assertNull(state.composingRange)
	}

	@Test
	fun `a dead key types over the selection`() {
		state.setText("word")
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 4))

		dead(acute)
		type('a')

		assertEquals("á", text)
	}

	@Test
	fun `typed text offered is the composed character, or the accent when nothing composes`() {
		val offered = mutableListOf<String>()
		state.editBehaviors += object : EditBehavior {
			override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
				offered += text
				return false
			}
		}

		dead(acute)
		type('e')
		dead(acute)
		type('x')

		assertEquals(listOf("é", "´", "x"), offered)
	}

	@Test
	fun `a key with no character commits the accent`() {
		dead(acute)

		press(Key.Escape)

		assertEquals("´", text)
		assertNull(state.composingRange)
	}

	@Test
	fun `a flagged value that is no accent is refused`() {
		assertFalse(handler.handleCharacterInput(typed(-1), state))
		assertFalse(handler.handleCharacterInput(typed(COMBINING_ACCENT), state))

		assertEquals("", text)
		assertNull(state.composingRange)
	}

	@Test
	fun `an accent pending on another state is not composed here`() {
		dead(acute)
		val other = TextEditorState(scope = scope, measurer = mockk(relaxed = true), initialText = AnnotatedString(""))

		assertTrue(handler.handleCharacterInput(typed('e'.code), other))

		assertEquals("e", other.getAllText().text)
	}

	@Test
	fun `a lock or modifier key between leaves the accent pending`() {
		dead(acute)

		press(Key.CapsLock)
		press(Key.ShiftLeft)
		type('E')

		assertEquals("É", text)
	}
}
