package utils

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.LocalKeyBindings
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Text, caret, and selection of one widget at one moment, as flat UTF-16 indices.
 * [anchor] is the fixed end of the selection and equals [caret] when nothing is
 * selected, the same shape as `TextFieldState.selection`.
 */
data class EditSnapshot(val text: String, val anchor: Int, val caret: Int) {
	constructor(text: String, caret: Int) : this(text, caret, caret)

	val hasSelection: Boolean get() = anchor != caret

	/** The text with `|` at the caret and `^` at the anchor, newlines shown as `⏎`. */
	override fun toString(): String = buildString {
		append('"')
		for (i in 0..text.length) {
			if (i == anchor && hasSelection) append('^')
			if (i == caret) append('|')
			if (i < text.length) append(if (text[i] == '\n') '⏎' else text[i])
		}
		append('"')
	}
}

/** One keystroke, replayed identically through either widget. */
sealed interface Stroke {
	data class Press(val key: Key, val ctrl: Boolean = false, val shift: Boolean = false) : Stroke {
		override fun toString(): String = buildString {
			if (ctrl) append("Ctrl+")
			if (shift) append("Shift+")
			append(KEY_NAMES[key] ?: key.toString())
		}
	}

	/** Types [text] one code point at a time, as desktop KEY_TYPED events. */
	data class Type(val text: String) : Stroke {
		override fun toString(): String = "Type(${text.escapedForTranscript()})"
	}
}

private val KEY_NAMES = mapOf(
	Key.DirectionLeft to "Left",
	Key.DirectionRight to "Right",
	Key.DirectionUp to "Up",
	Key.DirectionDown to "Down",
	Key.MoveHome to "Home",
	Key.MoveEnd to "End",
	Key.PageUp to "PageUp",
	Key.PageDown to "PageDown",
	Key.Backspace to "Backspace",
	Key.Delete to "Delete",
	Key.Enter to "Enter",
)

private fun String.escapedForTranscript(): String = buildString {
	append('"')
	for (ch in this@escapedForTranscript) {
		when {
			ch == '\n' -> append("\\n")
			ch.isSurrogate() || ch.isISOControl() || Character.getType(ch) == Character.FORMAT.toInt() ||
				Character.getType(ch) == Character.NON_SPACING_MARK.toInt() ->
				append("\\u%04X".format(ch.code))

			else -> append(ch)
		}
	}
	append('"')
}

val Left = Stroke.Press(Key.DirectionLeft)
val Right = Stroke.Press(Key.DirectionRight)
val Up = Stroke.Press(Key.DirectionUp)
val Down = Stroke.Press(Key.DirectionDown)
val Home = Stroke.Press(Key.MoveHome)
val End = Stroke.Press(Key.MoveEnd)
val PageUp = Stroke.Press(Key.PageUp)
val PageDown = Stroke.Press(Key.PageDown)
val CtrlLeft = Stroke.Press(Key.DirectionLeft, ctrl = true)
val CtrlRight = Stroke.Press(Key.DirectionRight, ctrl = true)
val CtrlUp = Stroke.Press(Key.DirectionUp, ctrl = true)
val CtrlDown = Stroke.Press(Key.DirectionDown, ctrl = true)
val CtrlHome = Stroke.Press(Key.MoveHome, ctrl = true)
val CtrlEnd = Stroke.Press(Key.MoveEnd, ctrl = true)
val ShiftLeft = Stroke.Press(Key.DirectionLeft, shift = true)
val ShiftRight = Stroke.Press(Key.DirectionRight, shift = true)
val ShiftUp = Stroke.Press(Key.DirectionUp, shift = true)
val ShiftDown = Stroke.Press(Key.DirectionDown, shift = true)
val ShiftHome = Stroke.Press(Key.MoveHome, shift = true)
val ShiftEnd = Stroke.Press(Key.MoveEnd, shift = true)
val CtrlShiftLeft = Stroke.Press(Key.DirectionLeft, ctrl = true, shift = true)
val CtrlShiftRight = Stroke.Press(Key.DirectionRight, ctrl = true, shift = true)
val CtrlShiftUp = Stroke.Press(Key.DirectionUp, ctrl = true, shift = true)
val CtrlShiftDown = Stroke.Press(Key.DirectionDown, ctrl = true, shift = true)
val CtrlShiftHome = Stroke.Press(Key.MoveHome, ctrl = true, shift = true)
val CtrlShiftEnd = Stroke.Press(Key.MoveEnd, ctrl = true, shift = true)
val Backspace = Stroke.Press(Key.Backspace)
val Delete = Stroke.Press(Key.Delete)
val CtrlBackspace = Stroke.Press(Key.Backspace, ctrl = true)
val CtrlDelete = Stroke.Press(Key.Delete, ctrl = true)
val Enter = Stroke.Press(Key.Enter)

