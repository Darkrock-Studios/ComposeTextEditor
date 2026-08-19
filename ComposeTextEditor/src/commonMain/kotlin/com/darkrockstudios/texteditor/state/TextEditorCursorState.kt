package com.darkrockstudios.texteditor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.annotatedstring.withInheritedStyles
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

	/**
	 * The document revision [styles] was last derived from. [updatePosition] skips the
	 * recompute when neither the caret nor this changed since then. Starts as the
	 * revision seen at construction, matching the empty [styles].
	 */
	private var stylesDerivedFrom: DocumentSnapshot = editorState.workingContent

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
		val oldPosition = _position
		val newPosition = position.coerceInto(editorState.textLines)
		_position = newPosition
		_cursorPositionFlow.tryEmit(newPosition)

		// Recompute the typing style from the surrounding text only when something it
		// derives from changed: the caret moved, or an edit altered the text under it
		// (an edit can leave the caret offset untouched, e.g. styling a selection).
		// Focus handlers and pointer taps re-assert the position the caret already
		// has; recomputing then would wipe styles toggled onto the caret
		// ([toggleStyle]) before the user gets to type with them.
		// (Skip during text operations to preserve manually-set styles still applies
		// via [updateStyles].)
		if (updateStyles &&
			(newPosition != oldPosition || editorState.workingContent !== stylesDerivedFrom)
		) {
			updateStylesFromPosition(newPosition)
		}

		editorState.requestCursorVisible()
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

	/**
	 * Recomputes the typing style from the text around the caret. Needed after the
	 * document is replaced wholesale, which leaves the caret where it is and so never
	 * goes through [updatePosition].
	 */
	internal fun refreshStyles() {
		updateStylesFromPosition(_position)
	}

	private fun updateStylesFromPosition(position: CharLineOffset) {
		stylesDerivedFrom = editorState.workingContent
		styles = editorState.getSpanStylesForEditAt(position)
	}

	/** @see withInheritedStyles */
	fun applyCursorStyle(text: AnnotatedString): AnnotatedString = text.withInheritedStyles(styles)

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