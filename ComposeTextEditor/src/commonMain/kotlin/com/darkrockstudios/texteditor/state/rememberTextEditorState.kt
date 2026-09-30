package com.darkrockstudios.texteditor.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import kotlinx.coroutines.flow.first
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Creates and remembers a [TextEditorState], wiring it to a coroutine scope and a
 * text measurer that follow the composition. Hoist the returned state and pass it to
 * [com.darkrockstudios.texteditor.TextEditor] to read or mutate the content.
 *
 * The state is lost with the composition; [rememberSaveableTextEditorState] keeps it
 * across configuration changes and process death.
 *
 * @param initialText Content to seed the editor with on first composition. Later
 *   recompositions with a different value do not replace the content; call
 *   [TextEditorState.setText] to change it after creation.
 */
@Composable
fun rememberTextEditorState(initialText: AnnotatedString? = null): TextEditorState {
	val scope = rememberCoroutineScope()
	val textMeasurer = rememberEditorTextMeasurer()
	val state = remember {
		TextEditorState(
			scope = scope,
			measurer = textMeasurer,
			initialText = initialText,
		)
	}
	FollowTextMeasurer(state, textMeasurer)
	return state
}

/**
 * [rememberTextEditorState] that survives what `rememberSaveable` survives: an
 * Android configuration change or process death, and a screen leaving and returning
 * through a saveable state holder (navigation back stacks).
 *
 * Kept: the text; its character styles' plain values (colour, size, weight, style,
 * decoration, background, letter spacing, baseline shift, feature settings, and a
 * generic font family); the rich spans of the built-in styles (lists, quotes, code
 * fences, rules, headings, links) and their lines' indents; other paragraph styles'
 * indent, line height, alignment, direction, line breaking and hyphenation; the caret;
 * the selection; and the line at the top of the viewport, scrolled back to once laid
 * out. Not kept: the undo history, which a restored editor starts without; a loaded
 * font, a brush, a shadow and other style values that are not plain; decoration spans
 * (spell check, find), which their owners recompute; and any other rich span style
 * (images, your own) unless [richSpanStyleSaver] saves it.
 *
 * The whole document is saved, and an Android Bundle holds about a megabyte, so a very
 * long document belongs in the host's own storage, with its identity saved here instead.
 *
 * @param initialText Content for the first composition; a restored state keeps its own.
 * @param richSpanStyleSaver Saves the rich span styles the editor does not know, such as
 *   an [com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle] with your image
 *   provider. Return null from `save` to drop a style; what it returns must be savable
 *   on the platform (strings, numbers, lists of them), or saving fails naming this
 *   parameter. Called only for styles that are not built in.
 */
@Composable
fun rememberSaveableTextEditorState(
	initialText: AnnotatedString? = null,
	richSpanStyleSaver: Saver<RichSpanStyle, Any>? = null,
): TextEditorState {
	val scope = rememberCoroutineScope()
	val textMeasurer = rememberEditorTextMeasurer()
	val saver = remember(scope, textMeasurer, richSpanStyleSaver) {
		textEditorStateSaver(scope, textMeasurer, richSpanStyleSaver)
	}
	val state = rememberSaveable(saver = saver) {
		TextEditorState(scope = scope, measurer = textMeasurer, initialText = initialText)
	}
	FollowTextMeasurer(state, textMeasurer)
	LaunchedEffect(state) {
		val target = state.restoredFirstVisible ?: return@LaunchedEffect
		snapshotFlow { state.lineOffsets.isNotEmpty() }.first { it }
		state.scrollManager.scrollToPosition(target, top = true, animated = false)
		state.restoredFirstVisible = null
	}
	return state
}

/**
 * The default 8-entry cache thrashes on any document taller than a few lines whenever
 * a full pass runs (style, density, or viewport changes).
 */
@Composable
private fun rememberEditorTextMeasurer(): TextMeasurer = rememberTextMeasurer(cacheSize = 32)

@OptIn(ExperimentalUuidApi::class)
@Composable
private fun FollowTextMeasurer(state: TextEditorState, textMeasurer: TextMeasurer) {
	val density = LocalDensity.current
	val windowInfo = LocalWindowInfo.current
	// Trigger recomposition when window info changes
	val measuringKey = remember(density, windowInfo) { Uuid.random() }
	LaunchedEffect(measuringKey, textMeasurer) {
		state.textMeasurer = textMeasurer
	}
}
