package com.darkrockstudios.texteditor.clipboard

import android.content.ClipData
import android.os.PersistableBundle
import android.util.Log
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.html.HtmlDocument
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

	actual suspend fun getText(
		clipboard: Clipboard,
		styles: RichTextStyles,
		allowedLinkSchemes: Set<String>,
	): AnnotatedString? = clipboard.getClipEntry()?.clipData?.let { readPaste(it, styles, allowedLinkSchemes) }?.text

	/** [clip] as a paste reads it ([readStyledItems]), with the copy id. */
	internal fun readPaste(clip: ClipData, styles: RichTextStyles, allowedLinkSchemes: Set<String>): ClipboardPaste? {
		val copyId = clip.copyId()
		// This editor's own copy must paste the characters it copied, which the in-editor
		// span buffer matches against.
		val read = clip.readStyledItems(styles, allowedLinkSchemes, ours = copyId != null) ?: return null
		return ClipboardPaste(read.text, read.html, copyId, read.document)
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

	actual suspend fun readCopyId(clipboard: Clipboard): Long? = clipboard.getClipEntry()?.clipData?.copyId()

	actual val supportsCopyProvenance: Boolean get() = true

	private fun ClipData.copyId(): Long? {
		val extras = description?.extras ?: return null
		return if (extras.containsKey(COPY_ID_EXTRA)) extras.getLong(COPY_ID_EXTRA, 0L) else null
	}

}

internal fun ClipData.items(): List<ClipData.Item> = (0 until itemCount).map(::getItemAt)

/** What a clip's items read as: the text, and the markup it came from, parsed. */
internal class StyledItems(val text: AnnotatedString, val html: String?, val document: HtmlDocument?)

/**
 * The items of a clip pasted or dropped: each item's markup, or its text where it has none,
 * or what [readUri] reads of an item that has only a URI (a dropped file), one item per
 * line, as `TextView` takes them. [ours], this editor's own copy or drag, takes an item's
 * markup only where it re-parses to the item's text, since its rich spans are matched
 * against that. The markup comes along only where the text came from it: other markup
 * describes other text, so its blocks cannot apply.
 */
internal fun ClipData.readStyledItems(
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
	ours: Boolean,
	readUri: (ClipData.Item) -> ItemContent? = { null },
): StyledItems? {
	val read = items().mapNotNull { item ->
		val text = item.text?.toString()
		val markup = item.htmlText?.takeIf { it.isNotEmpty() }
		val fromUri = if (text == null && markup == null && item.uri != null) readUri(item) else null
		val html = markup ?: fromUri?.html
		val document = html
			?.let { parsePasteHtml(it, styles, allowedLinkSchemes) }
			?.takeIf { !ours || text == null || it.text.text == text }
		val styled = document?.text ?: (text ?: fromUri?.text)?.let(::AnnotatedString) ?: return@mapNotNull null
		StyledItems(styled, html.takeIf { document != null }, document)
	}
	read.singleOrNull()?.let { return it }
	if (read.isEmpty()) return null
	val joined = buildAnnotatedString {
		read.forEachIndexed { index, item ->
			if (index > 0) append('\n')
			append(item.text)
		}
	}
	return StyledItems(joined, html = null, document = null)
}

/** What an item that is neither text nor markup holds: markup, or else text. */
internal class ItemContent(val text: String?, val html: String?)
