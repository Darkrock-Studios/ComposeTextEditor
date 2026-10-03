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
): String = toMarkdown(configuration, links, emptyList())

/**
 * [retiredConfigurations] are configurations the document was styled under
 * before [configuration]; a span still carrying one of their configured
 * styles is written as that style's marker, not as its colour or size.
 */
internal fun AnnotatedString.toMarkdown(
	configuration: MarkdownConfiguration,
	links: List<Pair<IntRange, String>>,
	retiredConfigurations: List<MarkdownConfiguration>,
): String {
	if (text.isEmpty()) return ""

	// Group ranges by marker, not SpanStyle, so distinct but equivalent styles
	// (bold spans of different colours) still coalesce below, and a style that
	// means several things (bold and red) contributes a range to each marker.
	val rangesByMarker = LinkedHashMap<StyleMarkerPair, MutableList<IntRange>>()
	spanStyles.forEach { span ->
		if (span.end <= span.start) return@forEach
		styleMarkers(span.item, configuration, retiredConfigurations).forEach { marker ->
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
	// after whitespace ("**word **" is literal asterisks to any parser, and
	// re-importing it escalates into escaped garbage), and the importer cannot
	// read an emphasis delimiter's own character escaped against it
	// ("**x \***"), so an edge character equal to the delimiter is left outside
	// the run, unstyled; the highlight pre-pass reads "==\=x==" as meant. Null
	// once nothing is left.
	fun trimRun(run: MarkerRun): MarkerRun? {
		if (!run.marker.trimsWhitespaceEdges) return run
		val delimiter = run.marker.openMarker[0].takeIf { it != '=' }
		var start = run.start
		var end = run.end
		while (start < end && (text[start].isWhitespace() || text[start] == delimiter)) start++
		while (end > start && (text[end - 1].isWhitespace() || text[end - 1] == delimiter)) end--
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
		if (marker.splitsAroundCode) {
			// CommonMark code spans take their content literally, so an emphasis run
			// or a tag crossing one would open its markers inside the backticks and
			// corrupt on the next import. The run splits around code spans; markdown
			// cannot express styled code, so the overlap itself is unrepresentable
			// anyway.
			runs = runs.flatMap { run -> subtractRuns(run, codeRuns) }
		}
		if (marker == DOUBLE_EQUALS_MARKER) {
			// The `==` pre-pass on import pairs delimiters within one line, so a
			// highlight over a line break is written as one per line.
			runs = runs.flatMap { (start, end) ->
				val breaks = (start until end).filter { text[it] == '\n' }
				val edges = listOf(start) + breaks.flatMap { listOf(it, it + 1) } + listOf(end)
				edges.chunked(2).map { (from, to) -> from to to }
			}
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

	// Prose is escaped only where a character would start markdown syntax in
	// its position, the emitter's own delimiters counted as neighbours; see
	// markdownEscapes.
	val escapes = markdownEscapes(
		text,
		linkTexts = links.map { it.first },
		markerBoundaries = ordered.flatMapTo(HashSet()) { listOf(it.start, it.end) },
	)

	// A `=` beside a `==` highlight delimiter the emitter writes must be escaped
	// or it merges with the delimiter and shifts the highlight on re-import:
	// [afterHighlightMarker] says one was just written, [nextMarker] is the
	// marker written right after the text up to [target]. A `==` in the text
	// itself is the positional pass's concern.
	var afterHighlightMarker = false
	fun appendTextTo(target: Int, nextMarker: StyleMarkerPair? = null) {
		// CommonMark code spans take their content literally (backslash escapes do
		// not apply), so raw characters are emitted inside a code span; escaping
		// them would double the escapes on each export.
		while (currentIndex < target) {
			val ch = text[currentIndex]
			val besideMarker = ch == '=' &&
				(afterHighlightMarker || (currentIndex + 1 == target && nextMarker == DOUBLE_EQUALS_MARKER))
			when {
				codeSpanDepth > 0 -> result.append(ch)
				besideMarker || escapes[currentIndex] -> result.append('\\').append(ch)
				else -> result.append(ch)
			}
			afterHighlightMarker = false
			currentIndex++
		}
	}

	while (true) {
		val nextClose = open.lastOrNull()?.end ?: Int.MAX_VALUE
		val nextOpen = ordered.getOrNull(nextRun)?.start ?: Int.MAX_VALUE
		if (nextClose == Int.MAX_VALUE && nextOpen == Int.MAX_VALUE) break

		// Close before open at a shared index, so `~~a~~*b*` never becomes `~~a*~~b*`.
		if (nextClose <= nextOpen) {
			appendTextTo(nextClose, open.last().marker)
			val closing = open.removeLast()
			if (closing.marker.closeMarker == "`") codeSpanDepth--
			result.append(closing.marker.closeMarker)
			afterHighlightMarker = closing.marker == DOUBLE_EQUALS_MARKER
			if (closing.marker.closeMarker == "\n") {
				// Avoid duplicate newlines
				if (currentIndex < text.length && text[currentIndex] == '\n') currentIndex++
			}
		} else {
			appendTextTo(nextOpen, ordered[nextRun].marker)
			val opening = ordered[nextRun]
			nextRun++
			if (opening.marker.isHeading) {
				// Ensure header starts on a new line
				if (!result.endsWith("\n") && result.isNotEmpty()) result.append("\n")
			}
			result.append(opening.marker.openMarker)
			afterHighlightMarker = opening.marker == DOUBLE_EQUALS_MARKER
			if (opening.marker.openMarker == "`") codeSpanDepth++
			open.addLast(opening)
		}
	}

	appendTextTo(text.length)

	return result.toString()
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

/**
 * Every marker pair [style] means: one per inline semantic it carries, HTML
 * tags first so they enclose the CommonMark delimiters they share a range with.
 */
private fun styleMarkers(
	style: SpanStyle,
	config: MarkdownConfiguration,
	retiredConfigurations: List<MarkdownConfiguration> = emptyList(),
): List<StyleMarkerPair> {
	// A configured style is written as the one marker it stands for: its colour,
	// size or background is how the configuration shows that marker, not
	// something the document says about the text. That holds for a
	// configuration the document was styled under earlier as well.
	configuredMarkers(style, config)?.let { return it }
	retiredConfigurations.forEach { retired -> configuredMarkers(style, retired)?.let { return it } }

	// Legacy heading path for content styled without a HeaderSpanStyle span
	// (old documents, host apps writing raw font sizes). Checked first so a
	// bold style with an explicit size never reads as inline bold. Heading
	// lines carrying a span never reach here; export strips their baked style
	// before serializing the line.
	if (style.fontWeight == FontWeight.Bold && style.fontSize != TextUnit.Unspecified) {
		val heading = when (style.fontSize.value) {
			config.header1Style.fontSize.value -> StyleMarkerPair("# ", "\n")
			config.header2Style.fontSize.value -> StyleMarkerPair("## ", "\n")
			config.header3Style.fontSize.value -> StyleMarkerPair("### ", "\n")
			config.header4Style.fontSize.value -> StyleMarkerPair("#### ", "\n")
			config.header5Style.fontSize.value -> StyleMarkerPair("##### ", "\n")
			config.header6Style.fontSize.value -> StyleMarkerPair("###### ", "\n")
			else -> null
		}
		if (heading != null) return listOf(heading)
	}

	return buildList {
		style.markdownColor?.let { add(StyleMarkerPair(colorSpanTag(it), "</span>")) }
		style.markdownFontSize(config)?.let { size ->
			fontSizeSpanTag(size)?.let { add(StyleMarkerPair(it, "</span>")) }
		}
		if (style.isUnderlineStyle) add(UNDERLINE_MARKER)
		if (style.isHighlightStyle) add(highlightMarker(config))
		if (style.isCodeStyle) add(CODE_MARKER)
		// Not isBoldStyle: a bold run with a size that is no heading's is bold
		// text at that size, and the heading branch above has already passed it.
		if (style.fontWeight == FontWeight.Bold) add(BOLD_MARKER)
		if (style.isItalicStyle) add(ITALIC_MARKER)
		if (style.isStrikethroughStyle) add(STRIKETHROUGH_MARKER)
	}
}

/**
 * The marker a style equal to one of [config]'s configured inline styles
 * stands for, or null when it equals none. The styles with a marker are
 * checked before the link, body and quote styles, which write nothing, so a
 * host whose link style is a plain underline still gets its underlines written.
 */
private fun configuredMarkers(style: SpanStyle, config: MarkdownConfiguration): List<StyleMarkerPair>? =
	when (style) {
		config.boldStyle -> listOf(BOLD_MARKER)
		config.italicStyle -> listOf(ITALIC_MARKER)
		config.codeStyle -> listOf(CODE_MARKER)
		config.strikethroughStyle -> listOf(STRIKETHROUGH_MARKER)
		config.underlineStyle -> listOf(UNDERLINE_MARKER)
		config.highlightStyle -> listOf(highlightMarker(config))
		config.defaultTextStyle, config.linkStyle, config.blockquoteStyle -> emptyList()
		else -> null
	}

private val BOLD_MARKER = StyleMarkerPair("**", "**")
private val ITALIC_MARKER = StyleMarkerPair("*", "*")
private val CODE_MARKER = StyleMarkerPair("`", "`")
private val STRIKETHROUGH_MARKER = StyleMarkerPair("~~", "~~")
private val UNDERLINE_MARKER = StyleMarkerPair("<u>", "</u>")
private val DOUBLE_EQUALS_MARKER = StyleMarkerPair("==", "==")
private val MARK_TAG_MARKER = StyleMarkerPair("<mark>", "</mark>")

private fun highlightMarker(config: MarkdownConfiguration): StyleMarkerPair =
	when (config.highlightSyntax) {
		HighlightSyntax.DOUBLE_EQUALS -> DOUBLE_EQUALS_MARKER
		HighlightSyntax.MARK_TAG -> MARK_TAG_MARKER
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
	/** Emphasis delimiters; code spans, tags and headers tolerate edge whitespace. */
	val trimsWhitespaceEdges: Boolean
		get() = openMarker == "**" || openMarker == "*" || openMarker == "~~" || openMarker == "=="

	/** A legacy font-size heading, which opens on a fresh line and closes with the line. */
	val isHeading: Boolean
		get() = closeMarker == "\n"

	/** Whether the run must stay outside code spans: everything but code itself and a heading. */
	val splitsAroundCode: Boolean
		get() = openMarker != "`" && !isHeading
}

