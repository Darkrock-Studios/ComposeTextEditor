package com.darkrockstudios.texteditor.clipboard

import androidx.compose.runtime.Composable
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Moves the clipboard's data through the platform's own copy, cut and paste events
 * while [state] has focus, where the platform grants clipboard access freely only
 * inside them; the key bindings' actions still do the editing. Only the web does;
 * elsewhere this does nothing.
 */
@Composable
internal expect fun ClipboardEventsEffect(state: TextEditorState)
