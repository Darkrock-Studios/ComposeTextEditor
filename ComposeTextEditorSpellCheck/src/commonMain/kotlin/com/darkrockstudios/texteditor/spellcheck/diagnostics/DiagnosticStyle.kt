package com.darkrockstudios.texteditor.spellcheck.diagnostics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.drawWavyUnderline
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * The underline of one diagnostic. It carries the diagnostic's [message] and [fixes] itself, so they
 * move with the span as the text around it is edited.
 */
data class DiagnosticStyle(
	val message: String,
	val fixes: List<String>,
	val color: Color,
) : RichSpanStyle {
	override val isDecoration: Boolean get() = true

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		drawWavyUnderline(layoutResult, lineWrap, textRange, color)
	}
}
