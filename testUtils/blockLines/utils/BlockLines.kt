package utils

import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HR_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.IMAGE_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.ImageProvider
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.state.TextEditorState

/*
 * Block lines: core's notation for a document's line blocks, so a test can build a
 * document and check one in a line of text without a markdown parser. Each line of the
 * notation is one document line, written as its block markers and then its text:
 *
 * | Marker                                | Block                                |
 * | ------------------------------------- | ------------------------------------ |
 * | `> `                                  | blockquote, first when it stacks     |
 * | `#` to `######`, then a space         | heading of that level                |
 * | two spaces per level, then `- `       | bullet item                          |
 * | two spaces per level, then `1. `      | ordered item (any number reads)      |
 * | three backticks, then a space         | code fence line                      |
 * | `---` alone                           | horizontal rule                      |
 * | `![alt](source)` alone                | image, when a provider is given      |
 *
 * A `\` after the markers keeps the rest as text (`\- not a list`), and is written
 * wherever the text would read as a marker. Unlike markdown there is no inline syntax,
 * nothing between lines (a blank notation line is a blank document line), and a fence
 * marks each line rather than opening a run. Ordered items are written `1. `: the
 * editor numbers a run itself.
 */

/**
 * Loads [blockLines] as the document, as a format importer does: the text, then its
 * blocks through `applyDocumentBlocks`, as one revision off the undo history, with the
 * styles assigned (so typed text takes the body style) and the body style under every
 * line. Without [asImported] the text is bare and the styles are left alone.
 */
fun TextEditorState.setBlockLines(
	blockLines: String,
	imageProvider: ImageProvider? = null,
	asImported: Boolean = true,
) {
	val hrLines = mutableListOf<Int>()
	val images = mutableMapOf<Int, ImageBlockSpanStyle>()
	val blocks = mutableMapOf<RichSpanStyle, MutableList<Int>>()
	val text = blockLines.split('\n').mapIndexed { index, line ->
		val parsed = parseBlockLine(line)
		parsed.blocks.forEach { blocks.getOrPut(it) { mutableListOf() } += index }
		val image = IMAGE_LINE.matchEntire(parsed.body)
		when {
			parsed.escaped -> parsed.body
			parsed.body == HR_MARKER -> HR_PLACEHOLDER.also { hrLines += index }
			image != null && imageProvider != null -> IMAGE_PLACEHOLDER.also {
				images[index] = ImageBlockSpanStyle(
					source = image.groupValues[2],
					alt = image.groupValues[1],
					provider = imageProvider,
				)
			}

			else -> parsed.body
		}
	}
	if (asImported) richTextStyles = richTextStyles
	val body = richTextStyles.defaultTextStyle
	val document = buildAnnotatedString {
		text.forEachIndexed { index, line ->
			if (index > 0) append('\n')
			if (asImported && line.isNotEmpty()) withStyle(body) { append(line) } else append(line)
		}
	}
	editGroup {
		setText(document)
		applyDocumentBlocks(horizontalRuleLines = hrLines, imageLines = images, blockLines = blocks)
	}
}

/** The document in block lines (see [setBlockLines]), read from one snapshot. */
fun TextEditorState.blockLines(): String {
	val snapshot = snapshot()
	val spansByLine = snapshot.richSpans.groupBy({ it.range.start.line }, { it.style })
	return snapshot.lines.mapIndexed { index, line ->
		val styles = spansByLine[index].orEmpty()
		val markers = listOfNotNull(
			styles.firstNotNullOfOrNull { it as? HeaderSpanStyle }?.let { "#".repeat(it.level) + " " },
			styles.firstNotNullOfOrNull { it as? BulletListSpanStyle }?.let { "  ".repeat(it.level) + "- " },
			styles.firstNotNullOfOrNull { it as? OrderedListSpanStyle }?.let { "  ".repeat(it.level) + "1. " },
			if (CodeFenceSpanStyle in styles) "$FENCE_MARKER " else null,
		)
		// The blocks never stack these, and the notation reads one of them per line.
		check(markers.size <= 1) { "line $index stacks blocks the notation cannot write: $styles" }
		val prefix = (if (BlockquoteSpanStyle in styles) "> " else "") + markers.joinToString("")
		val image = styles.firstNotNullOfOrNull { it as? ImageBlockSpanStyle }
		val body = when {
			HorizontalRuleSpanStyle in styles -> HR_MARKER
			image != null -> "![${image.alt}](${image.source})"
			readsAsMarker(line.text) -> "\\" + line.text
			else -> line.text
		}
		prefix + body
	}.joinToString("\n")
}

/** The lines currently carrying a rich span of exactly [style], sorted. */
fun TextEditorState.linesWith(style: RichSpanStyle): List<Int> =
	richSpanManager.getAllRichSpans()
		.filter { it.style === style }
		.map { it.range.start.line }
		.sorted()

/** The lines currently carrying an image block span, sorted. */
fun TextEditorState.imageLines(): List<Int> =
	richSpanManager.getAllRichSpans()
		.filter { it.style is ImageBlockSpanStyle }
		.map { it.range.start.line }
		.sorted()

private class ParsedBlockLine(val blocks: List<RichSpanStyle>, val body: String, val escaped: Boolean)

private fun parseBlockLine(line: String): ParsedBlockLine {
	val blocks = mutableListOf<RichSpanStyle>()
	var rest = line
	if (rest.startsWith("> ")) {
		blocks += BlockquoteSpanStyle
		rest = rest.removePrefix("> ")
	}
	val heading = HEADING.find(rest)
	val list = LIST_ITEM.find(rest)
	when {
		heading != null -> {
			blocks += HeaderSpanStyle.of(heading.groupValues[1].length)
			rest = rest.substring(heading.range.last + 1)
		}

		list != null -> {
			val level = list.groupValues[1].length / 2
			blocks += if (list.groupValues[2] == "-") BulletListSpanStyle.of(level) else OrderedListSpanStyle.of(level)
			rest = rest.substring(list.range.last + 1)
		}

		rest.startsWith("$FENCE_MARKER ") -> {
			blocks += CodeFenceSpanStyle
			rest = rest.removePrefix("$FENCE_MARKER ")
		}
	}
	val escaped = rest.startsWith('\\')
	return ParsedBlockLine(blocks, if (escaped) rest.drop(1) else rest, escaped)
}

/** Whether [text], written without markers, would read back as a block. */
private fun readsAsMarker(text: String): Boolean {
	val parsed = parseBlockLine(text)
	return parsed.blocks.isNotEmpty() || parsed.escaped || parsed.body == HR_MARKER || IMAGE_LINE.matches(parsed.body)
}

private const val FENCE_MARKER = "```"
private const val HR_MARKER = "---"
private val HEADING = Regex("^(#{1,6}) ")
private val LIST_ITEM = Regex("^((?:  )*)(-|\\d+\\.) ")
private val IMAGE_LINE = Regex("""!\[([^\]]*)]\(([^)]*)\)""")
