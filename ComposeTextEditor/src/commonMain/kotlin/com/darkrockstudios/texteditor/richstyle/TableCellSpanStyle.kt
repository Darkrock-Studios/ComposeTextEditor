package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState

/** The most columns a table can have; a wider table's extra cells are dropped on import. */
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

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
	}

	override fun toString(): String = "TableCellSpanStyle(column=$column, alignment=$alignment)"

	companion object {
		private val CELLS: List<List<TableCellSpanStyle>> = List(MAX_TABLE_COLUMNS) { column ->
			TableAlignment.entries.map { TableCellSpanStyle(column, it) }
		}

		/** The singleton for [column], coerced into 0 until [MAX_TABLE_COLUMNS], and [alignment]. */
		fun of(column: Int, alignment: TableAlignment = TableAlignment.NONE): TableCellSpanStyle =
			CELLS[column.coerceIn(0, MAX_TABLE_COLUMNS - 1)][alignment.ordinal]

		/** Every cell style, column by column. */
		internal val ALL: List<TableCellSpanStyle> = CELLS.flatten()
	}
}

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
