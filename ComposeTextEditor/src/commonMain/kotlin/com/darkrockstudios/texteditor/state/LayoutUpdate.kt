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
