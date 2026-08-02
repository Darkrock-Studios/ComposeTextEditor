package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

/**
 * Converts an AnnotatedString to a markdown string, handling supported markdown styles.
 * Only converts styles that match our supported markdown styles, dropping any unsupported styles.
 */
fun AnnotatedString.toMarkdown(
	configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
): String {
	if (text.isEmpty()) return ""

	// Create a list of style boundaries (start and end points)
	data class StyleBoundary(
		val index: Int,
		val isStart: Boolean,
		val marker: StyleMarkerPair,
		val length: Int
	)

	// Group ranges by marker (not SpanStyle) so distinct-but-equivalent styles
	// — e.g. bold spans of different colors — still coalesce below.
	val rangesByMarker = LinkedHashMap<StyleMarkerPair, MutableList<IntRange>>()
	spanStyles.forEach { span ->
		val marker = getStyleMarker(span.item, configuration) ?: return@forEach
		if (span.end > span.start) {
			rangesByMarker.getOrPut(marker) { mutableListOf() }
				.add(span.start until span.end)
		}
	}

	// Coalesce touching/overlapping same-marker ranges into one run, so fragmented
	// spans emit a single marker pair: **E****n****d** -> **End**.
	val boundaries = mutableListOf<StyleBoundary>()
	rangesByMarker.forEach { (marker, ranges) ->
		ranges.sortBy { it.first }
		var runStart = ranges.first().first
		var runEnd = ranges.first().last + 1
		fun emit() {
			var start = runStart
			var end = runEnd
			// CommonMark emphasis cannot open before or close after whitespace:
			// "**word **" is literal asterisks to any parser, and re-importing it
			// escalates into escaped garbage. Shrink the markers onto the text.
			if (marker.trimsWhitespaceEdges) {
				while (start < end && text[start].isWhitespace()) start++
				while (end > start && text[end - 1].isWhitespace()) end--
			}
			if (start >= end) return
			val length = end - start
			boundaries.add(StyleBoundary(start, true, marker, length))
			boundaries.add(StyleBoundary(end, false, marker, length))
		}
		for (i in 1 until ranges.size) {
			val nextStart = ranges[i].first
			val nextEnd = ranges[i].last + 1
			if (nextStart <= runEnd) {
				// Touching or overlapping — extend the current run.
				if (nextEnd > runEnd) runEnd = nextEnd
			} else {
				emit()
				runStart = nextStart
				runEnd = nextEnd
			}
		}
		emit()
	}

	// Sort boundaries:
	// 1. By index
	// 2. For same index, close markers come before open markers
	// 3. For same index and type, sort by run length (longer runs close first)
	boundaries.sortWith(compareBy<StyleBoundary> { it.index }
		.thenBy { it.isStart }
		.thenByDescending { it.length })

	val result = StringBuilder()
	var currentIndex = 0
	var codeSpanDepth = 0

	boundaries.forEach { boundary ->
		// Add any text between the last position and this boundary. CommonMark code
		// spans take their content literally — backslash escapes do not apply — so we
		// must emit raw characters inside a code span, otherwise round-tripping doubles
		// the escapes on each export.
		while (currentIndex < boundary.index) {
			val ch = text[currentIndex]
			if (codeSpanDepth > 0) {
				result.append(ch)
			} else {
				result.append(escapeMarkdownChar(ch))
			}
			currentIndex++
		}

		// Add the appropriate marker
		if (boundary.isStart) {
			if (boundary.marker.openMarker.contains("#")) {
				// Ensure header starts on a new line
				if (!result.endsWith("\n") && result.isNotEmpty()) {
					result.append("\n")
				}
				result.append(boundary.marker.openMarker)
			} else {
				result.append(boundary.marker.openMarker)
			}
			if (boundary.marker.openMarker == "`") {
				codeSpanDepth++
			}
		} else {
			if (boundary.marker.closeMarker == "`") {
				codeSpanDepth--
			}
			result.append(boundary.marker.closeMarker)
			if (boundary.marker.closeMarker == "\n") {
				// Avoid duplicate newlines
				if (currentIndex < text.length && text[currentIndex] == '\n') {
					currentIndex++
				}
			}
		}
	}

	// Add any remaining text
	while (currentIndex < text.length) {
		result.append(escapeMarkdownChar(text[currentIndex]))
		currentIndex++
	}

	return escapeOrderedListMarkers(result.toString())
}

private fun getStyleMarker(
	style: SpanStyle,
	config: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
): StyleMarkerPair? {
	if (style.fontWeight == FontWeight.Bold && style.fontSize != TextUnit.Unspecified) {
		return when (style.fontSize.value) {
			config.header1Style.fontSize.value -> StyleMarkerPair("# ", "\n")
			config.header2Style.fontSize.value -> StyleMarkerPair("## ", "\n")
			config.header3Style.fontSize.value -> StyleMarkerPair("### ", "\n")
			config.header4Style.fontSize.value -> StyleMarkerPair("#### ", "\n")
			config.header5Style.fontSize.value -> StyleMarkerPair("##### ", "\n")
			config.header6Style.fontSize.value -> StyleMarkerPair("###### ", "\n")
			else -> null
		}
	}

	return when {
		style.matches(config.boldStyle) -> StyleMarkerPair("**", "**")
		style.matches(config.italicStyle) -> StyleMarkerPair("*", "*")
		style.matches(config.codeStyle) -> StyleMarkerPair("`", "`")
		style.matches(config.strikethroughStyle) -> StyleMarkerPair("~~", "~~")
		style.matches(config.linkStyle) -> StyleMarkerPair("[", "]()")
		else -> null
	}
}

private fun SpanStyle.matches(other: SpanStyle): Boolean {
	return when {
		this.fontWeight == FontWeight.Bold && other.fontWeight == FontWeight.Bold -> true
		this.fontStyle == FontStyle.Italic && other.fontStyle == FontStyle.Italic -> true
		this.fontFamily == FontFamily.Monospace && other.fontFamily == FontFamily.Monospace -> true
		this.textDecoration == TextDecoration.LineThrough && other.textDecoration == TextDecoration.LineThrough -> true
		this.textDecoration == TextDecoration.Underline && other.textDecoration == TextDecoration.Underline -> true
		else -> false
	}
}

private data class StyleMarkerPair(
	val openMarker: String,
	val closeMarker: String
) {
	/** Emphasis delimiters; code spans and headers tolerate edge whitespace. */
	val trimsWhitespaceEdges: Boolean
		get() = openMarker == "**" || openMarker == "*" || openMarker == "~~"
}

