package com.darkrockstudios.texteditor.annotatedstring

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily

fun String.toAnnotatedString(fontFamily: FontFamily? = null): AnnotatedString {
	return if (fontFamily != null && fontFamily != FontFamily.Default) {
		buildAnnotatedString {
			append(this@toAnnotatedString)
			addStyle(SpanStyle(fontFamily = fontFamily), 0, length)
		}
	} else {
		AnnotatedString(this)
	}
}

internal fun AnnotatedString.subSequence(startIndex: Int = 0, endIndex: Int = length) =
	subSequence(startIndex = startIndex, endIndex = endIndex)

/**
 * Applies [styles] to this text, unless it carries formatting of its own.
 *
 * Text that arrives already styled is returned verbatim. A rich paste describes its
 * own formatting completely, so letting the style at the insertion point apply as
 * well would bold the unstyled runs of the pasted content. Plain text still picks up
 * the surrounding style, which is what makes typing and plain pastes continue the
 * formatting around them.
 */
internal fun AnnotatedString.withInheritedStyles(styles: Set<SpanStyle>): AnnotatedString {
	// Paragraph styles belong to the line, which already carries its own over its
	// whole length. Letting an inserted one through would nest a second paragraph
	// inside that range and split one logical line into several rendered ones.
	val content = AnnotatedString(text, spanStyles)
	if (styles.isEmpty() || content.spanStyles.isNotEmpty()) {
		return content
	}

	return buildAnnotatedString {
		styles.forEach { style ->
			pushStyle(style)
		}
		append(content)
		repeat(styles.size) {
			pop()
		}
	}
}

internal fun AnnotatedString.splitAnnotatedString(): List<AnnotatedString> {
	if (this.isEmpty()) return listOf(AnnotatedString(""))

	val lineTexts = text.split('\n')
	val lineStarts = IntArray(lineTexts.size)
	for (line in 1 until lineTexts.size) lineStarts[line] = lineStarts[line - 1] + lineTexts[line - 1].length + 1

	/** The line holding [offset]. */
	fun lineOf(offset: Int): Int {
		var low = 0
		var high = lineStarts.lastIndex
		while (low < high) {
			val mid = (low + high + 1) ushr 1
			if (lineStarts[mid] <= offset) low = mid else high = mid - 1
		}
		return low
	}

	// Each range goes only to the lines it overlaps, found by search, so the cost is
	// the lines plus the lines each range covers.
	fun <T> distribute(ranges: List<AnnotatedString.Range<T>>): Array<MutableList<AnnotatedString.Range<T>>?> {
		val byLine = arrayOfNulls<MutableList<AnnotatedString.Range<T>>>(lineTexts.size)
		ranges.forEach { range ->
			var line = lineOf(range.start.coerceAtLeast(0))
			while (line < lineTexts.size && lineStarts[line] < range.end) {
				val overlapStart = maxOf(range.start - lineStarts[line], 0)
				val overlapEnd = minOf(range.end - lineStarts[line], lineTexts[line].length)
				if (overlapStart < overlapEnd) {
					val onLine = byLine[line] ?: mutableListOf<AnnotatedString.Range<T>>().also { byLine[line] = it }
					onLine += AnnotatedString.Range(range.item, overlapStart, overlapEnd)
				}
				line++
			}
		}
		return byLine
	}

	val spansByLine = distribute(spanStyles)
	val paragraphsByLine = distribute(paragraphStyles)
	return lineTexts.mapIndexed { line, lineText ->
		AnnotatedString(lineText, spansByLine[line].orEmpty(), paragraphsByLine[line].orEmpty())
	}
}
