package com.darkrockstudios.texteditor.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Creates and remembers a [TextEditorState], wiring it to a coroutine scope and a
 * text measurer that follow the composition. Hoist the returned state and pass it to
 * [com.darkrockstudios.texteditor.TextEditor] to read or mutate the content.
 *
 * @param initialText Content to seed the editor with on first composition. Later
 *   recompositions with a different value do not replace the content; call
 *   [TextEditorState.setText] to change it after creation.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun rememberTextEditorState(initialText: AnnotatedString? = null): TextEditorState {
	val scope = rememberCoroutineScope()
	val density = LocalDensity.current
	val windowInfo = LocalWindowInfo.current

	// Trigger recomposition when window info changes
	val measuringKey = remember(density, windowInfo) { Uuid.random() }
	// The default 8-entry cache thrashes on any document taller than a few lines
	// whenever a full pass runs (style, density, or viewport changes).
	val textMeasurer = rememberTextMeasurer(cacheSize = 32)

	val state = remember {
		TextEditorState(
			scope = scope,
			measurer = textMeasurer,
			initialText = initialText,
		)
	}

	LaunchedEffect(measuringKey, textMeasurer) {
		state.textMeasurer = textMeasurer
	}

	return state
}