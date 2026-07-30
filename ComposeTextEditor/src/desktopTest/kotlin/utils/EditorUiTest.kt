package utils

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState

/**
 * Harness for end-to-end editor tests: composes a real [BasicTextEditor],
 * drives it with synthetic keyboard/mouse events, and exposes [state] for
 * data-level assertions. Character-index-based helpers ([clickAtCharacter],
 * [dragSelect]) resolve pixel positions through the editor's own layout, so
 * tests never hard-code coordinates.
 */
@OptIn(ExperimentalTestApi::class)
fun editorUiTest(
	initialText: AnnotatedString = AnnotatedString(""),
	width: Dp = 400.dp,
	height: Dp = 300.dp,
	block: EditorUiTestScope.() -> Unit,
) = runSkikoComposeUiTest {
	lateinit var state: TextEditorState
	setContent {
		state = rememberTextEditorState(initialText = initialText)
		BasicTextEditor(
			state = state,
			modifier = Modifier.size(width, height),
			autoFocus = true,
		)
	}
	waitForIdle()
	EditorUiTestScope(this, state).block()
}

@OptIn(ExperimentalTestApi::class)
class EditorUiTestScope(
	val test: SkikoComposeUiTest,
	val state: TextEditorState,
) {
	/** Plain text of the whole document. */
	val text: String get() = state.getAllText().text

	/** Plain text of the current selection, or empty when there is none. */
	val selectedText: String get() = state.selector.getSelectedText().text

	/** Types printable characters through real desktop key events. */
	fun typeText(text: String) = test.typeText(text)

	/** Presses [key] with optional modifiers held, e.g. `press(Key.Z, ctrl = true)`. */
	fun press(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) {
		test.onRoot().performKeyInput {
			if (ctrl) keyDown(Key.CtrlLeft)
			if (shift) keyDown(Key.ShiftLeft)
			if (alt) keyDown(Key.AltLeft)
			pressKey(key)
			if (alt) keyUp(Key.AltLeft)
			if (shift) keyUp(Key.ShiftLeft)
			if (ctrl) keyUp(Key.CtrlLeft)
		}
		test.waitForIdle()
	}

	/** Left-clicks the character at flat index [charIndex]. */
	fun clickAtCharacter(charIndex: Int, shift: Boolean = false) {
		if (shift) test.onRoot().performKeyInput { keyDown(Key.ShiftLeft) }
		test.onRoot().performMouseInput { click(positionOfCharacter(charIndex)) }
		if (shift) test.onRoot().performKeyInput { keyUp(Key.ShiftLeft) }
		test.waitForIdle()
	}

	/** Double-clicks the character at flat index [charIndex] (word select). */
	fun doubleClickAtCharacter(charIndex: Int) {
		test.onRoot().performMouseInput { doubleClick(positionOfCharacter(charIndex)) }
		test.waitForIdle()
	}

	/** Presses at [fromChar], drags to [toChar], and releases. */
	fun dragSelect(fromChar: Int, toChar: Int) {
		val from = positionOfCharacter(fromChar)
		val to = positionOfCharacter(toChar)
		test.onRoot().performMouseInput {
			moveTo(from)
			press()
			moveTo(to)
			release()
		}
		test.waitForIdle()
	}

	/** Pixel position of the character at flat index [charIndex], vertically centered on its line. */
	fun positionOfCharacter(charIndex: Int): Offset {
		val metrics = state.getPositionForOffset(state.getOffsetAtCharacter(charIndex))
		return Offset(metrics.position.x, metrics.position.y + metrics.height / 2f)
	}

	/** All [SpanStyle]s covering the character at flat index [charIndex]. */
	fun stylesAt(charIndex: Int): List<SpanStyle> =
		state.getAllText().spanStyles
			.filter { charIndex >= it.start && charIndex < it.end }
			.map { it.item }

	fun waitForIdle() = test.waitForIdle()
}
