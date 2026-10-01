package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.RichTextStyles

internal actual suspend fun readClipboardHtml(clipboard: Clipboard): String? = clipboard.readClipboard { transferable ->
	// An in-process copy is read here too, not just foreign markup. Its rich-span
	// buffer only survives as far as the next edit, and the HTML is what carries
	// its blocks after that; the two agree, and applying a block a line already
	// carries is a no-op. The caller still checks the markup re-parses to the text
	// it was handed, which is what rejects a flavor describing something else.
	transferable.readHtmlMarkup()
}

/** One AWT read: each fetches every flavor the source offers, and on X11 can wait on the owner. */
internal actual suspend fun readClipboardPaste(
	clipboard: Clipboard,
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
): ClipboardPaste? = clipboard.readClipboard { transferable ->
	transferable.readStyledText(styles, allowedLinkSchemes)?.let { text ->
		ClipboardPaste(text, transferable.readHtmlMarkup(), transferable.readCopyId())
	}
}
