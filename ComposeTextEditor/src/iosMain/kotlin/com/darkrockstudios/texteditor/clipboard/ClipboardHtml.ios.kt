package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard

/**
 * The markup the paste's [ClipboardHelper.getText] just read. An in-editor copy carries
 * its markup too, which is what keeps its blocks; the caller checks the markup re-parses
 * to the pasted text.
 */
internal actual suspend fun readClipboardHtml(clipboard: Clipboard): String? =
	ClipboardHelper.takeLastReadHtml()
