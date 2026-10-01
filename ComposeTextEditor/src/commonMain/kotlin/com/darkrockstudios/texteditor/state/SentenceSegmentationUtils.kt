package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange

/**
 * Extension function to segment the entire document into sentences.
 *
 * A line is a paragraph, so a sentence never runs past the end of its line. Each
 * sentence's text is the line's text over its range, from its first non-whitespace
 * character to its last.
 *
 * Sentence boundaries are determined by:
 * - Period (.) followed by whitespace or end of text (but not in abbreviations)
 * - Question mark (?) and exclamation mark (!)
 * - Ellipsis (...) followed by capital letter
 *
 * Handles abbreviations for Latin scripts including:
 * - English: Mr., Mrs., Dr., Prof., Inc., Ltd., etc.
 * - French: M., Mme., Mlle.
 * - German: z.B., usw., bzw.
 * - Spanish: Sr., Sra., Dr.
 */
fun TextEditorState.sentenceSegments(): Sequence<SentenceSegment> = sequence {
	// The line list is immutable, so an edit during a scan cannot pull lines out from under it.
	for ((lineIndex, line) in textLines.withIndex()) {
		yieldAll(lineSentences(lineIndex, line.text))
	}
}

/**
 * Find all sentences that intersect with the given range, segmenting only its lines.
 */
fun TextEditorState.sentenceSegmentsInRange(range: TextEditorRange): List<SentenceSegment> {
	val lines = textLines
	val first = range.start.line.coerceAtLeast(0)
	val last = range.end.line.coerceAtMost(lines.lastIndex)
	return (first..last).flatMap { lineIndex ->
		lineSentences(lineIndex, lines[lineIndex].text).filter { it.range.intersects(range) }
	}
}

/**
 * Find the sentence containing the given position.
 */
fun TextEditorState.findSentenceSegmentAt(position: CharLineOffset): SentenceSegment? {
	val line = textLines.getOrNull(position.line) ?: return null
	return lineSentences(position.line, line.text).find { segment ->
		position >= segment.range.start && position <= segment.range.end
	}
}

/** The sentences of one line, [lineIndex], whose text is [text]. */
private fun lineSentences(lineIndex: Int, text: String): List<SentenceSegment> {
	val sentences = mutableListOf<SentenceSegment>()
	fun add(start: Int, end: Int) {
		var trimmedEnd = end
		while (trimmedEnd > start && text[trimmedEnd - 1].isWhitespace()) trimmedEnd--
		if (trimmedEnd > start) {
			sentences += SentenceSegment(
				text = text.substring(start, trimmedEnd),
				range = TextEditorRange(CharLineOffset(lineIndex, start), CharLineOffset(lineIndex, trimmedEnd)),
			)
		}
	}

	var sentenceStart = text.skipWhitespace(0)
	var charIndex = sentenceStart
	while (charIndex < text.length) {
		if (isSentenceEndingPunctuation(text[charIndex]) && isTrueSentenceEnd(text, charIndex, sentenceStart)) {
			add(sentenceStart, charIndex + 1)
			sentenceStart = text.skipWhitespace(charIndex + 1)
			charIndex = sentenceStart
		} else {
			charIndex++
		}
	}
	add(sentenceStart, text.length)
	return sentences
}

private fun String.skipWhitespace(from: Int): Int {
	var index = from
	while (index < length && this[index].isWhitespace()) index++
	return index
}

private fun isSentenceEndingPunctuation(char: Char): Boolean {
	return char == '.' || char == '?' || char == '!' || char == '…'
}

/**
 * Determines if a punctuation mark is a true sentence end,
 * handling abbreviations like "U.S.A.", "Mr.", "Dr.", etc.
 * [sentenceStart] is where the current sentence starts in [lineText].
 */
private fun isTrueSentenceEnd(lineText: String, position: Int, sentenceStart: Int): Boolean {
	val char = lineText[position]

	// Question marks and exclamation marks are always sentence ends
	if (char == '?' || char == '!') {
		return true
	}

	// Ellipsis character is a sentence end if followed by whitespace + capital
	if (char == '…') {
		val nextChar = nextNonWhitespaceChar(lineText, position)
		return nextChar == null || nextChar.isUpperCase()
	}

	// For periods, check for abbreviations
	if (char == '.') {
		// Check for ellipsis pattern (...)
		if (isEllipsis(lineText, position)) {
			val nextChar = nextNonWhitespaceChar(lineText, position)
			return nextChar == null || nextChar.isUpperCase()
		}

		// Check for single-letter abbreviations (U.S.A.)
		if (isSingleLetterAbbreviation(lineText, position)) {
			return false
		}

		// Check common abbreviations
		if (isCommonAbbreviation(wordBeforePeriod(lineText, position, sentenceStart))) {
			return false
		}

		val nextChar = nextNonWhitespaceChar(lineText, position)

		// Check for number followed by period (ordinals in some languages):
		// if followed by lowercase, probably not sentence end
		if (position > 0 && lineText[position - 1].isDigit() && nextChar?.isLowerCase() == true) {
			return false
		}

		// If followed by nothing or uppercase letter, it's a sentence end
		// If followed by lowercase letter, likely an abbreviation
		return nextChar == null || nextChar.isUpperCase() || nextChar.isDigit() ||
				nextChar == '"' || nextChar == '\'' || nextChar == ')' || nextChar == ']' ||
				nextChar == '¿' || nextChar == '¡'
	}

	return false
}

