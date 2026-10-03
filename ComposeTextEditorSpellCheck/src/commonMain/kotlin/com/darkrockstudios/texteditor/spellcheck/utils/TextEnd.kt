package com.darkrockstudios.texteditor.spellcheck.utils

import com.darkrockstudios.texteditor.CharLineOffset

/** Where this text ends once inserted at [start], on the line its last line break leads to. */
internal fun String.endWhenInsertedAt(start: CharLineOffset): CharLineOffset {
	val lastBreak = lastIndexOf('\n')
	if (lastBreak < 0) return start.copy(char = start.char + length)
	return CharLineOffset(start.line + count { it == '\n' }, length - lastBreak - 1)
}
