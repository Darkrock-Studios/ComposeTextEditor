package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange

/**
 * Extension function to segment the entire document into sentences.
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
	val lines = textLines
	if (lines.isEmpty()) return@sequence

	var sentenceStartLine = 0
	var sentenceStartChar = 0
	val sentenceBuilder = StringBuilder()

	// Track position within the accumulated sentence for multi-line handling
	var currentLineInSentence = 0
	var currentCharInLine = 0

	for ((lineIndex, line) in lines.withIndex()) {
		val text = line.text
		var charIndex = 0

		while (charIndex < text.length) {
			val char = text[charIndex]
			sentenceBuilder.append(char)
			currentCharInLine = charIndex

			if (isSentenceEndingPunctuation(char)) {
				val accumulated = sentenceBuilder.toString()
				if (isTrueSentenceEnd(text, charIndex, accumulated, lines, lineIndex)) {
					// Found a sentence end
					val sentenceText = accumulated.trim()
					if (sentenceText.isNotEmpty()) {
						yield(
							SentenceSegment(
								text = sentenceText,
								range = TextEditorRange(
									start = CharLineOffset(sentenceStartLine, sentenceStartChar),
									end = CharLineOffset(lineIndex, charIndex + 1)
								)
							)
						)
					}

					sentenceBuilder.clear()

					// Skip trailing whitespace to find next sentence start
					charIndex++
					while (charIndex < text.length && text[charIndex].isWhitespace()) {
						charIndex++
					}

					// Set new sentence start
					if (charIndex < text.length) {
						sentenceStartLine = lineIndex
						sentenceStartChar = charIndex
					} else {
						// Sentence starts on next line
						sentenceStartLine = lineIndex + 1
						sentenceStartChar = 0
					}
					continue
				}
			}
			charIndex++
		}

		// Add newline to sentence builder for multi-line sentences (preserves spacing)
		if (lineIndex < lines.lastIndex && sentenceBuilder.isNotEmpty()) {
			sentenceBuilder.append('\n')
		}
	}

	// Yield any remaining text as a final sentence
	val remainingText = sentenceBuilder.toString().trim()
	if (remainingText.isNotEmpty()) {
		val lastLine = lines.lastIndex
		val lastLineLength = lines[lastLine].text.length
		yield(
			SentenceSegment(
				text = remainingText,
				range = TextEditorRange(
					start = CharLineOffset(sentenceStartLine, sentenceStartChar),
					end = CharLineOffset(lastLine, lastLineLength)
				)
			)
		)
	}
}

/**
 * Find all sentences that intersect with the given range.
 */
fun TextEditorState.sentenceSegmentsInRange(range: TextEditorRange): List<SentenceSegment> {
	return sentenceSegments()
		.filter { it.range.intersects(range) }
		.toList()
}

/**
 * Find the sentence containing the given position.
 */
fun TextEditorState.findSentenceSegmentAt(position: CharLineOffset): SentenceSegment? {
	return sentenceSegments().find { segment ->
		position >= segment.range.start && position <= segment.range.end
	}
}

private fun isSentenceEndingPunctuation(char: Char): Boolean {
	return char == '.' || char == '?' || char == '!' || char == '…'
}

/**
 * Determines if a punctuation mark is a true sentence end,
 * handling abbreviations like "U.S.A.", "Mr.", "Dr.", etc.
 */
private fun isTrueSentenceEnd(
	currentLineText: String,
	position: Int,
	accumulatedSentence: String,
	allLines: List<androidx.compose.ui.text.AnnotatedString>,
	currentLineIndex: Int
): Boolean {
	val char = currentLineText[position]

	// Question marks and exclamation marks are always sentence ends
	if (char == '?' || char == '!') {
		return true
	}

	// Ellipsis character is a sentence end if followed by whitespace + capital
	if (char == '…') {
		val nextChar = getNextNonWhitespaceChar(currentLineText, position, allLines, currentLineIndex)
		return nextChar == null || nextChar.isUpperCase()
	}

	// For periods, check for abbreviations
	if (char == '.') {
		// Check for ellipsis pattern (...)
		if (isEllipsis(currentLineText, position)) {
			val nextChar = getNextNonWhitespaceChar(currentLineText, position + 2, allLines, currentLineIndex)
			return nextChar == null || nextChar.isUpperCase()
		}

		// Check for single-letter abbreviations (U.S.A.)
		if (isSingleLetterAbbreviation(currentLineText, position)) {
			return false
		}

		// Check common abbreviations
		val wordBeforePeriod = extractWordBeforePeriod(accumulatedSentence)
		if (isCommonAbbreviation(wordBeforePeriod)) {
			return false
		}

		// Check for number followed by period (ordinals in some languages)
		if (position > 0 && currentLineText[position - 1].isDigit()) {
			val nextChar = getNextNonWhitespaceChar(currentLineText, position, allLines, currentLineIndex)
			// If followed by lowercase, probably not sentence end
			if (nextChar?.isLowerCase() == true) {
				return false
			}
		}

		// Check what follows the period
		val nextChar = getNextNonWhitespaceChar(currentLineText, position, allLines, currentLineIndex)

		// If followed by nothing or uppercase letter, it's a sentence end
		// If followed by lowercase letter, likely an abbreviation
		return nextChar == null || nextChar.isUpperCase() || nextChar.isDigit() ||
				nextChar == '"' || nextChar == '\'' || nextChar == ')' || nextChar == ']' ||
				nextChar == '¿' || nextChar == '¡'
	}

	return false
}

/**
 * Gets the next non-whitespace character after the given position,
 * potentially looking into subsequent lines.
 */
private fun getNextNonWhitespaceChar(
	currentLineText: String,
	position: Int,
	allLines: List<androidx.compose.ui.text.AnnotatedString>,
	currentLineIndex: Int
): Char? {
	// Check rest of current line
	for (i in (position + 1) until currentLineText.length) {
		val c = currentLineText[i]
		if (!c.isWhitespace()) return c
	}

	// Check subsequent lines
	for (lineIdx in (currentLineIndex + 1) until allLines.size) {
		val lineText = allLines[lineIdx].text
		for (c in lineText) {
			if (!c.isWhitespace()) return c
		}
	}

	return null
}

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

private fun extractWordBeforePeriod(text: String): String {
	val trimmed = text.trimEnd('.', ' ', '\n', '\t')
	val lastSpace = trimmed.lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
	return if (lastSpace >= 0) {
		trimmed.substring(lastSpace + 1)
	} else {
		trimmed
	}
}

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
