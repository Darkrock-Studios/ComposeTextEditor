package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

/**
 * Converts an AnnotatedString to a markdown string, handling supported markdown styles.
 * Only converts styles that match our supported markdown styles, dropping any unsupported styles.
 *
 * @param links Hyperlinks to emit, as text ranges (in this string's character
 * offsets) paired with their destination URLs. Each becomes `[text](url)`,
 * with the markers enclosing any emphasis inside the range.
 */
fun AnnotatedString.toMarkdown(
	configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	links: List<Pair<IntRange, String>> = emptyList(),
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
	fun coalesceRuns(ranges: List<IntRange>): List<Pair<Int, Int>> {
		val sorted = ranges.sortedBy { it.first }
		val runs = mutableListOf<Pair<Int, Int>>()
		var runStart = sorted.first().first
		var runEnd = sorted.first().last + 1
		for (i in 1 until sorted.size) {
			val nextStart = sorted[i].first
			val nextEnd = sorted[i].last + 1
			if (nextStart <= runEnd) {
				// Touching or overlapping — extend the current run.
				if (nextEnd > runEnd) runEnd = nextEnd
			} else {
				runs.add(runStart to runEnd)
				runStart = nextStart
				runEnd = nextEnd
			}
		}
		runs.add(runStart to runEnd)
		return runs
	}

	val codeRuns = rangesByMarker.entries
		.firstOrNull { it.key.openMarker == "`" }
		?.let { coalesceRuns(it.value) }
		?: emptyList()

	val boundaries = mutableListOf<StyleBoundary>()
	rangesByMarker.forEach { (marker, ranges) ->
		var runs = coalesceRuns(ranges)
		if (marker.trimsWhitespaceEdges) {
			// CommonMark code spans take their content literally, so an emphasis run
			// crossing one would open its markers inside the backticks and corrupt on
			// the next import. The run splits around code spans; markdown cannot
			// express styled code, so the overlap itself is unrepresentable anyway.
			runs = runs.flatMap { run -> subtractRuns(run, codeRuns) }
		}
		runs.forEach { (rawStart, rawEnd) ->
			var start = rawStart
			var end = rawEnd
			// CommonMark emphasis cannot open before or close after whitespace:
			// "**word **" is literal asterisks to any parser, and re-importing it
			// escalates into escaped garbage. Shrink the markers onto the text.
			if (marker.trimsWhitespaceEdges) {
				while (start < end && text[start].isWhitespace()) start++
				while (end > start && text[end - 1].isWhitespace()) end--
			}
			if (start >= end) return@forEach
			val length = end - start
			boundaries.add(StyleBoundary(start, true, marker, length))
			boundaries.add(StyleBoundary(end, false, marker, length))
		}
	}

	// The destination is emitted verbatim inside `(...)`; a URL whose characters
	// would terminate or corrupt the destination gets the CommonMark
	// angle-bracket form instead.
	links.forEach { (range, url) ->
		val start = range.first.coerceAtLeast(0)
		val end = (range.last + 1).coerceAtMost(text.length)
		if (start >= end) return@forEach
		val marker = StyleMarkerPair(
			openMarker = "[",
			closeMarker = "](${markdownLinkDestination(url)})",
			isLink = true,
		)
		boundaries.add(StyleBoundary(start, true, marker, end - start))
		boundaries.add(StyleBoundary(end, false, marker, end - start))
	}

	// Sort boundaries:
	// 1. By index
	// 2. For same index, close markers come before open markers
	// 3. Link markers enclose inline emphasis sharing the boundary: a link
	//    opens before and closes after any other marker at the same index
	// 4. For same index and type, sort by run length (longer runs close first)
	boundaries.sortWith(compareBy<StyleBoundary> { it.index }
		.thenBy { it.isStart }
		.thenBy { if (it.marker.isLink == it.isStart) 0 else 1 }
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

/** Splits [run] into the segments left after removing every overlap with [holes]. */
private fun subtractRuns(
	run: Pair<Int, Int>,
	holes: List<Pair<Int, Int>>,
): List<Pair<Int, Int>> {
	var segments = listOf(run)
	holes.forEach { (holeStart, holeEnd) ->
		segments = segments.flatMap { (start, end) ->
			when {
				holeEnd <= start || holeStart >= end -> listOf(start to end)
				else -> buildList {
					if (start < holeStart) add(start to holeStart)
					if (holeEnd < end) add(holeEnd to end)
				}
			}
		}
	}
	return segments
}

private fun getStyleMarker(
	style: SpanStyle,
	config: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
): StyleMarkerPair? {
	// Legacy heading path for content styled without a HeaderSpanStyle span
	// (old documents, host apps writing raw font sizes). Checked first so a
	// bold style with an explicit size never reads as inline bold. Heading
	// lines carrying a span never reach here; export strips their baked style
	// before serializing the line.
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
		style.isBoldStyle -> StyleMarkerPair("**", "**")
		style.isItalicStyle -> StyleMarkerPair("*", "*")
		style.isCodeStyle -> StyleMarkerPair("`", "`")
		style.isStrikethroughStyle -> StyleMarkerPair("~~", "~~")
		else -> null
	}
}

/**
 * The destination as it appears inside `(...)`: angle-bracketed when it holds
 * a character CommonMark cannot take in a bare destination.
 */
private fun markdownLinkDestination(url: String): String =
	if (url.any { it == ')' || it == ' ' || it == '\n' }) "<$url>" else url

private data class StyleMarkerPair(
	val openMarker: String,
	val closeMarker: String,
	val isLink: Boolean = false,
) {
	/** Emphasis delimiters; code spans and headers tolerate edge whitespace. */
	val trimsWhitespaceEdges: Boolean
		get() = openMarker == "**" || openMarker == "*" || openMarker == "~~"
}

