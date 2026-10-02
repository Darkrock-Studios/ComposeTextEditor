package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.html.toHtml
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.UIKit.UIPasteboard

/**
 * Copies put one pasteboard item holding the selection as `public.html` beside its
 * `public.utf8-plain-text`, which Notes, Mail and Safari paste styled, and the copy id in
 * a private type. Pastes prefer the markup another app or this editor offered and fall
 * back to the plain text; several items paste one per line, as on Android.
 */
actual object ClipboardHelper {
	/**
	 * The markup the last [getText] read, handed once to [readClipboardHtml], so a paste
	 * reads the pasteboard once: iOS tells the user each time an app reads another's.
	 */
	private var lastReadHtml: String? = null

	/** The copy id the last [getText] read, handed once to [readCopyId] for the same reason. */
	private var lastReadCopyId: Long? = null

	actual suspend fun getText(
		clipboard: Clipboard,
		configuration: MarkdownConfiguration,
	): AnnotatedString? {
		val paste = UIPasteboard.generalPasteboard.readStyled(configuration)
		lastReadHtml = paste.html
		lastReadCopyId = paste.copyId
		return paste.text
	}

	actual suspend fun getPlainText(clipboard: Clipboard): String? =
		UIPasteboard.generalPasteboard.readPlain()

	actual suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		configuration: MarkdownConfiguration,
		copyId: Long?,
		html: String?,
	) = UIPasteboard.generalPasteboard.writeStyled(text.text, html ?: text.toHtml(configuration), copyId)

	actual suspend fun readCopyId(clipboard: Clipboard): Long? = lastReadCopyId.also { lastReadCopyId = null }

	actual val supportsCopyProvenance: Boolean get() = true

	internal fun takeLastReadHtml(): String? = lastReadHtml.also { lastReadHtml = null }
}

private const val HTML_TYPE = "public.html"
private const val TEXT_TYPE = "public.utf8-plain-text"
private const val COPY_ID_TYPE = "com.darkrockstudios.texteditor.copy-id"

/**
 * What a paste read: the text to insert, the markup it came from, and the copy id this
 * editor attached to its own copy.
 */
internal class PasteboardPaste(val text: AnnotatedString?, val html: String?, val copyId: Long?)

/**
 * The markup parsed with [configuration] where there is some, else the plain text. This
 * editor's own copy must paste the characters it copied, which the in-editor span buffer
 * matches against, so its markup is used only where it re-parses to them. Several items
 * paste as their texts one per line.
 */
internal fun UIPasteboard.readStyled(configuration: MarkdownConfiguration): PasteboardPaste {
	if (numberOfItems > 1) return PasteboardPaste(allTexts()?.let(::AnnotatedString), html = null, copyId = null)
	val plain = string?.takeIf { it.isNotEmpty() }
	val copyId = utf8(COPY_ID_TYPE)?.toLongOrNull()
	val html = utf8(HTML_TYPE)
	val styled = html?.toAnnotatedStringFromHtml(configuration)
		?.takeIf { it.text.isNotEmpty() && (copyId == null || it.text == plain) }
	return PasteboardPaste(text = styled ?: plain?.let(::AnnotatedString), html = html, copyId = copyId)
}

/** The source's own plain text, or the text of its markup where it offered none. */
internal fun UIPasteboard.readPlain(): String? {
	if (numberOfItems > 1) return allTexts()
	return string?.takeIf { it.isNotEmpty() }
		?: utf8(HTML_TYPE)?.toAnnotatedStringFromHtml()?.text?.takeIf { it.isNotEmpty() }
}

/** Replaces the pasteboard with one item holding [text], its [markup], and [copyId]. */
internal fun UIPasteboard.writeStyled(text: String, markup: String, copyId: Long? = null) {
	val item = buildMap<Any?, Any?> {
		put(TEXT_TYPE, text)
		markup.toUtf8Data()?.let { put(HTML_TYPE, it) }
		copyId?.toString()?.toUtf8Data()?.let { put(COPY_ID_TYPE, it) }
	}
	setItems(listOf(item))
}

/** Every item's plain text, one per line. */
private fun UIPasteboard.allTexts(): String? =
	strings?.mapNotNull { (it as? String)?.takeIf(String::isNotEmpty) }?.takeIf { it.isNotEmpty() }?.joinToString("\n")

/** The first item's [type], decoded as UTF-8, or null when it offers none. */
@OptIn(BetaInteropApi::class)
private fun UIPasteboard.utf8(type: String): String? {
	val data = dataForPasteboardType(type) ?: return null
	return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()?.takeIf { it.isNotEmpty() }
}

@Suppress("CAST_NEVER_SUCCEEDS")
private fun String.toUtf8Data() = (this as NSString).dataUsingEncoding(NSUTF8StringEncoding)
