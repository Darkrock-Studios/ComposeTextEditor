package utils

import androidx.compose.ui.input.key.Key
import com.darkrockstudios.texteditor.TextEditorRange

/** Applies [op] through real key events and the clipboard, the UI twin of [StateFuzzInterpreter]. */
fun EditorUiTestScope.applyFuzzOpUi(op: FuzzOp) {
	fun clampIndex(raw: Int): Int = raw % (text.length + 1)

	when (op) {
		is FuzzOp.TypeText -> typeText(op.text)

		is FuzzOp.Enter -> press(Key.Enter)

		is FuzzOp.Backspace -> press(Key.Backspace)

		is FuzzOp.DeleteForward -> press(Key.Delete)

		is FuzzOp.MoveCursor -> when (op.slot % 8) {
			0 -> press(Key.MoveHome, ctrl = true)
			1 -> press(Key.MoveEnd, ctrl = true)
			2 -> press(Key.MoveHome)
			3 -> press(Key.MoveEnd)
			4 -> press(Key.DirectionLeft)
			5 -> press(Key.DirectionRight)
			6 -> press(Key.DirectionUp)
			else -> press(Key.DirectionDown)
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
			press(Key.V, ctrl = true)
		}

		is FuzzOp.UndoBurst -> repeat(op.count) { press(Key.Z, ctrl = true) }

		is FuzzOp.RedoBurst -> repeat(op.count) { press(Key.Y, ctrl = true) }

		is FuzzOp.SelectAllType -> {
			press(Key.A, ctrl = true)
			typeText(op.text)
		}
	}
}
