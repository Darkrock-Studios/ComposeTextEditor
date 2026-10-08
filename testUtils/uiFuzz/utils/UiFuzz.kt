package utils

import androidx.compose.ui.input.key.Key
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.insertTable

/** What a composed editor harness offers the fuzz script: a module's own harness implements it. */
interface FuzzUiDriver {
	val state: TextEditorState

	/** Types through real desktop key events; `\n` and `\t` become Enter and Tab. */
	fun typeText(text: String)

	/** Presses [key], with Ctrl held when [ctrl] and Shift when [shift], and waits for the editor to settle. */
	fun sendKey(key: Key, ctrl: Boolean = false, shift: Boolean = false)

	/** Seeds the clipboard with unstyled text, as another application would. */
	fun setPlainClipboardText(value: String)

	fun waitForIdle()

	/** Runs [block] on the UI thread once the editor is idle. */
	fun runOnIdle(block: () -> Unit)
}

/**
 * Selects the flat character range [fromChar, toChar) directly through the selection
 * manager. Use a harness's mouse drag only when the gesture itself is under test; it
 * costs a real 350ms sleep per call.
 */
fun FuzzUiDriver.selectChars(fromChar: Int, toChar: Int) {
	state.selector.updateSelection(
		state.getOffsetAtCharacter(fromChar),
		state.getOffsetAtCharacter(toChar),
	)
	waitForIdle()
}

/** Applies [op] through real key events and the clipboard, the UI twin of [StateFuzzInterpreter]. */
fun FuzzUiDriver.applyFuzzOpUi(op: FuzzOp) {
	val text by lazy { state.getAllText().text }
	fun clampIndex(raw: Int): Int = raw % (text.length + 1)

	when (op) {
		is FuzzOp.TypeText -> typeText(op.text)

		is FuzzOp.Enter -> sendKey(Key.Enter)

		is FuzzOp.Backspace -> sendKey(Key.Backspace)

		is FuzzOp.DeleteForward -> sendKey(Key.Delete)

		is FuzzOp.MoveCursor -> when (op.slot % 8) {
			0 -> sendKey(Key.MoveHome, ctrl = true)
			1 -> sendKey(Key.MoveEnd, ctrl = true)
			2 -> sendKey(Key.MoveHome)
			3 -> sendKey(Key.MoveEnd)
			4 -> sendKey(Key.DirectionLeft)
			5 -> sendKey(Key.DirectionRight)
			6 -> sendKey(Key.DirectionUp)
			else -> sendKey(Key.DirectionDown)
		}

		is FuzzOp.SelectRange -> {
			val a = clampIndex(op.a)
			val b = clampIndex(op.b)
			if (a != b) selectChars(minOf(a, b), maxOf(a, b))
		}

		is FuzzOp.ToggleBlock -> {
			val target = blockToggleTarget(op, state) ?: return
			target.second(state, target.first)
			waitForIdle()
		}

		is FuzzOp.ToggleBold -> {
			trimmedStyleRange(text, op.a, op.b)?.let { (start, end) ->
				state.addStyleSpan(
					TextEditorRange(
						state.getOffsetAtCharacter(start),
						state.getOffsetAtCharacter(end),
					),
					FUZZ_BOLD,
				)
				waitForIdle()
			}
		}

		is FuzzOp.PastePlain -> {
			setPlainClipboardText(op.text)
			sendKey(Key.V, ctrl = true)
		}

		is FuzzOp.UndoBurst -> repeat(op.count) { sendKey(Key.Z, ctrl = true) }

		is FuzzOp.RedoBurst -> repeat(op.count) { sendKey(Key.Y, ctrl = true) }

		is FuzzOp.SelectAllType -> {
			sendKey(Key.A, ctrl = true)
			typeText(op.text)
		}

		is FuzzOp.InsertTable -> {
			runOnIdle { state.insertTable(op.rows, op.columns) }
			waitForIdle()
		}

		is FuzzOp.TableEdit -> {
			runOnIdle { state.applyTableEdit(op.kind, state.getOffsetAtCharacter(clampIndex(op.slot)).line) }
			waitForIdle()
		}

		is FuzzOp.Tab -> sendKey(Key.Tab, shift = op.backward)

		is FuzzOp.CopyPaste -> {
			val a = clampIndex(op.a)
			val b = clampIndex(op.b)
			if (a == b) return
			selectChars(minOf(a, b), maxOf(a, b))
			sendKey(if (op.cut) Key.X else Key.C, ctrl = true)
			val at = state.getOffsetAtCharacter(op.slot % (state.getAllText().text.length + 1))
			runOnIdle {
				state.selector.clearSelection()
				state.cursor.updatePosition(at)
			}
			sendKey(Key.V, ctrl = true)
		}
	}
}
