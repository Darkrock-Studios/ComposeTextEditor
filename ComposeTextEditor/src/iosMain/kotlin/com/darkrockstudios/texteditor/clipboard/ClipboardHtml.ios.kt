package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.RichTextStyles
import platform.UIKit.UIPasteboard

/** One read of the pasteboard: iOS tells the user each time an app reads another's. */
internal actual suspend fun readClipboardPaste(
	clipboard: Clipboard,
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
): ClipboardPaste? {
	val paste = UIPasteboard.generalPasteboard.readStyled(styles, allowedLinkSchemes)
	val text = paste.text ?: return null
	return ClipboardPaste(text, paste.html, paste.copyId, paste.document)
}
