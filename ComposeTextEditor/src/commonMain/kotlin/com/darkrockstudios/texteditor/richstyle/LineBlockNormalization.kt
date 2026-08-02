package com.darkrockstudios.texteditor.richstyle

import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.state.DocumentSnapshot

/**
 * Repairs line-block invariant violations in a revision about to be published.
 * A placeholder line (blank text owned by a full-line span) may carry a
 * [Blockquote]; an image may also carry one list style; anything else is a
 * marker with nothing to decorate that cannot survive a serialization round
 * trip. Violating spans are removed and their lines rebuilt without the
 * orphaned indent.
 *
 * Runs on every publish, from [com.darkrockstudios.texteditor.state.TextEditorState],
 * so the invariant holds no matter which path attached the span: a toggle, an
 * importer, smart Enter, a host app on the public span API, or span
 * re-anchoring after an edit. The repair is deterministic and outside the undo
 * history; since only blank lines classify as placeholders, the most it can
 * ever discard is a marker on empty content. Returns the snapshot unchanged
 * (no line allocation) when the document is already valid, the overwhelmingly
 * common case.
 */
internal fun normalizeLineBlocks(
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

	val lines = snapshot.lines.toMutableList()
	violations.forEach { (span, block) ->
		val line = span.range.start.line
		lines.getOrNull(line)?.let { lines[line] = rebuildWithoutBlock(it, block) }
	}
	return DocumentSnapshot(lines, snapshot.richSpans - violations.map { it.first }.toSet())
}
