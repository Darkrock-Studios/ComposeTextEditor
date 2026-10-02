package com.darkrockstudios.texteditor

import androidx.compose.ui.Modifier
import com.darkrockstudios.texteditor.state.TextEditorState

internal actual fun Modifier.textMagnifier(state: TextEditorState): Modifier = this