/** The next non-whitespace character after [position] on the line, or null at its end. */
private fun nextNonWhitespaceChar(lineText: String, position: Int): Char? =
	lineText.getOrNull(lineText.skipWhitespace(position + 1))

/**
 * Checks if the period at the given position is part of an ellipsis (...)
 */
private fun isEllipsis(text: String, position: Int): Boolean {
	if (position < 2) return false
	return text.getOrNull(position - 1) == '.' && text.getOrNull(position - 2) == '.'
}

/**
 * Checks if this is a single-letter abbreviation pattern like "U.S.A."
 */
private fun isSingleLetterAbbreviation(text: String, position: Int): Boolean {
	// Pattern: single letter before period
	if (position >= 1) {
		val prev = text[position - 1]
		// Check if it's a single uppercase letter preceded by start, whitespace, or another period
		if (prev.isUpperCase()) {
			val prevPrev = text.getOrNull(position - 2)
			if (prevPrev == null || prevPrev.isWhitespace() || prevPrev == '.' || prevPrev == '(') {
				// Check if followed by another letter (continuation of abbreviation)
				val next = text.getOrNull(position + 1)
				if (next?.isUpperCase() == true) {
					return true
				}
				// Check if this is the end of a multi-part abbreviation (e.g., "U.S.A." at end)
				if (prevPrev == '.' && position >= 3) {
					val thirdBack = text.getOrNull(position - 3)
					if (thirdBack?.isUpperCase() == true) {
						return true // Part of abbreviation like "U.S.A."
					}
				}
			}
		}
	}
	return false
}

/**
 * The word ending at the period at [position], without trailing periods or spaces, read
 * back no further than [sentenceStart]. Each of the two runs read back is cut off at
 * [ABBREVIATION_LOOKBACK] characters, and a word cut off is empty, so a run of periods
 * with no space scans in linear time.
 */
private fun wordBeforePeriod(lineText: String, position: Int, sentenceStart: Int): String {
	val trimLimit = maxOf(sentenceStart, position - ABBREVIATION_LOOKBACK)
	var end = position + 1
	while (end > trimLimit && (lineText[end - 1] == '.' || lineText[end - 1].isWhitespace())) end--
	val wordLimit = maxOf(sentenceStart, end - ABBREVIATION_LOOKBACK)
	var start = end
	while (start > wordLimit && !lineText[start - 1].isWhitespace()) start--
	if (start == wordLimit && start > sentenceStart && !lineText[start - 1].isWhitespace()) return ""
	return lineText.substring(start, end)
}

/** Longer than any abbreviation below, with its periods. */
private const val ABBREVIATION_LOOKBACK = 16

// Common abbreviations for Latin scripts
private val COMMON_ABBREVIATIONS = setOf(
	// English
	"Mr", "Mrs", "Ms", "Dr", "Prof", "Sr", "Jr",
	"vs", "etc", "al", "approx", "dept", "est", "govt", "misc",
	// English with periods embedded
	"e.g", "i.e", "cf", "viz",
	// Months
	"Jan", "Feb", "Mar", "Apr", "Jun", "Jul", "Aug", "Sep", "Sept", "Oct", "Nov", "Dec",
	// Days
	"Mon", "Tue", "Tues", "Wed", "Thu", "Thur", "Thurs", "Fri", "Sat", "Sun",
	// Business
	"Inc", "Ltd", "Corp", "Co", "LLC", "Ave", "Blvd", "St", "Rd",
	// French
	"M", "Mme", "Mlle", "Cie",
	// German
	"Nr", "Str",
	// Spanish
	"Ud", "Uds", "Srta",
	// Academic/Professional
	"Ph", "vol", "no", "pp", "ed", "eds", "rev", "trans",
	// Military/Government
	"Gen", "Col", "Maj", "Capt", "Lt", "Sgt", "Gov", "Sen", "Rep",
	// Other common
	"tel", "fax", "ext", "ref", "max", "min", "avg"
)

// Abbreviations that include periods (need special handling)
private val DOTTED_ABBREVIATIONS = setOf(
	"e.g", "i.e", "z.B", "usw", "bzw", "u.a", "d.h", "v.a"
)

private fun isCommonAbbreviation(word: String): Boolean {
	val normalized = word.trimEnd('.')
	return COMMON_ABBREVIATIONS.contains(normalized) ||
			COMMON_ABBREVIATIONS.contains(normalized.lowercase()) ||
			DOTTED_ABBREVIATIONS.contains(normalized) ||
			DOTTED_ABBREVIATIONS.contains(normalized.lowercase())
}
