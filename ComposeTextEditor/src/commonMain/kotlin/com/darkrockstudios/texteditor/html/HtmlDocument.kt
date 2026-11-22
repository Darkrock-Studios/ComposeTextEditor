package com.darkrockstudios.texteditor.html

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedListStartSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.listLevel
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle

/**
 * An HTML fragment parsed into the editor's document model: the styled text plus
 * the line-anchored decorations the markup implied.
 *
 * Line indices are relative to [text], so an importer replacing the whole
 * document can use them directly while a paste offsets them by its insertion
 * line.
 */
internal class HtmlDocument(
	val text: AnnotatedString,
	/** The lines of each line block, keyed by the block's span style. */
	val blockLines: Map<RichSpanStyle, Set<Int>>,
	val horizontalRuleLines: Set<Int>,
	val imageLines: Map<Int, HtmlImageRef>,
	/** Each link's range in [text], one per line it covers, and its sanitized destination. */
	val links: List<Pair<TextEditorRange, String>> = emptyList(),
	/** The paragraph format of each line whose element's inline CSS set one. */
	val paragraphFormats: Map<Int, ParagraphFormatSpanStyle> = emptyMap(),
	/** The start of each `<ol start>` but 1, by its first item's line. */
	val listStarts: Map<Int, Int> = emptyMap(),
) {
	/** True when the markup carried nothing but styled text. */
	fun hasNoDecorations(): Boolean =
		blockLines.values.all { it.isEmpty() } &&
			horizontalRuleLines.isEmpty() && imageLines.isEmpty() && links.isEmpty() && paragraphFormats.isEmpty()
}

/**
 * The [HtmlDocument.listStarts] on the lines [from] to [to]. A list whose first item
 * comes before [from] (a paste's first line, joined into the line it lands on) hands
 * its start to its next item at [from] or after, counted on by the items before it.
 */
internal fun HtmlDocument.listStartsFrom(from: Int, to: Int): Map<Int, Int> {
	val starts = listStarts.filterKeys { it in from..to }.toMutableMap()
	val lists = HashMap<Int, RichSpanStyle>()
	blockLines.forEach { (style, lines) -> if (style is OrderedListSpanStyle || style is BulletListSpanStyle) lines.forEach { lists[it] = style } }
	for ((first, start) in listStarts) {
		if (first >= from) continue
		val level = lists[first]?.listLevel ?: continue
		var number = start
		for (line in first + 1..to) {
			val list = lists[line] ?: break
			val lineLevel = list.listLevel!!
			if (lineLevel > level) continue
			if (lineLevel < level || list !is OrderedListSpanStyle) break
			number++
			if (line < from) continue
			if (number != 1 && OrderedListStartSpanStyle.isValidStart(number) && line !in starts) starts[line] = number
			break
		}
	}
	return starts
}

/**
 * An `<img>` the parser reserved a line for. Resolving it to a drawable span
 * needs an `ImageProvider`, which only the importer has, so the parser records
 * the source and alt text and leaves the rest alone.
 */
internal data class HtmlImageRef(val source: String, val alt: String)
