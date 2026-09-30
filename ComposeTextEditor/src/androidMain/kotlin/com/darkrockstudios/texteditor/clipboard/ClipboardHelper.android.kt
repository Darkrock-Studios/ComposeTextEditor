package com.darkrockstudios.texteditor.clipboard

import android.content.ClipData
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration

@OptIn(ExperimentalComposeUiApi::class)
actual object ClipboardHelper {
	actual suspend fun getText(
		clipboard: Clipboard,
		configuration: MarkdownConfiguration,
	): AnnotatedString? = getPlainText(clipboard)?.let(::AnnotatedString)

	/** Every item's text, one per line as `TextView` pastes them, or null when none has any. */
	actual suspend fun getPlainText(clipboard: Clipboard): String? {
		val clipData = clipboard.getClipEntry()?.clipData ?: return null
		return (0 until clipData.itemCount)
			.mapNotNull { index ->
				val item = clipData.getItemAt(index)
				item.text?.toString() ?: item.htmlText?.toAnnotatedStringFromHtml()?.text
			}
			.takeIf { it.isNotEmpty() }
			?.joinToString("\n")
	}

	actual suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		configuration: MarkdownConfiguration,
		copyId: Long?,
		html: String?,
	) {
		val clipData = ClipData.newPlainText("text", text.text)
		clipboard.setClipEntry(clipData.toClipEntry())
	}

	actual suspend fun readCopyId(clipboard: Clipboard): Long? = null

	actual val supportsCopyProvenance: Boolean get() = false
}
