package com.darkrockstudios.texteditor.html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.withSpanStyles
import com.darkrockstudios.texteditor.clipboard.withBodyStyleBeneath
import com.darkrockstudios.texteditor.richstyle.Blockquote
import com.darkrockstudios.texteditor.richstyle.CodeFence
import com.darkrockstudios.texteditor.richstyle.DocumentBlocks
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.ImageProvider
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.richstyle.listLevel
import com.darkrockstudios.texteditor.richstyle.documentBlocksOf
import com.darkrockstudios.texteditor.state.LayoutUpdate
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.paragraphFormat

/**
 * An extension to [TextEditorState] that reads and writes the document as HTML.
 *
 * Where the `AnnotatedString` converters handle inline styling alone, this
 * carries the whole document: headings, lists, blockquotes, code fences,
 * horizontal rules, images and links all survive the round trip. Link
 * destinations pass [sanitizeLinkUrl] both ways, so a `javascript:` link is
 * neither imported nor written.
 */
class HtmlExtension(
	val editorState: TextEditorState,
	var imageProvider: ImageProvider? = null,
) {
	init {
		// Installs the styles: an HTML document carries the body style, so typed text
		// takes it too (see TextEditorState.richTextStyles).
		editorState.richTextStyles = editorState.richTextStyles
	}

	/**
	 * Serializes the document to an HTML fragment: no `<html>` or `<body>`
	 * wrapper, so it can be embedded directly or written to a file as-is.
	 *
	 * Safe to call from any thread: the text and the blocks come from one snapshot,
	 * so a concurrent edit can neither interrupt the walk nor place a block on a
	 * line index belonging to a different revision.
	 */
	fun exportAsHtml(): String {
		val content = editorState.content
		val styles = editorState.richTextStyles
		val blocks = documentBlocksOf(content.richSpans, styles)
		val headerLevels = headerLevelsOf(content.richSpans)
		val formats = content.paragraphFormats(content.lines.indices)
		val lines = content.lines
		val links = linksByLine(content.richSpans) { lines.getOrNull(it)?.length ?: 0 }
		if (lines.size == 1 && lines[0].isEmpty() && blocks.isEmpty() && headerLevels.isEmpty() && formats.isEmpty()) {
			return ""
		}

		return renderHtmlFragment(
			lines = lines.mapIndexed { index, line -> HtmlLine(line, index, links = links[index].orEmpty()) },
			blocks = blocks,
			headerLevels = headerLevels,
			formats = formats,
			styles = styles,
			retiredStyles = editorState.retiredRichTextStyles,
			allowedLinkSchemes = editorState.allowedLinkSchemes,
		)
	}

	/**
	 * Replaces the document with [html], parsed as a fragment.
	 *
	 * Images are only reconstructed when an [imageProvider] is set; without one
	 * every `<img>` is dropped rather than left as a blank line.
	 */
	fun importHtml(html: String) {
		val provider = imageProvider
		val document = parseHtmlDocument(
			html = html,
			styles = editorState.richTextStyles,
			includeImages = provider != null,
			allowedLinkSchemes = editorState.allowedLinkSchemes,
		)
		// One revision, so a concurrent export can't catch the document loaded but
		// not yet styled.
		editorState.withAtomicEdit {
			editorState.setText(editorState.withBodyStyleBeneath(document.text))
			if (document.links.isNotEmpty()) {
				editorState.richSpanManager.addRichSpans(pastedLinkSpans(document.links, CharLineOffset(0, 0)))
				editorState.updateBookKeeping(LayoutUpdate.SpansOnly)
			}
			editorState.addParagraphFormats(document.paragraphFormats)
			editorState.applyDocumentBlocks(
				horizontalRuleLines = document.horizontalRuleLines,
				imageLines = if (provider == null) {
					emptyMap()
				} else {
					document.imageLines.mapValues { (_, image) ->
						ImageBlockSpanStyle(source = image.source, alt = image.alt, provider = provider)
					}
				},
				blockLines = document.blockLines,
			)
		}
	}

}

/** A line to serialize, paired with the document line its decorations come from. */
internal class HtmlLine(
	val text: AnnotatedString,
	val docLine: Int,
	/** The links over [text], in its own offsets. */
	val links: List<HtmlLink> = emptyList(),
)

/** The format of each of [lines] that has one, read as the layout reads it. */
internal fun DocumentSnapshot.paragraphFormats(lines: IntRange): Map<Int, ParagraphFormatSpanStyle> {
	if (richSpans.none { it.style is ParagraphFormatSpanStyle }) return emptyMap()
	return lines.mapNotNull { line -> spansOn(line).paragraphFormat(line)?.let { line to it } }.toMap()
}

/**
 * Gives each line in [formats] its paragraph format, in place of any it had (a pasted
 * paragraph's own replaces the one a paste at a line's start leaves on it), through the
 * direct path as the blocks are; a paste records both in its step
 * (`TextEditManager.recordLineChanges`).
 */
