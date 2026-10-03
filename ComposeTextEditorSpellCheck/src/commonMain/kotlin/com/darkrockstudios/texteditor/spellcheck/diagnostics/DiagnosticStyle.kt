package com.darkrockstudios.texteditor.spellcheck.diagnostics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.DecorationStyle
import com.darkrockstudios.texteditor.richstyle.drawDottedUnderline
import com.darkrockstudios.texteditor.richstyle.drawWavyUnderline
import com.darkrockstudios.texteditor.state.TextEditorState

/** The layer [TextDiagnosticsState] reads, replaces and clears as its own. */
internal val DiagnosticsLayer = DecorationLayer("diagnostics")

/**
 * The underline of one diagnostic, on [layer]. It carries the diagnostic's [message] and [fixes]
 * itself, so they move with the span as the text around it is edited. By default it is on the
 * layer a [TextDiagnosticsState] reads, replaces and clears as its own; one on a layer of its
 * own is left to that layer's owner.
 */
data class DiagnosticStyle(
	val message: String,
	val fixes: List<DiagnosticFix>,
	val color: Color,
	val severity: DiagnosticSeverity = DiagnosticSeverity.Error,
	override val layer: DecorationLayer = DiagnosticsLayer,
) : DecorationStyle {
	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		when (severity) {
			DiagnosticSeverity.Error -> drawWavyUnderline(layoutResult, lineWrap, textRange, color)
			DiagnosticSeverity.Suggestion -> drawDottedUnderline(layoutResult, lineWrap, textRange, color)
		}
	}
}
