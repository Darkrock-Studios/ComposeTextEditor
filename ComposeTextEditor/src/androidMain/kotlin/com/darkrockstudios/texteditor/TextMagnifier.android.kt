package com.darkrockstudios.texteditor

import androidx.compose.foundation.magnifier
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.darkrockstudios.texteditor.state.TextEditorState

internal actual fun Modifier.textMagnifier(state: TextEditorState): Modifier =
	magnifier(sourceCenter = { state.selector.magnifierCenter ?: Offset.Unspecified })
