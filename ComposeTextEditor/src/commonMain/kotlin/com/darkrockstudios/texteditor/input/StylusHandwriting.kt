package com.darkrockstudios.texteditor.input

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Hands a stylus stroke on the editor to the platform's handwriting, where it has one
 * (Android 14 and later, with a keyboard that writes): the stroke is consumed and the
 * keyboard turns what is written into text through the input session. [onStroke] runs
 * first, with where the stroke began in this element's coordinates and whether the editor
 * had focus. Nothing happens unless [enabled].
 */
internal expect fun Modifier.stylusHandwriting(
	state: TextEditorState,
	enabled: Boolean,
	onStroke: (start: Offset, focused: Boolean) -> Unit,
): Modifier

/**
 * Where an unfocused editor's caret goes when a stylus starts writing in it: under the
 * stroke's start, as `EditText` places it. [at] is in the text's coordinates.
 */
internal fun TextEditorState.placeCaretForHandwriting(at: Offset) {
	if (composingRange != null) finishComposition()
	val hit = pointerHitAt(at)
	cursor.updatePosition(hit.position, hit.affinity)
	selector.clearSelection()
}