fun type(text: String): Stroke = Stroke.Type(text)

private const val REFERENCE_TEST_TAG = "reference-text-field"

/**
 * Harness for differential tests: composes [BasicTextEditor] beside Compose's own
 * `BasicTextField(TextFieldState)`, the desktop reference for native behaviour
 * ("The reference rule" in docs/ROADMAP.md), and replays one [Stroke] script
 * through both.
 *
 * Both widgets use the default font and text style, and the reference field is as
 * wide as the editor's text viewport (the editor's scrollbar takes the rest), so
 * rows wrap at the same offsets; [DifferentialScope.assertSameRows] checks that.
 * Only the focused widget receives keys, and the reference collapses its selection
 * when it loses focus, so a script is replayed through the reference first, then
 * through the editor, never interleaved.
 *
 * Key bindings are pinned on both sides so the suite means the same thing on any
 * OS: the editor gets [CtrlKeyBindings], and the reference gets Compose's
 * Linux mapping through foundation's test-only `keyMappingOverride`, set by
 * reflection because it is internal.
 *
 * Typed text goes through [typeCodePoints]: KEY_TYPED events that carry an AWT
 * event, which the reference requires and the editor ignores.
 *
 * Cases the editor gets wrong today stay in the suite, marked with the roadmap
 * item that fixes them; see `divergesUntil` on [assertMatchesNative].
 */
@OptIn(ExperimentalTestApi::class)
internal fun differentialUiTest(
	initialText: String,
	width: Dp = 400.dp,
	height: Dp = 300.dp,
	block: DifferentialScope.() -> Unit,
) = withPinnedReferenceKeyMapping {
	runSkikoComposeUiTest {
		val clipboard = InMemoryClipboard()
		val fieldState = TextFieldState(initialText, TextRange(0))
		val fieldFocus = FocusRequester()
		var fieldFocused = false
		var fieldLayout: (() -> TextLayoutResult?)? = null
		lateinit var editorState: TextEditorState
		setContent {
			editorState = rememberTextEditorState(initialText = AnnotatedString(initialText))
			CompositionLocalProvider(
				LocalClipboard provides clipboard,
				LocalKeyBindings provides CtrlKeyBindings,
			) {
				Row {
					BasicTextEditor(
						state = editorState,
						modifier = Modifier.size(width, height).testTag(EDITOR_TEST_TAG),
						autoFocus = true,
					)
					val textWidth = with(LocalDensity.current) { editorState.viewportSize.width.toDp() }
					BasicTextField(
						state = fieldState,
						modifier = Modifier
							.width(textWidth)
							.height(height)
							.testTag(REFERENCE_TEST_TAG)
							.focusRequester(fieldFocus)
							.onFocusChanged { fieldFocused = it.isFocused },
						onTextLayout = { getResult -> fieldLayout = getResult },
					)
				}
			}
		}
		waitForIdle()
		waitUntil(timeoutMillis = 5_000) { editorState.isFocused }
		DifferentialScope(
			test = this,
			editor = editorState,
			reference = fieldState,
			focusField = {
				runOnIdle { fieldFocus.requestFocus() }
				waitUntil(timeoutMillis = 5_000) { fieldFocused }
			},
			fieldLayout = { fieldLayout?.invoke() },
		).block()
	}
}

