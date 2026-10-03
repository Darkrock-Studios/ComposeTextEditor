package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Nesting and un-nesting list items, and keeping the items that follow an
 * edited one at levels markdown can hold. See `docs/design/line-blocks.md`,
 * "Nested lists".
 */

/**
 * Whether a line with [text] and [spansOnLine] is one nesting looks through:
 * blank text with no block but a quote. The exporter and the importer read
 * the same definition, so what the editor nests, the file holds.
 */
internal fun isNestingBlank(text: AnnotatedString, spansOnLine: Iterable<RichSpan>): Boolean =
	text.isBlank() && spansOnLine.none { span ->
		val style = span.style
		style.listBlock() != null || style is BlockSpanStyle || style === CodeFenceSpanStyle || style is HeaderSpanStyle
	}

internal fun TextEditorState.isNestingBlank(line: Int): Boolean =
	isNestingBlank(textLines[line], richSpanManager.getRichSpansStartingOn(line))

private fun TextEditorState.isQuotedLine(line: Int): Boolean = hasLineBlock(line, Blockquote)

/** The level of the list item before [line], skipping blank lines, with [quoted] status; -1 when none. */
private fun TextEditorState.previousListLevel(line: Int, quoted: Boolean): Int {
	var i = line - 1
	while (i >= 0 && isQuotedLine(i) == quoted) {
		listBlockAt(i)?.let { return it.listLevel!! }
		if (!isNestingBlank(i)) return -1
		i--
	}
	return -1
}

/**
 * The deepest level a list item on [line] may have: one below the list item
 * before it, skipping blank lines, with the same quote status; otherwise 0.
 */
internal fun TextEditorState.allowedListLevel(line: Int): Int =
	minOf(previousListLevel(line, isQuotedLine(line)) + 1, MAX_LIST_LEVEL)

/**
 * The lines after [line] that a change of its level can reach: the following
 * list items and blank lines with [quoted] status, up to and including the
 * first item at or above [level], which is where [relevelListFollowers] stops.
 */
private fun TextEditorState.listFollowerLines(line: Int, level: Int, quoted: Boolean): List<Int> {
	val followers = mutableListOf<Int>()
	var i = line + 1
	while (i < textLines.size && isQuotedLine(i) == quoted) {
		val block = listBlockAt(i)
		if (block == null && !isNestingBlank(i)) break
		followers += i
		if (block != null && block.listLevel!! <= level) break
		i++
	}
	return followers
}

/**
 * Brings the list items from [from] on back to at most one level below their
 * predecessor, whose level is now [previousLevel], after the item before them
 * was lowered from or cleared at [editedLevel]: the items deeper than
 * [editedLevel] that hung under it all come up by the same amount, and the
 * first item at or above [editedLevel], a sibling, ends the shift. Only lines
 * with [quoted] status are reached. Mutates lines through the direct path;
 * callers record the change.
 */
private fun TextEditorState.relevelListFollowers(from: Int, editedLevel: Int, quoted: Boolean, previousLevel: Int) {
	var previous = previousLevel
	var shift = 0
	for (line in from until textLines.size) {
		if (isQuotedLine(line) != quoted) return
		val block = listBlockAt(line)
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
		if (target != level) setListLevelRaw(line, block, target)
		previous = target
		// Once a line stands where it stood, nothing after it is affected.
		if (shift == 0 && target == level) return
	}
}

/** Moves the list item on [line] to [level] through the direct path: the span and the indent together. */
internal fun TextEditorState.setListLevelRaw(line: Int, block: LineBlockStyle, level: Int) {
	val target = block.atListLevel(level)
	if (target === block) return
	val existing = textLines[line]
	removeLineBlockSpans(line, block)
	addLineBlockSpan(line, existing.length, target)
	updateLine(line, rebuildWithBlock(rebuildWithoutBlock(existing, block), target))
}

/**
 * Runs [mutate] over [targets] and records it, with what it does to the items
 * after them, as one LineBlock undo step. The last target that is a list item
 * before [mutate] is the one whose followers can be orphaned: once [mutate]
 * has run, the items nested under it come up to what it now allows (its new
 * level, or nothing at all when it stopped being a list item with their quote
 * status), the subtree moving together. Every line that can change is
 * captured before [mutate], so undo restores followers exactly.
 */
internal fun TextEditorState.recordListEdit(targets: List<Int>, mutate: () -> Unit) {
	val last = targets.lastOrNull { listBlockAt(it) != null }
	if (last == null) {
		editManager.recordLineBlockChanges(targets, mutate)
		return
	}
	val lastLevel = listBlockAt(last)!!.listLevel!!
	val quoted = isQuotedLine(last)
	val followers = listFollowerLines(last, lastLevel, quoted)
	editManager.recordLineBlockChanges(targets + followers) {
		mutate()
		val previousLevel = when {
			isQuotedLine(last) != quoted -> -1
			listBlockAt(last) != null -> listBlockAt(last)!!.listLevel!!
			isNestingBlank(last) -> previousListLevel(last, quoted)
			else -> -1
		}
		relevelListFollowers(last + 1, lastLevel, quoted, previousLevel)
	}
}

/**
 * Nests each list item in [lines] one level, never deeper than one below the
 * item before it (so a selection nests as one block, its first item bounded
 * by the item above the selection). An item's followers stay where they are,
 * its former children now its siblings, as Google Docs has it. One undo step;
 * returns whether any item moved.
 */
internal fun TextEditorState.nestListItems(lines: IntRange): Boolean {
	val targets = lines.filter { it in textLines.indices && listBlockAt(it) != null }
	if (targets.isEmpty()) return false
	var moved = false
	editManager.recordLineBlockChanges(targets) {
		targets.forEach { line ->
			val block = listBlockAt(line) ?: return@forEach
			val level = block.listLevel!!
			if (level < allowedListLevel(line)) {
				setListLevelRaw(line, block, level + 1)
				moved = true
			}
		}
	}
	return moved
}

/**
 * Un-nests each nested list item in [lines] one level, as Shift+Tab does.
 * Only the selected items move, as in Google Docs; the items nested under the
 * last of them come up with it, so nothing is left deeper than it allows. One
 * undo step; returns whether any item moved.
 */
internal fun TextEditorState.unnestListItems(lines: IntRange): Boolean {
	val targets = lines.filter { line ->
		line in textLines.indices && (listBlockAt(line)?.listLevel ?: 0) > 0
	}
	if (targets.isEmpty()) return false
	recordListEdit(targets) {
		targets.forEach { line ->
			val block = listBlockAt(line) ?: return@forEach
			setListLevelRaw(line, block, block.listLevel!! - 1)
		}
	}
	return true
}

/** Whether both blocks are list items of the same kind, at whatever levels. */
internal fun LineBlockStyle.sameListKind(other: LineBlockStyle): Boolean =
	(spanStyle is BulletListSpanStyle && other.spanStyle is BulletListSpanStyle) ||
		(spanStyle is OrderedListSpanStyle && other.spanStyle is OrderedListSpanStyle)
