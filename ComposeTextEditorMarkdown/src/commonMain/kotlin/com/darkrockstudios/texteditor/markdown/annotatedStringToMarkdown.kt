package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import com.darkrockstudios.texteditor.RichTextStyles

/**
 * Converts an AnnotatedString to a markdown string, handling supported markdown styles.
 * Only converts styles that match our supported markdown styles, dropping any unsupported styles.
 *
 * @param configuration The syntax choices to write in.
 * @param links Hyperlinks to emit, as text ranges (in this string's character
 * offsets) paired with their destination URLs. Each becomes `[text](url)`,
 * with the markers enclosing any emphasis inside the range.
 * @param styles The styles a span is recognised by; an editor's own are on
 * `TextEditorState.richTextStyles`.
 *
 * A string carries no heading blocks, so a run bold at one of [styles]' heading
 * sizes is written as that heading, on a line of its own. Emphasis whose
 * delimiters could not open or close where it stands (`**Note:**text`) is written
 * as `<em>`, `<strong>` or `<del>`.
 */
fun AnnotatedString.toMarkdown(
	configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	links: List<Pair<IntRange, String>> = emptyList(),
	styles: RichTextStyles = RichTextStyles.DEFAULT,
): String = toMarkdown(configuration, links, styles, emptyList(), headingsBySize = true)

/**
 * [retiredStyles] are the style configurations the document was styled under
 * before [styles], oldest first; a span still carrying one of their configured
 * styles is written as that style's marker (the most recent one's), not as its
 * colour or size. Without [headingsBySize] a run bold at a heading's size is bold
 * text at that size: an editor's headings come from its heading blocks.
 */
