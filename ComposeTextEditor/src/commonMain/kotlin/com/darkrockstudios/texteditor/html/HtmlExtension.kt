package com.darkrockstudios.texteditor.html

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.richstyle.Blockquote
import com.darkrockstudios.texteditor.richstyle.BulletList
import com.darkrockstudios.texteditor.richstyle.CodeFence
import com.darkrockstudios.texteditor.richstyle.DocumentBlocks
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.ImageProvider
import com.darkrockstudios.texteditor.richstyle.OrderedList
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.richstyle.documentBlocksOf
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * An extension to [TextEditorState] that reads and writes the document as HTML.
 *
 * Where the `AnnotatedString` converters handle inline styling alone, this
 * carries the whole document: headings, lists, blockquotes, code fences,
 * horizontal rules and images all survive the round trip.
 */
class HtmlExtension(
	val editorState: TextEditorState,
	initialConfiguration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	var imageProvider: ImageProvider? = null,
) {
	/**
	 * The style bundle heading levels and inline styles are matched against. Custom
	 * heading sizes only survive a round trip when the same configuration is used
	 * for both directions.
	 */
	var configuration: MarkdownConfiguration = initialConfiguration
		set(value) {
			field = value
			editorState.markdownConfiguration = value
		}

	init {
		editorState.markdownConfiguration = configuration
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
		val blocks = documentBlocksOf(content.richSpans, configuration)
		val headerLevels = headerLevelsOf(content.richSpans)
		val lines = content.lines
		if (lines.size == 1 && lines[0].isEmpty() && blocks.isEmpty() && headerLevels.isEmpty()) {
			return ""
		}

		return renderHtmlFragment(
			lines = lines.mapIndexed { index, line -> HtmlLine(line, index) },
			blocks = blocks,
			headerLevels = headerLevels,
			configuration = configuration,
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
			configuration = configuration,
			includeImages = provider != null,
		)
		// One revision, so a concurrent export can't catch the document loaded but
		// not yet styled.
		editorState.withAtomicEdit {
			editorState.setText(document.text)
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

/**
 * A line to serialize, paired with the document line its decorations come from.
 *
 * [isWholeLine] is false for the partly covered first and last lines of a copied
 * selection. Only a whole line can be read as a heading by how it is styled: any
 * fragment of a bold run is uniformly styled, and the default h4 is bold at the
 * body size, so the size match cannot tell the two apart.
 */
internal class HtmlLine(
	val text: AnnotatedString,
	val docLine: Int,
	val isWholeLine: Boolean = true,
)

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
 * clipboard, so a copied selection carries the same markup a save would.
 */
internal fun renderHtmlFragment(
	lines: List<HtmlLine>,
	blocks: DocumentBlocks,
	headerLevels: Map<Int, Int>,
	configuration: MarkdownConfiguration,
): String {
	val writer = HtmlWriter()
	lines.forEach { line ->
		writer.openContainers(containersFor(line.docLine, blocks))
		writer.appendLine(
			lineHtml(
				index = line.docLine,
				line = line.text,
				blocks = blocks,
				headerLevel = headerLevels[line.docLine],
				isWholeLine = line.isWholeLine,
				configuration = configuration,
			),
			inCodeFence = blocks.has(line.docLine, CodeFence),
		)
	}
	return writer.finish()
}

/** The elements wrapping [line], outermost first. */
private fun containersFor(line: Int, blocks: DocumentBlocks): List<String> {
	val containers = mutableListOf<String>()
	if (blocks.has(line, Blockquote)) containers += "blockquote"
	when {
		blocks.has(line, OrderedList) -> containers += "ol"
		blocks.has(line, BulletList) -> containers += "ul"
		// `<code>` nests inside `<pre>` so a reader that only understands one of
		// the two still sees a code block.
		blocks.has(line, CodeFence) -> containers += listOf("pre", "code")
	}
	return containers
}

private fun lineHtml(
	index: Int,
	line: AnnotatedString,
	blocks: DocumentBlocks,
	headerLevel: Int?,
	isWholeLine: Boolean,
	configuration: MarkdownConfiguration,
): String {
	// Fenced lines are literal code: running them through `toHtml` would see the
	// baked-in monospace as an inline code run and wrap every line in `<code>`.
	if (blocks.has(index, CodeFence)) return line.text.escapeHtmlText()

	val image = blocks.imageLines[index]
	val isRule = index in blocks.horizontalRuleLines
	// A heading's span carries its level; font-size matching remains only as
	// the fallback for spanless content, and only for a whole line, since any
	// fragment of a styled run is uniform on its own. A uniformly styled heading
	// line has no inner formatting left to render, so the tag is written here
	// rather than by `toHtml`, which declines any heading level it cannot tell
	// apart from bold body text.
	val heading = when {
		isRule || image != null -> null
		headerLevel != null -> HtmlTag.entries[headerLevel - 1]
		isWholeLine -> line.uniformHeadingTag(configuration)
		else -> null
	}
	val content = when {
		isRule -> "<hr>"
		image != null -> "<img src=\"${image.source.escapeHtmlAttribute()}\"" +
			" alt=\"${image.alt.escapeHtmlAttribute()}\">"

		heading != null -> "<${heading.tag}>${line.text.escapeHtmlText()}</${heading.tag}>"
		else -> line.toHtml(configuration)
	}

	val inList = blocks.has(index, BulletList) || blocks.has(index, OrderedList)
	return when {
		inList -> "<li>$content</li>"
		// Rules, images and headings are block elements in their own right;
		// wrapping one in `<p>` is invalid and browsers close the paragraph
		// before it anyway.
		isRule || image != null || heading != null -> content
		else -> "<p>$content</p>"
	}
}

/**
 * Assembles the fragment, keeping container elements open across the lines that
 * share them so a run of list items becomes one `<ul>` rather than one per item.
 *
 * Line breaks between elements are cosmetic everywhere except inside `<pre>`,
 * where they are the code's own line separators — hence the care about which
 * boundaries get one.
 */
private class HtmlWriter {
	private val builder = StringBuilder()
	private var open = emptyList<String>()
	private var atCodeFenceStart = false

	fun openContainers(containers: List<String>) {
		var shared = 0
		while (shared < open.size && shared < containers.size && open[shared] == containers[shared]) {
			shared++
		}
		closeDownTo(shared)
		val opening = containers.drop(shared)
		opening.forEach { tag ->
			// `<pre><code>` is one opening, and a newline after it would render as a
			// blank first line of the code block.
			if (tag != "code") separate()
			builder.append('<').append(tag).append('>')
			open = open + tag
		}
		// Only the line that opens the fence sits flush against `<code>`; every
		// line after it is separated by the newline it follows.
		atCodeFenceStart = opening.isNotEmpty() && open.lastOrNull() == "code"
	}

	fun appendLine(html: String, inCodeFence: Boolean) {
		if (inCodeFence) {
			if (!atCodeFenceStart) builder.append('\n')
			atCodeFenceStart = false
		} else {
			separate()
		}
		builder.append(html)
	}

	fun finish(): String {
		closeDownTo(0)
		return builder.toString()
	}

	private fun closeDownTo(depth: Int) {
		while (open.size > depth) {
			val tag = open.last()
			if (tag != "code" && tag != "pre") separate()
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
 * import and export.
 *
 * @param initialConfiguration Styling that heading levels and inline styles are
 * matched against in both directions.
 * @param imageProvider Resolves image sources for imported `<img>` elements;
 * pass `null` to drop images.
 */
fun TextEditorState.withHtml(
	initialConfiguration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	imageProvider: ImageProvider? = null,
): HtmlExtension = HtmlExtension(this, initialConfiguration, imageProvider)
