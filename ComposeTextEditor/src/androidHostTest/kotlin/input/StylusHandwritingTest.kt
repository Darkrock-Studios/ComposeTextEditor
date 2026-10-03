package input

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import com.darkrockstudios.texteditor.input.KeyboardSettings
import com.darkrockstudios.texteditor.input.allowsHandwriting
import com.darkrockstudios.texteditor.input.stylusHandwritingSupported
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When a stylus stroke is handed to the keyboard to write. */
class StylusHandwritingTest {

	private val state = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString("hello"),
	)

	@Test
	fun `handwriting starts from Android 14, as in Compose`() {
		assertFalse(stylusHandwritingSupported(33))
		assertTrue(stylusHandwritingSupported(34))
	}

	@Test
	fun `a password is never written by hand`() {
		for (type in listOf(KeyboardType.Password, KeyboardType.NumberPassword, KeyboardType.PasswordVisible)) {
			assertFalse(KeyboardSettings(keyboardType = type).allowsHandwriting(), "$type")
		}
		assertTrue(KeyboardSettings().allowsHandwriting())
		assertTrue(KeyboardSettings(keyboardType = KeyboardType.Email).allowsHandwriting())
	}

	@Test
	fun `a stroke before the session starts is handed to it`() = runTest {
		state.platformExtensions.requestStylusHandwriting()

		assertNotNull(withTimeoutOrNull(1_000) { state.platformExtensions.stylusHandwriting.first() })
	}

	@Test
	fun `a stroke a session took is not replayed to the next`() = runTest {
		state.platformExtensions.requestStylusHandwriting()
		state.platformExtensions.forgetStylusHandwriting()

		assertNull(withTimeoutOrNull(1_000) { state.platformExtensions.stylusHandwriting.first() })
	}
}