internal fun TextEditorState.addParagraphFormats(formats: Map<Int, ParagraphFormatSpanStyle>) {
	val replaced = mutableListOf<RichSpan>()
	val spans = formats.mapNotNull { (line, format) ->
		val text = textLines.getOrNull(line) ?: return@mapNotNull null
		replaced += workingContent.spansOn(line).filter { it.style is ParagraphFormatSpanStyle && it.range.start.line == line }
		RichSpan(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, text.length)), format)
	}
	if (spans.isEmpty()) return
	richSpanManager.removeRichSpans(replaced)
	richSpanManager.addRichSpans(spans)
	// The lines' text is shaped by the pass their insert posted, which reads the spans then.
	updateBookKeeping(LayoutUpdate.SpansOnly)
}

/** The semantic heading level of each line that carries a [HeaderSpanStyle]. */
internal fun headerLevelsOf(spans: Set<RichSpan>): Map<Int, Int> =
	spans
		.mapNotNull { span ->
			(span.style as? HeaderSpanStyle)?.let { span.range.start.line to it.level }
		}
		.toMap()

/**
 * Writes [lines] as an HTML fragment, taking each line's block structure from
 * [blocks] by its [HtmlLine.docLine]. Shared by whole-document export and by the
 * clipboard, so a copied selection carries the same markup a save would. A span
 * carrying one of [retiredStyles]' styles writes as that style's markup, and a link
 * to a scheme outside [allowedLinkSchemes] as its text alone.
 */
internal fun renderHtmlFragment(
	lines: List<HtmlLine>,
	blocks: DocumentBlocks,
	headerLevels: Map<Int, Int>,
	formats: Map<Int, ParagraphFormatSpanStyle>,
	styles: RichTextStyles,
	retiredStyles: List<RichTextStyles>,
	allowedLinkSchemes: Set<String>,
): String {
	val writer = HtmlWriter()
	val containers = HtmlContainers(blocks, formats)
	val retired = RetiredStyles(styles, retiredStyles)
	lines.forEach { line ->
		writer.openContainers(containers.around(line.docLine))
		writer.appendLine(
			lineHtml(
				index = line.docLine,
				line = line.text,
				blocks = blocks,
				headerLevel = headerLevels[line.docLine],
				// An item's format is on its `<li>`.
				format = formats[line.docLine].takeIf { blocks.listBlockAt(line.docLine) == null },
				links = line.links,
				styles = styles,
				retired = retired,
				allowedLinkSchemes = allowedLinkSchemes,
			),
			inCodeFence = blocks.has(line.docLine, CodeFence),
		)
	}
	return writer.finish()
}

/**
 * An element wrapping lines; an `<li>` is told apart from its siblings by the [item]
 * line it opens on, and carries that line's paragraph format as its [style].
 */
private data class HtmlContainer(val tag: String, val item: Int = -1, val style: String? = null)

/**
 * The elements wrapping each line, asked of in order. A list item is an `<li>` of its
 * own ([HtmlContainer.item]), so it stays open while the lists nested in it are written
 * and closes before its next sibling. An item nests under the nearest open item at a
 * shallower level, so an orphan is written one below the item before it and a copy
 * that starts at a nested item keeps its items' nesting. Any other line closes every
 * item, since HTML cannot hold it inside one, and so does a change of quote.
 */
private class HtmlContainers(
	private val blocks: DocumentBlocks,
	private val formats: Map<Int, ParagraphFormatSpanStyle>,
) {
	/** The open items, outermost first: their list, their `<li>`, and their level. */
	private val openItems = mutableListOf<Triple<HtmlContainer, HtmlContainer, Int>>()
	private var quoted = false

	fun around(line: Int): List<HtmlContainer> {
		val containers = mutableListOf<HtmlContainer>()
		val lineQuoted = blocks.has(line, Blockquote)
		if (lineQuoted) containers += BLOCKQUOTE
		val list = blocks.listBlockAt(line)
		if (list == null || lineQuoted != quoted) openItems.clear()
		quoted = lineQuoted
		when {
			list != null -> {
				val level = list.listLevel ?: 0
				while (openItems.isNotEmpty() && openItems.last().third >= level) openItems.removeAt(openItems.lastIndex)
				openItems.forEach { (listContainer, item) ->
					containers += listContainer
					containers += item
				}
				val listContainer = HtmlContainer(if (list.spanStyle is OrderedListSpanStyle) "ol" else "ul")
				val item = HtmlContainer("li", line, formats[line]?.toCss())
				containers += listContainer
				containers += item
				openItems += Triple(listContainer, item, level)
			}
			// `<code>` nests inside `<pre>` so a reader that only understands one of
			// the two still sees a code block.
			blocks.has(line, CodeFence) -> containers += CODE_BLOCK
		}
		return containers
	}

	private companion object {
		val BLOCKQUOTE = HtmlContainer("blockquote")
		val CODE_BLOCK = listOf(HtmlContainer("pre"), HtmlContainer("code"))
	}
}

