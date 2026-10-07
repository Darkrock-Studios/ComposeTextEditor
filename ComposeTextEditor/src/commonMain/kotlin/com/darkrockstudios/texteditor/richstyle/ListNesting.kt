package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Nesting and un-nesting list items, and keeping the items that follow an
 * edited one at levels the text forms can hold. See `docs/design/line-blocks.md`,
 * "Nested lists".
 */

/**
 * Whether a line with [text] and [spansOnLine] is one nesting looks through:
 * blank text with no block but a quote. A format's exporter and importer read
 * the same definition, so what the editor nests, the file holds.
 */
fun isNestingBlank(text: AnnotatedString, spansOnLine: Iterable<RichSpan>): Boolean =
	text.isBlank() && spansOnLine.none { span ->
		val style = span.style
		style.listBlock() != null || style is BlockSpanStyle || style === CodeFenceSpanStyle || style is HeaderSpanStyle ||
			style.inlineOnly
	}

internal fun TextEditorState.isNestingBlank(line: Int): Boolean =
	isNestingBlank(textLines[line], richSpanManager.getRichSpansStartingOn(line))

private fun TextEditorState.isQuotedLine(line: Int): Boolean = hasLineBlock(line, Blockquote)

/**
 * List items moved to new levels, planned over the document and written together:
 * a selection of items nests with one write of the lines and spans, not one per
 * item. The level queries read the planned levels, so each move sees the moves
 * before it.
 */
internal class ListMoves(private val state: TextEditorState) {
	/** Each planned line's block as it stands, and the block it moves to. */
	private val moves = LinkedHashMap<Int, Pair<LineBlockStyle, LineBlockStyle>>()

	/** The lines planned to move. */
	val lines: Set<Int> get() = moves.keys

	/** The list block on [line], at its planned level. */
	fun blockAt(line: Int): LineBlockStyle? = moves[line]?.second ?: state.listBlockAt(line)

	/** Plans the list item on [line] at [level]. */
	fun move(line: Int, level: Int) {
		val original = moves[line]?.first ?: state.listBlockAt(line) ?: return
		val target = original.atListLevel(level)
		if (target === original) moves.remove(line) else moves[line] = original to target
	}

	/** Writes every planned move, each item's span and indent together, through the direct path. */
	fun write() {
		state.writeLineBlocks(moves.mapNotNull { (line, move) -> state.planLineBlock(line, move.second) })
		moves.clear()
	}
}

/** The level of the list item before [line], skipping blank lines, with [quoted] status; -1 when none. */
private fun TextEditorState.previousListLevel(line: Int, quoted: Boolean, moves: ListMoves): Int {
	var i = line - 1
	while (i >= 0 && isQuotedLine(i) == quoted) {
		moves.blockAt(i)?.let { return it.listLevel!! }
		if (!isNestingBlank(i)) return -1
		i--
	}
	return -1
}

/**
 * The deepest level a list item on [line] may have: one below the list item
 * before it, skipping blank lines, with the same quote status; otherwise 0.
 */
private fun TextEditorState.allowedListLevel(line: Int, moves: ListMoves): Int =
	minOf(previousListLevel(line, isQuotedLine(line), moves) + 1, MAX_LIST_LEVEL)

/**
 * Brings the list items from [from] on back to at most one level below their
 * predecessor, whose level is now [previousLevel], after the item before them
 * was lowered from or cleared at [editedLevel]: the items deeper than
 * [editedLevel] that hung under it all come up by the same amount, and the
 * first item at or above [editedLevel], a sibling, ends the shift. Only lines
 * with [quoted] status are reached. Plans the moves in [moves]; callers write
 * and record them.
 */
private fun TextEditorState.relevelListFollowers(
	from: Int,
	editedLevel: Int,
	quoted: Boolean,
	previousLevel: Int,
	moves: ListMoves,
) {
	var previous = previousLevel
	var shift = 0
	for (line in from until textLines.size) {
		if (isQuotedLine(line) != quoted) return
		val block = moves.blockAt(line)
		if (block == null) {
			if (isNestingBlank(line)) continue else return
		}
		val level = block.listLevel!!
		// A sibling of the edited item, or any top-level item, is outside its subtree.
		if (level <= editedLevel || level == 0) shift = 0
		var target = maxOf(level - shift, 0)
		val allowed = previous + 1
		if (target > allowed) {
			shift += target - allowed
			target = allowed
		}
		if (target != level) moves.move(line, target)
		previous = target
		// Once a line stands where it stood, nothing after it is affected.
		if (shift == 0 && target == level) return
	}
}

