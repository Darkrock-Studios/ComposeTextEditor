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

/**
 * Which row the caret draws on when its position is a wrap offset: [Downstream]
 * puts it at the start of the row after the wrap, [Upstream] at the end of the row
 * before it. Anywhere else the two name the same row.
 */
enum class CaretAffinity { Downstream, Upstream }

class TextEditorCursorState(
	private val editorState: TextEditorState
) {
	private var _position by mutableStateOf(CharLineOffset(0, 0))
	val position: CharLineOffset get() = _position

	private var _affinity by mutableStateOf(CaretAffinity.Downstream)

	/** The row the caret draws on at a wrap offset; every move resets it to [CaretAffinity.Downstream]. */
	val affinity: CaretAffinity get() = _affinity

	private var _isVisible by mutableStateOf(true)
	val isVisible: Boolean get() = _isVisible

	var styles: Set<SpanStyle> = emptySet()
		private set(value) {
			field = value
			_stylesFlow.tryEmit(value)
		}

	/**
	 * Whether [styles] were set by hand ([addStyle] and friends) rather than derived
	 * from the text. While set, [updatePosition] keeps them unless the caret moves.
	 * Edits clear it through [releaseManualStyles].
	 */
	private var stylesSetManually = false

	/**
	 * The x a run of vertical moves aims for, so passing through a short row does not
	 * pull the caret left for the rest of the run. Tied to the document it was taken
	 * in: any edit ends the run, as does any other caret movement.
	 */
	private var verticalGoal: VerticalGoal? = null

	private class VerticalGoal(val x: Float, val document: DocumentSnapshot)

	/** The goal x of the vertical run under way, or null when there is none. */
	internal val verticalGoalX: Float?
		get() = verticalGoal?.takeIf { it.document === editorState.content }?.x

	internal fun rememberVerticalGoalX(x: Float) {
		verticalGoal = VerticalGoal(x, editorState.content)
	}

	internal fun forgetVerticalGoal() {
		verticalGoal = null
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

	/**
	 * Moves the caret to [position], clamped into the document. Not snapped to a
	 * grapheme boundary: typing a ZWJ sequence one code point at a time in front of
	 * an emoji passes through a caret inside the joined cluster, so each movement
	 * and hit-test path snaps for itself.
	 */
	fun updatePosition(position: CharLineOffset, updateStyles: Boolean = true) =
		updatePosition(position, CaretAffinity.Downstream, updateStyles)

	/** [updatePosition], then the row the caret draws on: End on a wrapped row places it [CaretAffinity.Upstream]. */
	internal fun updatePosition(position: CharLineOffset, affinity: CaretAffinity) =
		updatePosition(position, affinity, updateStyles = true)

	private fun updatePosition(position: CharLineOffset, affinity: CaretAffinity, updateStyles: Boolean) {
		val oldPosition = _position
		val newPosition = position.coerceInto(editorState.textLines)
		verticalGoal = null
		// Before the scroll request below, which reads the row the caret is on.
		_affinity = affinity
		_position = newPosition
		_cursorPositionFlow.tryEmit(newPosition)

		// Focus handlers and pointer taps re-assert the position the caret already
		// has; recomputing then would wipe styles toggled onto the caret before the
		// user gets to type with them.
		if (updateStyles && (newPosition != oldPosition || !stylesSetManually)) {
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
		stylesSetManually = true
		styles = styles + style
	}

	fun removeStyle(style: SpanStyle) {
		stylesSetManually = true
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
		stylesSetManually = true
		styles = emptySet()
	}

	/**
	 * Lets the next [updatePosition] re-derive [styles] even if the caret stays put.
	 * For edits, which can change the text under an unmoved caret.
	 */
	internal fun releaseManualStyles() {
		stylesSetManually = false
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
		stylesSetManually = false
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

	/** Moves the caret back [n] grapheme clusters, a line break counting as one. */
	fun moveLeft(n: Int = 1) {
		val lines = editorState.textLines
		var (line, char) = position.coerceInto(lines)
		var remaining = n
		while (remaining > 0) {
			if (char > 0) {
				graphemeCursor(lines[line].text).use { breaks ->
					while (remaining > 0 && char > 0) {
						char = breaks.preceding(char).coerceAtLeast(0)
						remaining--
					}
				}
			} else if (line > 0) {
				line--
				char = lines[line].length
				remaining--
			} else {
				break
			}
		}
		updatePosition(CharLineOffset(line, char))
	}

	/** Moves the caret forward [n] grapheme clusters, a line break counting as one. */
	fun moveRight(n: Int = 1) {
		val lines = editorState.textLines
		var (line, char) = position.coerceInto(lines)
		var remaining = n
		while (remaining > 0) {
			val length = lines[line].length
			if (char < length) {
				graphemeCursor(lines[line].text).use { breaks ->
					while (remaining > 0 && char < length) {
						char = breaks.following(char).let { if (it == BreakCursor.DONE) length else it }
						remaining--
					}
				}
			} else if (line < lines.lastIndex) {
				line++
				char = 0
				remaining--
			} else {
				break
			}
		}
		updatePosition(CharLineOffset(line, char))
	}

	fun moveToLineStart() {
		val wrapStart = editorState.lineOffsets.getOrNull(editorState.cursorRowIndex())?.wrapStartsAtIndex ?: 0
		updatePosition(position.copy(char = wrapStart))
	}
}