package com.darkrockstudios.texteditor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.coerceInto
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class TextEditorCursorState(
	private val editorState: TextEditorState
) {
	private var _position by mutableStateOf(CharLineOffset(0, 0))
	val position: CharLineOffset get() = _position

	private var _isVisible by mutableStateOf(true)
	val isVisible: Boolean get() = _isVisible

	var styles: Set<SpanStyle> = emptySet()
		private set(value) {
			field = value
			_stylesFlow.tryEmit(value)
		}

	private val _stylesFlow = MutableSharedFlow<Set<SpanStyle>>(
		extraBufferCapacity = 1,
		onBufferOverflow = BufferOverflow.DROP_OLDEST
	)
	val stylesFlow: SharedFlow<Set<SpanStyle>> = _stylesFlow

	private val _cursorPositionFlow = MutableSharedFlow<CharLineOffset>(
		extraBufferCapacity = 1,
		onBufferOverflow = BufferOverflow.DROP_OLDEST
	)
	val positionFlow: SharedFlow<CharLineOffset> = _cursorPositionFlow

	fun updatePosition(position: CharLineOffset, updateStyles: Boolean = true) {
		val newPosition = position.coerceInto(editorState.textLines)
		_position = newPosition
		_cursorPositionFlow.tryEmit(newPosition)

		// Update styles based on surrounding text (skip during text operations to preserve manually-set styles)
		if (updateStyles) {
			updateStylesFromPosition(newPosition)
		}

		editorState.scrollManager.ensureCursorVisible()
	}

	fun updateVisibility(visible: Boolean) {
		_isVisible = visible
	}

	fun setVisible() {
		_isVisible = true
	}

	fun toggleVisibility() {
		_isVisible = !_isVisible
	}

	fun addStyle(style: SpanStyle) {
		styles = styles + style
	}

	fun removeStyle(style: SpanStyle) {
		styles = styles - style
	}

	fun toggleStyle(style: SpanStyle) {
		if (styles.contains(style)) {
			removeStyle(style)
		} else {
			addStyle(style)
		}
	}

	fun clearStyles() {
		styles = emptySet()
	}

	private fun updateStylesFromPosition(position: CharLineOffset) {
		// If we're at the start of the line and it's not the first line,
		// check the end of the previous line
		if (position.char == 0 && position.line > 0) {
			val previousLine = position.line - 1
			val previousLineLength = editorState.textLines[previousLine].length - 1
			if (previousLineLength >= 0) {
				val previousPosition = CharLineOffset(previousLine, previousLineLength)
				styles = editorState.getSpanStylesAtPosition(previousPosition)
			}
		} else {
			// If we're not at the start of the text, look at the character before the cursor
			if (position.char > 0) {
				val beforePosition = position.copy(char = position.char - 1)
				styles = editorState.getSpanStylesAtPosition(beforePosition)
			} else {
				// If we're at the start of text or there's no style before us, clear styles
				styles = emptySet()
			}
		}
	}

	/**
	 * Applies the cursor's active styles to [text], unless [text] carries
	 * formatting of its own.
	 *
	 * Text that arrives already styled is inserted verbatim. A rich paste
	 * describes its own formatting completely, so letting the typing style at the
	 * insertion point apply as well would bold the unstyled runs of the pasted
	 * content. Plain text still picks up the cursor style, which is what makes
	 * typing and plain pastes continue the surrounding formatting.
	 */
	fun applyCursorStyle(text: AnnotatedString): AnnotatedString {
		if (styles.isEmpty() || text.spanStyles.isNotEmpty()) {
			return text
		}

		return buildAnnotatedString {
			styles.forEach { style ->
				pushStyle(style)
			}
			append(text)
			repeat(styles.size) {
				pop()
			}
		}
	}

	fun applyCursorStyle(string: String): AnnotatedString {
		if (styles.isEmpty()) {
			return AnnotatedString(string)
		}

		return buildAnnotatedString {
			styles.forEach { style ->
				pushStyle(style)
			}
			append(string)
			repeat(styles.size) {
				pop()
			}
		}
	}

	fun moveLeft(n: Int = 1) {
		val currentCharIndex = editorState.getCharacterIndex(position)
		val newCharIndex = maxOf(currentCharIndex - n, 0)
		updatePosition(editorState.getOffsetAtCharacter(newCharIndex))
	}

	fun moveRight(n: Int = 1) {
		val currentCharIndex = editorState.getCharacterIndex(position)
		val totalChars = editorState.textLines.sumOf { it.length + 1 } - 1
		val newCharIndex = minOf(currentCharIndex + n, totalChars)
		updatePosition(editorState.getOffsetAtCharacter(newCharIndex))
	}

	fun moveToLineStart() {
		val currentWrappedLine = editorState.getWrappedLine(position)
		updatePosition(position.copy(char = currentWrappedLine.wrapStartsAtIndex))
	}
}