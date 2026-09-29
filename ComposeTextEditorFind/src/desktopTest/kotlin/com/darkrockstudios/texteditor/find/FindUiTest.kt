package com.darkrockstudios.texteditor.find

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState

internal const val FIND_EDITOR_TAG = "find-editor-under-test"

/**
 * Composes a real editor under a [FindBar] the way a host does: the bar is shown while
 * [FindUiTestScope.barVisible] is set, and [findShortcut] on the editor toggles it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun findUiTest(
	initialText: String,
	barInitiallyVisible: Boolean = true,
	block: FindUiTestScope.() -> Unit,
) = runSkikoComposeUiTest {
	lateinit var textState: TextEditorState
	lateinit var findState: FindState
	val host = FindHost(barInitiallyVisible)
	setContent {
		textState = rememberTextEditorState(AnnotatedString(initialText))
		findState = rememberFindState(textState)
		MaterialTheme {
			Column {
				if (host.barVisible) {
					FindBar(state = findState, onClose = { host.barVisible = false })
				}
				BasicTextEditor(
					state = textState,
					modifier = Modifier
						.size(400.dp, 300.dp)
						.testTag(FIND_EDITOR_TAG)
						.findShortcut { host.barVisible = !host.barVisible },
					autoFocus = !barInitiallyVisible,
				)
			}
		}
	}
	waitForIdle()
	val scope = FindUiTestScope(this, textState, findState, host)
	if (barInitiallyVisible) scope.awaitSearchFieldFocus() else waitUntil(timeoutMillis = 5_000) { textState.isFocused }
	scope.block()
}

internal class FindHost(initiallyVisible: Boolean) {
	var barVisible by mutableStateOf(initiallyVisible)
}

@OptIn(ExperimentalTestApi::class)
internal class FindUiTestScope(
	val test: SkikoComposeUiTest,
	val textState: TextEditorState,
	val findState: FindState,
	private val host: FindHost,
) {
	val barVisible: Boolean get() = host.barVisible

	/** Hides the bar the way a host's own button would, without calling into [FindState]. */
	fun hideBarFromHost() {
		host.barVisible = false
		test.waitForIdle()
	}

	/** How many find highlight spans are on the document. */
	val highlightCount: Int
		get() = textState.richSpanManager.getAllRichSpans()
			.count { it.style is FindMatchStyle || it.style is FindCurrentMatchStyle }

	val selectedText: String get() = textState.selector.getSelectedText().text

	/** The text field that holds focus, the find bar's search field once it opens. */
	val focusedField: SemanticsNodeInteraction
		get() = test.onNode(isFocused() and hasSetTextAction())

	/** Waits for the bar's search field to take focus, which it requests when shown. */
	fun awaitSearchFieldFocus() {
		test.waitUntil(timeoutMillis = 5_000) {
			!textState.isFocused &&
				test.onAllNodes(isFocused() and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
		}
	}

	/** Types [query] into the focused search field. */
	fun typeQuery(query: String) {
		focusedField.performTextInput(query)
		test.waitForIdle()
	}

	/** Presses [key] on the focused node with optional modifiers held. */
	fun press(
		key: Key,
		ctrl: Boolean = false,
		shift: Boolean = false,
		alt: Boolean = false,
		meta: Boolean = false,
	) {
		test.onRoot().performKeyInput {
			if (ctrl) keyDown(Key.CtrlLeft)
			if (shift) keyDown(Key.ShiftLeft)
			if (alt) keyDown(Key.AltLeft)
			if (meta) keyDown(Key.MetaLeft)
			pressKey(key)
			if (meta) keyUp(Key.MetaLeft)
			if (alt) keyUp(Key.AltLeft)
			if (shift) keyUp(Key.ShiftLeft)
			if (ctrl) keyUp(Key.CtrlLeft)
		}
		test.waitForIdle()
	}
}
