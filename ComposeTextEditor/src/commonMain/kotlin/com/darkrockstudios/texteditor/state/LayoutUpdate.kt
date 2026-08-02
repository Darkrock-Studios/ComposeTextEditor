package com.darkrockstudios.texteditor.state

/**
 * Describes how much layout work a call to [TextEditorState.updateBookKeeping] must
 * do. A [Partial] re-shapes only the lines an edit touched; every other line reuses
 * its previous [androidx.compose.ui.text.TextLayoutResult] and gets fresh offsets,
 * spans, and list numbering, which are arithmetic and set lookups, not shaping.
 */
internal sealed class LayoutUpdate {
	/** Re-measure every line. Required when the style, measurer, density, or viewport changed. */
	data object Full : LayoutUpdate()

	/**
	 * Re-measure only [remeasureFirst]..[remeasureLast], expressed in post-edit line
	 * indices; the range may be empty. [lineDelta] is the post-edit line count minus
	 * the pre-edit count, so a line after the range maps to pre-edit index
	 * `index - lineDelta` when reusing its layout.
	 */
	data class Partial(
		val remeasureFirst: Int,
		val remeasureLast: Int,
		val lineDelta: Int,
	) : LayoutUpdate()

	companion object {
		/** Span-only change: no line content moved or changed, so nothing re-measures. */
		val SpansOnly = Partial(0, -1, 0)
	}
}

/**
 * Combines two deferred updates into one that covers both. A range union is only
 * sound when neither update can have shifted the other's line coordinates: partials
 * were posted at different moments of the same transaction, so an earlier delta-0
 * partial's lines may sit at different indices by commit. Every composition that
 * cannot be proven shift-free degrades to [LayoutUpdate.Full], which is always sound.
 */
internal fun LayoutUpdate.mergedWith(other: LayoutUpdate): LayoutUpdate {
	if (this is LayoutUpdate.Full || other is LayoutUpdate.Full) return LayoutUpdate.Full
	val a = this as LayoutUpdate.Partial
	val b = other as LayoutUpdate.Partial
	val aEmpty = a.remeasureLast < a.remeasureFirst && a.lineDelta == 0
	val bEmpty = b.remeasureLast < b.remeasureFirst && b.lineDelta == 0
	val union = LayoutUpdate.Partial(
		remeasureFirst = minOf(a.remeasureFirst, b.remeasureFirst),
		remeasureLast = maxOf(a.remeasureLast, b.remeasureLast),
		lineDelta = a.lineDelta + b.lineDelta,
	)
	return when {
		aEmpty -> b
		bEmpty -> a
		// No line moved, so both ranges are in commit coordinates.
		a.lineDelta == 0 && b.lineDelta == 0 -> union
		a.lineDelta != 0 && b.lineDelta != 0 -> LayoutUpdate.Full
		// One structural update: the delta-0 range is only trustworthy when it lies
		// entirely above the shift point, where no coordinate can have moved.
		else -> {
			val structural = if (a.lineDelta != 0) a else b
			val stable = if (a.lineDelta != 0) b else a
			if (stable.remeasureLast < structural.remeasureFirst) union else LayoutUpdate.Full
		}
	}
}
