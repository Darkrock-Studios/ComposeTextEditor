package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import com.darkrockstudios.texteditor.RichTextStyles

/** One read of the clip: Android 12 and later tell the user each time an app reads another's. */
@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun readClipboardPaste(
	clipboard: Clipboard,
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
): ClipboardPaste? = clipboard.getClipEntry()?.clipData?.let { ClipboardHelper.readPaste(it, styles, allowedLinkSchemes) }
