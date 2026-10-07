package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import com.darkrockstudios.texteditor.state.LineSplice

/**
 * Repairs line-block invariant violations in a revision about to be published.
 * A placeholder line (blank text owned by a full-line span) may carry a
 * [Blockquote]; an image may also carry one list style; anything else is a
 * marker with nothing to decorate that cannot survive a serialization round
 * trip. Violating spans are removed and their lines rebuilt without the
 * orphaned indent. Before that, a table cell keeps its line (see [repairCells]).
 * Then every line of a fence run gets a language span if the run has a language
 * (see [repairFenceLanguages]).
 *
 * Runs on every publish, from [com.darkrockstudios.texteditor.state.TextEditorState],
 * so the invariant holds no matter which path attached the span: a toggle, an
 * importer, smart Enter, a host app on the public span API, or span
 * re-anchoring after an edit. It examines only [changed], the lines the
 * revision touched since the last publish (a violation can arise nowhere else),
 * and repairs fence languages only when [spansChanged], when a span was added,
 * removed or lost, or a line came or went: a keystroke inside a fenced line moves
 * no language. The repair is deterministic and outside the undo history; since
 * only blank lines classify as placeholders, the most it ever discards is a
 * marker on empty content, a marker or format put on a table cell, or a fence
 * language that cannot be written.
 * Returns the snapshot unchanged (no line allocation) when the document is
 * already valid, the overwhelmingly common case.
 */
internal fun normalizeLineBlocks(
	snapshot: DocumentSnapshot,
	config: RichTextStyles,
	changed: IntRange,
	spansChanged: Boolean,
): DocumentSnapshot {
	if (changed.isEmpty()) return snapshot
	val cells = if (spansChanged) repairCells(snapshot, config, changed) else snapshot
	val placeholders = repairPlaceholders(cells, config, changed)
	// A repair can drop a fence span, which leaves its language span to drop too.
	return if (spansChanged || placeholders !== snapshot) repairFenceLanguages(placeholders, changed) else placeholders
}

/**
 * Keeps every table cell's line for the cell alone, so no path, the public span API
 * included, can stack a block on a cell and break its table: another block, a rule or
 * an image, a paragraph format, or a second cell marker (the lowest column's stays) is
 * removed, a block's indent and look with it. A cell only meets one of them when a span
 * is added or a line comes or goes, so this runs only then.
 */
private fun repairCells(
	snapshot: DocumentSnapshot,
	config: RichTextStyles,
	changed: IntRange,
): DocumentSnapshot {
	val removed = ArrayList<RichSpan>()
	var lines = snapshot.lineList
	var rebuiltFirst = Int.MAX_VALUE
	var rebuiltLast = -1
	for (line in changed) {
		val starting = snapshot.spansOn(line).filter { it.range.start.line == line }
		val cell = starting.filter { it.style is TableCellSpanStyle }
			.minByOrNull { (it.style as TableCellSpanStyle).column } ?: continue
		for (span in starting) {
			if (span === cell) continue
			val block = lineBlockFor(span.style, config)
			if (block == null && span.style !is BlockSpanStyle && !span.style.boundToParagraph) continue
			removed += span
			if (block != null) {
				lines = lines.splice(line, line + 1, listOf(rebuildWithoutBlock(lines[line], block)))
				rebuiltFirst = minOf(rebuiltFirst, line)
				rebuiltLast = maxOf(rebuiltLast, line)
			}
		}
	}
	if (removed.isEmpty()) return snapshot
	val splice = if (rebuiltLast < 0) null else LineSplice(rebuiltFirst, lines.size - 1 - rebuiltLast)
	return snapshot.withLines(lines, splice).withSpanIndex(snapshot.spanIndex.minus(removed))
}

