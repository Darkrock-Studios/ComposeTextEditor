package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle

class RichSpanManager(
	private val state: TextEditorState
) {
	/**
	 * Copy-on-write: every mutation publishes a brand new set rather than editing the
	 * previous one, so the sets handed out by [getAllRichSpans] and iterated by the
	 * queries below can never be modified underneath a reader.
	 */
	private var spans: Set<RichSpan>
		get() = state.workingContent.richSpans
		set(value) = state.setRichSpans(value)

	/**
	 * Every rich span in the document, as an immutable snapshot. Safe to hold onto and
	 * to iterate from any thread; it will not reflect later edits.
	 */
	fun getAllRichSpans(): Set<RichSpan> = spans

	internal fun addRichSpan(range: TextEditorRange, style: RichSpanStyle) {
		spans = spans + RichSpan(range, style)
	}

	internal fun addRichSpan(start: CharLineOffset, end: CharLineOffset, style: RichSpanStyle) {
		addRichSpan(TextEditorRange(start, end), style)
	}

	internal fun removeRichSpan(start: CharLineOffset, end: CharLineOffset, style: RichSpanStyle) {
		removeRichSpan(RichSpan(TextEditorRange(start, end), style))
	}

	internal fun removeRichSpan(span: RichSpan) {
		spans = spans - span
	}

	fun getSpansForLineWrap(lineWrap: LineWrap): List<RichSpan> {
		return spans.filter { it.intersectsWith(lineWrap) }
	}

	fun updateSpans(operation: TextEditOperation, metadata: OperationMetadata?) {
		val updatedSpans = mutableSetOf<RichSpan>()

		spans.forEach { span ->
			span.range.apply {
				when (operation) {
					is TextEditOperation.Insert -> handleInsert(operation, updatedSpans, span)
					is TextEditOperation.Delete -> handleDelete(
						metadata,
						operation,
						updatedSpans,
						span
					)
					is TextEditOperation.Replace -> handleReplace(operation, updatedSpans, span)
					is TextEditOperation.StyleSpan -> handleSpanOnly(updatedSpans, span)
					is TextEditOperation.RichSpan -> handleSpanOnly(updatedSpans, span)
					is TextEditOperation.LineBlock -> handleSpanOnly(updatedSpans, span)
				}
			}
		}

		spans = mergeLineAnchoredDuplicates(updatedSpans)
	}

	/**
	 * Collapses any same-line duplicates of line-anchored (sticky-at-start) styles
	 * — bullet, blockquote, etc. — into a single span covering the union range.
	 * A multi-line merge that joins two same-style line-anchored lines naturally
	 * produces two adjacent spans on the joined line; this fold gives us the
	 * "one gutter marker per line" invariant those styles assume.
	 */
	private fun mergeLineAnchoredDuplicates(spans: Set<RichSpan>): Set<RichSpan> {
		val (anchored, others) = spans.partition { it.style.stickyAtStart }
		val merged = anchored
			.groupBy { it.style to it.range.start.line }
			.map { (key, group) ->
				if (group.size == 1) group.first() else {
					val (style, line) = key
					RichSpan(
						range = TextEditorRange(
							start = CharLineOffset(line, group.minOf { it.range.start.char }),
							end = CharLineOffset(line, group.maxOf { it.range.end.char }),
						),
						style = style,
					)
				}
			}
		return (others + merged).toSet()
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
			val newStart = if (span.style.stickyAtStart && insertAtStart) {
				start
			} else {
				operation.transformOffset(start, state)
			}
			val newEnd = operation.transformOffset(end, state)
			updatedSpans.add(
				span.copy(
					range = span.range.copy(
						start = newStart,
						end = newEnd
					),
				)
			)
		}
	}

	private fun handleReplace(
		operation: TextEditOperation.Replace,
		updatedSpans: MutableSet<RichSpan>,
		span: RichSpan
	) {
		// Calculate new end position after replacement
		val newEnd = if (operation.newText.contains('\n')) {
			val lines = operation.newText.text.split('\n')
			val lastLineLength = lines.last().length
			CharLineOffset(
				operation.range.start.line + (lines.size - 1),
				if (lines.size == 1) operation.range.start.char + lastLineLength else lastLineLength
			)
		} else {
			CharLineOffset(
				operation.range.start.line,
				operation.range.start.char + operation.newText.length
			)
		}

		when {
			// Span ends before replacement - keep as is
			span.range.end.line < operation.range.start.line ||
					(span.range.end.line == operation.range.start.line &&
							span.range.end.char <= operation.range.start.char) -> {
				updatedSpans.add(span)
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
				val newStart = CharLineOffset(
					span.range.start.line + lineDiff,
					span.range.start.char + charDiff
				)
				val newEndPos = CharLineOffset(
					span.range.end.line + lineDiff,
					span.range.end.char + charDiff
				)
				updatedSpans.add(span.copy(range = TextEditorRange(newStart, newEndPos)))
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
						// Create spanning span
						updatedSpans.add(
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
						updatedSpans.add(
							span.copy(
								range = TextEditorRange(
									span.range.start,
									operation.range.start
								)
							)
						)
					}
				} else if (span.range.end.line > operation.range.end.line ||
					(span.range.end.line == operation.range.end.line &&
							span.range.end.char > operation.range.end.char)
				) {
					// Span starts within replacement but ends after - preserve end portion
					updatedSpans.add(
						span.copy(
							range = TextEditorRange(
								newEnd,
								CharLineOffset(
									newEnd.line + (span.range.end.line - operation.range.end.line),
									if (span.range.end.line == operation.range.end.line)
										newEnd.char + (span.range.end.char - operation.range.end.char)
									else span.range.end.char
								)
							)
						)
					)
				}
				// Else span is entirely within replacement - it gets removed
			}
		}
	}

	private fun TextEditorRange.handleDelete(
		metadata: OperationMetadata?,
		operation: TextEditOperation.Delete,
		updatedSpans: MutableSet<RichSpan>,
		span: RichSpan
	) {
		// updateSpans rebuilds the whole set from what these handlers contribute, so a
		// handler that adds nothing erases the span. With no metadata to transform
		// against, a stale position beats deleting the span outright.
		if (metadata == null) {
			updatedSpans.add(span)
			return
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

			updatedSpans.add(
				span.copy(
					range = TextEditorRange(
						start = joinOffset(start),
						end = joinOffset(end)
					)
				)
			)
		} else {
			// Regular delete operation
			val newStart = operation.transformOffset(start, state)
			val newEnd = operation.transformOffset(end, state)
			if (newStart != newEnd) {
				updatedSpans.add(
					span.copy(
						range = TextEditorRange(
							start = newStart, end = newEnd
						)
					)
				)
			}
		}
	}

	private fun handleSpanOnly(
		updatedSpans: MutableSet<RichSpan>,
		span: RichSpan
	) {
		// Span-only operations leave existing spans in place.
		updatedSpans.add(span)
	}

	fun getSpansInRange(range: TextEditorRange): List<RichSpan> {
		return spans.filter { span ->
			span.range.start isBeforeOrEqual range.end && span.range.end isAfterOrEqual range.start
		}
	}
}