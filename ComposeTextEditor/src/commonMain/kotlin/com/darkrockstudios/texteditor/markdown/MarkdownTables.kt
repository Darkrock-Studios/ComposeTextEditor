package com.darkrockstudios.texteditor.markdown

/**
 * The editor has no table model: a GFM table imports as literal text, one
 * line per row, and must pass through export as written. Both sides need to
 * know which lines form one, so a table's rows are kept together and its
 * cells are not read for inline syntax.
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
	var i = 0
	while (i < lines.size) {
		if (!isTableHeader(lines[i], lines.getOrNull(i + 1))) {
			i++
			continue
		}
		rows += i
		rows += i + 1
		var j = i + 2
		while (j < lines.size && lines[j].isNotBlank() && !BLOCK_START.containsMatchIn(lines[j])) {
			rows += j
			j++
		}
		i = j
	}
	return rows
}

/** A line that starts a block of its own and so ends a table: a heading, quote, list item, fence or rule. */
private val BLOCK_START = Regex("""^ {0,3}(?:#{1,6}(?:[ \t]|$)|>|[-*+][ \t]|[0-9]{1,9}[.)][ \t]|```|~~~|(?:[-*_][ \t]*){3,}$)""")

private fun isTableHeader(line: String, next: String?): Boolean {
	if (next == null || !line.contains('|') || !next.contains('|')) return false
	if (!TABLE_DELIMITER_ROW.matches(next)) return false
	return tableCellCount(line) == tableCellCount(next)
}

private fun tableCellCount(row: String): Int =
	row.trim().removePrefix("|").removeSuffix("|").split('|').size
