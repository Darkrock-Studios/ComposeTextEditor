package com.darkrockstudios.texteditor.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.TextEditorRange
import kotlin.concurrent.Volatile

/**
 * Counts the document's words: the segments holding a letter or digit in the platform's
 * ICU word breaks, the ones word motion stops at and spell check reads. So "don't" is
 * one word, "self-aware" two, an emoji or punctuation none, and CJK counts dictionary
 * words.
 *
 * Kept per line: a recount segments only the lines that differ, by identity, from the
 * last count's, found by matching the unchanged lines at both ends. An edit replaces
 * only the lines it touches, so a keystroke recounts one line.
 */
internal class WordCounter(private val state: TextEditorState) {
	/** One count, replaced whole, so a count running on another thread never sees half of one. */
	private class Counted(val lines: List<AnnotatedString>, val counts: IntArray, val total: Int)

	@Volatile
	private var last = Counted(emptyList(), IntArray(0), 0)

	/** How many lines have been segmented, for cost tests. */
	var linesSegmented = 0
		private set

	val count by derivedStateOf {
		state.revision
		countOf(state.snapshot().lines)
	}

	private fun countOf(newLines: List<AnnotatedString>): Int {
		val old = last
		if (newLines === old.lines) return old.total
		val oldLines = old.lines
		var head = 0
		val shorter = minOf(oldLines.size, newLines.size)
		while (head < shorter && oldLines[head] === newLines[head]) head++
		var tail = 0
		while (tail < shorter - head && oldLines[oldLines.size - 1 - tail] === newLines[newLines.size - 1 - tail]) tail++

		val counts = IntArray(newLines.size)
		old.counts.copyInto(counts, 0, 0, head)
		old.counts.copyInto(counts, newLines.size - tail, oldLines.size - tail, oldLines.size)
		wordCursor("").use { breaks ->
			for (i in head until newLines.size - tail) {
				counts[i] = newLines[i].text.countWords(breaks)
				linesSegmented++
			}
		}
		return Counted(newLines, counts, counts.sum()).also { last = it }.total
	}
}

/**
 * The number of words in [range]: every word it touches, even in part, counted as
 * [TextEditorState.wordCount] counts them, in the same committed document. A collapsed
 * range counts none. Segments only the lines [range] spans.
 */
fun TextEditorState.wordCount(range: TextEditorRange): Int {
	if (range.start == range.end) return 0
	val lines = snapshot().lines
	if (range.start.line < 0 || range.end.line >= lines.size) return 0
	var count = 0
	wordCursor("").use { breaks ->
		for (lineIndex in range.start.line..range.end.line) {
			val text = lines[lineIndex].text
			val from = if (lineIndex == range.start.line) range.start.char else 0
			val to = if (lineIndex == range.end.line) range.end.char else text.length
			count += text.countWords(breaks, from, to)
		}
	}
	return count
}

/** The words of this line that overlap [from] until [to]. */
private fun String.countWords(breaks: BreakCursor, from: Int = 0, to: Int = length): Int =
	wordRuns(breaks).count { it.kind == WordKind.LEXICAL && it.start < to && it.end > from }
