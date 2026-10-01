package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.RichTextStyles

/**
 * The first item's HTML. An in-editor copy carries its markup too, which is what keeps
 * its blocks once the in-editor span buffer has gone; the caller checks the markup
 * re-parses to the pasted text.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun readClipboardHtml(clipboard: Clipboard): String? {
	val clipData = ClipboardHelper.pasteClip ?: clipboard.getClipEntry()?.clipData ?: return null
	if (clipData.itemCount == 0) return null
	return clipData.getItemAt(0).htmlText?.takeIf { it.isNotEmpty() }
}

internal actual suspend fun readClipboardPaste(
	clipboard: Clipboard,
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
): ClipboardPaste? = readClipboardPasteFromHelper(clipboard, styles, allowedLinkSchemes)
