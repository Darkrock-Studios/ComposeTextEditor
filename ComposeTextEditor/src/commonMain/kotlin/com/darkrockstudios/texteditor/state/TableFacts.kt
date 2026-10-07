package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.HtmlDocument
import com.darkrockstudios.texteditor.richstyle.TableCellSpanStyle
import com.darkrockstudios.texteditor.richstyle.planDemoteLineBlock
import com.darkrockstudios.texteditor.richstyle.startsTableRow
import com.darkrockstudios.texteditor.richstyle.tableCellBlock
import com.darkrockstudios.texteditor.richstyle.tableCellOn
import com.darkrockstudios.texteditor.richstyle.writeLineBlocks

/**
 * Tables: a cell takes its place in its table from the cells around it, is laid out at
 * its column's share of the viewport beside the other cells of its table row, and no
 * deletion or paste joins it to another line or leaves its table with a short row.
 * See `docs/design/tables.md`.
 */
internal object TableKind : BlockKind<TableCellFacts> {
	override fun walk(spans: SpanIndex): FactsWalk<TableCellFacts> = TableFactsWalk(spans)

	/** The row before an edit ends at the line before it, and whether it is its table's last can change. */
	override fun walkStart(spans: SpanIndex, line: Int): Int = tableRowStart(spans, (line - 1).coerceAtLeast(0))

	override fun shapesDifferently(now: TableCellFacts?, was: TableCellFacts?): Boolean =
		(now != null || was != null) && !(now?.shapesAs(was) ?: false)

	/** A cell wraps at its column's width whether lines wrap or not, and a header cell is bold, for measuring only, under the styles its text carries. */
	override fun shape(facts: TableCellFacts, line: AnnotatedString, shaper: LineShaping): TextLayoutResult {
		val measureLine = if (facts.isHeader) {
			AnnotatedString(line.text, listOf(AnnotatedString.Range(HEADER_CELL_STYLE, 0, line.length)) + line.spanStyles, line.paragraphStyles)
		} else line
		return shaper.measureWrapped(measureLine, maxOf(1, cellTextWidth(facts, shaper.inputs).toInt()))
	}

	override fun place(facts: TableCellFacts, inputs: LineInputs, textHeight: Float): LinePlacement =
		cellPlacement(facts, inputs, textHeight)

	override fun deletionPieces(state: TextEditorState, range: TextEditorRange): List<TextEditorRange>? =
		state.tablePreservingPieces(range)

	/** A run that took a table whole joins its lines into one no cell is in, but a cell's marker at the run's end, or its start, can land on it. */
	override fun afterJoin(state: TextEditorState, line: Int) {
		val cell = state.tableCellAt(line) ?: return
		state.editManager.recordLineBlockChanges(listOf(line)) {
			state.planDemoteLineBlock(line, tableCellBlock(cell))?.let { state.writeLineBlocks(listOf(it)) }
		}
	}

	override fun beforePastedBlocks(state: TextEditorState, document: HtmlDocument, landedAt: CharLineOffset, text: AnnotatedString): CharLineOffset =
		state.keepingTablesWhole(document, landedAt, text)

	override fun settlePasted(state: TextEditorState, lines: IntRange) = state.textForBrokenTables(lines)
}

/**
 * Where a cell line sits in its table, as the layout walk derives it: the [cell] marker
 * it carries, its table [row] (0 is the header), how many [columns] its row is laid
 * out in (the header's count, or more for a row that reaches past it), the header's
 * count itself ([tableColumns], so a walk can resume after the line), and whether it
 * starts or ends its row and whether its row is the table's last.
 */
internal data class TableCellFacts(
	val cell: TableCellSpanStyle,
	val row: Int,
	val columns: Int,
	val tableColumns: Int,
	val rowStart: Boolean,
	val rowEnd: Boolean,
	val lastRow: Boolean,
) {
	val isHeader: Boolean get() = row == 0

	/** Whether a line with these facts is shaped as one with [other]'s: the same width and look. */
	fun shapesAs(other: TableCellFacts?): Boolean =
		other != null && other.cell.column == cell.column && other.columns == columns && other.isHeader == isHeader
}

/**
 * Derives [TableCellFacts] line by line for [LineFacts]: a row's extent and whether it
 * is the table's last are read ahead once at its first cell, and the header's column
 * count at the table's first row. Reads only the cell markers in [spans].
 */