@OptIn(ExperimentalTestApi::class)
class DifferentialScope internal constructor(
	val test: SkikoComposeUiTest,
	val editor: TextEditorState,
	private val reference: TextFieldState,
	private val focusField: () -> Unit,
	private val fieldLayout: () -> TextLayoutResult?,
) {
	val editorSnapshot: EditSnapshot get() = editor.editSnapshot()

	val referenceSnapshot: EditSnapshot
		get() = EditSnapshot(reference.text.toString(), reference.selection.start, reference.selection.end)

	/**
	 * Replays [strokes] through the reference field from [start] and returns its
	 * state after each stroke. Leaves the reference focused.
	 */
	fun replayReference(start: EditSnapshot, strokes: List<Stroke>): List<EditSnapshot> {
		focusField()
		test.runOnIdle {
			reference.edit {
				replace(0, length, start.text)
				selection = TextRange(start.anchor, start.caret)
			}
		}
		test.waitForIdle()
		return strokes.map { stroke ->
			send(stroke)
			referenceSnapshot
		}
	}

	/** Focuses the editor without a pointer event, so the caret stays where it was. */
	fun focusEditor() {
		test.onNode(hasAnyAncestor(hasTestTag(EDITOR_TEST_TAG)) and hasClickAction())
			.performSemanticsAction(SemanticsActions.OnClick)
		test.waitUntil(timeoutMillis = 5_000) { editor.isFocused }
	}

	/** Puts the editor in [snapshot]'s state programmatically. */
	fun setEditor(snapshot: EditSnapshot) {
		test.runOnIdle {
			if (editor.getAllText().text != snapshot.text) editor.setText(snapshot.text)
			editor.selector.clearSelection()
			editor.cursor.updatePosition(editor.getOffsetAtCharacter(snapshot.caret))
			if (snapshot.hasSelection) {
				editor.selector.updateSelection(
					editor.getOffsetAtCharacter(snapshot.anchor),
					editor.getOffsetAtCharacter(snapshot.caret),
				)
			}
		}
		test.waitForIdle()
	}

	/** Sends [stroke] to whichever widget holds focus. */
	fun send(stroke: Stroke) = test.sendStroke(stroke)

	/**
	 * Asserts both widgets break the current text into the same visual rows, the
	 * precondition for comparing vertical motion. Call while both hold the same text.
	 */
	fun assertSameRows() {
		assertEquals(
			referenceRows(),
			editorRows(),
			"harness precondition: the editor and the reference must wrap at the same offsets",
		)
	}

	/**
	 * Whether both widgets break the editor's current text into the same rows. Loads
	 * that text into the reference, so call it only once the reference replay is done.
	 */
	fun rowsAgree(): Boolean {
		val text = editor.getAllText().text
		test.runOnIdle { reference.edit { replace(0, length, text) } }
		test.waitForIdle()
		return referenceRows() == editorRows()
	}

	/** The flat offset where each of the editor's visual rows starts. */
	fun editorRows(): List<Int> = editor.lineOffsets.map {
		editor.getCharacterIndex(CharLineOffset(it.line, it.wrapStartsAtIndex))
	}

	private fun referenceRows(): List<Int> {
		val layout = test.runOnIdle { fieldLayout() } ?: fail("the reference field has no layout")
		return (0 until layout.lineCount).map { layout.getLineStart(it) }
	}
}

/** The editor's text, caret, and selection as an [EditSnapshot]. */
fun TextEditorState.editSnapshot(): EditSnapshot {
	val text = getAllText().text
	val caret = getCharacterIndex(cursorPosition)
	val selection = selector.selection ?: return EditSnapshot(text, caret)
	val start = getCharacterIndex(selection.start)
	val end = getCharacterIndex(selection.end)
	val anchor = when (caret) {
		end -> start
		start -> end
		else -> fail("the editor's caret $caret is on neither end of its selection $start..$end")
	}
	return EditSnapshot(text, anchor, caret)
}

/** Sends [stroke] to whichever widget holds focus. */
@OptIn(ExperimentalTestApi::class)
fun SkikoComposeUiTest.sendStroke(stroke: Stroke) {
	when (stroke) {
		is Stroke.Press -> onRoot().performKeyInput {
			if (stroke.ctrl) keyDown(Key.CtrlLeft)
			if (stroke.shift) keyDown(Key.ShiftLeft)
			pressKey(stroke.key)
			if (stroke.shift) keyUp(Key.ShiftLeft)
			if (stroke.ctrl) keyUp(Key.CtrlLeft)
		}

		is Stroke.Type -> typeCodePoints(stroke.text)
	}
	waitForIdle()
}

/** Sends [stroke] to the editor. */
@OptIn(ExperimentalTestApi::class)
fun EditorUiTestScope.send(stroke: Stroke) = test.sendStroke(stroke)

