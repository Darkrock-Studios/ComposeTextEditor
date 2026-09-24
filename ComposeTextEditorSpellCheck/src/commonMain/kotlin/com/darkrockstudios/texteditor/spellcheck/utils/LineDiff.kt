package com.darkrockstudios.texteditor.spellcheck.utils

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange

/**
 * How the lines of [from] land in [to], seen as an untouched prefix and suffix around a
 * single band of changed lines. Several separate edits collapse into one band spanning
 * all of them, which over-reports the change but never misses any.
 *
 * Lines compare by text, so a style-only change counts as untouched.
 */
internal class LineDiff(
	from: List<AnnotatedString>,
	private val to: List<AnnotatedString>,
) {
	/** First changed line, the same index in both documents. */
	private val bandStart: Int

	/** End (exclusive) of the changed band in [from]. */
	private val fromBandEnd: Int

	/** End (exclusive) of the changed band in [to]. */
	private val toBandEnd: Int

	init {
		val limit = minOf(from.size, to.size)
		var prefix = 0
		while (prefix < limit && sameLine(from[prefix], to[prefix])) prefix++
		var suffix = 0
		while (suffix < limit - prefix &&
			sameLine(from[from.size - 1 - suffix], to[to.size - 1 - suffix])
		) suffix++

		bandStart = prefix
		fromBandEnd = from.size - suffix
		toBandEnd = to.size - suffix
	}

	/**
	 * [range] carried over to [to], or null when a change landed on or between its
	 * lines. Ranges wholly after the band shift by the lines it gained or lost.
	 */
	fun move(range: TextEditorRange): TextEditorRange? {
		if (range.start.line < fromBandEnd && range.end.line >= bandStart) return null
		if (range.start.line < bandStart) return range
		return range.shiftLines(toBandEnd - fromBandEnd)
	}

	/**
	 * [range] carried over to [to], widened to cover whole changed lines where a change
	 * landed on it. Null when every line it covered was deleted.
	 */
	fun cover(range: TextEditorRange): TextEditorRange? {
		move(range)?.let { return it }

		val start = if (range.start.line < bandStart) range.start else CharLineOffset(bandStart, 0)
		val end = if (range.end.line >= fromBandEnd) {
			range.end.copy(line = range.end.line + toBandEnd - fromBandEnd)
		} else {
			val lastLine = toBandEnd - 1
			if (lastLine < start.line) return null
			CharLineOffset(lastLine, to[lastLine].length)
		}
		return TextEditorRange(start, end)
	}

	private fun sameLine(a: AnnotatedString, b: AnnotatedString): Boolean = a === b || a.text == b.text
}

private fun TextEditorRange.shiftLines(delta: Int): TextEditorRange =
	if (delta == 0) this else TextEditorRange(
		start.copy(line = start.line + delta),
		end.copy(line = end.line + delta),
	)
