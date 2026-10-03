package com.darkrockstudios.texteditor

import androidx.compose.ui.Modifier
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Shows the platform's text magnifier over the point a touch handle drag reports
 * (`TextEditorSelectionManager.magnifierCenter`). Compose has a magnifier only on
 * Android, from API 28; everywhere else this adds nothing.
 */
internal expect fun Modifier.textMagnifier(state: TextEditorState): Modifier
