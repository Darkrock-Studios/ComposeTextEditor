package com.darkrockstudios.texteditor.state

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the undo history groups and keeps edits, set on [TextEditorState.undoSettings].
 *
 * @property maxSteps the most undo steps kept; past it the oldest go. At least 1.
 * @property typingPause a pause in typing this long ends the typing run, so what is
 * typed or deleted after it is an undo step of its own; [Duration.INFINITE] ends runs
 * by words alone. An input method's rewrites of the word it is composing are never
 * split, however long the pause.
 */
data class UndoSettings(
	val maxSteps: Int = 1000,
	val typingPause: Duration = 2.seconds,
) {
	init {
		require(maxSteps >= 1) { "the history must keep at least one step, was $maxSteps" }
		require(typingPause.isPositive()) { "the typing pause must be positive, was $typingPause" }
	}
}
