package com.darkrockstudios.texteditor.clipboard

import androidx.compose.runtime.Composable
import com.darkrockstudios.texteditor.state.TextEditorState

/** Clipboard chords arrive here as key events, which the key bindings handle. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal actual fun ClipboardEventsEffect(state: TextEditorState) = Unit