/**
 * Runs [mutate] over [targets] and records it, with what it does to the items
 * after them, as one LineBlock undo step. The last target that is a list item
 * before [mutate] is the one whose followers can be orphaned: once [mutate]
 * has run, the items nested under it come up to what it now allows (its new
 * level, or nothing at all when it stopped being a list item with their quote
 * status), the subtree moving together. Level moves [mutate] plans in the
 * [ListMoves] it is given are written with the followers', in one write, and
 * each line is captured before it is first written, so undo restores the
 * followers exactly.
 */
internal fun TextEditorState.recordListEdit(targets: List<Int>, mutate: (ListMoves) -> Unit) = withAtomicEdit {
	val cursorBefore = cursorPosition
	val before = editManager.lineBlocksOf(targets)
	val moves = ListMoves(this)
	val last = targets.lastOrNull { listBlockAt(it) != null }
	val lastLevel = last?.let { listBlockAt(it)!!.listLevel!! }
	val quoted = last?.let { isQuotedLine(it) }
	mutate(moves)
	// A target moved into another quote can be left deeper than the items before it
	// there allow: from each run of targets in one quote, the items come up together.
	var runReleveled = false
	targets.forEachIndexed { index, line ->
		val quote = isQuotedLine(line)
		if (index == 0 || targets[index - 1] != line - 1 || isQuotedLine(line - 1) != quote) runReleveled = false
		if (runReleveled || moves.blockAt(line) == null) return@forEachIndexed
		relevelListFollowers(line, editedLevel = -1, quote, previousListLevel(line, quote, moves), moves)
		runReleveled = true
	}
	if (last != null && lastLevel != null && quoted != null) {
		val previousLevel = when {
			isQuotedLine(last) != quoted -> -1
			else -> moves.blockAt(last)?.listLevel
				?: if (isNestingBlank(last)) previousListLevel(last, quoted, moves) else -1
		}
		relevelListFollowers(last + 1, lastLevel, quoted, previousLevel, moves)
	}
	val followers = editManager.lineBlocksOf(moves.lines.filter { it !in targets })
	moves.write()
	editManager.recordLineBlocksSince(before + followers, cursorBefore)
}

/**
 * Nests each list item in [lines] one level, never deeper than one below the
 * item before it (so a selection nests as one block, its first item bounded
 * by the item above the selection). An item's followers stay where they are,
 * its former children now its siblings, as Google Docs has it. One undo step;
 * returns whether any item moved.
 */
fun TextEditorState.nestListItems(lines: IntRange): Boolean {
	val targets = lines.filter { it in textLines.indices && listBlockAt(it) != null }
	if (targets.isEmpty()) return false
	var moved = false
	val moves = ListMoves(this)
	editManager.recordLineBlockChanges(targets) {
		targets.forEach { line ->
			val level = moves.blockAt(line)?.listLevel ?: return@forEach
			if (level < allowedListLevel(line, moves)) {
				moves.move(line, level + 1)
				moved = true
			}
		}
		moves.write()
	}
	return moved
}

/**
 * Un-nests each nested list item in [lines] one level, as Shift+Tab does.
 * Only the selected items move, as in Google Docs; the items nested under the
 * last of them come up with it, so nothing is left deeper than it allows. One
 * undo step; returns whether any item moved.
 */
fun TextEditorState.unnestListItems(lines: IntRange): Boolean {
	val targets = lines.filter { line ->
		line in textLines.indices && (listBlockAt(line)?.listLevel ?: 0) > 0
	}
	if (targets.isEmpty()) return false
	recordListEdit(targets) { moves ->
		targets.forEach { line ->
			val level = moves.blockAt(line)?.listLevel ?: return@forEach
			moves.move(line, level - 1)
		}
	}
	return true
}

/** Whether both blocks are list items of the same kind, at whatever levels. */
internal fun LineBlockStyle.sameListKind(other: LineBlockStyle): Boolean =
	(spanStyle is BulletListSpanStyle && other.spanStyle is BulletListSpanStyle) ||
		(spanStyle is OrderedListSpanStyle && other.spanStyle is OrderedListSpanStyle)
