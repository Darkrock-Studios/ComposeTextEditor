package com.darkrockstudios.texteditor.state

/**
 * Describes how much layout work a call to [TextEditorState.updateBookKeeping] must
 * do. Every mutation path that changes line content declares the lines it touched so
 * the pass can re-measure only those and reuse the previous layout for the rest.
 */
internal sealed class LayoutUpdate {
	/**
	 * Re-measure every line: a document load, and the fallback when a partial pass cannot
	 * be trusted. Now, unless the document is long, when the lines around the viewport
	 * are measured now and the rest settle between frames, each provisional until then.
	 */
	data object Full : LayoutUpdate()

	/**
	 * Every line needs shaping again (the style, measurer, density or viewport width
	 * changed) but the rows can stand in the meantime: the lines in view are shaped at
	 * once and the rest settle in the background, each kept at its old shape until then.
	 */
	data object Reshape : LayoutUpdate()

	/**
	 * Re-measure only [remeasureFirst]..[remeasureLast], expressed in post-edit line
	 * indices; the range may be empty. [lineDelta] is the post-edit line count minus
	 * the pre-edit count, so a line after the range maps to pre-edit index
	 * `index - lineDelta` when reusing its layout.
	 *
	 * [spansFirst]..[spansLast] are lines whose spans changed but not their text: their
	 * block heights and neighbour-derived facts are resolved again without shaping.
	 * A [spansLast] of [ALL_LINES] means every line, for a caller that cannot say.
	 */
	data class Partial(
		val remeasureFirst: Int,
		val remeasureLast: Int,
		val lineDelta: Int,
		val spansFirst: Int = 0,
		val spansLast: Int = -1,
	) : LayoutUpdate()

	companion object {
		const val ALL_LINES = Int.MAX_VALUE

		/** Spans changed on [first]..[last] and no text moved, so nothing re-measures. */
		fun Spans(first: Int, last: Int): Partial = Partial(0, -1, 0, first, last)

		/** Spans changed on lines the caller cannot name: every line resolves its spans again, none re-measures. */
		val SpansOnly: Partial = Spans(0, ALL_LINES)
	}
}

/**
 * Combines two deferred updates into one that covers both. A range union is only
 * sound when neither update can have shifted the other's line coordinates: partials
 * were posted at different moments of the same transaction, so an earlier delta-0
 * range's lines may sit at different indices by commit. Every composition that
 * cannot be proven shift-free degrades to [LayoutUpdate.Full], which is always sound.
 * A spans range covering every line names no coordinate, so it survives any shift.
 */
internal fun LayoutUpdate.mergedWith(other: LayoutUpdate): LayoutUpdate {
	if (this is LayoutUpdate.Full || other is LayoutUpdate.Full) return LayoutUpdate.Full
	// A reshape keeps every line's facts and the lines out of view as they are, which
	// cannot stand in for a partial's walk.
	if (this is LayoutUpdate.Reshape && other is LayoutUpdate.Reshape) return LayoutUpdate.Reshape
	if (this is LayoutUpdate.Reshape || other is LayoutUpdate.Reshape) return LayoutUpdate.Full
	val a = this as LayoutUpdate.Partial
	val b = other as LayoutUpdate.Partial
	val structural = when {
		a.lineDelta != 0 && b.lineDelta != 0 -> return LayoutUpdate.Full
		a.lineDelta != 0 -> a
		b.lineDelta != 0 -> b
		else -> null
	}
	// A delta-0 range is trustworthy beside a structural update only when it lies
	// entirely above the shift point, where no coordinate can have moved.
	fun shiftedBy(structural: LayoutUpdate.Partial?, first: Int, last: Int): Boolean =
		structural != null && first <= last && last >= structural.remeasureFirst

	val stable = if (structural === a) b else a
	if (structural != null && shiftedBy(structural, stable.remeasureFirst, stable.remeasureLast)) return LayoutUpdate.Full

	val aShapes = a.remeasureFirst <= a.remeasureLast
	val bShapes = b.remeasureFirst <= b.remeasureLast
	val remeasureFirst = minOf(if (aShapes) a.remeasureFirst else Int.MAX_VALUE, if (bShapes) b.remeasureFirst else Int.MAX_VALUE)
	val remeasureLast = maxOf(if (aShapes) a.remeasureLast else -1, if (bShapes) b.remeasureLast else -1)

	val all = a.spansLast == LayoutUpdate.ALL_LINES || b.spansLast == LayoutUpdate.ALL_LINES
	val aSpans = a.spansFirst <= a.spansLast
	val bSpans = b.spansFirst <= b.spansLast
	val spansFirst = if (all) 0 else minOf(if (aSpans) a.spansFirst else Int.MAX_VALUE, if (bSpans) b.spansFirst else Int.MAX_VALUE)
	val spansLast = if (all) LayoutUpdate.ALL_LINES else maxOf(if (aSpans) a.spansLast else -1, if (bSpans) b.spansLast else -1)
	if (!all && shiftedBy(structural, spansFirst, spansLast)) return LayoutUpdate.Full

	return LayoutUpdate.Partial(
		remeasureFirst = if (remeasureFirst == Int.MAX_VALUE) 0 else remeasureFirst,
		remeasureLast = remeasureLast,
		lineDelta = a.lineDelta + b.lineDelta,
		spansFirst = if (spansFirst == Int.MAX_VALUE) 0 else spansFirst,
		spansLast = spansLast,
	)
}
