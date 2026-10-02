package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.RichTextStyles

/** One read of the clipboard, since a browser may ask the user on every read. */
internal actual suspend fun readClipboardPaste(
	clipboard: Clipboard,
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
): ClipboardPaste? = ClipboardHelper.readPaste(styles, allowedLinkSchemes)