private fun repairPlaceholders(
	snapshot: DocumentSnapshot,
	config: RichTextStyles,
	changed: IntRange,
): DocumentSnapshot {
	val violations = ArrayList<Pair<RichSpan, LineBlockStyle>>()
	for (line in changed) {
		val kind = placeholderKindOf(snapshot, line) ?: continue
		for (span in snapshot.spansOn(line)) {
			if (span.range.start.line != line) continue
			val block = lineBlockFor(span.style, config) ?: continue
			if (!block.allowedOn(kind)) violations += span to block
		}
	}
	if (violations.isEmpty()) return snapshot

	var lines = snapshot.lineList
	violations.forEach { (span, block) ->
		val line = span.range.start.line
		if (line in lines.indices) lines = lines.splice(line, line + 1, listOf(rebuildWithoutBlock(lines[line], block)))
	}
	val rebuilt = violations.map { it.first.range.start.line }.filter { it in lines.indices }
	val splice = if (rebuilt.isEmpty()) null else LineSplice(rebuilt.min(), lines.size - 1 - rebuilt.max())
	return snapshot.withLines(lines, splice).withSpanIndex(snapshot.spanIndex.minus(violations.map { it.first }))
}

/**
 * Gives every line of a fence run a [CodeFenceLanguageSpanStyle] span when
 * any line of the run has one, and drops a language span off a fence or one
 * that cannot be written (see [CodeFenceLanguageSpanStyle.isWritable]).
 *
 * Carrying the language on every line, as the fence marker itself is, means
 * it survives whatever the fence survives: a split at the run's first line, a
 * join with the line above, fencing the line above, un-fencing the first line.
 * Each edit leaves some line of the run with the language, and the run's
 * other lines take it here; undoing the edit leaves it likewise. A run's
 * language is its first line's, which is what export writes; a line already
 * holding a different language keeps it, so that when two runs are joined and
 * the join undone, the second run has its language back. Two languages on one
 * line resolve to the alphabetically first; `setCodeFenceLanguage` replaces a
 * run's spans together and never leaves two on a line.
 *
 * Examines the lines in [changed] and the fence runs they touch, walked out
 * to their ends: a language can only be missing or misplaced there.
 */
private fun repairFenceLanguages(snapshot: DocumentSnapshot, changed: IntRange): DocumentSnapshot {
	fun fenced(line: Int) = snapshot.spansOn(line).any { it.style === CodeFenceSpanStyle && it.range.start.line == line }
	var first = changed.first.coerceAtLeast(0)
	var last = changed.last.coerceAtMost(snapshot.lines.size - 1)
	if (first > last) return snapshot
	while (first > 0 && fenced(first - 1)) first--
	while (last < snapshot.lines.size - 1 && fenced(last + 1)) last++

	val fenced = HashSet<Int>()
	val languagesByLine = HashMap<Int, MutableList<RichSpan>>()
	for (line in first..last) {
		for (span in snapshot.spansOn(line)) {
			if (span.range.start.line != line) continue
			when (span.style) {
				CodeFenceSpanStyle -> fenced += line
				is CodeFenceLanguageSpanStyle -> languagesByLine.getOrPut(line) { ArrayList(1) } += span
			}
		}
	}
	if (languagesByLine.isEmpty()) return snapshot

	val removed = HashSet<RichSpan>()
	val added = HashSet<RichSpan>()

	/**
	 * The one language [line] keeps, or null; spans besides it are removed. The
	 * line's own marker at column 0 wins over one an edit landed mid-line, then
	 * the alphabetically first, so the outcome does not depend on span order.
	 */
	fun languageOn(line: Int): String? {
		val present = languagesByLine[line] ?: return null
		val kept = present
			.filter { CodeFenceLanguageSpanStyle.isWritable((it.style as CodeFenceLanguageSpanStyle).language) }
			.minWithOrNull(
				compareBy<RichSpan>({ it.range.start.char != 0 }, { (it.style as CodeFenceLanguageSpanStyle).language })
			)
		present.forEach { if (it !== kept) removed += it }
		return (kept?.style as CodeFenceLanguageSpanStyle?)?.language
	}

	languagesByLine.keys.forEach { line -> if (line !in fenced) removed += languagesByLine.getValue(line) }

	fenced.forEach { line ->
		// Each run is walked once, from its first line.
		if ((line - 1) in fenced) return@forEach
		var runLast = line
		while ((runLast + 1) in fenced) runLast++
		val kept = (line..runLast).map { languageOn(it) }
		val runLanguage = kept.firstOrNull { it != null } ?: return@forEach
		kept.forEachIndexed { index, own ->
			if (own != null) return@forEachIndexed
			val member = line + index
			// A span left past the last line by a stale re-anchoring has no line to fill.
			val length = snapshot.lines.getOrNull(member)?.length ?: return@forEachIndexed
			added += RichSpan(
				range = TextEditorRange(CharLineOffset(member, 0), CharLineOffset(member, length)),
				style = CodeFenceLanguageSpanStyle(runLanguage),
			)
		}
	}
	if (removed.isEmpty() && added.isEmpty()) return snapshot
	return snapshot.withSpanIndex(snapshot.spanIndex.minus(removed).plus(added))
}

