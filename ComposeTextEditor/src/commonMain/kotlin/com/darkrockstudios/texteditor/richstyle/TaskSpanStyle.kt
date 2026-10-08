package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.utils.lineTextLeft

/**
 * Marks a list item as a task, GFM's `- [ ]` and `- [x]`: a checkbox drawn in the
 * item's gutter, in place of a bullet or after a numeral, [checked] or not. A task
 * is always a list item's, bullet or ordered, at any level; on a line that is no list
 * item it is taken off by normalization. Its indent adds to the list's, making room
 * for the box. See `docs/design/line-blocks.md`, "Task lists".
 *
 * Instances are singletons ([UNCHECKED], [CHECKED]); block detection compares span
 * styles by identity.
 */
class TaskSpanStyle private constructor(val checked: Boolean) : RichSpanStyle {
	override val stickyAtStart: Boolean get() = true

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		if (lineWrap.virtualLineIndex != 0) return
		val box = checkboxBounds(layoutResult, this)
		val color = if (state.bulletColor.isSpecified) state.bulletColor else Color.DarkGray
		val corner = CornerRadius(CORNER_DP.dp.toPx())
		if (checked) {
			drawRoundRect(color, box.first, box.second, corner)
			val check = Path().apply {
				moveTo(box.first.x + box.second.width * 0.22f, box.first.y + box.second.height * 0.52f)
				lineTo(box.first.x + box.second.width * 0.42f, box.first.y + box.second.height * 0.72f)
				lineTo(box.first.x + box.second.width * 0.78f, box.first.y + box.second.height * 0.30f)
			}
			drawPath(check, Color.White, style = Stroke(width = CHECK_STROKE_DP.dp.toPx()))
		} else {
			drawRoundRect(color, box.first, box.second, corner, style = Stroke(width = BORDER_DP.dp.toPx()))
		}
	}

	override fun toString(): String = "TaskSpanStyle(checked=$checked)"

	companion object {
		val UNCHECKED: TaskSpanStyle = TaskSpanStyle(false)
		val CHECKED: TaskSpanStyle = TaskSpanStyle(true)

		/** The singleton for [checked]. */
		fun of(checked: Boolean): TaskSpanStyle = if (checked) CHECKED else UNCHECKED

		/** The room a task takes before its item's text: the box and the gap after it. */
		internal const val GUTTER_SP = 22f
		private const val BOX_SP = 13f
		private const val GAP_SP = 6f
		private const val CORNER_DP = 2.5f
		private const val BORDER_DP = 1.5f
		private const val CHECK_STROKE_DP = 2f

		/**
		 * The checkbox of a task item whose first row [layout] lays out, relative to the
		 * row: its top left and size, just before the row's text, centred on its height.
		 */
		internal fun checkboxBounds(layout: TextLayoutResult, density: Density): Pair<Offset, Size> = with(density) {
			val textLeft = layout.lineTextLeft(0, density)
			val size = BOX_SP.sp.toPx()
			val left = (textLeft - GAP_SP.sp.toPx() - size).coerceAtLeast(0f)
			val top = (layout.multiParagraph.getLineHeight(0) - size) / 2f
			Offset(left, top) to Size(size, size)
		}
	}
}

/** Whether this row's line is a task item. */
internal val LineWrap.isTask: Boolean
	get() = richSpans.any { it.style is TaskSpanStyle }

/** The indent a task takes inside its list item, so the text clears the box. */
val TASK_PARAGRAPH_STYLE: ParagraphStyle = ParagraphStyle(
	textIndent = TextIndent(firstLine = TaskSpanStyle.GUTTER_SP.sp, restLine = TaskSpanStyle.GUTTER_SP.sp),
)
