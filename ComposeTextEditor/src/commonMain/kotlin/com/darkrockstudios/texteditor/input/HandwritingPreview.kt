package com.darkrockstudios.texteditor.input

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.DrawSelection
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * A stylus gesture the keyboard is previewing while the stylus is down (Android's
 * `previewHandwritingGesture`): the text it would select, or delete.
 */
internal class HandwritingPreview(val range: TextEditorRange, val deletes: Boolean)

/**
 * Previews [range] (flat) as the text a gesture would select, or delete when [deletes]; a
 * collapsed range ends any preview. Returns the preview shown, if any.
 */
internal fun TextEditorState.previewHandwriting(range: TextRange, deletes: Boolean): HandwritingPreview? {
	val preview = if (range.collapsed) {
		null
	} else {
		HandwritingPreview(TextEditorRange(getOffsetAtCharacter(range.min), getOffsetAtCharacter(range.max)), deletes)
	}
	handwritingPreview = preview
	return preview
}

/** Ends [preview] if it is still the one shown, so a late cancellation leaves a newer one. */
internal fun TextEditorState.endHandwritingPreview(preview: HandwritingPreview?) {
	if (preview != null && handwritingPreview === preview) handwritingPreview = null
}

internal fun TextEditorState.endHandwritingPreview() {
	handwritingPreview = null
}

/**
 * Highlights the text a previewed gesture would act on, as the selection is drawn: in
 * [selectionColor] for a select, and in the text colour at a fifth of its alpha for a
 * delete, as Compose's text fields draw them.
 */
internal fun DrawScope.DrawHandwritingPreview(state: TextEditorState, selectionColor: Color, textColor: Color) {
	val preview = state.handwritingPreview ?: return
	val color = if (preview.deletes) {
		val ink = textColor.takeOrElse { state.textStyle.color.takeOrElse { Color.Black } }
		ink.copy(alpha = ink.alpha * DELETE_PREVIEW_ALPHA)
	} else {
		selectionColor
	}
	DrawSelection(state, color, preview.range)
}

private const val DELETE_PREVIEW_ALPHA = 0.2f
