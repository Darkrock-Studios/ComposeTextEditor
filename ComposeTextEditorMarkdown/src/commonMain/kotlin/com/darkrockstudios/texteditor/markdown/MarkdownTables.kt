package com.darkrockstudios.texteditor.markdown

import com.darkrockstudios.texteditor.richstyle.MAX_TABLE_COLUMNS
import com.darkrockstudios.texteditor.richstyle.TableAlignment

/*
 * GFM pipe tables. Import reads a table into one line per cell (see
 * `docs/design/tables.md`); export writes a table's cell lines back as rows. A table
 * the editor does not hold (one inside a quote) stays literal text, its rows kept
 * together and its cells not read for inline syntax.
 */

private val TABLE_DELIMITER_ROW = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")

/**
 * The indices of the lines in [lines] that are rows of a GFM table: a header
 * row followed by a delimiter row with a pipe and the same cell count, then
 * every following line up to a blank line or the start of another block, as
 * GFM continues a table.
 */
internal fun tableRowIndices(lines: List<String>): Set<Int> {
	val rows = HashSet<Int>()
	for (table in findTables(lines)) rows += table
	return rows
}

/** Each GFM table's lines in [lines], header first, skipping the lines in [skip]. */
internal fun findTables(lines: List<String>, skip: Set<Int> = emptySet()): List<IntRange> {
	val tables = ArrayList<IntRange>()
	var i = 0
	while (i < lines.size) {
		if (i in skip || i + 1 in skip || !isTableHeader(lines[i], lines.getOrNull(i + 1))) {
			i++
			continue
		}
		var j = i + 2
		while (j < lines.size && j !in skip && lines[j].isNotBlank() && !startsBlock(lines[j])) j++
		tables += i until j
		i = j
	}
	return tables
}

/**
 * A line that starts a block of its own and so ends a table: a heading, quote, list item,
 * fence, rule, or an HTML block of a kind that can interrupt a paragraph.
 */
private fun startsBlock(line: String): Boolean = BLOCK_START.containsMatchIn(line) || HTML_BLOCK_START.containsMatchIn(line)

private val BLOCK_START = Regex("""^ {0,3}(?:#{1,6}(?:[ \t]|$)|>|[-*+][ \t]|[0-9]{1,9}[.)][ \t]|```|~~~|(?:[-*_][ \t]*){3,}$)""")

/** CommonMark's HTML block starts 1 to 6, the GFM spec's tag list for 6. */
private val HTML_BLOCK_START = Regex(
	"""^ {0,3}(?:<(?:script|pre|style|textarea)(?:[ \t>]|$)|<!--|<\?|<![A-Za-z]|<!\[CDATA\[|</?(?:""" +
		"address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details|dialog|dir|div|dl|dt|" +
		"fieldset|figcaption|figure|footer|form|frame|frameset|h[1-6]|head|header|hr|html|iframe|legend|li|link|main|menu|" +
		"menuitem|nav|noframes|ol|optgroup|option|p|param|section|source|summary|table|tbody|td|tfoot|th|thead|title|tr|" +
		"track|ul" + """)(?:[ \t>]|/>|$))""",
	RegexOption.IGNORE_CASE,
)

/** A line indented four columns or more, which is code where a table could start. */
private val INDENTED_FOR_CODE = Regex("""^(?: {4}| {0,3}\t)""")

private fun isTableHeader(line: String, next: String?): Boolean {
	if (next == null || !line.contains('|') || !next.contains('|')) return false
	if (INDENTED_FOR_CODE.containsMatchIn(line) || INDENTED_FOR_CODE.containsMatchIn(next)) return false
	if (!TABLE_DELIMITER_ROW.matches(next)) return false
	return tableCells(line).size == tableCells(next).size
}

/**
 * A table row's cells as GFM splits it: at each pipe no backslash escapes, an outer one
 * at either end dropped, each cell trimmed. A `\|` is a pipe in the cell, inside a code
 * span too, as GFM reads it there.
 */
internal fun tableCells(row: String): List<String> {
	val trimmed = row.trim()
	val cells = ArrayList<String>()
	val cell = StringBuilder()
	var endedAtPipe = false
	var index = if (trimmed.startsWith('|')) 1 else 0
	while (index < trimmed.length) {
		val c = trimmed[index]
		endedAtPipe = false
		when {
			c == '\\' && trimmed.getOrNull(index + 1) == '|' -> {
				cell.append('|')
				index++
			}

			c == '\\' && index + 1 < trimmed.length -> {
				cell.append(c).append(trimmed[index + 1])
				index++
			}

			c == '|' -> {
				cells += cell.toString().trim()
				cell.clear()
				endedAtPipe = true
			}

			else -> cell.append(c)
		}
		index++
	}
	// A pipe closing the row leaves no cell after it.
	if (!endedAtPipe || cells.isEmpty()) cells += cell.toString().trim()
	return cells
}

private fun delimiterAlignment(cell: String): TableAlignment = when {
	cell.startsWith(':') && cell.endsWith(':') && cell.length > 1 -> TableAlignment.CENTER
	cell.endsWith(':') -> TableAlignment.RIGHT
	cell.startsWith(':') -> TableAlignment.LEFT
	else -> TableAlignment.NONE
}

/**
 * A table read from markdown: its header's alignments and each row's cell texts, the
 * header first, every row as wide as the header.
 */
internal class ImportedTable(val alignments: List<TableAlignment>, val rows: List<List<String>>)

/**
 * The lines of [lines] with each GFM table outside [skip] read: the tables, by the index
 * of their first line, and how many lines each spans. A row wider than the header
 * loses its extra cells and a narrower one is padded, as GFM renders them; a header
 * wider than [MAX_TABLE_COLUMNS] loses its last columns.
 */
internal fun readTables(lines: List<String>, skip: Set<Int>): Map<Int, Pair<ImportedTable, Int>> =
	findTables(lines, skip).associate { range ->
		val header = tableCells(lines[range.first]).take(MAX_TABLE_COLUMNS)
		val alignments = tableCells(lines[range.first + 1]).map(::delimiterAlignment).take(header.size)
		val body = (range.first + 2..range.last).map { row ->
			val cells = tableCells(lines[row])
			List(header.size) { cells.getOrElse(it) { "" } }
		}
		range.first to (ImportedTable(alignments, listOf(header) + body) to range.count())
	}

/** A delimiter row cell for [alignment]. */
private fun delimiterCell(alignment: TableAlignment): String = when (alignment) {
	TableAlignment.NONE -> "---"
	TableAlignment.LEFT -> ":--"
	TableAlignment.CENTER -> ":-:"
	TableAlignment.RIGHT -> "--:"
}

/** A row of [cells], their markdown already written, with each pipe in them escaped. */
internal fun tableRow(cells: List<String>): String =
	cells.joinToString(" | ", prefix = "| ", postfix = " |") { it.replace("|", "\\|") }

/** The delimiter row for columns aligned by [alignments]. */
internal fun tableDelimiterRow(alignments: List<TableAlignment>): String =
	alignments.joinToString(" | ", prefix = "| ", postfix = " |", transform = ::delimiterCell)
