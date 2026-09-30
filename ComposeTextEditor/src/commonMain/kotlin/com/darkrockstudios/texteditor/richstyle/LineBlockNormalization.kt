package com.darkrockstudios.texteditor.richstyle

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
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
 * re-anchoring after an edit. The repair is deterministic and outside the undo
 * history; since only blank lines classify as placeholders, the most it ever
 * discards is a marker on empty content, or a fence language that cannot be
 * written. Returns the snapshot unchanged (no line allocation) when the
 * document is already valid, the overwhelmingly common case.
 */
internal fun normalizeLineBlocks(
	snapshot: DocumentSnapshot,
	config: MarkdownConfiguration,
): DocumentSnapshot = repairFenceLanguages(repairPlaceholders(snapshot, config))

private fun repairPlaceholders(
	snapshot: DocumentSnapshot,
	config: MarkdownConfiguration,
): DocumentSnapshot {
	val kinds = placeholderKinds(snapshot.richSpans, snapshot.lines)
	if (kinds.isEmpty()) return snapshot

	val registry = allBlockStyles(config)
	val violations = snapshot.richSpans.mapNotNull { span ->
		val block = registry.firstOrNull { it.spanStyle === span.style }
			?: return@mapNotNull null
		val kind = kinds[span.range.start.line] ?: return@mapNotNull null
		if (block.allowedOn(kind)) null else span to block
	}
	if (violations.isEmpty()) return snapshot

	var lines = snapshot.lineList
	violations.forEach { (span, block) ->
		val line = span.range.start.line
		if (line in lines.indices) lines = lines.splice(line, line + 1, listOf(rebuildWithoutBlock(lines[line], block)))
	}
	val rebuilt = violations.map { it.first.range.start.line }.filter { it in lines.indices }
	val splice = if (rebuilt.isEmpty()) null else LineSplice(rebuilt.min(), lines.size - 1 - rebuilt.max())
	return snapshot.withLines(lines, splice).withRichSpans(snapshot.richSpans - violations.map { it.first }.toSet())
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
 */
private fun repairFenceLanguages(snapshot: DocumentSnapshot): DocumentSnapshot {
	val spans = snapshot.richSpans
	if (spans.none { it.style is CodeFenceLanguageSpanStyle }) return snapshot

	val fenced = HashSet<Int>()
	val languagesByLine = HashMap<Int, MutableList<RichSpan>>()
	spans.forEach { span ->
		when (span.style) {
			CodeFenceSpanStyle -> fenced += span.range.start.line
			is CodeFenceLanguageSpanStyle ->
				languagesByLine.getOrPut(span.range.start.line) { ArrayList(1) } += span
		}
	}

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
		var last = line
		while ((last + 1) in fenced) last++
		val kept = (line..last).map { languageOn(it) }
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
	return snapshot.withRichSpans(spans - removed + added)
}
