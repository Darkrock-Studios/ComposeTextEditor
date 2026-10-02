package com.darkrockstudios.texteditor.sample

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test on a real Android device or emulator: typing reaches the editor
 * through the platform's key event dispatch, and an input method's edits through the
 * editor's own InputConnection. The emulator CI job runs it.
 */
@RunWith(AndroidJUnit4::class)
class EditorTypingSmokeTest {
	@get:Rule
	val compose = createAndroidComposeRule<ComponentActivity>()

	private lateinit var state: TextEditorState

	private fun composeEditor() {
		compose.setContent {
			state = rememberTextEditorState()
			BasicTextEditor(state = state, modifier = Modifier.fillMaxSize(), autoFocus = true)
		}
		compose.waitUntil(timeoutMillis = 10_000) { state.hasFocus }
		// Injected key events go to the focused window; the activity can still be
		// taking focus from the launcher.
		compose.waitUntil(timeoutMillis = 10_000) { compose.activity.window.decorView.hasWindowFocus() }
	}

	@Test
	fun keyEventsTypeIntoTheEditor() {
		composeEditor()
		val before = awaitKeysReachTheEditor()

		// Key events injected into the window, as a hardware keyboard sends them.
		InstrumentationRegistry.getInstrumentation().sendStringSync("Hello")

		assertTextBecomes(before + "Hello")
	}

	/**
	 * Presses X until one arrives, then returns the text once it stops changing. An input
	 * method (Gboard on the emulator) turns hardware keys into commits on its connection,
	 * and until it has started input on this window it commits them to the previous
	 * test's activity, which drops them; the client reports the connection active before
	 * that, and a cold emulator can take seconds. A press still on its way can land
	 * after the first arrives, hence the wait for a steady text.
	 */
	private fun awaitKeysReachTheEditor(): String {
		val deadline = SystemClock.uptimeMillis() + 20_000
		fun text() = compose.runOnIdle { state.getAllText().text }
		while (text().isEmpty()) {
			check(SystemClock.uptimeMillis() < deadline) { "no key reached the editor in 20 s" }
			InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_X)
			SystemClock.sleep(250)
		}
		var previous = text()
		while (true) {
			SystemClock.sleep(500)
			val current = text()
			if (current == previous) return current
			previous = current
		}
	}

	@Test
	fun anInputMethodComposesAndCommitsThroughTheInputConnection() {
		composeEditor()
		val connection = focusedInputConnection()

		compose.runOnUiThread {
			connection.setComposingText("wor", 1)
			connection.setComposingText("world", 1)
			connection.finishComposingText()
			connection.commitText("!", 1)
		}

		assertTextBecomes("world!")
	}

	/**
	 * Waits for the document to read [expected], then asserts it. Injected keys pass
	 * through the input method before the view, so the last can land after the
	 * injection call returns.
	 */
	private fun assertTextBecomes(expected: String) {
		try {
			compose.waitUntil(timeoutMillis = 5_000) { state.getAllText().text == expected }
		} catch (_: ComposeTimeoutException) {
			// The assertion below says what the text is instead.
		}
		assertEquals(expected, state.getAllText().text)
	}

	/** The connection an input method would get from the focused view: the editor's. */
	private fun focusedInputConnection(): InputConnection = compose.runOnUiThread {
		val focused: View = checkNotNull(compose.activity.window.decorView.findFocus()) { "nothing has focus" }
		checkNotNull(focused.onCreateInputConnection(EditorInfo())) { "the focused view has no input connection" }
	}
}
