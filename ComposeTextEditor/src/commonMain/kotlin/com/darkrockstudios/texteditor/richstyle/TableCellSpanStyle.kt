package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TableCellBox
import com.darkrockstudios.texteditor.state.TextEditorState

/** The most columns a table can have. */
const val MAX_TABLE_COLUMNS: Int = 16

/** How a table column's cells align their text, as a GFM delimiter row writes it. */
enum class TableAlignment {
	/** `---`: the reading direction's start. */
	NONE,

	/** `:--` */
	LEFT,

	/** `:-:` */
	CENTER,

	/** `--:` */
	RIGHT,
}

/**
 * Marks a line as one cell of a table: the cell in [column] of its table row, its text
 * aligned by [alignment]. A table is a run of consecutive cell lines in row-major
 * order: a cell starts a table row when the line before it is no cell or holds a cell
 * of the same or a later column, and continues the row otherwise. The row's cells sit
 * side by side, and the table's first row is its header. Which table row a cell is in,
 * whether it is the header, and how many columns its table has are derived when the
 * document is laid out, as list numerals are, never stored; see `docs/design/tables.md`.
 *
 * Instances are per column and alignment singletons ([of]); block detection compares
 * span styles by identity.
 */
class TableCellSpanStyle private constructor(
	val column: Int,
	val alignment: TableAlignment,
) : RichSpanStyle {
	override val stickyAtStart: Boolean get() = true

	/** The header row's fill, behind the text, from the cell's first row over its whole box. */
	override fun DrawScope.drawBackground(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		val box = lineWrap.tableCell ?: return
		if (lineWrap.virtualLineIndex != 0 || !box.isHeader) return
		val fill = if (state.tableHeaderBackgroundColor.isSpecified) state.tableHeaderBackgroundColor else Color.Gray.copy(alpha = 0.18f)
		drawRect(fill, topLeft = box.topLeftIn(lineWrap), size = Size(box.width, box.height))
	}

	/**
	 * The cell's borders, from its first row: its top and left edges, its right edge when
	 * it ends its row and its bottom edge in the table's last row, so each line is drawn once.
	 */
	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		val box = lineWrap.tableCell ?: return
		if (lineWrap.virtualLineIndex != 0) return
		val color = if (state.tableBorderColor.isSpecified) state.tableBorderColor else Color.Gray.copy(alpha = 0.55f)
		val stroke = BORDER_WIDTH_DP.dp.toPx()
		val origin = box.topLeftIn(lineWrap)
		drawRect(color, origin, Size(box.width, stroke))
		drawRect(color, origin, Size(stroke, box.height))
		if (box.endsRow) drawRect(color, Offset(origin.x + box.width - stroke, origin.y), Size(stroke, box.height))
		if (box.isLastRow) drawRect(color, Offset(origin.x, origin.y + box.height - stroke), Size(box.width, stroke))
	}

	override fun toString(): String = "TableCellSpanStyle(column=$column, alignment=$alignment)"

	companion object {
		private const val BORDER_WIDTH_DP = 1f

		private val CELLS: List<List<TableCellSpanStyle>> = List(MAX_TABLE_COLUMNS) { column ->
			TableAlignment.entries.map { TableCellSpanStyle(column, it) }
		}

		/** The singleton for [column], 0 until [MAX_TABLE_COLUMNS], and [alignment]. */
		fun of(column: Int, alignment: TableAlignment = TableAlignment.NONE): TableCellSpanStyle {
			require(column in 0 until MAX_TABLE_COLUMNS) { "A cell's column is 0 until $MAX_TABLE_COLUMNS, was $column" }
			return CELLS[column][alignment.ordinal]
		}

		/** Every cell style, column by column. */
		internal val ALL: List<TableCellSpanStyle> = CELLS.flatten()
	}
}

/** The box's top left in the coordinates [row]'s spans draw in, which start at the row's offset. */
private fun TableCellBox.topLeftIn(row: LineWrap): Offset = Offset(left - row.offset.x, top - row.offset.y)

/** The paragraph style a cell aligned by [alignment] carries: the alignment and nothing else. */
fun tableCellParagraphStyle(alignment: TableAlignment): ParagraphStyle = TABLE_CELL_PARAGRAPH_STYLES[alignment.ordinal]

private val TABLE_CELL_PARAGRAPH_STYLES: List<ParagraphStyle> = TableAlignment.entries.map {
	ParagraphStyle(
		textAlign = when (it) {
			TableAlignment.NONE -> TextAlign.Start
			TableAlignment.LEFT -> TextAlign.Left
			TableAlignment.CENTER -> TextAlign.Center
			TableAlignment.RIGHT -> TextAlign.Right
		}
	)
}
