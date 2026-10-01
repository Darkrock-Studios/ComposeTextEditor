package com.darkrockstudios.texteditor.richstyle

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
 * orphaned indent. Then every line of a fence run gets a language span if the
 * run has a language (see [repairFenceLanguages]).
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
 * marker on empty content, or a fence language that cannot be written.
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
	val placeholders = repairPlaceholders(snapshot, config, changed)
	// A placeholder repair can drop a fence span, which leaves its language span to drop too.
	return if (spansChanged || placeholders !== snapshot) repairFenceLanguages(placeholders, changed) else placeholders
}

private fun repairPlaceholders(
	snapshot: DocumentSnapshot,
	config: RichTextStyles,
	changed: IntRange,
): DocumentSnapshot {
	val registry = allBlockStyles(config)
	val violations = ArrayList<Pair<RichSpan, LineBlockStyle>>()
	for (line in changed) {
		val kind = placeholderKindOf(snapshot, line) ?: continue
		for (span in snapshot.spansOn(line)) {
			if (span.range.start.line != line) continue
			val block = registry.firstOrNull { it.spanStyle === span.style } ?: continue
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
