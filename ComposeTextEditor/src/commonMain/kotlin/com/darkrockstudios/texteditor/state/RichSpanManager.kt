package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle

class RichSpanManager(
	private val state: TextEditorState
) {
	/**
	 * The revision's spans keyed by line. Copy-on-write: every mutation publishes a new
	 * index rather than editing this one in place, so the sets handed out by
	 * [getAllRichSpans] and the lists the queries below read can never be modified
	 * underneath a reader.
	 */
	private val index: SpanIndex get() = state.workingContent.spanIndex

	/**
	 * Every rich span in the document, as an immutable snapshot. Safe to hold onto and
	 * to iterate from any thread; it will not reflect later edits. Built from the
	 * per-line index on first read per revision, so a reader that wants one line's
	 * spans should ask for that line.
	 */
	fun getAllRichSpans(): Set<RichSpan> = state.workingContent.richSpans

	/**
	 * Every span anchored to (starting on) [line]. Reads the per-line index rather
	 * than scanning the whole set, so the line-block queries stay cheap on documents
	 * carrying thousands of spans.
	 */
	internal fun getRichSpansStartingOn(line: Int): List<RichSpan> =
		spansOnLine(line).filter { it.range.start.line == line }

	/** Every span covering [line], from the snapshot's per-line index. */
	private fun spansOnLine(line: Int): List<RichSpan> = index.spansOn(line)

	internal fun addRichSpan(range: TextEditorRange, style: RichSpanStyle) {
		addRichSpans(listOf(RichSpan(range, style)))
	}

	/**
	 * Adds a span whose range was computed against an earlier revision of the
	 * document, coerced onto the lines that exist now. Undo restoration and rich
	 * paste replay recorded offsets; the document they land in may have shifted.
	 */
	internal fun addRichSpanClamped(range: TextEditorRange, style: RichSpanStyle) {
		clampSpanToLines(RichSpan(range, style), state.textLines)?.let { addRichSpans(listOf(it)) }
	}

	internal fun addRichSpan(start: CharLineOffset, end: CharLineOffset, style: RichSpanStyle) {
		addRichSpan(TextEditorRange(start, end), style)
	}

	/**
	 * Adds every span in [newSpans] in one publish, for bulk callers like an import.
	 * Same-line duplicates of a line-anchored style fold into one span, so a line
	 * carries one gutter marker of a kind.
	 */
	internal fun addRichSpans(newSpans: Collection<RichSpan>) {
		if (newSpans.isEmpty()) return
		state.setSpanIndex(index.plus(newSpans), newSpans)
	}

	/**
	 * Adds every span in [newSpans] in one publish, each coerced onto the current
	 * document like the re-anchoring after an edit does, and returns the spans as
	 * they landed. Batched overlay callers (spell check, find) compute ranges
	 * asynchronously, so a range can arrive pointing past a document that shrank in
	 * the meantime; unclamped, such a span is invisible, uncollectable by range
	 * queries, and still counted by span scans.
	 */
	internal fun addRichSpansClamped(newSpans: Collection<RichSpan>): List<RichSpan> {
		val lines = state.textLines
		val clamped = newSpans.mapNotNull { clampSpanToLines(it, lines) }
		addRichSpans(clamped)
		return clamped
	}

	internal fun removeRichSpan(start: CharLineOffset, end: CharLineOffset, style: RichSpanStyle) {
		removeRichSpan(RichSpan(TextEditorRange(start, end), style))
	}

	internal fun removeRichSpan(span: RichSpan) {
		removeRichSpans(listOf(span))
	}

	/** Drops every span in [doomed] in one publish. */
	internal fun removeRichSpans(doomed: Collection<RichSpan>) {
		if (doomed.isEmpty()) return
		state.setSpanIndex(index.minus(doomed), doomed)
	}

	fun getSpansForLineWrap(lineWrap: LineWrap): List<RichSpan> {
		// The layout pass runs this once per visual line; the per-line index keeps
		// each call proportional to the spans on that line, not in the document.
		return spansOnLine(lineWrap.line).filter { it.intersectsWith(lineWrap) }
	}

	/**
	 * Re-anchors the spans across [operation], which the lines already reflect: the
	 * spans on the edit's pre-edit lines and the loose ones (crossing a line break, or
	 * beyond the lines) are run through the operation's transform, folded, clamped
	 * onto the new lines and written back over the edit's post-edit lines; every other
	 * line's spans move with their chunk of the index, which is spliced to the new
	 * line count. Span-only operations move no text, so every span keeps its range
	 * verbatim.
	 */
	fun updateSpans(operation: TextEditOperation, metadata: OperationMetadata?) {
		val (first, last) = when (operation) {
			is TextEditOperation.Insert -> operation.position.line to operation.position.line
			is TextEditOperation.Delete -> operation.range.start.line to operation.range.end.line
			is TextEditOperation.Replace -> operation.range.start.line to operation.range.end.line
			is TextEditOperation.StyleSpan,
			is TextEditOperation.RichSpan,
			is TextEditOperation.LineBlock -> return
		}
		val before = index
		val oldCount = before.lineCount
		val from = first.coerceIn(0, oldCount)
		val to = (last + 1).coerceIn(from, oldCount)

		val transformed = LinkedHashSet<RichSpan>()
		var candidates = 0
		fun transform(span: RichSpan) = span.range.run {
			candidates++
			when (operation) {
				is TextEditOperation.Insert -> handleInsert(operation, transformed, span)
				is TextEditOperation.Delete -> handleDelete(metadata, operation, transformed, span)
				is TextEditOperation.Replace -> handleReplace(operation, transformed, span)
				else -> error("unreachable")
			}
		}
		for (line in from until to) before.ownSpansOn(line).forEach(::transform)
		before.loose.forEach(::transform)

		val results = clampAllToDocument(mergeLineAnchoredDuplicates(transformed))
		val replaced = state.textLines.size - (oldCount - (to - from))
		val perLine = arrayOfNulls<MutableList<LineSpan>>(replaced)
		val loose = LinkedHashSet<RichSpan>()
		val elsewhere = ArrayList<RichSpan>()
		for (span in results) {
			val line = span.range.start.line - from
			when {
				span.range.start.line != span.range.end.line -> loose += span
				line in 0 until replaced -> {
					val onLine = perLine[line] ?: ArrayList<LineSpan>(1).also { perLine[line] = it }
					val lineSpan = LineSpan(span.range.start.char, span.range.end.char, span.style)
					if (lineSpan !in onLine) onLine += lineSpan
				}
				else -> elsewhere += span
			}
		}
		var after = before.splice(from, to, List(replaced) { perLine[it] ?: emptyList() }, loose)
		var landedFirst = from
		var landedLast = from + replaced - 1
		if (elsewhere.isNotEmpty()) {
			// A loose span clamped onto a line lands where the edit did not reach.
			after = after.plus(elsewhere)
			for (span in elsewhere) {
				landedFirst = minOf(landedFirst, span.range.start.line)
				landedLast = maxOf(landedLast, span.range.end.line)
			}
		}
		// Which lines carry which spans changes only when a span died, landed, or a line came or went.
		val structureChanged = results.size != candidates || replaced != to - from || elsewhere.isNotEmpty()
		state.setSpanIndex(after, landedFirst, landedLast, spansChanged = structureChanged)
	}

	/**
	 * Coerces every transformed span onto the already-mutated document. Handler
	 * arithmetic near line joins can land a hair past a shortened line; a span
	 * shrunk to nothing dies unless its sticky marker renders on empty lines.
	 */
	private fun clampAllToDocument(updatedSpans: Set<RichSpan>): Set<RichSpan> {
		val lines = state.textLines
		return updatedSpans.mapNotNullTo(LinkedHashSet()) { clampSpanToLines(it, lines) }
	}

	/**
	 * Collapses any same-line duplicates of line-anchored (sticky-at-start) styles,
	 * bullet, blockquote and so on, into a single span covering the union range.
	 * A multi-line merge that joins two same-style line-anchored lines naturally
	 * produces two adjacent spans on the joined line; this fold gives us the
	 * "one gutter marker per line" invariant those styles assume.
	 */
	private fun mergeLineAnchoredDuplicates(spans: Set<RichSpan>): Set<RichSpan> {
		val (anchored, others) = spans.partition { it.style.stickyAtStart }
		val positionOrder = compareBy<CharLineOffset>({ it.line }, { it.char })
		val merged = anchored
			.groupBy { it.style to it.range.start.line }
			.map { (key, group) ->
				if (group.size == 1) group.first() else {
					// The union must respect multi-line members: collapsing everything
					// onto the start line invents columns past that line's end.
					RichSpan(
						range = TextEditorRange(
							start = group.minOfWith(positionOrder) { it.range.start },
							end = group.maxOfWith(positionOrder) { it.range.end },
						),
						style = key.first,
					)
				}
			}
		return (others + merged).toCollection(LinkedHashSet())
	}

	private fun TextEditorRange.handleInsert(
		operation: TextEditOperation.Insert,
		updatedSpans: MutableSet<RichSpan>,
		span: RichSpan
	) {
		if (operation.text.text == "\n") {
			// Special handling for newline insertion
			when {
				// Case 1: Newline inserted before the span
				operation.position.line < start.line ||
						(operation.position.line == start.line && operation.position.char <= start.char) -> {
					// Move entire span to new line
					val newStart = operation.transformOffset(start, state)
					val newEnd = operation.transformOffset(end, state)
					updatedSpans.add(
						span.copy(
							range = TextEditorRange(
								start = newStart,
								end = newEnd
							)
						)
					)
					// The paragraph left above, now empty, keeps the format it had.
					if (span.style.boundToParagraph && operation.position.line == start.line) {
						val above = CharLineOffset(start.line, 0)
						updatedSpans.add(span.copy(range = TextEditorRange(above, above)))
					}
				}

				// Case 2: Newline inserted inside the span
				operation.position.line == start.line &&
						operation.position.char > start.char &&
						operation.position.char < end.char -> {
					// Calculate remaining length in original span after split point
					val remainingLength = end.char - operation.position.char

					// First part remains on original line
					updatedSpans.add(
						span.copy(
							range = span.range.copy(
								end = CharLineOffset(
									start.line,
									operation.position.char
								)
							)
						)
					)

					// Second part moves to new line
					// Only spans the remaining length from the original span
					updatedSpans.add(
						span.copy(
							span.range.copy(
								start = CharLineOffset(start.line + 1, 0),
								end = CharLineOffset(
									start.line + 1,
									remainingLength
								)
							)
						)
					)
				}

				// Case 3: Newline inserted after the span
				operation.position.line > start.line ||
						(operation.position.line == start.line && operation.position.char >= end.char) -> {
					// Keep span as is
					updatedSpans.add(span)
					// A paragraph's format carries to the paragraph Enter makes after it, as
					// word processors carry it; the new line is empty, so its span starts empty.
					if (span.style.boundToParagraph && operation.position.line == start.line) {
						val next = CharLineOffset(start.line + 1, 0)
						updatedSpans.add(span.copy(range = TextEditorRange(next, next)))
					}
				}
			}
		} else {
			// Regular text insertion. For styles flagged stickyAtStart (line-anchored
			// gutter markers), an insert at the span's exact start boundary keeps the
			// start put so the new chars land inside the span. Without this, typing
			// the first character into an empty bullet/blockquote line shifts the
			// span past the line content and the gutter marker visually disappears.
			val insertAtStart = operation.position.line == start.line &&
					operation.position.char == start.char
			// Where the inserted text ends: past a line break, a marker the insert lands in
			// front of follows its line's text down, as Enter at a line's start moves it.
			// An empty line's marker stays on the first line, and a paragraph's format
			// stays there too, with a copy on the paragraph's text below.
			val insertEnd = operation.transformOffset(operation.position, state)
			val followsDown = span.style.stickyAtStart && !span.style.boundToParagraph && end != start
			val newStart = when {
				followsDown && insertAtStart && insertEnd.line > start.line -> CharLineOffset(insertEnd.line, 0)
				span.style.stickyAtStart && insertAtStart -> start
				else -> operation.transformOffset(start, state)
			}
			val transformedEnd = operation.transformOffset(end, state)
			// A line-anchored marker stays on its line when the insert brings more lines
			// (the edit pipeline continues a block onto them): the clamp trims the end
			// to the line.
			val newEnd = if (span.style.stickyAtStart && transformedEnd.line > newStart.line) {
				CharLineOffset(newStart.line, Int.MAX_VALUE)
			} else {
				transformedEnd
			}
			updatedSpans.add(
				span.copy(
					range = span.range.copy(
						start = newStart,
						end = newEnd
					),
				)
			)
			// Lines landing at a paragraph's start leave its format on the first of them
			// and on the paragraph's own text below, as Enter there does.
			if (span.style.boundToParagraph && insertAtStart && insertEnd.line > start.line) {
				updatedSpans.add(
					span.copy(range = TextEditorRange(CharLineOffset(insertEnd.line, 0), CharLineOffset(insertEnd.line, Int.MAX_VALUE)))
				)
			}
		}
	}

	private fun handleReplace(
		operation: TextEditOperation.Replace,
		updatedSpans: MutableSet<RichSpan>,
		span: RichSpan
	) {
		val landed = replaced(operation, span)
		updatedSpans += landed
		// Lines landing at a paragraph's start leave its format on the first of them and
		// on the paragraph's own text after the last, as an insert or Enter there does.
		if (span.style.boundToParagraph && landed.isNotEmpty() && span.range.start == operation.range.start) {
			val last = operation.newTextEnd.line
			if (last > operation.range.start.line) {
				listOf(operation.range.start.line, last).forEach { line ->
					updatedSpans += span.copy(range = TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, Int.MAX_VALUE)))
				}
			}
		}
	}

	/** Where [span] lands after [operation], or nothing when the replace takes it. */
	private fun replaced(operation: TextEditOperation.Replace, span: RichSpan): List<RichSpan> = buildList {
		val newEnd = operation.newTextEnd
		val breaks = newEnd.line > operation.range.start.line

		when {
			// A line-anchored marker on a line a replace breaks after its start stays on
			// that line, the first; the edit pipeline continues a block onto the lines
			// after it. One at the replace's start follows its line's text down.
			span.style.stickyAtStart && operation.range.isSingleLine() && breaks &&
					span.range.start.line == operation.range.start.line &&
					operation.range.start.char > span.range.start.char -> {
				val line = span.range.start.line
				add(span.copy(range = TextEditorRange(span.range.start, CharLineOffset(line, Int.MAX_VALUE))))
			}

			// A line-anchored marker covers its line whatever a replace of nothing adds
			// to its first line, as an insert there leaves it: its start stays at
			// column 0 and an end at the insert point takes the text in. Text holding
			// a line break takes the branches below.
			span.style.stickyAtStart && operation.range.start == operation.range.end &&
					!breaks && operation.range.start.line == span.range.start.line &&
					operation.range.start.char >= span.range.start.char &&
					(span.range.end.line > span.range.start.line || operation.range.start.char <= span.range.end.char) -> {
				val shift = newEnd.char - operation.range.start.char
				val end = if (span.range.end.line == span.range.start.line) {
					span.range.end.copy(char = span.range.end.char + shift)
				} else {
					span.range.end
				}
				add(span.copy(range = TextEditorRange(span.range.start, end)))
			}

			// Span ends before replacement - keep as is. An empty line's marker at the
			// start of a replace that takes whole lines goes with its line.
			span.range.end.line < operation.range.start.line ||
					(span.range.end.line == operation.range.start.line &&
							span.range.end.char <= operation.range.start.char) -> {
				val lineTaken = span.style.stickyAtStart && span.range.start == span.range.end &&
					span.range.start == operation.range.start && !operation.range.isSingleLine()
				if (!lineTaken) add(span)
			}

			// Span starts after replacement - adjust position
			span.range.start.line > operation.range.end.line ||
					(span.range.start.line == operation.range.end.line &&
							span.range.start.char >= operation.range.end.char) -> {
				// Calculate position adjustment
				val lineDiff = newEnd.line - operation.range.end.line
				val charDiff = if (span.range.start.line == operation.range.end.line) {
					newEnd.char - operation.range.end.char
				} else 0

				// Adjust span positions
				var newStart = CharLineOffset(span.range.start.line + lineDiff, span.range.start.char + charDiff)
				if ((span.style.stickyAtStart || span.style is BlockSpanStyle) && newStart.char != 0) {
					// A line-anchored marker (or placeholder block) whose text the replacement
					// puts after new text stays at its line's start when that line is the
					// replacement's own (a line break in it, or whole lines replaced up to the
					// marker). Joined onto the kept head of an earlier line, it goes, as a line
					// joined by a delete does, unless that line is the same kind of item.
					val ownLine = breaks || operation.range.start.char == 0
					if (!ownLine && !receivingLineHasSame(span, newStart.line)) return@buildList
					newStart = CharLineOffset(newStart.line, 0)
				}
				// A column moves only on the replacement's last line.
				val newEndPos = CharLineOffset(
					span.range.end.line + lineDiff,
					span.range.end.char + if (span.range.end.line == operation.range.end.line) charDiff else 0,
				)
				add(span.copy(range = TextEditorRange(newStart, newEndPos)))
			}

			// Span overlaps with replacement
			else -> {
				// If span starts before replacement
				if (span.range.start.line < operation.range.start.line ||
					(span.range.start.line == operation.range.start.line &&
							span.range.start.char < operation.range.start.char)
				) {

					// If span also ends after replacement, bridge across
					if (span.range.end.line > operation.range.end.line ||
						(span.range.end.line == operation.range.end.line &&
								span.range.end.char > operation.range.end.char)
					) {
						add(
							span.copy(
								range = TextEditorRange(
									span.range.start,
									CharLineOffset(
										newEnd.line + (span.range.end.line - operation.range.end.line),
										if (span.range.end.line == operation.range.end.line)
											newEnd.char + (span.range.end.char - operation.range.end.char)
										else span.range.end.char
									)
								)
							)
						)
					} else {
						// Span ends within replacement - truncate at replacement start
						add(
							span.copy(range = TextEditorRange(span.range.start, operation.range.start))
						)
					}
				} else if (span.range.end.line > operation.range.end.line ||
					(span.range.end.line == operation.range.end.line &&
							span.range.end.char > operation.range.end.char)
				) {
					// Span starts within replacement but ends after - preserve the end
					// portion. A line-anchored marker re-anchors to the start of the
					// line its tail survives on; without the sticky start, replacing a
					// selection that begins at the item start detaches the gutter marker.
					// Its tail joined onto the kept head of an earlier line, it goes, as
					// in the branch above.
					val newStart = if (span.style.stickyAtStart) {
						val ownLine = breaks || operation.range.start.char == 0
						if (!ownLine && !receivingLineHasSame(span, newEnd.line)) return@buildList
						CharLineOffset(newEnd.line, 0)
					} else {
						newEnd
					}
					add(
						span.copy(
							range = TextEditorRange(
								newStart,
								CharLineOffset(
									newEnd.line + (span.range.end.line - operation.range.end.line),
									if (span.range.end.line == operation.range.end.line)
										newEnd.char + (span.range.end.char - operation.range.end.char)
									else span.range.end.char
								)
							)
						)
					)
				} else if (span.style.stickyAtStart && operation.range.isSingleLine()) {
					// A line-anchored marker whose text is replaced within its own line
					// survives: that is editing the item, not deleting it, and it stays on
					// the first line when the new text breaks it. A multi-line replacement
					// removed the marker's line, so the marker goes with it.
					val end = if (newEnd.line > operation.range.start.line) CharLineOffset(operation.range.start.line, Int.MAX_VALUE) else newEnd
					add(
						span.copy(
							range = TextEditorRange(
								CharLineOffset(operation.range.start.line, 0),
								end,
							)
						)
					)
				}
				// Else span is entirely within replacement - it gets removed
			}
		}
	}

	/**
	 * Whether [line], which a join keeps (its index is the same before and after the
	 * edit), already starts with a marker of [span]'s style.
	 */
	private fun receivingLineHasSame(span: RichSpan, line: Int): Boolean = spansOnLine(line).any { other ->
		other.style == span.style && other.range.start.line == line && other.range.start.char == 0
	}

	private fun TextEditorRange.handleDelete(
		metadata: OperationMetadata?,
		operation: TextEditOperation.Delete,
		updatedSpans: MutableSet<RichSpan>,
		span: RichSpan
	) {
		// updateSpans rebuilds the touched lines from what these handlers contribute, so
		// a handler that adds nothing erases the span. With no metadata to transform
		// against, a stale position beats deleting the span outright.
		if (metadata == null) {
			updatedSpans.add(span)
			return
		}

		fun addTransformed(newStart: CharLineOffset, newEnd: CharLineOffset) {
			val lineAnchored = span.style.stickyAtStart || span.style is BlockSpanStyle
			if (lineAnchored && newStart.char != 0) {
				// The marker was pulled off column 0: its line was consumed by a join.
				// It survives only onto a receiving line that is already the same kind
				// of item (rejoining split halves); otherwise the receiving line keeps
				// its own identity and the marker dies with its line. The receiving line
				// is the join's own line, at the same index before and after the edit.
				if (!receivingLineHasSame(span, newStart.line)) return
			}
			if (newStart == newEnd) {
				// Emptied within its own line, an item survives as an empty item; a
				// marker consumed by a multi-line delete goes with its deleted line.
				if (!span.style.rendersWhenEmpty || !operation.range.isSingleLine()) return
			}
			updatedSpans.add(span.copy(range = TextEditorRange(newStart, newEnd)))
		}

		if (metadata.deletedText?.text == "\n") {
			// A pure newline delete joins two lines: nothing on the first line moves,
			// the second line's content slides onto the join point, and everything
			// below is pulled up one line.
			val deletionPoint = operation.range.start
			val nextLineStart = operation.range.end

			fun joinOffset(offset: CharLineOffset): CharLineOffset = when {
				offset.line < nextLineStart.line -> offset
				offset.line == nextLineStart.line -> CharLineOffset(
					deletionPoint.line,
					deletionPoint.char + offset.char
				)

				else -> CharLineOffset(offset.line - 1, offset.char)
			}

			addTransformed(joinOffset(start), joinOffset(end))
		} else {
			// Regular delete operation
			addTransformed(
				operation.transformOffset(start, state),
				operation.transformOffset(end, state),
			)
		}
	}

	fun getSpansInRange(range: TextEditorRange): List<RichSpan> {
		// Any span whose character range intersects [range] covers at least one of
		// its lines, so walking the per-line index misses nothing. A multi-line
		// span appears under several lines; the set keeps it once.
		val result = linkedSetOf<RichSpan>()
		for (line in range.start.line..range.end.line) {
			spansOnLine(line).forEach { span ->
				if (span.range.start isBeforeOrEqual range.end &&
					span.range.end isAfterOrEqual range.start
				) {
					result.add(span)
				}
			}
		}
		return result.toList()
	}
}

