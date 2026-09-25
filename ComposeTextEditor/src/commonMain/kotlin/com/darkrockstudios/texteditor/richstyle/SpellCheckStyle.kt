package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * A [RichSpanStyle] that draws a red wavy underline beneath misspelled text.
 *
 * The companion is the plain underline. Subclass it to carry data with the span: the
 * editor keeps a span's style as edits move its range, so the data stays attached to the
 * text it describes. Match spans with `is SpellCheckStyle` to include subclasses.
 */
open class SpellCheckStyle protected constructor() : RichSpanStyle {
	companion object : SpellCheckStyle()

	/** Marks this as a non-editing decoration so it stays out of the undo/edit stream. */
	override val isDecoration: Boolean = true

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		drawWavyUnderline(layoutResult, lineWrap, textRange, Color.Red)
	}
}