/**
 * Types [text] one code point at a time as KeyDown, KEY_TYPED, KeyUp. The KEY_TYPED
 * event carries a real AWT event, without which `BasicTextField` ignores it; the
 * editor reads only its code point. Supplementary code points arrive whole, as
 * they do from a desktop input method.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
fun SkikoComposeUiTest.typeCodePoints(text: String) {
	var i = 0
	while (i < text.length) {
		val codePoint = text.codePointAt(i)
		val key = Key(java.awt.event.KeyEvent.getExtendedKeyCodeForChar(codePoint))
		val awtTyped = java.awt.event.KeyEvent(
			AWT_EVENT_SOURCE,
			java.awt.event.KeyEvent.KEY_TYPED,
			0L,
			0,
			java.awt.event.KeyEvent.VK_UNDEFINED,
			text[i],
		)
		runOnUiThread {
			scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown, codePoint = codePoint))
			scene.sendKeyEvent(
				KeyEvent(
					key = Key.Unknown,
					type = KeyEventType.Unknown,
					codePoint = codePoint,
					nativeEvent = awtTyped,
				)
			)
			scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp, codePoint = codePoint))
		}
		i += Character.charCount(codePoint)
	}
	waitForIdle()
}

private val AWT_EVENT_SOURCE = object : java.awt.Component() {}

private const val FOUNDATION_TEXT = "androidx.compose.foundation.text"

/**
 * Runs [block] with `BasicTextField` on Compose's Linux key mapping, restoring
 * the previous override afterwards. The mapping is read when a field is created.
 */
private fun <T> withPinnedReferenceKeyMapping(block: () -> T): T {
	val holder = Class.forName("$FOUNDATION_TEXT.KeyMapping_desktopKt")
	val mappingType = Class.forName("$FOUNDATION_TEXT.KeyMapping")
	val getter = holder.getMethod("getKeyMappingOverride")
	val setter = holder.getMethod("setKeyMappingOverride", mappingType)
	val linuxMapping = Class.forName("$FOUNDATION_TEXT.DefaultSkikoKeyMapping").getField("INSTANCE").get(null)
	val previous = getter.invoke(null)
	setter.invoke(null, linuxMapping)
	try {
		return block()
	} finally {
		setter.invoke(null, previous)
	}
}

/**
 * Replays [strokes] through both widgets from [start] and asserts the editor ends
 * every stroke in the same state as the reference.
 *
 * [divergesUntil] marks a case the editor is known to get wrong today, naming the
 * roadmap item whose fix makes it match (for example "1.2"). Such a case passes
 * while it diverges and fails once it matches, with a message to delete the
 * marker, so a fix switches its cases on rather than leaving them silently
 * skipped. Search for `divergesUntil = "1.2"` to find the cases an item owns.
 */
internal fun assertMatchesNative(
	start: EditSnapshot,
	strokes: List<Stroke>,
	width: Dp = 400.dp,
	divergesUntil: String? = null,
) = differentialUiTest(initialText = start.text, width = width) {
	assertSameRows()
	val reference = replayReference(start, strokes)
	focusEditor()
	setEditor(start)
	val actual = strokes.map { stroke ->
		send(stroke)
		editorSnapshot
	}
	val firstDivergence = actual.indices.firstOrNull { actual[it] != reference[it] }
	when {
		firstDivergence != null && divergesUntil == null -> fail(
			"the editor diverged from BasicTextField at stroke ${firstDivergence + 1}\n" +
				transcript(start, strokes, reference, actual)
		)

		firstDivergence == null && divergesUntil != null -> fail(
			"the editor now matches BasicTextField; if roadmap item $divergesUntil has " +
				"landed, delete divergesUntil = \"$divergesUntil\" from this case\n" +
				transcript(start, strokes, reference, actual)
		)
	}
}

private fun transcript(
	start: EditSnapshot,
	strokes: List<Stroke>,
	reference: List<EditSnapshot>,
	actual: List<EditSnapshot>,
): String = buildString {
	appendLine("start      $start")
	strokes.forEachIndexed { i, stroke ->
		val mark = if (reference[i] != actual[i]) "  <-- differs" else ""
		appendLine("${i + 1}. $stroke$mark")
		appendLine("   native  ${reference[i]}")
		appendLine("   editor  ${actual[i]}")
	}
}
