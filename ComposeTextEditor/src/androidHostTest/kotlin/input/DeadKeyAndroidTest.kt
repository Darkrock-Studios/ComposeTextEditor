package input

import android.view.KeyCharacterMap
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.TextEditorKeyCommandHandler
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.TestScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent

/**
 * A hardware dead key as Android delivers it reaches the composer: `getUnicodeChar`
 * carries [KeyCharacterMap.COMBINING_ACCENT], and the pair composes through
 * [KeyCharacterMap.getDeadChar]. The composing rules are in the desktop
 * suite's `DeadKeyTest`.
 */
class DeadKeyAndroidTest {

	private val state = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(""),
	)
	private val handler = TextEditorKeyCommandHandler(CtrlKeyBindings)

	private fun keyDown(unicodeChar: Int): ComposeKeyEvent {
		val native = mockk<AndroidKeyEvent>(relaxed = true)
		every { native.action } returns AndroidKeyEvent.ACTION_DOWN
		every { native.unicodeChar } returns unicodeChar
		return ComposeKeyEvent(native)
	}

	@AfterTest
	fun tearDown() = unmockkStatic(KeyCharacterMap::class)

	@Test
	fun `a dead key composes with the next key through the key character map`() {
		mockkStatic(KeyCharacterMap::class)
		every { KeyCharacterMap.getDeadChar(0xB4, 'e'.code) } returns 'é'.code

		assertTrue(handler.handleCharacterInput(keyDown(0xB4 or KeyCharacterMap.COMBINING_ACCENT), state))
		assertEquals("´", state.getAllText().text)

		assertTrue(handler.handleCharacterInput(keyDown('e'.code), state))
		assertEquals("é", state.getAllText().text)
		assertNull(state.composingRange)
	}
}
