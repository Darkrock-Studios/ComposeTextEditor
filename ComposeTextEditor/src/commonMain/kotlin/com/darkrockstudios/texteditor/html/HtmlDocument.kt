package com.darkrockstudios.texteditor.html

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.richstyle.LineBlockStyle

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
	val blockLines: Map<LineBlockStyle, Set<Int>>,
	val horizontalRuleLines: Set<Int>,
	val imageLines: Map<Int, HtmlImageRef>,
) {
	/** True when the markup carried nothing but styled text. */
	fun hasNoBlocks(): Boolean =
		blockLines.values.all { it.isEmpty() } &&
			horizontalRuleLines.isEmpty() && imageLines.isEmpty()
}

/**
 * An `<img>` the parser reserved a line for. Resolving it to a drawable span
 * needs an `ImageProvider`, which only the importer has, so the parser records
 * the source and alt text and leaves the rest alone.
 */
internal data class HtmlImageRef(val source: String, val alt: String)
