package com.darkrockstudios.texteditor.spellcheck.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Remembers a [TextDiagnosticsState] for [textState], following [checker] and [color] as they change.
 * Pass a stable [checker]: a new instance checks the whole text again.
 */
@Composable
fun rememberTextDiagnosticsState(
	textState: TextEditorState,
	checker: TextDiagnosticsChecker?,
	color: Color = DefaultDiagnosticColor,
): TextDiagnosticsState {
	val state = remember(textState) { TextDiagnosticsState(textState, checker, color) }
	LaunchedEffect(state, checker) { state.setChecker(checker) }
	LaunchedEffect(state, color) { state.setColor(color) }
	return state
}
