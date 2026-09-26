package com.darkrockstudios.texteditor.input

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * iOS implementation of TextEditorTextInputService.
 * This creates a PlatformTextInputMethodRequest that bridges iOS's UITextInput
 * to the TextEditorState.
 */
actual class TextEditorTextInputService actual constructor(
	private val state: TextEditorState
) {
	actual suspend fun startInput(session: PlatformTextInputSession): Nothing {
		val request = TextEditorIOSInputMethodRequest(state)
		session.startInputMethod(request)
	}
}

/**
 * Adapts the library's [TextEditorState] to Compose's skiko `TextEditorState`
 * (CharSequence + selection + composition). Mapping logic is shared via the
 * `*AsTextRange` / `imeSubSequence` helpers in commonMain.
 */
@OptIn(ExperimentalComposeUiApi::class)
private class ImeComposeStateAdapter(
	private val editorState: com.darkrockstudios.texteditor.state.TextEditorState
) : androidx.compose.ui.text.input.TextEditorState {
	override val length: Int get() = editorState.getTextLength()
	override fun get(index: Int): Char = editorState.imeCharAt(index)
	override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
		editorState.imeSubSequence(startIndex, endIndex)
	override val text: String get() = editorState.getAllText().text
	override fun toString(): String = text
	override val selection get() = editorState.selectionAsTextRange()
	override val composition get() = editorState.composingAsTextRange()
}

/**
 * iOS-specific implementation of PlatformTextInputMethodRequest.
 * Bridges UITextInput to the custom TextEditorState.
 */
@OptIn(ExperimentalComposeUiApi::class)
private class TextEditorIOSInputMethodRequest(
	private val editorState: com.darkrockstudios.texteditor.state.TextEditorState
) : PlatformTextInputMethodRequest {

	// Shared bridge to Compose framework's TextEditorState (see ImeStateAdapter.kt)
	override val state: androidx.compose.ui.text.input.TextEditorState =
		ImeComposeStateAdapter(editorState)

	override val imeOptions: ImeOptions = ImeOptions(
		keyboardType = KeyboardType.Text,
		imeAction = ImeAction.Default,
		singleLine = false
	)

	// Provide current text field value to iOS
	override val value: () -> TextFieldValue = {
		TextFieldValue(editorState.getAllText(), editorState.selectionAsTextRange())
	}

	// Handle edit commands from iOS IME
	override val onEditCommand: (List<EditCommand>) -> Unit = { commands ->
		for (command in commands) {
			when (command) {
				is CommitTextCommand -> {
					// Delete selection if present
					if (editorState.selector.hasSelection()) {
						editorState.selector.deleteSelection()
					}
					// Insert text at cursor
					editorState.insertStringAtCursor(command.text)
				}

				is DeleteSurroundingTextCommand -> {
					val cursorIndex = editorState.getCharacterIndex(editorState.cursorPosition)
					val deleteStart = maxOf(0, cursorIndex - command.lengthBeforeCursor)
					val deleteEnd = minOf(editorState.getTextLength(), cursorIndex + command.lengthAfterCursor)

					if (deleteStart < deleteEnd) {
						val startOffset = editorState.getOffsetAtCharacter(deleteStart)
						val endOffset = editorState.getOffsetAtCharacter(deleteEnd)
						val range = TextEditorRange(startOffset, endOffset)
						editorState.delete(range)
					}
				}

				is SetSelectionCommand -> {
					val start = command.start.coerceIn(0, editorState.getTextLength())
					val end = command.end.coerceIn(0, editorState.getTextLength())

					if (start == end) {
						// Just cursor
						val offset = editorState.getOffsetAtCharacter(start)
						editorState.cursor.updatePosition(offset)
						editorState.selector.clearSelection()
					} else {
						// Selection
						val startOffset = editorState.getOffsetAtCharacter(start)
						val endOffset = editorState.getOffsetAtCharacter(end)
						editorState.selector.updateSelection(startOffset, endOffset)
						editorState.cursor.updatePosition(endOffset)
					}
				}

				is BackspaceCommand -> {
					if (editorState.selector.hasSelection()) {
						editorState.selector.deleteSelection()
					} else {
						editorState.backspaceAtCursor()
					}
				}

				// Handle other edit commands as needed
				else -> {
					// Log or handle unknown commands
				}
			}
		}
	}

	override val onImeAction: ((ImeAction) -> Unit)? = null

	// No text layout available for now
	override val textLayoutResult: () -> TextLayoutResult? = { null }

	// Placeholder rects - iOS may not use these for custom text editors
	override val focusedRectInRoot: () -> Rect? = { null }
	override val textFieldRectInRoot: () -> Rect? = { null }
	override val textClippingRectInRoot: () -> Rect? = { null }
	override val unclippedTextOffsetInRoot: () -> Offset? = { null }

	// Edit text scope - allows iOS to apply edits
	override val editText: (TextEditingScope.() -> Unit) -> Unit = { block ->
		val currentValue = value()
		val scope = object : TextEditingScope {
			var value: TextFieldValue = currentValue

			override fun deleteSurroundingTextInCodePoints(lengthBeforeCursor: Int, lengthAfterCursor: Int) {
				// Not implemented for now
			}

			override fun commitText(text: CharSequence, newCursorPosition: Int) {
				// Insert text at cursor
				val currentVal = value
				val newText = currentVal.text.replaceRange(
					currentVal.selection.start,
					currentVal.selection.end,
					text.toString()
				)
				val newCursor = currentVal.selection.start + text.length
				value = TextFieldValue(newText, TextRange(newCursor, newCursor))
			}

			override fun setSelection(start: Int, end: Int) {
				val length = value.text.length
				value = value.copy(selection = TextRange(start.coerceIn(0, length), end.coerceIn(0, length)))
			}

			override fun setComposingRegion(start: Int, end: Int) {
				// No-op for now
			}

			override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
				// For now, treat like commit
				commitText(text, newCursorPosition)
			}

			override fun finishComposingText() {
				// No-op for now
			}
		}
		scope.block()
		// Apply the changes from scope back to editorState if needed
		val newValue = scope.value
		if (newValue.text != currentValue.text || newValue.selection != currentValue.selection) {
			applyTextFieldValue(newValue)
		}
	}

	private fun applyTextFieldValue(newValue: TextFieldValue) {
		val currentText = editorState.getAllText()
		val newText = newValue.text

		// Compare as CharSequence since both are CharSequence types
		if (!currentText.contentEquals(newText)) {
			// Replace all text (simple approach for now)
			val allRange = TextEditorRange(
				editorState.getOffsetAtCharacter(0),
				editorState.getOffsetAtCharacter(currentText.length)
			)
			// Pass AnnotatedString directly - replace() has an overload for it
			editorState.replace(allRange, newText)
		}

		// Update cursor/selection
		val selStart = newValue.selection.start
		val selEnd = newValue.selection.end

		if (selStart == selEnd) {
			val offset = editorState.getOffsetAtCharacter(selStart.coerceIn(0, newText.length))
			editorState.cursor.updatePosition(offset)
			editorState.selector.clearSelection()
		} else {
			val startOffset = editorState.getOffsetAtCharacter(selStart.coerceIn(0, newText.length))
			val endOffset = editorState.getOffsetAtCharacter(selEnd.coerceIn(0, newText.length))
			editorState.selector.updateSelection(startOffset, endOffset)
			editorState.cursor.updatePosition(endOffset)
		}
	}
}
