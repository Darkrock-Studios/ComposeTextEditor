package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.CodeFenceBoundary
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.repairFenceLanguages

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
