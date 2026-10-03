package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import platform.UIKit.UIPasteboard

/**
 * iOS implementation of ClipboardHelper using UIPasteboard.
 * Supports plain text only (styled text is not preserved).
 */
actual object ClipboardHelper {
	actual suspend fun getText(
		clipboard: Clipboard,
		configuration: MarkdownConfiguration,
	): AnnotatedString? = getPlainText(clipboard)?.let(::AnnotatedString)

	actual suspend fun getPlainText(clipboard: Clipboard): String? =
		UIPasteboard.generalPasteboard.string

	actual suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		configuration: MarkdownConfiguration,
		copyId: Long?,
		html: String?,
	) {
		UIPasteboard.generalPasteboard.string = text.text
	}

	actual suspend fun readCopyId(clipboard: Clipboard): Long? = null

	actual val supportsCopyProvenance: Boolean get() = false
}
