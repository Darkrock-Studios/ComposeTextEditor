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
	): AnnotatedString? {
		return UIPasteboard.generalPasteboard.string?.let { text ->
			AnnotatedString(text)
		}
	}

	actual suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		configuration: MarkdownConfiguration,
	) {
		UIPasteboard.generalPasteboard.string = text.text
	}
}
