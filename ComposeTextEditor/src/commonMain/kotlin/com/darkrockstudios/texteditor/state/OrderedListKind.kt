package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.richstyle.MAX_LIST_LEVEL
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedListStartSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.listBlock
import com.darkrockstudios.texteditor.richstyle.listLevel

/**
 * An ordered item's [number], by its position in its level's run, and each nesting
 * level's next number after its line ([counters], 0 where no run is open), so a walk
 * resumes from it. A bullet inside an ordered item's run has no number but keeps the
 * run's counters.
 */
internal class ListFacts(val number: Int?, val counters: IntArray) {
	override fun equals(other: Any?): Boolean = other is ListFacts && other.number == number && other.counters.contentEquals(counters)

	override fun hashCode(): Int = 31 * (number ?: 0) + counters.contentHashCode()
}

/**
 * Ordered lists: a level-k item continues its level's run and restarts every deeper
 * level; a bullet at a level or any non-list line ends the run at and below it. The
 * item opening a run takes its [OrderedListStartSpanStyle]'s start, or 1.
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
					val number = if (running[level] == 0) startValueOn(spans.spansOn(line), line) ?: 1 else running[level]
					running[level] = number + 1
					number
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

	/**
	 * Keeps a list start only on an ordered item, one per line: a list toggled off or
	 * made bullets, an item left with Enter, a host's span leaves none behind. Lines
	 * only gain or lose one when a span comes or goes, so this runs only then.
	 */
	override fun repair(snapshot: DocumentSnapshot, config: RichTextStyles, changed: IntRange, spansChanged: Boolean): DocumentSnapshot {
		if (!spansChanged) return snapshot
		val removed = ArrayList<RichSpan>()
		for (line in changed.first.coerceAtLeast(0)..minOf(changed.last, snapshot.lines.size - 1)) {
			val onLine = snapshot.spansOn(line)
			val starts = onLine.filter { it.style is OrderedListStartSpanStyle && it.range.start.line == line }
			if (starts.isEmpty()) continue
			val kept = if (orderedOn(onLine, line)) startOn(onLine, line) else null
			starts.filterTo(removed) { it !== kept }
		}
		if (removed.isEmpty()) return snapshot
		return snapshot.withSpanIndex(snapshot.spanIndex.minus(removed))
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

	private fun orderedOn(spans: List<RichSpan>, line: Int): Boolean =
		spans.any { it.style is OrderedListSpanStyle && it.range.start.line == line }

	/**
	 * The span holding [line]'s list start, of the [spans] on it: the one at the line's
	 * start over one an edit landed mid-line, then the lowest, so the walk and [repair]
	 * agree on it.
	 */
	internal fun startOn(spans: List<RichSpan>, line: Int): RichSpan? {
		var best: RichSpan? = null
		for (span in spans) {
			if (span.style !is OrderedListStartSpanStyle || span.range.start.line != line) continue
			val current = best
			if (current == null || startOrder.compare(span, current) < 0) best = span
		}
		return best
	}

	/** The start [line] holds, of the [spans] on it, or null for none. */
	internal fun startValueOn(spans: List<RichSpan>, line: Int): Int? =
		(startOn(spans, line)?.style as OrderedListStartSpanStyle?)?.start

	private val startOrder = compareBy<RichSpan>({ it.range.start.char != 0 }, { (it.style as OrderedListStartSpanStyle).start })

	/** Every level's counter at zero, shared by every line outside an ordered run. */
	private val NO_COUNTERS = IntArray(MAX_LIST_LEVEL + 1)
}
