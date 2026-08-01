package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * A snapshot of every line-anchored decoration in a document, keyed by line.
 *
 * Serializers need the same three questions answered for each line — is it a
 * horizontal rule, is it an image, which block styles does it carry — and
 * answering them from [RichSpanManager] means a full scan per question. Taking
 * one snapshot up front keeps the markdown and HTML exporters reading the same
 * view of the document.
 */
internal class DocumentBlocks(
	val horizontalRuleLines: Set<Int>,
	val imageLines: Map<Int, ImageBlockSpanStyle>,
	val blockLines: Map<LineBlockStyle, Set<Int>>,
) {
	fun isEmpty(): Boolean =
		horizontalRuleLines.isEmpty() && imageLines.isEmpty() &&
			blockLines.values.all { it.isEmpty() }

	fun linesFor(block: LineBlockStyle): Set<Int> = blockLines[block] ?: emptySet()

	fun has(line: Int, block: LineBlockStyle): Boolean = line in linesFor(block)
}

/** Collects every line-anchored decoration currently attached to this document. */
internal fun TextEditorState.documentBlocks(): DocumentBlocks =
	documentBlocksOf(richSpanManager.getAllRichSpans())

/**
 * Collects the decorations in [allSpans].
 *
 * Serializers take this from the same [TextEditorState.content] snapshot they read
 * the text from, so the blocks they place and the lines they place them on come
 * from one revision.
 */
internal fun documentBlocksOf(allSpans: Set<RichSpan>): DocumentBlocks {
	return DocumentBlocks(
		horizontalRuleLines = allSpans
			.asSequence()
			.filter { it.style === HorizontalRuleSpanStyle }
			.map { it.range.start.line }
			.toHashSet(),
		imageLines = allSpans
			.asSequence()
			.mapNotNull { span ->
				val style = span.style as? ImageBlockSpanStyle ?: return@mapNotNull null
				span.range.start.line to style
			}
			.toMap(),
		blockLines = ALL_BLOCK_STYLES.associateWith { block ->
			allSpans.asSequence()
				.filter { it.style === block.spanStyle }
				.map { it.range.start.line }
				.toHashSet()
		},
	)
}

/**
 * Attaches the decorations an importer parsed out of a source document.
 *
 * Every span is published and every block line rebuilt against the current content
 * before a single relayout runs at the end. A relayout re-measures from the line it
 * is given to the end of the document, so one per block line would measure an
 * n-line import O(n²) times. No edit is recorded: loading a document is not
 * something the user should be able to undo one list item at a time.
 */
internal fun TextEditorState.applyDocumentBlocks(
	horizontalRuleLines: Collection<Int> = emptyList(),
	imageLines: Map<Int, ImageBlockSpanStyle> = emptyMap(),
	blockLines: Map<LineBlockStyle, Collection<Int>> = emptyMap(),
) = withAtomicEdit {
	val added = mutableListOf<RichSpan>()
	val removed = mutableListOf<RichSpan>()
	val lines = textLines.toMutableList()
	var rebuiltAnyLine = false

	// The GFM parser drops a lone leading space at document start, so a
	// placeholder line at index 0 can arrive empty; restore the character the
	// span's range addresses.
	fun ensurePlaceholder(line: Int, placeholder: String) {
		if (lines.getOrNull(line)?.isEmpty() == true) {
			lines[line] = AnnotatedString(placeholder)
			rebuiltAnyLine = true
		}
	}

	horizontalRuleLines.forEach { line ->
		ensurePlaceholder(line, HR_PLACEHOLDER)
		added += RichSpan(
			range = lineRange(line, HR_PLACEHOLDER.length),
			style = HorizontalRuleSpanStyle,
		)
	}
	imageLines.forEach { (line, style) ->
		ensurePlaceholder(line, IMAGE_PLACEHOLDER)
		added += RichSpan(range = lineRange(line, IMAGE_PLACEHOLDER.length), style = style)
	}

	// Invert to line -> requested blocks so each line is visited once. Within a line
	// the [ALL_BLOCK_STYLES] order decides how a stack resolves: each block demotes
	// whatever it excludes, so a fence beats a list and blockquote stacks with both.
	val requested = mutableMapOf<Int, MutableList<LineBlockStyle>>()
	ALL_BLOCK_STYLES.forEach { block ->
		blockLines[block]?.forEach { line ->
			requested.getOrPut(line) { mutableListOf() } += block
		}
	}

	for ((line, blocks) in requested) {
		var text = lines.getOrNull(line) ?: continue
		val present = ALL_BLOCK_STYLES.filter { hasLineBlock(line, it) }.toMutableList()
		// Spans staged for this line, so a block demoted after being applied in this
		// same pass is withdrawn rather than published alongside the block that
		// replaced it.
		val staged = linkedMapOf<LineBlockStyle, RichSpan>()
		for (block in blocks) {
			if (block in present) continue
			for (excluded in mutuallyExcluded(block)) {
				if (!present.remove(excluded)) continue
				if (staged.remove(excluded) == null) removed += lineBlockSpans(line, excluded)
				text = rebuildWithoutBlock(text, excluded)
			}
			staged[block] = RichSpan(lineRange(line, text.length), block.spanStyle)
			text = rebuildWithBlock(text, block)
			present += block
		}
		if (staged.isEmpty()) continue
		added += staged.values
		lines[line] = text
		rebuiltAnyLine = true
	}

	richSpanManager.removeRichSpans(removed)
	richSpanManager.addRichSpans(added)
	if (rebuiltAnyLine) setLines(lines)
	// Attaching a span is enough on its own to need the relayout, even with no line
	// rebuilt: a rule or an image resolves its height from the spans on its line wrap,
	// which only book-keeping works out.
	if (added.isNotEmpty() || removed.isNotEmpty() || rebuiltAnyLine) updateBookKeeping()
}

/** The range covering [length] characters from the start of [line]. */
private fun lineRange(line: Int, length: Int) =
	TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, length))
