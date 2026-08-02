package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Marks a line as a markdown heading of [level] (1..6). The span is the
 * heading's semantic identity: serializers read the level from it instead of
 * reverse-matching font sizes, so restyling the document cannot demote a
 * heading to plain text. The visual treatment is a [androidx.compose.ui.text.SpanStyle]
 * baked into the line text from the active markdown configuration; this span
 * paints nothing itself.
 *
 * Instances are per-level singletons ([of]); block detection compares span
 * styles by identity.
 */
class HeaderSpanStyle private constructor(val level: Int) : RichSpanStyle {
	override val stickyAtStart: Boolean get() = true

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
	}

	override fun toString(): String = "HeaderSpanStyle(level=$level)"

	companion object {
		private val LEVELS: List<HeaderSpanStyle> = List(6) { HeaderSpanStyle(it + 1) }

		/** The singleton for [level], coerced into 1..6. */
		fun of(level: Int): HeaderSpanStyle = LEVELS[level.coerceIn(1, 6) - 1]
	}
}

/**
 * Paragraph style for heading lines. Headings need no indent; the constant
 * exists so heading blocks fit the [LineBlockStyle] apply/demote contract.
 */
val HEADER_PARAGRAPH_STYLE: ParagraphStyle = ParagraphStyle()
