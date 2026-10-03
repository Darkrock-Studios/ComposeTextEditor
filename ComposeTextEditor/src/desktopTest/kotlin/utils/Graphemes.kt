package utils

import org.jetbrains.skia.BreakIterator

/**
 * Whether [index] is a grapheme cluster boundary in this string, by the same ICU
 * character iterator `BasicTextField` uses on desktop. The JDK's iterator is not
 * used because the tests run on JDK 17, whose clusters predate emoji sequences.
 */
fun String.isGraphemeBoundary(index: Int): Boolean {
	if (index <= 0 || index >= length) return index == 0 || index == length
	return BreakIterator.makeCharacterInstance().use {
		it.setText(this)
		it.isBoundary(index)
	}
}

/** The index of the first unpaired surrogate, or null when every surrogate is paired. */
fun String.firstLoneSurrogate(): Int? {
	var i = 0
	while (i < length) {
		val ch = this[i]
		when {
			ch.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> i += 2
			ch.isSurrogate() -> return i
			else -> i++
		}
	}
	return null
}
