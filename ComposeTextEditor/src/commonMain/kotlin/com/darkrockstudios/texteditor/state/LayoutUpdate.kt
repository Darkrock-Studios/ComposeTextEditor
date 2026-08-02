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
 * Combines two deferred updates into one that covers both. Two updates that both
 * change the line count cannot be composed by range arithmetic alone, so that case
 * degrades to [LayoutUpdate.Full], which is always sound.
 */
internal fun LayoutUpdate.mergedWith(other: LayoutUpdate): LayoutUpdate {
	if (this is LayoutUpdate.Full || other is LayoutUpdate.Full) return LayoutUpdate.Full
	val a = this as LayoutUpdate.Partial
	val b = other as LayoutUpdate.Partial
	val aEmpty = a.remeasureLast < a.remeasureFirst && a.lineDelta == 0
	val bEmpty = b.remeasureLast < b.remeasureFirst && b.lineDelta == 0
	return when {
		aEmpty -> b
		bEmpty -> a
		a.lineDelta != 0 && b.lineDelta != 0 -> LayoutUpdate.Full
		else -> LayoutUpdate.Partial(
			remeasureFirst = minOf(a.remeasureFirst, b.remeasureFirst),
			remeasureLast = maxOf(a.remeasureLast, b.remeasureLast),
			lineDelta = a.lineDelta + b.lineDelta,
		)
	}
}
