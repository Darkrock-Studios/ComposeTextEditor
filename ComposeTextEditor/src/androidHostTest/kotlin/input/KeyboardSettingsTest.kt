package input

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.darkrockstudios.texteditor.input.ImeCursorSync
import com.darkrockstudios.texteditor.input.ImeUpdateSink
import com.darkrockstudios.texteditor.input.KeyboardSettings
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.input.androidImeOptions
import com.darkrockstudios.texteditor.input.androidInputType
import com.darkrockstudios.texteditor.state.CursorAnchor
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a host's [KeyboardSettings] ask of an Android keyboard (roadmap 3.11). */
class KeyboardSettingsTest {

	private val state = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString("hello"),
	)

	private fun Int.has(flag: Int) = this and flag == flag

	@Test
	fun `the defaults ask for multi-line prose`() {
		val settings = KeyboardSettings()

		assertEquals(
			InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
					InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT,
			settings.androidInputType(),
		)
		assertEquals(
			EditorInfo.IME_ACTION_UNSPECIFIED or EditorInfo.IME_FLAG_NO_ENTER_ACTION or
					EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI,
			settings.androidImeOptions(),
		)
	}

	@Test
	fun `an editor for code turns capitals and autocorrect off`() {
		val type = KeyboardSettings(capitalization = KeyboardCapitalization.None, autoCorrect = false)
			.androidInputType()

		assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, type)
	}

	@Test
	fun `each layout and capitalisation maps to its input type`() {
		assertTrue(KeyboardSettings(keyboardType = KeyboardType.Email).androidInputType()
			.has(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
		assertTrue(KeyboardSettings(keyboardType = KeyboardType.Uri).androidInputType()
			.has(InputType.TYPE_TEXT_VARIATION_URI))
		assertTrue(KeyboardSettings(capitalization = KeyboardCapitalization.Words).androidInputType()
			.has(InputType.TYPE_TEXT_FLAG_CAP_WORDS))
		assertTrue(KeyboardSettings(keyboardType = KeyboardType.Ascii).androidImeOptions()
			.has(EditorInfo.IME_FLAG_FORCE_ASCII))
		// Only the text class takes text flags; phone's class shares a bit with text's.
		assertEquals(InputType.TYPE_CLASS_PHONE, KeyboardSettings(keyboardType = KeyboardType.Phone).androidInputType())
		assertEquals(
			InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
			KeyboardSettings(keyboardType = KeyboardType.Decimal).androidInputType(),
		)
	}

	@Test
	fun `an action replaces Enter`() {
		val options = KeyboardSettings(imeAction = ImeAction.Send).androidImeOptions()

		assertEquals(EditorInfo.IME_ACTION_SEND, options and EditorInfo.IME_MASK_ACTION)
		assertEquals(0, options and EditorInfo.IME_FLAG_NO_ENTER_ACTION)
	}

	@Test
	fun `the action key calls the host, and Enter still types a line`() {
		val sent = mutableListOf<ImeAction>()
		state.keyboardSettings = KeyboardSettings(imeAction = ImeAction.Send)
		state.onImeAction = { sent += it }
		val connection = TextEditorInputConnection(state, mockk<View>(relaxed = true))

		assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_SEND))
		assertEquals(listOf(ImeAction.Send), sent)
		assertEquals("hello", state.getAllText().text)

		connection.performEditorAction(EditorInfo.IME_ACTION_DONE)
		assertEquals(1, sent.size, "an action the settings did not ask for is not the host's")

		connection.performEditorAction(EditorInfo.IME_ACTION_UNSPECIFIED)
		assertEquals(2, state.textLines.size)
	}

	@Test
	fun `without a host handler the action key takes the editor's default`() {
		val defaulted = mutableListOf<ImeAction>()
		state.defaultImeAction = { defaulted += it }
		state.keyboardSettings = KeyboardSettings(imeAction = ImeAction.Next)
		val connection = TextEditorInputConnection(state, mockk<View>(relaxed = true))

		connection.performEditorAction(EditorInfo.IME_ACTION_NEXT)

		assertEquals(listOf(ImeAction.Next), defaulted)
	}

	@Test
	fun `a connection answers the action it was opened with`() {
		val sent = mutableListOf<ImeAction>()
		state.onImeAction = { sent += it }
		state.keyboardSettings = KeyboardSettings(imeAction = ImeAction.Send)
		val connection = TextEditorInputConnection(state, mockk<View>(relaxed = true))

		// Changed before the restart reaches the keyboard, which still shows Send.
		state.keyboardSettings = KeyboardSettings(imeAction = ImeAction.Go)
		connection.performEditorAction(EditorInfo.IME_ACTION_SEND)

		assertEquals(listOf(ImeAction.Send), sent)
	}

	/** Roadmap 7.40: a single line has no Enter to offer, so its default key is Done. */
	@Test
	fun `a single line asks for single-line text and Done`() {
		val settings = KeyboardSettings()

		assertEquals(
			InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT,
			settings.androidInputType(singleLine = true),
		)
		assertEquals(
			EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI,
			settings.androidImeOptions(singleLine = true),
		)
		val send = KeyboardSettings(imeAction = ImeAction.Send).androidImeOptions(singleLine = true)
		assertEquals(EditorInfo.IME_ACTION_SEND, send and EditorInfo.IME_MASK_ACTION, "a chosen action stays")
	}

	@Test
	fun `a single line's Done key calls the host`() {
		val sent = mutableListOf<ImeAction>()
		state.singleLineEditors = 1
		state.onImeAction = { sent += it }
		val connection = TextEditorInputConnection(state, mockk<View>(relaxed = true))

		connection.performEditorAction(EditorInfo.IME_ACTION_DONE)

		assertEquals(listOf(ImeAction.Done), sent)
	}

	@Test
	fun `a single line's limit coming or going restarts input`() {
		val events = mutableListOf<String>()
		val sync = ImeCursorSync(state, restartRecorder(events)) {}
		sync.attach()
		sync.flush()

		state.singleLineEditors = 1
		sync.flush()
		state.singleLineEditors = 0
		sync.flush()

		assertEquals(listOf("restart", "restart"), events)
	}

	private fun restartRecorder(events: MutableList<String>) = object : ImeUpdateSink {
		override val isReady = true
		override fun restartInput() {
			events += "restart"
		}

		override fun updateSelection(selStart: Int, selEnd: Int, compStart: Int, compEnd: Int) = Unit
		override fun updateExtractedText(token: Int) = Unit
		override fun sendCursorAnchorInfo(anchor: CursorAnchor) = Unit
	}

	@Test
	fun `new settings restart input, equal ones do not`() {
		val events = mutableListOf<String>()
		val sync = ImeCursorSync(state, restartRecorder(events)) {}
		sync.attach()
		sync.flush()

		state.keyboardSettings = KeyboardSettings()
		sync.flush()
		assertEquals(emptyList(), events)

		state.keyboardSettings = state.keyboardSettings.copy(autoCorrect = false)
		sync.flush()
		assertEquals(listOf("restart"), events)
	}
}
