package com.darkrockstudios.texteditor.richstyle

import com.darkrockstudios.texteditor.CharLineOffset
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
 * Rules and images go through the manager directly while block styles go through
 * [applyLineBlock], which also installs the indent paragraph style. Neither path
 * records an edit: loading a document is not something the user should be able to
 * undo one list item at a time.
 */
internal fun TextEditorState.applyDocumentBlocks(
	horizontalRuleLines: Collection<Int> = emptyList(),
	imageLines: Map<Int, ImageBlockSpanStyle> = emptyMap(),
	blockLines: Map<LineBlockStyle, Collection<Int>> = emptyMap(),
) {
	horizontalRuleLines.forEach { line ->
		richSpanManager.addRichSpan(
			start = CharLineOffset(line, 0),
			end = CharLineOffset(line, HR_PLACEHOLDER.length),
			style = HorizontalRuleSpanStyle,
		)
	}
	imageLines.forEach { (line, style) ->
		richSpanManager.addRichSpan(
			start = CharLineOffset(line, 0),
			end = CharLineOffset(line, IMAGE_PLACEHOLDER.length),
			style = style,
		)
	}
	// Blockquote before the list styles: `applyLineBlock` demotes anything
	// mutually exclusive with what it is applying, and the two stack, so the
	// order only has to keep a list from arriving while a fence is pending.
	ALL_BLOCK_STYLES.forEach { block ->
		blockLines[block]?.forEach { applyLineBlock(it, block) }
	}
}