internal class TableFactsWalk(private val spans: SpanIndex) : FactsWalk<TableCellFacts> {
	private var row = -1
	private var tableColumns = 0
	private var rowEnd = -1
	private var rowColumns = 0
	private var lastRow = false

	/** The line [next] was last given and its cell, so the next line reads its own alone. */
	private var given = -1
	private var givenCell: TableCellSpanStyle? = null

	private fun cellOf(line: Int): TableCellSpanStyle? = when {
		line < 0 -> null
		line == given -> givenCell
		else -> spans.spansOn(line).tableCellOn(line)
	}

	override fun resume(after: TableCellFacts?) {
		row = after?.row ?: -1
		tableColumns = after?.tableColumns ?: 0
		rowEnd = -1
		given = -1
	}

	override fun next(line: Int): TableCellFacts? {
		val previous = cellOf(line - 1)
		val cell = spans.spansOn(line).tableCellOn(line)
		given = line
		givenCell = cell
		if (cell == null) {
			row = -1
			rowEnd = -1
			return null
		}
		val starts = startsTableRow(cell, previous)
		if (starts) row = if (previous == null) 0 else row + 1
		if (starts || line > rowEnd) readRow(line, cell)
		if (starts && row == 0) tableColumns = rowColumns
		return TableCellFacts(
			cell = cell,
			row = row,
			columns = maxOf(tableColumns, rowColumns),
			tableColumns = tableColumns,
			rowStart = starts,
			rowEnd = line == rowEnd,
			lastRow = lastRow,
		)
	}

	/** Reads the rest of the row [line] is in, from it on. */
	private fun readRow(line: Int, cell: TableCellSpanStyle) {
		var end = line
		var last = cell
		while (true) {
			val next = cellOf(end + 1) ?: break
			if (startsTableRow(next, last)) break
			end++
			last = next
		}
		rowEnd = end
		rowColumns = last.column + 1
		lastRow = cellOf(end + 1) == null
	}
}

/** A cell's padding around its text, in dp: sideways and above and below. */
internal const val CELL_PADDING_X_DP = 8f
internal const val CELL_PADDING_Y_DP = 4f

/** The pixels in a dp under [inputs], one when they have no density (a test's). */
private fun LineInputs.dp(value: Float): Float = value * (density?.density ?: 1f)

/** How wide a cell with [facts] lays its text out under [inputs]: its column's share, less its padding. */
private fun cellTextWidth(facts: TableCellFacts, inputs: LineInputs): Float =
	(inputs.width / facts.columns - 2 * inputs.dp(CELL_PADDING_X_DP)).coerceAtLeast(1f)

/**
 * Where a cell with [facts] whose rows are [textHeight] tall sits under [inputs], before
 * its row's height is known: its column's share of the viewport, padded around its
 * text, in a band that is its table row, with the paragraph spacing after the table's
 * last row.
 */
internal fun cellPlacement(facts: TableCellFacts, inputs: LineInputs, textHeight: Float): LinePlacement {
	val boxWidth = inputs.width / facts.columns
	val boxLeft = facts.cell.column * boxWidth
	val padding = inputs.dp(CELL_PADDING_Y_DP)
	return LinePlacement(
		boxLeft = boxLeft,
		boxWidth = boxWidth,
		textLeft = boxLeft + inputs.dp(CELL_PADDING_X_DP),
		padding = padding,
		bandHeight = textHeight + 2 * padding,
		spaceAfter = if (facts.lastRow) inputs.paragraphSpacing else 0f,
		startsBand = facts.rowStart,
		endsBand = facts.rowEnd,
	)
}

/** The look a header cell is shaped with, under its own styles: it is never in the text, so no format writes it. */
private val HEADER_CELL_STYLE = SpanStyle(fontWeight = FontWeight.Bold)

/** The first line of the table row [line] is in, as [spans] place cells; [line] itself outside a table. */
internal fun tableRowStart(spans: SpanIndex, line: Int): Int {
	var at = line
	while (at > 0) {
		val cell = spans.spansOn(at).tableCellOn(at) ?: break
		if (startsTableRow(cell, spans.spansOn(at - 1).tableCellOn(at - 1))) break
		at--
	}
	return at
}
