package com.darkrockstudios.texteditor.clipboard

import android.content.ClipData
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration

@OptIn(ExperimentalComposeUiApi::class)
actual object ClipboardHelper {
	actual suspend fun getText(
		clipboard: Clipboard,
		configuration: MarkdownConfiguration,
	): AnnotatedString? {
		return clipboard.getClipEntry()?.clipData?.let { clipData ->
			if (clipData.itemCount > 0) {
				clipData.getItemAt(0).text?.toString()?.let { text ->
					AnnotatedString(text)
				}
			} else null
		}
	}

	actual suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		configuration: MarkdownConfiguration,
		copyId: Long?,
	) {
		val clipData = ClipData.newPlainText("text", text.text)
		clipboard.setClipEntry(clipData.toClipEntry())
	}

	actual suspend fun readCopyId(clipboard: Clipboard): Long? = null

	actual val supportsCopyProvenance: Boolean get() = false
}
