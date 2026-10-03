package com.darkrockstudios.texteditor.decoration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.drawDottedUnderline
import com.darkrockstudios.texteditor.richstyle.drawRangeHighlight
import com.darkrockstudios.texteditor.richstyle.drawSolidUnderline
import com.darkrockstudios.texteditor.richstyle.drawWavyUnderline
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Names one owner's decorations: a syntax highlighter's colours, a linter's underlines, a
 * search's matches. Each owner sets, replaces and clears its own layer
 * ([setDecorations], [replaceDecorations], [clearDecorations]) without touching another's.
 * Compared by identity, so two layers of the same [name] stay apart; [name] is for debugging.
 */
class DecorationLayer(val name: String) {
	override fun toString(): String = "DecorationLayer($name)"
}

/**
 * The look of a decoration in [layer]. A decoration is a view of the text, not part of it:
 * it never enters the undo history or the edit stream, a copy, a drag, the saved state or
 * an export, and adding or removing one lays out no line. It moves with the text as edits
 * are made around and inside it until its owner replaces it; undo does not bring back one
 * an edit removed.
 *
 * It paints the text under it in [textColor], fills behind it in [drawBackground] and draws
 * over it in [drawCustomStyle]. Implement this to carry data a click can read (as spell
 * check's styles carry suggestions), or use [Decoration]. A decoration must stay one:
 * [isDecoration] true, and none of the line-shaping properties of [RichSpanStyle] set
 * ([stickyAtStart], [reshapesLine], [boundToParagraph], a block's height).
 */
interface DecorationStyle : RichSpanStyle {
	val layer: DecorationLayer

	/**
	 * The colour the text under the decoration is painted in; [Color.Unspecified] leaves it.
	 * The text is tinted when drawn, never shaped again, so colouring a whole file costs no
	 * layout. The tint wins over a colour the text has of its own; colour glyphs (emoji), and
	 * text a span style gives a background (inline code), keep theirs. Where two decorations
	 * colour the same text, the one drawn last wins, and the order between layers is not
	 * defined.
	 */
	val textColor: Color get() = Color.Unspecified

	override val isDecoration: Boolean get() = true

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) = Unit
}

/** The shape of a [Decoration]'s underline. */
enum class UnderlineShape { Solid, Wavy, Dotted }

/** An underline of [shape] in [color], for [Decoration.underline]. */
data class DecorationUnderline(val color: Color, val shape: UnderlineShape = UnderlineShape.Solid)

/**
 * A ready-made [DecorationStyle]: [textColor] for the text, [background] behind it and
 * [underline] under it, any of them left out. It carries no data, so a click passes
 * through it to the spans it covers.
 */
data class Decoration(
	override val layer: DecorationLayer,
	override val textColor: Color = Color.Unspecified,
	val background: Color = Color.Unspecified,
	val underline: DecorationUnderline? = null,
) : DecorationStyle {
	override val isHitTestable: Boolean get() = false

	override fun DrawScope.drawBackground(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		if (background.isSpecified) drawRangeHighlight(layoutResult, lineWrap, textRange, background)
	}

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		val underline = underline ?: return
		when (underline.shape) {
			UnderlineShape.Solid -> drawSolidUnderline(layoutResult, lineWrap, textRange, underline.color)
			UnderlineShape.Wavy -> drawWavyUnderline(layoutResult, lineWrap, textRange, underline.color)
			UnderlineShape.Dotted -> drawDottedUnderline(layoutResult, lineWrap, textRange, underline.color)
		}
	}
}
