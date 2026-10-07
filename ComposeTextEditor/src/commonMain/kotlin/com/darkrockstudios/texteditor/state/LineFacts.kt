package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.CodeFenceBoundary
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.MAX_LIST_LEVEL
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.listBlock
import com.darkrockstudios.texteditor.richstyle.listLevel
import com.darkrockstudios.texteditor.richstyle.repairFenceLanguages

/**
 * The facts a line's neighbours decide, walked in line order: every [BlockKind]'s walk
 * at once. A walk starts at the document or [resume]s from the facts a [LineLayout]
 * kept.
 */
internal class LineFacts(spans: SpanIndex) {
	@Suppress("UNCHECKED_CAST")
	private val walks = BLOCK_KINDS.map { it.walk(spans) as FactsWalk<Any> }
	private val values = arrayOfNulls<Any>(walks.size)

	/** The facts of the line [next] was last given. */
	var facts: BlockFacts = BlockFacts.NONE
		private set

	/** Continues the walk after a line laid out as [after]. */
	fun resume(after: LineLayout) {
		@Suppress("UNCHECKED_CAST")
		walks.forEachIndexed { index, walk -> walk.resume(after.facts[BLOCK_KINDS[index] as BlockKind<Any>]) }
	}

	/** Derives the facts of [line], which must follow the line last given, or start the walk. */
	fun next(line: Int) {
		walks.forEachIndexed { index, walk -> values[index] = walk.next(line) }
		facts = BlockFacts.of(values)
	}
}

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

/** Code fences: a fenced line's card edges come from whether its neighbours are fenced. */
internal object CodeFenceKind : BlockKind<CodeFenceBoundary> {
	override fun walk(spans: SpanIndex): FactsWalk<CodeFenceBoundary> = object : FactsWalk<CodeFenceBoundary> {
		override fun resume(after: CodeFenceBoundary?) {}

		override fun next(line: Int): CodeFenceBoundary? {
			if (!fenced(spans, line)) return null
			val prevIn = fenced(spans, line - 1)
			val nextIn = fenced(spans, line + 1)
			return when {
				!prevIn && !nextIn -> CodeFenceBoundary.Only
				!prevIn -> CodeFenceBoundary.First
				!nextIn -> CodeFenceBoundary.Last
				else -> CodeFenceBoundary.Middle
			}
		}
	}

	/** A fence line's edges change with the line before it. */
	override fun walkStart(spans: SpanIndex, line: Int): Int = (line - 1).coerceAtLeast(0)

	override fun repair(snapshot: DocumentSnapshot, config: RichTextStyles, changed: IntRange, spansChanged: Boolean): DocumentSnapshot =
		if (spansChanged) repairFenceLanguages(snapshot, changed) else snapshot

	private fun fenced(spans: SpanIndex, line: Int): Boolean =
		spans.spansOn(line).any { it.style === CodeFenceSpanStyle && it.range.start.line == line }
}
