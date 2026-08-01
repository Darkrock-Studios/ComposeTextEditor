package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard

/** No HTML flavor is available here, so a paste keeps whatever styling [ClipboardHelper] found. */
internal actual suspend fun readClipboardHtml(clipboard: Clipboard): String? = null
