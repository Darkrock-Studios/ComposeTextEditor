package com.darkrockstudios.texteditor.input

import com.darkrockstudios.texteditor.cursor.CursorMetrics
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * The caret measured from the layout now, in the canvas's coordinates, or null before
 * the first layout. An input method asks as soon as the caret moves, before the next
 * frame draws it and records [TextEditorState.lastCursorMetrics].
 */
internal fun TextEditorState.measureCursorMetrics(): CursorMetrics? =
	if (lineOffsets.isEmpty()) null else calculateCursorPosition()
