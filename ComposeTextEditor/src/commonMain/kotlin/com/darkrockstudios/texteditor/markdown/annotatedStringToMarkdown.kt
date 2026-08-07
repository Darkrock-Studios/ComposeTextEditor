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

	// Shrinks a run onto its text: CommonMark emphasis cannot open before or close
	// after whitespace — "**word **" is literal asterisks to any parser, and
	// re-importing it escalates into escaped garbage. Null once nothing is left.
	fun trimRun(run: MarkerRun): MarkerRun? {
		if (!run.marker.trimsWhitespaceEdges) return run
		var start = run.start
		var end = run.end
		while (start < end && text[start].isWhitespace()) start++
		while (end > start && text[end - 1].isWhitespace()) end--
		return if (start >= end) null else MarkerRun(start, end, run.marker)
	}

	// The destination is emitted verbatim inside `(...)`; a URL whose characters
	// would terminate or corrupt the destination gets the CommonMark
	// angle-bracket form instead.
	val linkRuns = links.mapNotNull { (range, url) ->
		val start = range.first.coerceAtLeast(0)
		val end = (range.last + 1).coerceAtMost(text.length)
		if (start >= end) return@mapNotNull null
		MarkerRun(
			start, end,
			StyleMarkerPair(
				openMarker = "[",
				closeMarker = "](${markdownLinkDestination(url)})",
				isLink = true,
			),
		)
	}

	val styleRuns = mutableListOf<MarkerRun>()
	rangesByMarker.forEach { (marker, ranges) ->
		var runs = coalesceRuns(ranges)
		if (marker.trimsWhitespaceEdges) {
			// CommonMark code spans take their content literally, so an emphasis run
			// crossing one would open its markers inside the backticks and corrupt on
			// the next import. The run splits around code spans; markdown cannot
			// express styled code, so the overlap itself is unrepresentable anyway.
			runs = runs.flatMap { run -> subtractRuns(run, codeRuns) }
		}
		runs.forEach { (start, end) ->
			trimRun(MarkerRun(start, end, marker))?.let { styleRuns += it }
		}
	}

	// Splitting can expose a whitespace edge the first trim could not see, so trim
	// the pieces too — then resolve again, because trimming a run that encloses a
	// link can walk its start past the link's and cross what had been nested. The
	// emitter below reads a stack, and only properly nested runs keep it honest.
	val resolved = resolveCrossings(
		resolveCrossings(styleRuns, linkRuns).mapNotNull(::trimRun),
		linkRuns,
	)

	// Outermost first at a shared start: longer runs, then links, so a link
	// encloses the emphasis inside it. Closing is LIFO off the stack below, which
	// makes every close the exact mirror of its open.
	val ordered = (resolved + linkRuns).sortedWith(
		compareBy<MarkerRun> { it.start }
			.thenByDescending { it.end }
			.thenBy { if (it.marker.isLink) 0 else 1 }
	)

	val result = StringBuilder()
	var currentIndex = 0
	var codeSpanDepth = 0
	var nextRun = 0
	val open = ArrayDeque<MarkerRun>()

	fun appendTextTo(target: Int) {
		// CommonMark code spans take their content literally — backslash escapes do
		// not apply — so we must emit raw characters inside a code span, otherwise
		// round-tripping doubles the escapes on each export.
		while (currentIndex < target) {
			val ch = text[currentIndex]
			if (codeSpanDepth > 0) result.append(ch) else result.append(escapeMarkdownChar(ch))
			currentIndex++
		}
	}

	while (true) {
		val nextClose = open.lastOrNull()?.end ?: Int.MAX_VALUE
		val nextOpen = ordered.getOrNull(nextRun)?.start ?: Int.MAX_VALUE
		if (nextClose == Int.MAX_VALUE && nextOpen == Int.MAX_VALUE) break

		// Close before open at a shared index, so `~~a~~*b*` never becomes `~~a*~~b*`.
		if (nextClose <= nextOpen) {
			appendTextTo(nextClose)
			val closing = open.removeLast()
			if (closing.marker.closeMarker == "`") codeSpanDepth--
			result.append(closing.marker.closeMarker)
			if (closing.marker.closeMarker == "\n") {
				// Avoid duplicate newlines
				if (currentIndex < text.length && text[currentIndex] == '\n') currentIndex++
			}
		} else {
			appendTextTo(nextOpen)
			val opening = ordered[nextRun]
			nextRun++
			if (opening.marker.openMarker.contains("#")) {
				// Ensure header starts on a new line
				if (!result.endsWith("\n") && result.isNotEmpty()) result.append("\n")
			}
			result.append(opening.marker.openMarker)
			if (opening.marker.openMarker == "`") codeSpanDepth++
			open.addLast(opening)
		}
	}

	appendTextTo(text.length)

	return escapeOrderedListMarkers(result.toString())
}

private data class MarkerRun(val start: Int, val end: Int, val marker: StyleMarkerPair)

/**
 * Splits partially overlapping runs so every pair is either disjoint or nested.
 * Markdown delimiters only nest: a crossing pair serializes as `*a ~~b*~~`, which
 * no parser reads back as emphasis — the markers survive into the text as literal
 * characters and the styling is lost. A crossing is resolved by cutting the
 * earlier run at the later one's start, which keeps both styles over exactly the
 * text they covered. [fixed] runs are never cut: a split link would emit its
 * destination twice.
 */
private fun resolveCrossings(runs: List<MarkerRun>, fixed: List<MarkerRun>): List<MarkerRun> {
	val out = runs.toMutableList()
	// Every split resolves one crossing without creating any, so the loop is
	// bounded by the crossings present at entry.
	var guard = 512
	while (guard-- > 0) {
		val cut = out.indices.firstNotNullOfOrNull { i ->
			val run = out[i]
			(out + fixed).firstNotNullOfOrNull { other ->
				when {
					run.start < other.start && other.start < run.end && run.end < other.end ->
						i to other.start

					other.start < run.start && run.start < other.end && other.end < run.end ->
						i to other.end

					else -> null
				}
			}
		} ?: break
		val (index, at) = cut
		val run = out[index]
		out[index] = MarkerRun(run.start, at, run.marker)
		out.add(MarkerRun(at, run.end, run.marker))
	}
	return out
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