/** A revision whose lines [lines] were rewritten by [repairBlockStyles]. */
internal class RepairedLines(val snapshot: DocumentSnapshot, val lines: IntRange)

/**
 * Gives each line in [changed] exactly the paragraph styles its blocks want, and their
 * text styles, each over the whole line (see [blockStylesRepair]), or returns null when
 * every one has them.
 *
 * A line's text and its markers move separately: a join keeps one line's markers
 * while each piece brings its own line's indent and look over its part, a split
 * carries the indent onto a line the marker stays off, emptying a line drops its
 * indent while the marker stays, and a paste lands its pieces' indents before their
 * markers. Compose lays out each paragraph style run as a paragraph of its own and
 * rejects a line where two overlap, so the markers decide. Runs on every publish,
 * after [normalizeLineBlocks]; the lines it rewrites need shaping again.
 */
internal fun repairBlockStyles(
	snapshot: DocumentSnapshot,
	config: RichTextStyles,
	changed: IntRange,
): RepairedLines? {
	val lines = snapshot.lineList
	val repair = blockStylesRepair(config)
	val repaired = HashMap<Int, AnnotatedString>()
	for (line in changed.first.coerceAtLeast(0)..minOf(changed.last, lines.size - 1)) {
		val text = lines[line]
		val onLine = snapshot.spansOn(line)
		if (onLine.isEmpty() && text.paragraphStyles.isEmpty()) continue
		repair(text, onLine.filter { it.range.start.line == line })?.let { repaired[line] = it }
	}
	if (repaired.isEmpty()) return null
	val first = repaired.keys.min()
	val last = repaired.keys.max()
	val spliced = lines.splice(first, last + 1, (first..last).map { repaired[it] ?: lines[it] })
	return RepairedLines(snapshot.withLines(spliced, LineSplice(first, lines.size - 1 - last)), first..last)
}

/**
 * The placeholder kind of [line]: blank text owned by a full-line span that
 * replaces it. When two full-line spans share a line, the stricter policy applies.
 */
internal fun placeholderKindOf(snapshot: DocumentSnapshot, line: Int): PlaceholderKind? {
	val text = snapshot.lines.getOrNull(line) ?: return null
	if (!text.isBlank()) return null
	var kind: PlaceholderKind? = null
	for (span in snapshot.spansOn(line)) {
		if (span.range.start.line != line) continue
		if ((span.style as? BlockSpanStyle)?.replacesText() != true) continue
		val own = if (span.style is ImageBlockSpanStyle) PlaceholderKind.IMAGE else PlaceholderKind.OTHER
		if (kind != PlaceholderKind.OTHER) kind = own
	}
	return kind
}
