package utils

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.LocalKeyBindings
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.rememberTextEditorStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState

/**
 * A composed editor with [markdown] installed, on core's public API: real key events,
 * an in-memory clipboard and the Ctrl bindings, laid out in the test font. Core's
 * `editorUiTest` is the full harness; this one drives what the markdown suite needs.
 */
@OptIn(ExperimentalTestApi::class)
internal fun markdownUiTest(block: MarkdownUiTestScope.() -> Unit) = runSkikoComposeUiTest {
	val clipboard = InMemoryClipboard()
	lateinit var state: TextEditorState
	setContent {
		state = rememberTextEditorState()
		CompositionLocalProvider(
			LocalClipboard provides clipboard,
			LocalKeyBindings provides CtrlKeyBindings,
		) {
			BasicTextEditor(
				state = state,
				modifier = Modifier.size(400.dp, 300.dp).testTag(EDITOR_TEST_TAG),
				autoFocus = true,
				style = rememberTextEditorStyle(textStyle = TextStyle.Default.withTestFont()),
				keyBindings = CtrlKeyBindings,
			)
		}
	}
	waitForIdle()
	// Replayed keys go to the focused node, so wait for the focus request to land.
	waitUntil(timeoutMillis = 5_000) { state.hasFocus }
	MarkdownUiTestScope(this, MarkdownExtension(state), clipboard).block()
}

@OptIn(ExperimentalTestApi::class)
internal class MarkdownUiTestScope(
	private val test: SkikoComposeUiTest,
	val markdown: MarkdownExtension,
	private val clipboard: InMemoryClipboard,
) : FuzzUiDriver {
	override val state: TextEditorState get() = markdown.editorState

	override fun typeText(text: String) = test.typeText(text)

	override fun sendKey(key: Key, ctrl: Boolean) {
		test.onRoot().performKeyInput {
			if (ctrl) keyDown(Key.CtrlLeft)
			pressKey(key)
			if (ctrl) keyUp(Key.CtrlLeft)
		}
		test.waitForIdle()
	}

	override fun setPlainClipboardText(value: String) {
		clipboard.setPlainText(value)
		test.waitForIdle()
	}

	override fun waitForIdle() = test.waitForIdle()
}
