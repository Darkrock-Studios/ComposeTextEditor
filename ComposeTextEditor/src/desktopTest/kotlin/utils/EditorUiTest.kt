package utils

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.LocalNativeTextToolbar
import com.darkrockstudios.texteditor.RichSpanClickEventListener
import com.darkrockstudios.texteditor.handleCenter as drawnHandleCenter
import com.darkrockstudios.texteditor.RichSpanClickListener
import com.darkrockstudios.texteditor.rememberTextEditorStyle
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
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
	onRichSpanClickEvent: RichSpanClickEventListener? = null,
	onLinkClick: ((String) -> Unit)? = null,
	contextMenuState: TextEditorContextMenuState? = null,
	autoFocus: Boolean = enabled,
	contentPadding: PaddingValues = PaddingValues(0.dp),
	density: Float = 1f,
	textToolbar: TextToolbar? = null,
	textStyle: TextStyle = TextStyle.Default,
	trailingFocusable: Boolean = false,
	block: EditorUiTestScope.() -> Unit,
) = runSkikoComposeUiTest(density = Density(density)) {
	val clipboard = InMemoryClipboard()
	lateinit var state: TextEditorState
	val trailing = FocusFlag()
	setContent {
		state = rememberTextEditorState(initialText = initialText)
		CompositionLocalProvider(
			LocalClipboard provides clipboard,
			LocalKeyBindings provides keyBindings,
			// The desktop scene's own toolbar is inert; a test that passes one stands it in
			// for a platform toolbar, and without one the editor falls back to its menu.
			LocalTextToolbar provides (textToolbar ?: LocalTextToolbar.current),
			LocalNativeTextToolbar provides (textToolbar != null),
		) {
			Column {
				BasicTextEditor(
					state = state,
					modifier = Modifier.size(width, height).testTag(EDITOR_TEST_TAG),
					contentPadding = contentPadding,
					enabled = enabled,
					autoFocus = autoFocus,
					style = rememberTextEditorStyle(textStyle = textStyle),
					contextMenuState = contextMenuState,
					onRichSpanClick = onRichSpanClick,
					onRichSpanClickEvent = onRichSpanClickEvent,
					onLinkClick = onLinkClick,
					keyBindings = keyBindings,
				)
				if (trailingFocusable) {
					Box(Modifier.size(20.dp).onFocusChanged { trailing.focused = it.isFocused }.focusable())
				}
			}
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
	EditorUiTestScope(this, state, clipboard, trailing).block()
}

class FocusFlag {
	var focused = false
}

@OptIn(ExperimentalTestApi::class)
class EditorUiTestScope(
	val test: SkikoComposeUiTest,
	val state: TextEditorState,
	val clipboard: InMemoryClipboard,
	private val trailing: FocusFlag = FocusFlag(),
) {
	/** Whether the focusable placed after the editor by `trailingFocusable` holds focus. */
	val trailingFocused: Boolean get() = trailing.focused

	// Pointer input is injected at the tagged editor node, not onRoot(): once a
	// context menu popup is open there are two roots and onRoot() refuses to pick.
	private val editor get() = test.onNodeWithTag(EDITOR_TEST_TAG)

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
		editor.performTouchInput {
			down(position)
			up()
		}
		test.waitForIdle()
	}

	/** Taps the character at flat index [charIndex] with a finger. */
	fun tapAtCharacter(charIndex: Int) = tapAt(positionOfCharacter(charIndex))

	/** Holds a finger on the character at flat index [charIndex] past the long-press threshold. */
	fun longPressAtCharacter(charIndex: Int) {
		longPressAt(positionOfCharacter(charIndex))
		editor.performTouchInput { up() }
		test.waitForIdle()
	}

	/**
	 * Puts a finger down at [position] and holds it past the long-press threshold. The
	 * finger is still down when this returns, for a drag or a lift to follow.
	 */
	fun longPressAt(position: Offset) {
		editor.performTouchInput { down(position) }
		// The long-press timer is a coroutine on the editor's scope, which the test
		// clock drives; sleeping the thread would not move it.
		test.mainClock.advanceTimeBy(800)
		test.waitForIdle()
	}

	/**
	 * Taps the character at [charIndex] twice in quick succession with a finger, the touch
	 * word-select gesture. With [toChar], the second tap drags there before lifting.
	 */
	fun doubleTapAtCharacter(charIndex: Int, toChar: Int? = null, steps: Int = 4) {
		val position = positionOfCharacter(charIndex)
		editor.performTouchInput {
			down(position)
			up()
			advanceEventTime(MULTI_CLICK_INTERVAL_MS)
			down(position)
			if (toChar != null) {
				val delta = positionOfCharacter(toChar) - position
				for (step in 1..steps) moveTo(position + delta * (step / steps.toFloat()))
			}
			up()
		}
		test.waitForIdle()
	}

	/**
	 * Holds a finger on the character at [fromChar] past the long-press threshold, then
	 * drags it to [toChar] in [steps] moves and lifts.
	 */
	fun longPressDragToCharacter(fromChar: Int, toChar: Int, steps: Int = 4) {
		val from = positionOfCharacter(fromChar)
		longPressAt(from)
		val delta = positionOfCharacter(toChar) - from
		editor.performTouchInput {
			for (step in 1..steps) moveTo(from + delta * (step / steps.toFloat()))
			up()
		}
		test.waitForIdle()
	}

	/**
	 * Drags a finger [dy] pixels from [position] and lifts, the shape of a scroll.
	 * Well past touch slop, so it can never be mistaken for a tap.
	 */
	fun panFrom(position: Offset, dy: Float = -120f) {
		editor.performTouchInput {
			down(position)
			moveTo(position + Offset(0f, dy))
			up()
		}
		test.waitForIdle()
	}

	/** Runs finger [gestures] on the editor. */
	fun touch(gestures: TouchInjectionScope.() -> Unit) {
		editor.performTouchInput(gestures)
		test.waitForIdle()
	}

	/** Where the selection's start or end touch handle is drawn. */
	fun handleCenter(isStart: Boolean): Offset {
		val selection = checkNotNull(state.selector.selection) { "no selection, so no handles" }
		val metrics = state.getPositionForOffset(if (isStart) selection.start else selection.end)
		return canvasToNode(with(test.density) { drawnHandleCenter(metrics) })
	}

	/** Where the touch caret handle is drawn, under the caret. */
	fun caretHandleCenter(): Offset =
		canvasToNode(with(test.density) { drawnHandleCenter(state.getPositionForOffset(state.cursorPosition)) })

	/** Drags the touch caret handle so the caret travels to [toChar], then lifts. */
	fun dragCaretHandle(toChar: Int, steps: Int = 8) {
		val delta = positionOfCharacter(toChar) - positionOfCharacter(cursorIndex)
		val grab = caretHandleCenter()
		touch {
			down(grab)
			for (step in 1..steps) moveTo(grab + delta * (step / steps.toFloat()))
			up()
		}
	}

	/**
	 * Drags the start or end touch handle with a finger so that its end of the selection
	 * travels to [toChar]: the finger moves by the distance between the two characters,
	 * in [steps] moves, then lifts.
	 */
	fun dragHandle(isStart: Boolean, toChar: Int, steps: Int = 8) {
		val selection = checkNotNull(state.selector.selection)
		val from = positionOfCharacter(state.getCharacterIndex(if (isStart) selection.start else selection.end))
		val delta = positionOfCharacter(toChar) - from
		val grab = handleCenter(isStart)
		editor.performTouchInput {
			down(grab)
			for (step in 1..steps) moveTo(grab + delta * (step / steps.toFloat()))
			up()
		}
		test.waitForIdle()
	}

	/** Left-clicks the character at flat index [charIndex]. */
	fun clickAtCharacter(charIndex: Int, shift: Boolean = false) {
		clickAt(positionOfCharacter(charIndex), shift)
	}

	/** Left-clicks an arbitrary pixel [position], optionally with modifier keys held. */
	fun clickAt(position: Offset, shift: Boolean = false, ctrl: Boolean = false, meta: Boolean = false) =
		mouse(shift = shift, ctrl = ctrl, meta = meta) { click(position) }

	/** Right-clicks the character at flat index [charIndex]. */
	fun rightClickAtCharacter(charIndex: Int) = mouse {
		rightClick(positionOfCharacter(charIndex))
	}

	/** Middle-clicks the character at flat index [charIndex]. */
	fun middleClickAtCharacter(charIndex: Int) = mouse {
		moveTo(positionOfCharacter(charIndex))
		press(MouseButton.Tertiary)
		release(MouseButton.Tertiary)
	}

	/** Double-clicks the character at flat index [charIndex] (word select). */
	fun doubleClickAtCharacter(charIndex: Int, shift: Boolean = false) =
		multiClickAtCharacter(charIndex, clicks = 2, shift = shift)

	/** Triple-clicks the character at flat index [charIndex] (line select). */
	fun tripleClickAtCharacter(charIndex: Int, shift: Boolean = false) =
		multiClickAtCharacter(charIndex, clicks = 3, shift = shift)

	/**
	 * Clicks [clicks] times in quick succession on the character at [fromChar]. With
	 * [toChar], the last press drags there before releasing, the double-click-drag
	 * shape. [beforeRelease] runs while the last press is still held.
	 */
	fun multiClickAtCharacter(
		fromChar: Int,
		clicks: Int,
		toChar: Int? = null,
		shift: Boolean = false,
		beforeRelease: () -> Unit = {},
	) {
		mouse(shift = shift) {
			moveTo(positionOfCharacter(fromChar))
			repeat(clicks - 1) {
				press()
				release()
				advanceEventTime(MULTI_CLICK_INTERVAL_MS)
			}
			press()
			if (toChar != null) moveTo(positionOfCharacter(toChar))
		}
		beforeRelease()
		mouse(shift = shift, fresh = false) { release() }
	}

	/** Presses at [fromChar], drags to [toChar], and releases. */
	fun dragSelect(fromChar: Int, toChar: Int) = mouse {
		moveTo(positionOfCharacter(fromChar))
		press()
		moveTo(positionOfCharacter(toChar))
		release()
	}

	/**
	 * Runs mouse [gestures] on the editor, holding the given modifier keys throughout.
	 * Unless [fresh] is false, the first press starts a new click sequence rather than
	 * continuing the previous gesture's multi-click.
	 */
	fun mouse(
		shift: Boolean = false,
		ctrl: Boolean = false,
		meta: Boolean = false,
		fresh: Boolean = true,
		gestures: MouseInjectionScope.() -> Unit,
	) {
		val held = listOfNotNull(
			Key.ShiftLeft.takeIf { shift },
			Key.CtrlLeft.takeIf { ctrl },
			Key.MetaLeft.takeIf { meta },
		)
		if (held.isNotEmpty()) test.onRoot().performKeyInput { held.forEach { keyDown(it) } }
		editor.performMouseInput {
			if (fresh) defeatMultiClickDetection()
			gestures()
		}
		if (held.isNotEmpty()) test.onRoot().performKeyInput { held.forEach { keyUp(it) } }
		test.waitForIdle()
	}

	/**
	 * Presses at [from], drags to [to], and releases, both in editor node
	 * coordinates. Either end may lie outside the editor.
	 */
	fun dragBetween(from: Offset, to: Offset) = mouse {
		moveTo(from)
		press()
		moveTo(to)
		release()
	}

	/**
	 * Pixel position of the character at flat index [charIndex], vertically centered on
	 * its line, in editor node coordinates (content padding included).
	 */
	fun positionOfCharacter(charIndex: Int): Offset = canvasToNode(state.positionOfCharacter(charIndex))

	/** Converts a point in the editor's text canvas to editor node coordinates. */
	fun canvasToNode(canvasPosition: Offset): Offset {
		val canvas = checkNotNull(state.canvasLayoutCoordinates) { "the editor has not been laid out" }
		return canvasPosition + canvas.positionInRoot() - editor.fetchSemanticsNode().positionInRoot
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

/** Gap between the presses of a multi-click: well inside any double-click timeout. */
private const val MULTI_CLICK_INTERVAL_MS = 50L
