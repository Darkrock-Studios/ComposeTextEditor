package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.richstyle.MAX_LIST_LEVEL
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.listBlock
import com.darkrockstudios.texteditor.richstyle.listLevel

/**
 * An ordered item's [number], by its position in its level's run, and the counter of
 * each nesting level after its line ([counters]), so a walk resumes from it. A bullet
 * inside an ordered item's run has no number but keeps the run's counters.
 */
internal class ListFacts(val number: Int?, val counters: IntArray) {
	override fun equals(other: Any?): Boolean = other is ListFacts && other.number == number && other.counters.contentEquals(counters)

	override fun hashCode(): Int = 31 * (number ?: 0) + counters.contentHashCode()
}

/**
 * Ordered lists: a level-k item continues its level's run and restarts every deeper
 * level; a bullet at a level or any non-list line ends the run at and below it.
 */
internal object OrderedListKind : BlockKind<ListFacts> {
	override fun walk(spans: SpanIndex): FactsWalk<ListFacts> = object : FactsWalk<ListFacts> {
		private val running = IntArray(MAX_LIST_LEVEL + 1)
		private var counters = NO_COUNTERS

		override fun resume(after: ListFacts?) {
			counters = after?.counters ?: NO_COUNTERS
			counters.copyInto(running)
		}

		override fun next(line: Int): ListFacts? {
			val list = listOn(spans, line)
			val number = if (list < 0) {
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
			// Shared while unchanged: a line's facts keep the array, and compare by content.
			counters = when {
				running.contentEquals(counters) -> counters
				running.all { it == 0 } -> NO_COUNTERS
				else -> running.copyOf()
			}
			return if (number == null && counters === NO_COUNTERS) null else ListFacts(number, counters)
		}
	}

	/** The list on [line]: its level doubled, plus one when ordered; -1 for none. */
	private fun listOn(spans: SpanIndex, line: Int): Int {
		for (span in spans.spansOn(line)) {
			val block = span.style.listBlock() ?: continue
			if (span.range.start.line != line) continue
			return block.listLevel!! * 2 + if (block.spanStyle is OrderedListSpanStyle) 1 else 0
		}
		return -1
	}

	/** Every level's counter at zero, shared by every line outside an ordered run. */
	private val NO_COUNTERS = IntArray(MAX_LIST_LEVEL + 1)
}
