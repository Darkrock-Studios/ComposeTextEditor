package com.darkrockstudios.texteditor.clipboard

import android.content.ClipData
import android.os.PersistableBundle
import android.util.Log
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.html.toHtml
import com.darkrockstudios.texteditor.RichTextStyles

/**
 * Copies offer the selection as HTML beside its text (`ClipData.newHtmlText`), which
 * other apps paste styled, with the copy id in the description's extras. Pastes
 * prefer an item's HTML and fall back to its text; several items paste one per line,
 * as `TextView` pastes them.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual object ClipboardHelper {
	private const val COPY_ID_EXTRA = "com.darkrockstudios.texteditor.COPY_ID"
	private const val TAG = "ClipboardHelper"

	/**
	 * The clip the last [getText] read, which a paste's [readClipboardHtml] and
	 * [readCopyId] reuse: Android 12 and later tell the user each time an app reads
	 * another app's clip.
	 */
	internal var pasteClip: ClipData? = null
		private set

	actual suspend fun getText(
		clipboard: Clipboard,
		styles: RichTextStyles,
	): AnnotatedString? {
		val clipData = clipboard.getClipEntry()?.clipData
		pasteClip = clipData
		val items = clipData?.items() ?: return null
		// This editor's own copy must paste the characters it copied, which the in-editor
		// span buffer matches against; markup that re-parses to other text loses its
		// styling rather than change them.
		val ours = clipData.copyId() != null
		val styled = items.mapNotNull { item ->
			val text = item.text?.toString()
			item.htmlText
				?.toAnnotatedStringFromHtml(styles)
				?.takeIf { it.text.isNotEmpty() && (!ours || it.text == text) }
				?: text?.let(::AnnotatedString)
		}
		if (styled.isEmpty()) return null
		return styled.singleOrNull() ?: buildAnnotatedString {
			styled.forEachIndexed { index, text ->
				if (index > 0) append('\n')
				append(text)
			}
		}
	}

	actual suspend fun getPlainText(clipboard: Clipboard): String? {
		val items = clipboard.getClipEntry()?.clipData?.items() ?: return null
		return items
			.mapNotNull { item -> item.text?.toString() ?: item.htmlText?.toAnnotatedStringFromHtml()?.text }
			.takeIf { it.isNotEmpty() }
			?.joinToString("\n")
	}

	actual suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		styles: RichTextStyles,
		copyId: Long?,
		html: String?,
	): Boolean {
		val clipData = ClipData.newHtmlText("text", text.text, html ?: text.toHtml(styles))
		if (copyId != null) {
			clipData.description.extras = PersistableBundle().apply { putLong(COPY_ID_EXTRA, copyId) }
		}
		try {
			clipboard.setClipEntry(clipData.toClipEntry())
			return true
		} catch (e: RuntimeException) {
			// A clip past the binder transaction limit is refused; the text alone is
			// half the size, so a large selection still copies.
			Log.w(TAG, "Could not copy with markup, copying plain text", e)
		}
		return try {
			clipboard.setClipEntry(ClipData.newPlainText("text", text.text).toClipEntry())
			true
		} catch (e: RuntimeException) {
			Log.w(TAG, "Could not copy", e)
			false
		}
	}

	actual suspend fun readCopyId(clipboard: Clipboard): Long? {
		val clipData = pasteClip ?: clipboard.getClipEntry()?.clipData
		pasteClip = null
		return clipData?.copyId()
	}

	actual val supportsCopyProvenance: Boolean get() = true

	private fun ClipData.copyId(): Long? {
		val extras = description?.extras ?: return null
		return if (extras.containsKey(COPY_ID_EXTRA)) extras.getLong(COPY_ID_EXTRA, 0L) else null
	}

	private fun ClipData.items(): List<ClipData.Item> = (0 until itemCount).map(::getItemAt)
}
