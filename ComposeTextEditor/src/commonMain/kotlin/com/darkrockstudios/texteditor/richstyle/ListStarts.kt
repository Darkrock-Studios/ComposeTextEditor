package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.LayoutUpdate
import com.darkrockstudios.texteditor.state.OrderedListKind
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Keeping a list's start ([OrderedListStartSpanStyle]) on its first item through the
 * edits that change which item that is. See `docs/design/line-blocks.md`, "List
 * starts".
 */

/** The start [line] holds, or null for none. */
internal fun TextEditorState.listStartAt(line: Int): Int? =
	OrderedListKind.startValueOn(richSpanManager.getRichSpansStartingOn(line), line)

/**
 * The first item of the ordered list [line] is an item of, or null when it is no
 * ordered item: up from [line], past the lines nested deeper, to the first line that
 * is not an ordered item at its level, as the numbering walk counts.
 */
internal fun TextEditorState.orderedListRunStart(line: Int): Int? {
	if (line !in textLines.indices) return null
	val block = listBlockAt(line)?.takeIf { it.spanStyle is OrderedListSpanStyle } ?: return null
	val level = block.listLevel!!
	var first = line
	for (above in line - 1 downTo 0) {
		val list = listBlockAt(above) ?: break
		val aboveLevel = list.listLevel!!
		if (aboveLevel > level) continue
		if (aboveLevel < level || list.spanStyle !is OrderedListSpanStyle) break
		first = above
	}
	return first
}

/** Puts [start] on [line] in place of any start it holds, or takes its start off for null, and lays it out. */
internal fun TextEditorState.writeListStart(line: Int, start: Int?) {
	val styles = lineBlockSpanStyles(line).filter { it !is OrderedListStartSpanStyle } +
		listOfNotNull(start?.let { OrderedListStartSpanStyle(it) })
	writeLineBlocks(listOf(LineBlockWrite(line, textLines[line], styles)))
	updateBookKeeping(LayoutUpdate.Spans(line, line))
}

/**
 * The start of the list an ordered item at [level] on [line] opened, before an edit
 * that may take the item out of it; [heirAt] is the line its list's next item lands
 * on after a deletion taking it, when it survives the deletion.
 */
internal class HeldListStart(val line: Int, val level: Int, val start: Int, val heirAt: Int? = null)

/** The start [line] holds while it opens its list, or null. */
internal fun TextEditorState.heldListStart(line: Int): HeldListStart? {
	val start = listStartAt(line) ?: return null
	if (orderedListRunStart(line) != line) return null
	return HeldListStart(line, listBlockAt(line)!!.listLevel!!, start)
}

/**
 * Hands each of [held] whose item no longer opens its list with it (the item left
 * the list, or changed level or kind) to the item after it that opens the list now,
 * and takes it off the item. Returns the lines written that [captured] does not
 * hold, each as it stood before, for the caller's undo step.
 */
internal fun TextEditorState.handOnListStarts(
	held: List<HeldListStart>,
	captured: Set<Int>,
): List<Triple<Int, AnnotatedString, List<RichSpanStyle>>> {
	val written = ArrayList<Triple<Int, AnnotatedString, List<RichSpanStyle>>>()
	for (start in held) {
		val now = listBlockAt(start.line)
		val stillOpens = now?.spanStyle is OrderedListSpanStyle && now.listLevel == start.level &&
			heldListStart(start.line)?.start == start.start
		if (stillOpens) continue
		val heir = nextListItem(start.line, start.level)?.takeIf { listStartAt(it) == null && orderedListRunStart(it) == it } ?: continue
		if (heir !in captured) written += editManager.lineBlocksOf(listOf(heir))
		writeListStart(heir, start.start)
		if (listStartAt(start.line) != null) writeListStart(start.line, null)
	}
	return written
}

/**
 * Moves the start [from] holds onto [to], the empty item a line break at [from]'s start
 * left above it as its list's first item, so the list keeps counting from it.
 */
internal fun TextEditorState.moveListStartUp(from: Int, to: Int) {
	val start = listStartAt(from) ?: return
	val list = listBlockAt(to) ?: return
	if (list !== listBlockAt(from) || listStartAt(to) != null) return
	writeListStart(from, null)
	writeListStart(to, start)
}

/**
 * The list starts a deletion or replace of [range], putting [breaks] line breaks in
 * its place, takes with the items it takes: the starts of the lists whose first item
 * is on a line it joins away, or on its first line when it starts there at the line's
 * start. Each comes with where its list's next item that the edit leaves lands.
 */
internal fun TextEditorState.listStartsTakenBy(range: TextEditorRange, breaks: Int): List<HeldListStart> {
	if (range.isSingleLine()) return emptyList()
	val first = if (range.start.char == 0) range.start.line else range.start.line + 1
	return (first..minOf(range.end.line, textLines.size - 1)).mapNotNull { line ->
		val held = heldListStart(line) ?: return@mapNotNull null
		// The range's last line keeps its item only when the range starts at a line's start.
		var next = nextListItem(line, held.level)
		while (next != null && next <= range.end.line && !(next == range.end.line && range.start.char == 0)) {
			next = nextListItem(next, held.level)
		}
		val heir = next ?: return@mapNotNull null
		val lands = if (heir == range.end.line) range.start.line + breaks else heir - (range.end.line - range.start.line) + breaks
		HeldListStart(held.line, held.level, held.start, heirAt = lands)
	}
}

/** The ordered item at [level] after [line] in its list, past the items nested deeper, or null. */
private fun TextEditorState.nextListItem(line: Int, level: Int): Int? {
	for (next in line + 1 until textLines.size) {
		val list = listBlockAt(next) ?: return null
		val nextLevel = list.listLevel!!
		if (nextLevel > level) continue
		return next.takeIf { nextLevel == level && list.spanStyle is OrderedListSpanStyle }
	}
	return null
}

/**
 * Gives each of [held], taken by a deletion or replace, to the item that opens its
 * list now, its [HeldListStart.heirAt], when that is so and holds no start of its own.
 * Recorded as a step of its own, which the edit's undo group takes, so undoing it
 * takes the start off that item before the edit's own undo puts the taken item back
 * with it.
 */
internal fun TextEditorState.keepListStartsAfterJoin(held: List<HeldListStart>) {
	val heirs = held.mapNotNull { start ->
		val heir = start.heirAt?.takeIf { it in textLines.indices } ?: return@mapNotNull null
		val list = listBlockAt(heir)
		val opens = list?.spanStyle is OrderedListSpanStyle && list.listLevel == start.level &&
			listStartAt(heir) == null && orderedListRunStart(heir) == heir
		if (opens) heir to start.start else null
	}.distinctBy { it.first }
	if (heirs.isEmpty()) return
	editManager.recordLineBlockChanges(heirs.map { it.first }) {
		heirs.forEach { (heir, start) -> writeListStart(heir, start) }
	}
}
