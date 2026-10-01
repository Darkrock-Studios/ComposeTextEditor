package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.RichTextStyles

/**
 * One AWT read: each fetches every flavor the source offers, and on X11 can wait on the
 * owner. An in-process copy's markup is read too, not just foreign markup: its rich-span
 * buffer only survives as far as the next edit, and the markup is what carries its blocks
 * after that.
 */
internal actual suspend fun readClipboardPaste(
	clipboard: Clipboard,
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
): ClipboardPaste? = clipboard.readClipboard { transferable ->
	transferable.readPaste(styles, allowedLinkSchemes)
}
