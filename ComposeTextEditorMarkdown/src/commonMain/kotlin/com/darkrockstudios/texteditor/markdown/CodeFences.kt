package com.darkrockstudios.texteditor.markdown

/**
 * The fence marker [line] opens or closes (a run of three or more backticks or
 * tildes after up to three spaces of indentation), or null for any other line.
 */
internal fun codeFenceMarker(line: String): String? {
	val trimmed = line.trimStart(' ')
	if (line.length - trimmed.length > 3) return null
	val first = trimmed.firstOrNull() ?: return null
	if (first != '`' && first != '~') return null
	val run = trimmed.takeWhile { it == first }
	if (run.length < 3) return null
	// A backtick fence's info string may not hold a backtick: such a line is a code span.
	if (first == '`' && trimmed.indexOf('`', run.length) != -1) return null
	return run
}

/**
 * Whether [line] closes a fence [open] opened: a run of its character at least as long,
 * followed only by spaces and tabs.
 */
internal fun closesFence(line: String, open: String): Boolean {
	val marker = codeFenceMarker(line) ?: return false
	return marker[0] == open[0] && marker.length >= open.length && line.trimStart(' ').substring(marker.length).isBlank()
}