// Sticky gutter markers render on empty lines, and placeholder blocks (rules,
// images) own their whole line no matter how wide its text is.
private val RichSpanStyle.rendersWhenEmpty: Boolean
	get() = stickyAtStart || this is BlockSpanStyle

/**
 * Coerces [span] onto lines and columns that exist in [lines], or null when [lines] is
 * empty or the span shrinks to nothing and its style does not render when empty.
 * Returns [span] itself when it already fits.
 */
internal fun clampSpanToLines(span: RichSpan, lines: List<AnnotatedString>): RichSpan? {
	val lastLine = lines.lastIndex
	if (lastLine < 0) return null
	val range = span.range
	val startLine = range.start.line.coerceIn(0, lastLine)
	val endLine = range.end.line.coerceIn(startLine, lastLine)
	val startChar = range.start.char.coerceIn(0, lines[startLine].length)
	val endChar = range.end.char.coerceIn(
		if (startLine == endLine) startChar else 0,
		lines[endLine].length,
	)
	val clamped = TextEditorRange(
		CharLineOffset(startLine, startChar),
		CharLineOffset(endLine, endChar),
	)
	if (clamped.start == clamped.end && !span.style.rendersWhenEmpty) return null
	return if (clamped == range) span else span.copy(range = clamped)
}
