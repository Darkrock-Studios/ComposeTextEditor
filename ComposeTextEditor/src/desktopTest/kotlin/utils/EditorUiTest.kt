package utils

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.RichSpanClickListener
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.KeyBindings
import com.darkrockstudios.texteditor.input.LocalKeyBindings
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState

/**
 * Harness for end-to-end editor tests: composes a real [BasicTextEditor],
 * drives it with synthetic keyboard/mouse events, and exposes [state] for
 * data-level assertions. Character-index-based helpers ([clickAtCharacter],
 * [dragSelect]) resolve pixel positions through the editor's own layout, so
 * tests never hard-code coordinates. Clipboard operations go through an
 * isolated [InMemoryClipboard], never the OS clipboard.
 *
 * [keyBindings] is pinned rather than taken from the host, so the same shortcuts
 * are exercised no matter which OS runs the suite; pass [MacKeyBindings] to test
 * the macOS chords.
 */
@OptIn(ExperimentalTestApi::class)
internal fun editorUiTest(
	initialText: AnnotatedString = AnnotatedString(""),
	width: Dp = 400.dp,
	height: Dp = 300.dp,
	enabled: Boolean = true,
	keyBindings: KeyBindings = CtrlKeyBindings,
	onRichSpanClick: RichSpanClickListener? = null,
	autoFocus: Boolean = enabled,
	block: EditorUiTestScope.() -> Unit,
) = runSkikoComposeUiTest {
	val clipboard = InMemoryClipboard()
	lateinit var state: TextEditorState
	setContent {
		state = rememberTextEditorState(initialText = initialText)
		CompositionLocalProvider(
			LocalClipboard provides clipboard,
			LocalKeyBindings provides keyBindings,
		) {
			BasicTextEditor(
				state = state,
				modifier = Modifier.size(width, height),
				enabled = enabled,
				autoFocus = autoFocus,
				onRichSpanClick = onRichSpanClick,
			)
		}
	}
	waitForIdle()
	// Key events are replayed against the scene's focused node; under a loaded
	// JVM the autoFocus request can land after the first replayed keystrokes,
	// which are then silently dropped. Don't hand control to the test until the
	// editor actually holds focus.
	if (enabled && autoFocus) {
		waitUntil(timeoutMillis = 5_000) { state.isFocused }
	}
	EditorUiTestScope(this, state, clipboard).block()
}

@OptIn(ExperimentalTestApi::class)
class EditorUiTestScope(
	val test: SkikoComposeUiTest,
	val state: TextEditorState,
	val clipboard: InMemoryClipboard,
) {
	/**
	 * Markdown extension for this editor, created on first use. Deliberately a
	 * per-scope member: TextEditorState hashes by document content, so caching
	 * extensions in any shared hash-keyed map hands a test another test's editor
	 * whenever two documents happen to hold equal text.
	 */
	val markdown: MarkdownExtension by lazy { state.withMarkdown() }

	/** Plain text of the whole document. */
	val text: String get() = state.getAllText().text

	/** Plain text of each line of the document. */
	val lines: List<String> get() = state.textLines.map { it.text }

	/** Plain text of the current selection, or empty when there is none. */
	val selectedText: String get() = state.selector.getSelectedText().text

	/** The cursor position as a flat character index into [text]. */
	val cursorIndex: Int get() = state.getCharacterIndex(state.cursorPosition)

	/** Types printable characters through real desktop key events; `\n` and `\t` become Enter/Tab. */
	fun typeText(text: String) = test.typeText(text)

	/** Types [char] as a macOS Option chord over [key], the way Option+8 composes '{'. */
	fun typeWithOption(key: Key, char: Char) = test.typeWithOption(key, char)

	/** Presses [key] with optional modifiers held, e.g. `press(Key.Z, ctrl = true)`. */
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

	/** Taps [position] with a finger: down and up in the same place, no buttons. */
	fun tapAt(position: Offset) {
		test.onRoot().performTouchInput {
			down(position)
			up()
		}
		test.waitForIdle()
	}

	/** Taps the character at flat index [charIndex] with a finger. */
	fun tapAtCharacter(charIndex: Int) = tapAt(positionOfCharacter(charIndex))

	/** Holds a finger on the character at flat index [charIndex] past the long-press threshold. */
	fun longPressAtCharacter(charIndex: Int) {
		val position = positionOfCharacter(charIndex)
		test.onRoot().performTouchInput { down(position) }
		// The long-press timer is a coroutine on the editor's scope, which the test
		// clock drives; sleeping the thread would not move it.
		test.mainClock.advanceTimeBy(800)
		test.waitForIdle()
		test.onRoot().performTouchInput { up() }
		test.waitForIdle()
	}

	/**
	 * Drags a finger [dy] pixels from [position] and lifts, the shape of a scroll.
	 * Well past touch slop, so it can never be mistaken for a tap.
	 */
	fun panFrom(position: Offset, dy: Float = -120f) {
		test.onRoot().performTouchInput {
			down(position)
			moveTo(position + Offset(0f, dy))
			up()
		}
		test.waitForIdle()
	}

	/** Left-clicks the character at flat index [charIndex]. */
	fun clickAtCharacter(charIndex: Int, shift: Boolean = false) {
		clickAt(positionOfCharacter(charIndex), shift)
	}

	/** Left-clicks an arbitrary pixel [position], optionally with shift held. */
	fun clickAt(position: Offset, shift: Boolean = false) {
		defeatMultiClickDetection()
		if (shift) test.onRoot().performKeyInput { keyDown(Key.ShiftLeft) }
		test.onRoot().performMouseInput { click(position) }
		if (shift) test.onRoot().performKeyInput { keyUp(Key.ShiftLeft) }
		test.waitForIdle()
	}

	/** Double-clicks the character at flat index [charIndex] (word select). */
	fun doubleClickAtCharacter(charIndex: Int) {
		defeatMultiClickDetection()
		test.onRoot().performMouseInput { doubleClick(positionOfCharacter(charIndex)) }
		test.waitForIdle()
	}

	/** Presses at [fromChar], drags to [toChar], and releases. */
	fun dragSelect(fromChar: Int, toChar: Int) {
		defeatMultiClickDetection()
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

	// The editor's double/triple-click detection compares wall-clock timestamps
	// (Clock.System.now, 300ms window), not the virtual test clock, so gestures
	// issued back-to-back by a fast test read as multi-clicks and word-select.
	private fun defeatMultiClickDetection() {
		Thread.sleep(350)
	}

	/** Pixel position of the character at flat index [charIndex], vertically centered on its line. */
	fun positionOfCharacter(charIndex: Int): Offset {
		val metrics = state.getPositionForOffset(state.getOffsetAtCharacter(charIndex))
		return Offset(metrics.position.x, metrics.position.y + metrics.height / 2f)
	}

	/**
	 * Seeds the clipboard with unstyled text, as an external application or a
	 * plain-text-only platform clipboard would leave it.
	 */
	fun setPlainClipboardText(value: String) {
		clipboard.setPlainText(value)
		test.waitForIdle()
	}

	/** All [SpanStyle]s covering the character at flat index [charIndex]. */
	fun stylesAt(charIndex: Int): List<SpanStyle> =
		state.getAllText().spanStyles
			.filter { charIndex >= it.start && charIndex < it.end }
			.map { it.item }

	fun waitForIdle() = test.waitForIdle()
}
