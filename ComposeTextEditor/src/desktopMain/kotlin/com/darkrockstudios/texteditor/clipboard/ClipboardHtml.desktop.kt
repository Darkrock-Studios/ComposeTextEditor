package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.Transferable

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun readClipboardHtml(clipboard: Clipboard): String? {
	val transferable = clipboard.getClipEntry()?.nativeClipEntry as? Transferable ?: return null
	// An in-process copy is read here too, not just foreign markup. Its rich-span
	// buffer only survives as far as the next edit, and the HTML is what carries
	// its blocks after that; the two agree, and applying a block a line already
	// carries is a no-op. The caller still checks the markup re-parses to the text
	// it was handed, which is what rejects a flavor describing something else.
	return runCatching {
		val flavor = transferable.transferDataFlavors.firstOrNull {
			it.mimeType.startsWith("text/html") && it.representationClass == String::class.java
		} ?: return null
		transferable.getTransferData(flavor) as? String
	}.getOrNull()
}
