package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * The number an ordered list starts at, HTML's `<ol start>` and the first numeral of
 * a CommonMark list, carried on the list's first item beside its
 * [OrderedListSpanStyle]. It paints nothing: the item that opens its level's run
 * takes [start] as its number, and the items after it count on from there. A list
 * starting at 1 needs none.
 *
 * On an item that continues a run (two lists joined) it is kept but has no effect,
 * so splitting the run there again, or undoing the join, gives that list its start
 * back. Normalization takes it off a line that is no ordered item, and keeps one per
 * line. Read and set a list's start through `TextEditorState.orderedListStart` and
 * `setOrderedListStart`.
 */
data class OrderedListStartSpanStyle(val start: Int) : RichSpanStyle {
	init {
		require(isValidStart(start)) { "An ordered list starts at 0 to $MAX_START, not $start" }
	}

	override val stickyAtStart: Boolean get() = true
	override val isHitTestable: Boolean get() = false

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
	}

	companion object {
		/** The largest start: CommonMark's nine digits. */
		const val MAX_START: Int = 999_999_999

		/** Whether a list can start at [start]: 0 to [MAX_START]. */
		fun isValidStart(start: Int): Boolean = start in 0..MAX_START
	}
}
