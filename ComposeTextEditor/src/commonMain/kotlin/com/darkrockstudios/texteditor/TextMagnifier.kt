package com.darkrockstudios.texteditor

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Shows a text magnifier over the point a touch handle drag reports
 * (`TextEditorSelectionManager.magnifierCenter`): Compose's on Android, from API 28, and
 * a loupe the editor draws itself on the skiko platforms, which have none.
 */
internal expect fun Modifier.textMagnifier(state: TextEditorState, style: TextEditorStyle): Modifier

/**
 * What a drawn loupe fills behind its enlarged text: the editor's background where it is
 * opaque, else white or near black, whichever the text reads on, so the rows the loupe
 * covers never show through it.
 */
internal fun TextEditorStyle.loupeBackdrop(): Color {
	if (backgroundColor.isSpecified && backgroundColor.alpha == 1f) return backgroundColor
	val text = textColor.takeIf { it.isSpecified } ?: textStyle.color
	return if (text.isSpecified && text.luminance() > 0.5f) Color(0xFF1E1E1E) else Color.White
}
