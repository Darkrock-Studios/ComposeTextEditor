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

	val result = mutableListOf<AnnotatedString>()
	var currentIndex = 0

	this.text.split('\n').forEach { lineText ->
		val endIndex = currentIndex + lineText.length

		// Create a new AnnotatedString for this line
		val lineAnnotatedString = buildAnnotatedString {
			// Append the line text
			append(lineText)

			// Copy over all relevant spans for this line segment
			this@splitAnnotatedString.spanStyles.forEach { span ->
				val spanStart = span.start
				val spanEnd = span.end

				// Check if this span overlaps with our current line
				if (spanStart < endIndex && spanEnd > currentIndex) {
					// Calculate the overlapping region
					val overlapStart = maxOf(spanStart - currentIndex, 0)
					val overlapEnd = minOf(spanEnd - currentIndex, lineText.length)

					if (overlapStart < overlapEnd) {
						addStyle(span.item, overlapStart, overlapEnd)
					}
				}
			}

			// Copy over all relevant paragraph styles
			this@splitAnnotatedString.paragraphStyles.forEach { paragraph ->
				val paragraphStart = paragraph.start
				val paragraphEnd = paragraph.end

				// Check if this paragraph style overlaps with our current line
				if (paragraphStart < endIndex && paragraphEnd > currentIndex) {
					// Calculate the overlapping region
					val overlapStart = maxOf(paragraphStart - currentIndex, 0)
					val overlapEnd = minOf(paragraphEnd - currentIndex, lineText.length)

					if (overlapStart < overlapEnd) {
						addStyle(paragraph.item, overlapStart, overlapEnd)
					}
				}
			}
		}

		result.add(lineAnnotatedString)
		currentIndex = endIndex + 1 // +1 for the newline character
	}

	return result
}
