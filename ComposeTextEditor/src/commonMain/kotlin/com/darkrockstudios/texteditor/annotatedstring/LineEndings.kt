package com.darkrockstudios.texteditor.annotatedstring

import androidx.compose.ui.text.AnnotatedString

/**
 * This text with `\r\n` and lone `\r` line endings turned into `\n`, the only line
 * separator the document model knows. Text that enters the editor passes through
 * here, so a carriage return never lands inside a line.
 */
internal fun String.normalizeLineEndings(): String {
	if (indexOf('\r') == -1) return this
	return replace("\r\n", "\n").replace('\r', '\n')
}

/**
 * [String.normalizeLineEndings] for styled text: each span and paragraph style is
 * moved onto the characters it covered. When a line ending changes, annotations
 * other than those two are dropped, as the document keeps none.
 */
internal fun AnnotatedString.normalizeLineEndings(): AnnotatedString {
	val source = text
	if (source.indexOf('\r') == -1) return this
	// The offset of each `\r` dropped from a `\r\n`, ascending.
	val dropped = buildList {
		var from = source.indexOf("\r\n")
		while (from != -1) {
			add(from)
			from = source.indexOf("\r\n", from + 2)
		}
	}

	fun map(offset: Int): Int {
		val found = dropped.binarySearch(offset)
		val droppedBefore = if (found >= 0) found else -found - 1
		return offset - droppedBefore
	}

	fun <T> List<AnnotatedString.Range<T>>.remap() =
		map { AnnotatedString.Range(it.item, map(it.start), map(it.end)) }

	return AnnotatedString(source.normalizeLineEndings(), spanStyles.remap(), paragraphStyles.remap())
}
