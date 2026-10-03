package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.CodeFenceBoundary
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.MAX_LIST_LEVEL
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.listBlock
import com.darkrockstudios.texteditor.richstyle.listLevel

/**
 * The facts a line's neighbours decide, walked in line order: each ordered item is
 * numbered by its position in its level's run (a level-k item continues its level's
 * run and restarts every deeper level; a bullet at a level or any non-list line ends
 * the run at and below it), and a fenced line's card edges come from whether its
 * neighbours are fenced. A walk starts at the document or [resume]s from the counters a
 * [LineLayout] kept.
 */
internal class LineFacts(private val spans: SpanIndex) {
	private val running = IntArray(MAX_LIST_LEVEL + 1)

	/** The facts of the line [next] was last given. */
	var orderedListNumber: Int? = null
		private set
	var codeFenceBoundary: CodeFenceBoundary? = null
		private set

	/** The counters after the line [next] was last given: [NO_COUNTERS] while none is running. */
	var counters: IntArray = NO_COUNTERS
		private set

	/** Continues the walk after a line whose counters were [after]. */
	fun resume(after: IntArray) {
		after.copyInto(running)
		counters = after
	}

	/** The list on [line]: its level doubled, plus one when ordered; -1 for none. */
	private fun listOn(line: Int): Int {
		for (span in spans.spansOn(line)) {
			val block = span.style.listBlock() ?: continue
			if (span.range.start.line != line) continue
			return block.listLevel!! * 2 + if (block.spanStyle is OrderedListSpanStyle) 1 else 0
		}
		return -1
	}

	private fun fenced(line: Int): Boolean =
		spans.spansOn(line).any { it.style === CodeFenceSpanStyle && it.range.start.line == line }

	/** Derives the facts of [line], which must follow the line last given, or start the walk. */
	fun next(line: Int) {
		val list = listOn(line)
		orderedListNumber = if (list < 0) {
			running.fill(0)
			null
		} else {
			val level = list / 2
			for (deeper in level + 1..MAX_LIST_LEVEL) running[deeper] = 0
			if (list % 2 == 1) {
				running[level] += 1
				running[level]
			} else {
				running[level] = 0
				null
			}
		}
		// Shared while unchanged: a line's layout keeps the array, and compares by content.
		counters = when {
			running.contentEquals(counters) -> counters
			running.all { it == 0 } -> NO_COUNTERS
			else -> running.copyOf()
		}
		codeFenceBoundary = if (fenced(line)) {
			val prevIn = fenced(line - 1)
			val nextIn = fenced(line + 1)
			when {
				!prevIn && !nextIn -> CodeFenceBoundary.Only
				!prevIn -> CodeFenceBoundary.First
				!nextIn -> CodeFenceBoundary.Last
				else -> CodeFenceBoundary.Middle
			}
		} else null
	}

	companion object {
		/** Every level's counter at zero, shared by every line outside an ordered run. */
		val NO_COUNTERS = IntArray(MAX_LIST_LEVEL + 1)
	}
}