internal fun AnnotatedString.toMarkdown(
	configuration: MarkdownConfiguration,
	links: List<Pair<IntRange, String>>,
	styles: RichTextStyles,
	retiredStyles: List<RichTextStyles>,
	headingsBySize: Boolean,
): String {
	if (text.isEmpty()) return ""

	// Group ranges by marker, not SpanStyle, so distinct but equivalent styles
	// (bold spans of different colours) still coalesce below, and a style that
	// means several things (bold and red) contributes a range to each marker.
	val rangesByMarker = LinkedHashMap<StyleMarkerPair, MutableList<IntRange>>()
	spanStyles.forEach { span ->
		if (span.end <= span.start) return@forEach
		styleMarkers(span.item, styles, configuration, retiredStyles, headingsBySize).forEach { marker ->
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
	// re-importing it escalates into escaped garbage). Null once nothing is left.
	fun trimRun(run: MarkerRun): MarkerRun? {
		if (!run.marker.trimsWhitespaceEdges) return run
		var start = run.start
		var end = run.end
		while (start < end && text[start].isWhitespace()) start++
		while (end > start && text[end - 1].isWhitespace()) end--
		return if (start >= end) null else MarkerRun(start, end, run.marker)
	}

	// An indent is written as entities at the line's very start, where import reads it
	// (see leadingIndents), so no marker opens or closes inside one: a run starting in
	// an indent starts after it, and one ending in a later line's indent closes at the
	// end of the line before. The indent's own styling is not kept.
	val indents = leadingIndents(text)
	fun outsideIndents(run: MarkerRun): MarkerRun? {
		var start = run.start
		while (start < run.end && indents[start]) start++
		var end = run.end
		if (end > start && indents[end - 1]) end = text.lastIndexOf('\n', end - 1).coerceAtLeast(start)
		return if (start >= end) null else MarkerRun(start, end, run.marker)
	}

	// The destination is emitted verbatim inside `(...)`; a URL whose characters
	// would terminate or corrupt the destination gets the CommonMark
	// angle-bracket form instead.
	val linkRuns = links.mapNotNull { (range, url) ->
		val start = range.first.coerceAtLeast(0)
		val end = (range.last + 1).coerceAtMost(text.length)
		if (start >= end) return@mapNotNull null
		outsideIndents(
			MarkerRun(
				start, end,
				StyleMarkerPair(
					openMarker = "[",
					closeMarker = "](${markdownLinkDestination(url)})",
					isLink = true,
				),
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
		if (marker == CODE_MARKER) {
			// A code span takes link syntax literally, so code splits at the edges of a
			// link inside it, and the link encloses its piece: `` `a`[`b`](url)`c` ``.
			val linkEdges = linkRuns.flatMap { listOf(it.start, it.end) }
			runs = runs.flatMap { (start, end) ->
				(listOf(start) + linkEdges.filter { it in start + 1 until end }.distinct().sorted() + end).zipWithNext()
			}
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
			outsideIndents(MarkerRun(start, end, marker))?.let(::trimRun)?.let { styleRuns += it }
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
	var codeSpanClose = ""
	var nextRun = 0
	val open = ArrayDeque<MarkerRun>()
	// Where each emphasis run's delimiters were written, to check them once the text is in.
	val delimiters = ArrayList<WrittenDelimiters>()
	val openDelimiters = ArrayDeque<WrittenDelimiters?>()

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
				indents[currentIndex] -> result.append(leadingIndentEntity(ch))
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
			openDelimiters.removeLast()?.closeStart = result.length
			if (closing.marker == CODE_MARKER) {
				codeSpanDepth--
				result.append(codeSpanClose)
			} else {
				result.append(closing.marker.closeMarker)
			}
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
			openDelimiters.addLast(opening.marker.htmlTag?.let { WrittenDelimiters(opening.marker, result.length).also(delimiters::add) })
			if (opening.marker == CODE_MARKER) {
				val open = codeSpanOpener(text.subSequence(opening.start, opening.end))
				result.append(open)
				codeSpanClose = open.reversed()
				codeSpanDepth++
			} else {
				result.append(opening.marker.openMarker)
			}
			afterHighlightMarker = opening.marker == DOUBLE_EQUALS_MARKER
			open.addLast(opening)
		}
	}

	appendTextTo(text.length)

	return withUnflankedEmphasisAsTags(result.toString(), delimiters)
}

/** Where a run's delimiters start in the written markdown: its opener at [openStart], its closer at [closeStart]. */
private class WrittenDelimiters(val marker: StyleMarkerPair, val openStart: Int) {
	var closeStart = -1
}

/**
 * [markdown] with each emphasis run in [delimiters] whose delimiters cannot open and
 * close where they stand written as its HTML tags instead. CommonMark reads a delimiter
 * by the characters beside it, so emphasis that starts or ends on punctuation with a
 * letter outside (`**Note:**text`) is literal asterisks to any renderer; the tags read
 * the same anywhere. Delimiters of one character written side by side are one run to
 * the parser, read by what is beside the whole run. An opener right after a closer
 * pairs as meant only as `*` and `**` together, a run of three that can both open and
 * close (`**a***b*`); after any other closer it is written as tags, as is a run with
 * an escaped delimiter of its own character beside it, which the parser does not
 * pair. Strikethrough beside a `*` delimiter is written as tags too: the parser misreads
 * `~~` and `*` written side by side. A tag ends a run of delimiters it was part of, which
 * changes how the rest of the run reads, so the delimiters are read again until no more
 * are swapped.
 */
private fun withUnflankedEmphasisAsTags(markdown: String, delimiters: List<WrittenDelimiters>): String {
	val swaps = HashSet<WrittenDelimiters>()
	do {
		// Read as written so far: a swapped delimiter is a tag, punctuation that ends a run.
		val delimiterAt = HashMap<Int, Char>()
		val closerEnds = HashSet<Int>()
		val tagAt = HashSet<Int>()
		delimiters.forEach { written ->
			val opener = written.openStart until written.openStart + written.marker.openMarker.length
			val closer = written.closeStart until written.closeStart + written.marker.closeMarker.length
			if (written in swaps) {
				tagAt += opener
				tagAt += closer
			} else {
				(opener + closer).forEach { delimiterAt[it] = written.marker.openMarker[0] }
				closerEnds += closer.last + 1
			}
		}

		fun charAt(index: Int): Char? = if (index in tagAt) '<' else markdown.getOrNull(index)

		/** The delimiter run that the delimiter at [start] until [end] is part of. */
		fun runAround(start: Int, end: Int): IntRange {
			val char = markdown[start]
			var from = start
			while (delimiterAt[from - 1] == char) from--
			var to = end
			while (delimiterAt[to] == char) to++
			return from until to
		}

		fun IntRange.leftFlanking() = isLeftFlanking(charAt(first - 1), charAt(last + 1))
		fun IntRange.rightFlanking() = isRightFlanking(charAt(first - 1), charAt(last + 1))

		// The parser does not pair a delimiter beside an escaped one of its own character (`**x \***`).
		fun IntRange.besideEscapedOwn(): Boolean {
			val char = markdown[first]
			return (charAt(first - 1) == char && charAt(first - 2) == '\\') ||
				(charAt(last + 1) == '\\' && charAt(last + 2) == char)
		}

		fun IntRange.besideStar() = delimiterAt[first - 1] == '*' || delimiterAt[last + 1] == '*'

		val more = delimiters.filter { written ->
			if (written in swaps) return@filter false
			val opener = runAround(written.openStart, written.openStart + written.marker.openMarker.length)
			val closer = runAround(written.closeStart, written.closeStart + written.marker.closeMarker.length)
			if (written.marker == STRIKETHROUGH_MARKER && (opener.besideStar() || closer.besideStar())) return@filter true
			val afterCloser = written.openStart in closerEnds && delimiterAt[written.openStart - 1] == markdown[written.openStart]
			val pairsAfterCloser = !afterCloser || (opener.count() == 3 && opener.rightFlanking())
			!pairsAfterCloser || !opener.leftFlanking() || !closer.rightFlanking() ||
				opener.besideEscapedOwn() || closer.besideEscapedOwn()
		}.ifEmpty {
			// Each pair can open and close where it stands; the parser may still pair a
			// delimiter with another run's (`***a* *b*` by the rule of 3).
			val matches = delimiterMatches(delimiterAt) { run -> run.leftFlanking() to run.rightFlanking() }
			delimiters.filter { written ->
				if (written in swaps) return@filter false
				val positions = (written.openStart until written.openStart + written.marker.openMarker.length) +
					(written.closeStart until written.closeStart + written.marker.closeMarker.length)
				val match = matches[positions.first()]
				match == null || positions.any { matches[it] != match } || matches.values.count { it == match } != positions.size
			}
		}
		swaps += more
	} while (more.isNotEmpty())
	if (swaps.isEmpty()) return markdown
	val edits = swaps.flatMap { written ->
		val tag = written.marker.htmlTag!!
		listOf(
			Triple(written.openStart, written.marker.openMarker.length, "<$tag>"),
			Triple(written.closeStart, written.marker.closeMarker.length, "</$tag>"),
		)
	}.sortedBy { it.first }
	val out = StringBuilder(markdown.length + edits.size * 4)
	var from = 0
	edits.forEach { (at, length, replacement) ->
		out.append(markdown, from, at).append(replacement)
		from = at + length
	}
	return out.append(markdown, from, markdown.length).toString()
}

/**
 * Which delimiters CommonMark's emphasis pass pairs, by position: the positions of one
 * match share a number. [delimiterAt] holds each delimiter character by position, side
 * by side ones of a character one run, and [flanking] says whether a run can open and
 * close. A `~` run pairs only with one of its own length, as GFM has it.
 */
private fun delimiterMatches(delimiterAt: Map<Int, Char>, flanking: (IntRange) -> Pair<Boolean, Boolean>): Map<Int, Int> {
	class Run(val char: Char, val start: Int, val length: Int, val canOpen: Boolean, val canClose: Boolean) {
		var left = start
		var right = start + length
		var active = true
		val remaining get() = right - left
	}

	val runs = ArrayList<Run>()
	var index = 0
	val positions = delimiterAt.keys.sorted()
	while (index < positions.size) {
		val start = positions[index]
		val char = delimiterAt.getValue(start)
		var end = start + 1
		while (delimiterAt[end] == char) end++
		val (canOpen, canClose) = flanking(start until end)
		runs += Run(char, start, end - start, canOpen, canClose)
		while (index < positions.size && positions[index] < end) index++
	}
	val matches = HashMap<Int, Int>()
	var match = 0
	runs.forEachIndexed { closerIndex, closer ->
		if (!closer.canClose) return@forEachIndexed
		while (closer.remaining > 0) {
			val openerIndex = (closerIndex - 1 downTo 0).firstOrNull { i ->
				val opener = runs[i]
				opener.active && opener.canOpen && opener.char == closer.char && opener.remaining > 0 && when (closer.char) {
					'~' -> opener.remaining == closer.remaining
					else -> !((opener.canClose || closer.canOpen) && (opener.length + closer.length) % 3 == 0 &&
						!(opener.length % 3 == 0 && closer.length % 3 == 0))
				}
			} ?: break
			val opener = runs[openerIndex]
			val used = if (closer.char == '~' || (opener.remaining >= 2 && closer.remaining >= 2)) minOf(2, closer.remaining) else 1
			match++
			for (at in opener.right - used until opener.right) matches[at] = match
			for (at in closer.left until closer.left + used) matches[at] = match
			opener.right -= used
			closer.left += used
			for (between in openerIndex + 1 until closerIndex) runs[between].active = false
		}
		if (!closer.canOpen) closer.active = false
	}
	return matches
}

/**
 * What a code span holding [code] opens with, its closer the same reversed: a backtick
 * string longer than any in the code, then a space when the code starts or ends with a
 * backtick or has a space at both ends, since CommonMark takes one off each end.
 */
private fun codeSpanOpener(code: CharSequence): String {
	var longest = 0
	var run = 0
	code.forEach { c ->
		run = if (c == '`') run + 1 else 0
		longest = maxOf(longest, run)
	}
	val ticks = "`".repeat(longest + 1)
	val padded = code.isNotEmpty() && (
		code.first() == '`' || code.last() == '`' ||
			(code.length >= 2 && code.first() == ' ' && code.last() == ' ' && code.any { it != ' ' })
		)
	return if (padded) "$ticks " else ticks
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
	config: RichTextStyles,
	syntax: MarkdownConfiguration,
	retiredStyles: List<RichTextStyles>,
	headingsBySize: Boolean,
): List<StyleMarkerPair> {
	// A configured style is written as the one marker it stands for: its colour,
	// size or background is how the configuration shows that marker, not
	// something the document says about the text. That holds for a
	// configuration the document was styled under earlier as well.
	configuredMarkers(style, config, syntax)?.let { return it }
	retiredStyles.asReversed().forEach { retired -> configuredMarkers(style, retired, syntax)?.let { return it } }

	// Checked first so a heading's bold never also reads as inline bold.
	if (headingsBySize && style.fontWeight == FontWeight.Bold && style.fontSize != TextUnit.Unspecified) {
		val heading = when (style.fontSize) {
			config.header1Style.fontSize -> StyleMarkerPair("# ", "\n")
			config.header2Style.fontSize -> StyleMarkerPair("## ", "\n")
			config.header3Style.fontSize -> StyleMarkerPair("### ", "\n")
			config.header4Style.fontSize -> StyleMarkerPair("#### ", "\n")
			config.header5Style.fontSize -> StyleMarkerPair("##### ", "\n")
			config.header6Style.fontSize -> StyleMarkerPair("###### ", "\n")
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
		if (style.isHighlightStyle) add(highlightMarker(syntax))
		if (style.isCodeStyle) add(CODE_MARKER)
		// Bold at any size, the size written as its own tag above unless it is the body size.
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
private fun configuredMarkers(style: SpanStyle, config: RichTextStyles, syntax: MarkdownConfiguration): List<StyleMarkerPair>? =
	when (style) {
		config.boldStyle -> listOf(BOLD_MARKER)
		config.italicStyle -> listOf(ITALIC_MARKER)
		config.codeStyle -> listOf(CODE_MARKER)
		config.strikethroughStyle -> listOf(STRIKETHROUGH_MARKER)
		config.underlineStyle -> listOf(UNDERLINE_MARKER)
		config.highlightStyle -> listOf(highlightMarker(syntax))
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

private fun highlightMarker(syntax: MarkdownConfiguration): StyleMarkerPair =
	when (syntax.highlightSyntax) {
		HighlightSyntax.DOUBLE_EQUALS -> DOUBLE_EQUALS_MARKER
		HighlightSyntax.MARK_TAG -> MARK_TAG_MARKER
	}

/**
 * The destination as it appears inside `(...)`: angle-bracketed when it holds
 * a character CommonMark cannot take in a bare destination, and escaped where
 * import would decode it (see [escapeLinkDestination]).
 */
private fun markdownLinkDestination(url: String): String {
	val angled = url.any { it == ')' || it == ' ' || it == '\n' }
	val escaped = escapeLinkDestination(url, angled)
	return if (angled) "<$escaped>" else escaped
}

private data class StyleMarkerPair(
	val openMarker: String,
	val closeMarker: String,
	val isLink: Boolean = false,
) {
	/** Emphasis delimiters; code spans, tags and headers tolerate edge whitespace. */
	val trimsWhitespaceEdges: Boolean
		get() = openMarker == "**" || openMarker == "*" || openMarker == "~~" || openMarker == "=="

	/** A font-size heading, which opens on a fresh line and closes with the line. */
	val isHeading: Boolean
		get() = closeMarker == "\n"

	/** The HTML tag an emphasis delimiter pair is written as where it cannot flank its text. */
	val htmlTag: String?
		get() = when (openMarker) {
			"*" -> "em"
			"**" -> "strong"
			"~~" -> "del"
			else -> null
		}

	/** Whether the run must stay outside code spans: everything but code itself and a heading. */
	val splitsAroundCode: Boolean
		get() = openMarker != "`" && !isHeading
}

