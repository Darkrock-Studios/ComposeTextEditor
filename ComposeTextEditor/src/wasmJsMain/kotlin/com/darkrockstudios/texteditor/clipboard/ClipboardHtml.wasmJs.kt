package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard

/**
 * The markup [ClipboardHelper.getText] read for this paste. Handed over rather than
 * read again, since a browser may ask the user on every read.
 */
internal actual suspend fun readClipboardHtml(clipboard: Clipboard): String? = ClipboardHelper.takeLastReadHtml()
