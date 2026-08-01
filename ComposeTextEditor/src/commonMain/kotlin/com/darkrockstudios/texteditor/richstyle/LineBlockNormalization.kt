package com.darkrockstudios.texteditor.richstyle

import com.darkrockstudios.texteditor.state.DocumentSnapshot

/**
 * Repairs line-block invariant violations in a revision about to be published:
 * a placeholder line (rule, image) may carry a [Blockquote] span but never a
 * list or fence span, because those have nothing to decorate there and their
 * markers do not survive a serialization round trip. Violating spans are
 * removed and their lines rebuilt without the orphaned indent.
 *
 * Runs on every publish, from [com.darkrockstudios.texteditor.state.TextEditorState],
 * so the invariant holds no matter which path attached the span: a toggle, an
 * importer, smart Enter, a host app on the public span API, or span
 * re-anchoring after an edit. Returns the snapshot unchanged (no allocation)
 * when the document is already valid, which is the overwhelmingly common case.
 */
internal fun normalizeLineBlocks(snapshot: DocumentSnapshot): DocumentSnapshot {
	val placeholders = placeholderLines(snapshot.richSpans)
	if (placeholders.isEmpty()) return snapshot

	val violations = snapshot.richSpans.mapNotNull { span ->
		val block = ALL_BLOCK_STYLES.firstOrNull { it.spanStyle === span.style }
		if (block != null && !allowedOnPlaceholderLine(block) &&
			span.range.start.line in placeholders
		) span to block else null
	}
	if (violations.isEmpty()) return snapshot

	val lines = snapshot.lines.toMutableList()
	violations.forEach { (span, block) ->
		val line = span.range.start.line
		lines.getOrNull(line)?.let { lines[line] = rebuildWithoutBlock(it, block) }
	}
	return DocumentSnapshot(lines, snapshot.richSpans - violations.map { it.first }.toSet())
}