private fun lineHtml(
	index: Int,
	line: AnnotatedString,
	blocks: DocumentBlocks,
	headerLevel: Int?,
	format: ParagraphFormatSpanStyle?,
	links: List<HtmlLink>,
	styles: RichTextStyles,
	retired: RetiredStyles,
	allowedLinkSchemes: Set<String>,
): String {
	// Fenced lines are literal code: running them through `toHtml` would see the
	// baked-in monospace as an inline code run and wrap every line in `<code>`.
	if (blocks.has(index, CodeFence)) return line.text.escapeHtmlText()

	val image = blocks.imageLines[index]
	val isRule = index in blocks.horizontalRuleLines
	// The heading comes from the line's block, never from how its text is sized: bold
	// text at a heading's size is bold text.
	val heading = when {
		isRule || image != null -> null
		headerLevel != null -> HtmlTag.entries[headerLevel - 1]
		else -> null
	}
	val content = when {
		isRule -> "<hr>"
		image != null -> "<img src=\"${image.source.escapeHtmlAttribute()}\"" +
			" alt=\"${image.alt.escapeHtmlAttribute()}\">"

		heading != null -> "<${heading.tag}${format.styleAttribute()}>" +
			line.withoutSpanStyles(retired.headingLooks(heading))
				.toHtml(styles, links, retired, allowedLinkSchemes, headingsBySize = false) +
			"</${heading.tag}>"
		else -> line.toHtml(styles, links, retired, allowedLinkSchemes, headingsBySize = false)
	}

	return when {
		// A list item's `<li>` is a container (see [HtmlContainers]).
		blocks.listBlockAt(index) != null -> content
		// Rules, images and headings are block elements in their own right;
		// wrapping one in `<p>` is invalid and browsers close the paragraph
		// before it anyway.
		isRule || image != null || heading != null -> content
		else -> "<p${format.styleAttribute()}>$content</p>"
	}
}

/** This line with every span whose style is one of [looks] dropped. */
private fun AnnotatedString.withoutSpanStyles(looks: Set<SpanStyle>): AnnotatedString {
	if (spanStyles.none { it.item in looks }) return this
	return withSpanStyles(spanStyles.filter { it.item !in looks })
}

private fun ParagraphFormatSpanStyle?.styleAttribute(): String =
	this?.toCss()?.let { " style=\"${it.escapeHtmlAttribute()}\"" } ?: ""

/**
 * Assembles the fragment, keeping container elements open across the lines that
 * share them so a run of list items becomes one `<ul>` rather than one per item.
 *
 * Line breaks between elements are cosmetic everywhere except inside `<pre>`,
 * where they are the code's own line separators; hence the care about which
 * boundaries get one.
 */
private class HtmlWriter {
	private val builder = StringBuilder()
	private var open = emptyList<HtmlContainer>()
	private var atCodeFenceStart = false
	private var atItemStart = false

	fun openContainers(containers: List<HtmlContainer>) {
		var shared = 0
		while (shared < open.size && shared < containers.size && open[shared] == containers[shared]) {
			shared++
		}
		closeDownTo(shared)
		val opening = containers.drop(shared)
		opening.forEach { container ->
			// `<pre><code>` is one opening, and a newline after it would render as a
			// blank first line of the code block.
			if (container.tag != "code") separate()
			builder.append('<').append(container.tag)
			container.style?.let { builder.append(" style=\"").append(it.escapeHtmlAttribute()).append('"') }
			builder.append('>')
			open = open + container
		}
		// Only the line that opens the fence sits flush against `<code>`; every
		// line after it is separated by the newline it follows.
		atCodeFenceStart = opening.isNotEmpty() && open.lastOrNull()?.tag == "code"
		atItemStart = opening.lastOrNull()?.tag == "li"
	}

	fun appendLine(html: String, inCodeFence: Boolean) {
		if (inCodeFence) {
			if (!atCodeFenceStart) builder.append('\n')
			atCodeFenceStart = false
		} else if (!atItemStart) {
			separate()
		}
		atItemStart = false
		builder.append(html)
	}

	fun finish(): String {
		closeDownTo(0)
		return builder.toString()
	}

	private fun closeDownTo(depth: Int) {
		while (open.size > depth) {
			val tag = open.last().tag
			if (tag != "code" && tag != "pre" && tag != "li") separate()
			builder.append("</").append(tag).append('>')
			open = open.dropLast(1)
		}
	}

	private fun separate() {
		if (builder.isNotEmpty()) builder.append('\n')
	}

}

/**
 * Wraps this [TextEditorState] in an [HtmlExtension], the entry point for HTML
 * import and export. Heading levels and inline styles are matched against the
 * state's [TextEditorState.richTextStyles] in both directions.
 *
 * @param imageProvider Resolves image sources for imported `<img>` elements;
 * pass `null` to drop images.
 */
fun TextEditorState.withHtml(
	imageProvider: ImageProvider? = null,
): HtmlExtension = HtmlExtension(this, imageProvider)
