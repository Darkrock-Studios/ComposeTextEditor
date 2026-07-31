package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun readClipboardHtml(clipboard: Clipboard): String? {
	val transferable = clipboard.getClipEntry()?.nativeClipEntry as? Transferable ?: return null
	// An in-process copy carries its blocks through the editor's own rich-span
	// buffer, and its AnnotatedString flavor is what `getText` returns, so the
	// HTML it also offers would describe text the caller never received.
	if (transferable.isDataFlavorSupported(DataFlavor(AnnotatedString::class.java, "AnnotatedString"))) {
		return null
	}
	return runCatching {
		val flavor = transferable.transferDataFlavors.firstOrNull {
			it.mimeType.startsWith("text/html") && it.representationClass == String::class.java
		} ?: return null
		transferable.getTransferData(flavor) as? String
	}.getOrNull()
}
