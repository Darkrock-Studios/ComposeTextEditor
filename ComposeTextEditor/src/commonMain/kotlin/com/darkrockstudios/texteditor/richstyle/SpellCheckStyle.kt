package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.DecorationStyle
import com.darkrockstudios.texteditor.state.TextEditorState

private val SpellCheckLayer = DecorationLayer("spell check")

/**
 * A [DecorationStyle] that draws a red wavy underline beneath misspelled text, on [layer].
 *
 * The companion is the plain underline. Subclass it to carry data with the span: the
 * editor keeps a span's style as edits move its range, so the data stays attached to the
 * text it describes.
 *
 * The companion, and a subclass made without a layer, are on spell check's layer (the
 * companion's [layer]), which spell check reads, replaces and clears as its own: read them
 * with `decorations(SpellCheckStyle.layer)`. One made with a layer of its own is left to
 * that layer's owner.
 */
open class SpellCheckStyle protected constructor(final override val layer: DecorationLayer) : DecorationStyle {
	protected constructor() : this(SpellCheckLayer)

	companion object : SpellCheckStyle()

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		drawWavyUnderline(layoutResult, lineWrap, textRange, Color.Red)
	}
}
