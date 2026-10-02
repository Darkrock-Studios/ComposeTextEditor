package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange

// Words come from the platform's ICU word breaks (see wordRuns), the same
// segmentation the keyboard's word motion and double-click use.

/** Every word of the document that holds letters or digits, in order: the spell checker's candidates. */
fun TextEditorState.wordSegments(): Sequence<WordSegment> = wordSegments { wordCursor("") }

internal fun TextEditorState.wordSegments(openCursor: () -> BreakCursor): Sequence<WordSegment> = sequence {
	// The line list is immutable, so an edit during a scan cannot pull lines out from under it.
	val lines = textLines
	var from = 0
	var batchSize = 1
	while (from < lines.size) {
		val to = minOf(lines.size, from + batchSize)
		// A consumer that stops early never resumes the sequence, so a cursor open across a
		// yield would never close: each batch of lines is segmented whole before it yields.
		// Batches double, so an early stop reads few lines and a whole scan opens few cursors.
		val segments = openCursor().use { breaks ->
			buildList {
				for (lineIndex in from until to) {
					val text = lines[lineIndex].text
					text.wordRuns(breaks).forEach { run ->
						if (run.kind == WordKind.LEXICAL) add(WordSegment(text, lineIndex, run))
					}
				}
			}
		}
		yieldAll(segments)
		from = to
		batchSize = (batchSize * 2).coerceAtMost(MAX_SCAN_BATCH)
	}
}

private const val MAX_SCAN_BATCH = 256

/**
 * The word at [position] for a double-click: the word or emoji the position is in,
 * else the one ending there, else null.
 */
fun TextEditorState.findWordSegmentAt(position: CharLineOffset): WordSegment? {
	val text = textLines.getOrNull(position.line)?.text ?: return null
	if (position.char < 0 || position.char > text.length) return null
	val runs = text.wordRuns()
	val run = runs.firstOrNull { it.isWord && position.char in it.start until it.end }
		?: runs.lastOrNull { it.isWord && it.end == position.char }
		?: return null
	return WordSegment(text, position.line, run)
}

/**
 * The words that [range] touches, whole: those it overlaps, and when an end of it
 * falls between words, the nearest word beyond that end, so an edit beside a word
 * re-checks it. Empty for a range outside the document.
 */
fun TextEditorState.wordSegmentsInRange(range: TextEditorRange): List<WordSegment> {
	val lines = textLines
	if (range.start.line < 0 || range.end.line >= lines.size) return emptyList()
	val segments = mutableListOf<WordSegment>()
	for (lineIndex in range.start.line..range.end.line) {
		val text = lines[lineIndex].text
		if (text.isEmpty()) continue
		val from = if (lineIndex == range.start.line) range.start.char else 0
		val to = if (lineIndex == range.end.line) range.end.char else text.length
		val runs = text.wordRuns()
		val words = runs.filter { it.kind == WordKind.LEXICAL }
		val startAt = runs.firstOrNull { it.kind == WordKind.LEXICAL && from in it.start until it.end }?.start
			?: words.lastOrNull { it.end <= from }?.start ?: 0
		val endAt = runs.firstOrNull { it.kind == WordKind.LEXICAL && to in it.start until it.end }?.end
			?: words.firstOrNull { it.start >= to }?.end ?: text.length
		for (run in words) {
			if (run.start >= startAt && run.end <= endAt) segments.add(WordSegment(text, lineIndex, run))
		}
	}
	return segments
}

private fun WordSegment(lineText: String, line: Int, run: WordRun) = WordSegment(
	text = lineText.substring(run.start, run.end),
	range = TextEditorRange(CharLineOffset(line, run.start), CharLineOffset(line, run.end)),
)
