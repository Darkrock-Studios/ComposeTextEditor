package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.TaskSpanStyle
import com.darkrockstudios.texteditor.richstyle.listBlock
import com.darkrockstudios.texteditor.richstyle.rebuildWithoutBlock
import com.darkrockstudios.texteditor.richstyle.taskBlock

/**
 * Task lists: a task is a list item's checkbox, so a task on a line that is no list
 * item (its list toggled off, its item left by Enter, a host's span) is taken off it.
 * A task takes no facts from its neighbours.
 */
internal object TaskKind : BlockKind<Unit> {
	override fun walk(spans: SpanIndex): FactsWalk<Unit> = NO_WALK

	override fun repair(snapshot: DocumentSnapshot, config: RichTextStyles, changed: IntRange, spansChanged: Boolean): DocumentSnapshot {
		if (!spansChanged) return snapshot
		val removed = ArrayList<RichSpan>()
		var lines = snapshot.lineList
		var rebuiltFirst = Int.MAX_VALUE
		var rebuiltLast = -1
		for (line in changed) {
			val starting = snapshot.spansOn(line).filter { it.range.start.line == line }
			if (starting.any { it.style.listBlock() != null }) continue
			for (span in starting) {
				val task = span.style as? TaskSpanStyle ?: continue
				removed += span
				lines = lines.splice(line, line + 1, listOf(rebuildWithoutBlock(lines[line], taskBlock(task.checked))))
				rebuiltFirst = minOf(rebuiltFirst, line)
				rebuiltLast = maxOf(rebuiltLast, line)
			}
		}
		if (removed.isEmpty()) return snapshot
		return snapshot.withLines(lines, LineSplice(rebuiltFirst, lines.size - 1 - rebuiltLast))
			.withSpanIndex(snapshot.spanIndex.minus(removed))
	}

	private val NO_WALK = object : FactsWalk<Unit> {
		override fun resume(after: Unit?) {}
		override fun next(line: Int): Unit? = null
	}
}
