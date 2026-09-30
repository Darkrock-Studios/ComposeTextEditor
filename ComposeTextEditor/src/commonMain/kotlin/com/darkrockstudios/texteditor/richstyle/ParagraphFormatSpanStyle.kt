package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.isUnspecified
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * A paragraph's own formatting: the space above and below it, its alignment, its
 * indents and its line height. One span per line, anchored to the line like a block
 * marker and kept by the same edit rules: it survives an edit within its line, dies
 * with its line, splits with an Enter inside it, and an Enter at the line's start or
 * end carries it onto the new paragraph, as word processors do. Set it with
 * [com.darkrockstudios.texteditor.state.setParagraphFormat].
 *
 * Each field left unspecified (or null) means the editor's default: no space before,
 * [com.darkrockstudios.texteditor.TextEditorStyle.paragraphSpacing] after, and the
 * `textStyle`'s alignment, indent and line height. The indents add to a block's own
 * (a list item's or a quote's) when both are in the same unit, else the paragraph's
 * wins. Markdown cannot carry any of this, so it is lost in a markdown round trip;
 * the saveable state keeps it.
 */
data class ParagraphFormatSpanStyle(
	val spaceBefore: Dp = Dp.Unspecified,
	val spaceAfter: Dp = Dp.Unspecified,
	val textAlign: TextAlign? = null,
	/** The indent of every row of the paragraph. */
	val indent: TextUnit = TextUnit.Unspecified,
	/** The indent of the first row, on top of [indent]. */
	val firstLineIndent: TextUnit = TextUnit.Unspecified,
	val lineHeight: TextUnit = TextUnit.Unspecified,
) : RichSpanStyle {
	override val stickyAtStart: Boolean get() = true

	/** Formatting only: a click inside the paragraph answers to what it covers. */
	override val isHitTestable: Boolean get() = false

	override val boundToParagraph: Boolean get() = true

	override val reshapesLine: Boolean get() = shapesText

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) = Unit

	/** Whether shaping the paragraph differs from the editor's default at all. */
	val shapesText: Boolean
		get() = textAlign != null || indent.isSpecified || firstLineIndent.isSpecified || lineHeight.isSpecified

	/**
	 * The paragraph style to shape the paragraph with, over [base] (a block's indent,
	 * or the editor's baked indent): the alignment and line height replace the base's;
	 * [indent] adds to both of the base's indents and [firstLineIndent] to its first.
	 */
	fun paragraphStyleOver(base: ParagraphStyle?): ParagraphStyle {
		val baseIndent = base?.textIndent
		val indentStyle = if (indent.isSpecified || firstLineIndent.isSpecified) {
			TextIndent(
				firstLine = firstLineIndent.plusUnit(indent.plusUnit(baseIndent?.firstLine)),
				restLine = indent.plusUnit(baseIndent?.restLine),
			)
		} else {
			baseIndent
		}
		val own = ParagraphStyle(
			textAlign = textAlign ?: TextAlign.Unspecified,
			textIndent = indentStyle,
			lineHeight = lineHeight,
		)
		return base?.merge(own) ?: own
	}

	private fun TextUnit.plusUnit(other: TextUnit?): TextUnit = when {
		other == null || other.isUnspecified -> this
		isUnspecified -> other
		type == other.type -> TextUnit(value + other.value, type)
		else -> this
	}
}
