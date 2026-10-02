package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.html.DEFAULT_LINK_SCHEMES
import com.darkrockstudios.texteditor.html.HtmlDocument
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.html.toHtml
import com.darkrockstudios.texteditor.RichTextStyles
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
	actual suspend fun getText(
		clipboard: Clipboard,
		styles: RichTextStyles,
		allowedLinkSchemes: Set<String>,
	): AnnotatedString? = UIPasteboard.generalPasteboard.readStyled(styles, allowedLinkSchemes).text

	actual suspend fun getPlainText(clipboard: Clipboard): String? =
		UIPasteboard.generalPasteboard.readPlain()

	actual suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		styles: RichTextStyles,
		copyId: Long?,
		html: String?,
	): Boolean {
		UIPasteboard.generalPasteboard.writeStyled(text.text, html ?: text.toHtml(styles), copyId)
		return true
	}

	actual suspend fun readCopyId(clipboard: Clipboard): Long? = UIPasteboard.generalPasteboard.readCopyId()

	actual val supportsCopyProvenance: Boolean get() = true
}

private const val HTML_TYPE = "public.html"
private const val TEXT_TYPE = "public.utf8-plain-text"
private const val COPY_ID_TYPE = "com.darkrockstudios.texteditor.copy-id"

/**
 * What a paste read: the text to insert, the markup it came from, the copy id this
 * editor attached to its own copy, and the markup as parsed where the text came from it.
 */
internal class PasteboardPaste(
	val text: AnnotatedString?,
	val html: String?,
	val copyId: Long?,
	val document: HtmlDocument? = null,
)

/**
 * The markup parsed with [styles] where there is some, else the plain text. This
 * editor's own copy must paste the characters it copied, which the in-editor span buffer
 * matches against, so its markup is used only where it re-parses to them. Several items
 * paste as their texts one per line.
 */
internal fun UIPasteboard.readStyled(
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String> = DEFAULT_LINK_SCHEMES,
): PasteboardPaste {
	if (numberOfItems > 1) return PasteboardPaste(allTexts()?.let(::AnnotatedString), html = null, copyId = null)
	val plain = string?.takeIf { it.isNotEmpty() }
	val copyId = readCopyId()
	val html = utf8(HTML_TYPE)
	val document = html?.let { parsePasteHtml(it, styles, allowedLinkSchemes) }
		?.takeIf { copyId == null || it.text.text == plain }
	// Markup the text did not come from describes other text, so its blocks cannot apply.
	return PasteboardPaste(
		text = document?.text ?: plain?.let(::AnnotatedString),
		html = html?.takeIf { document != null },
		copyId = copyId,
		document = document,
	)
}

/** The copy id this editor attached to the pasteboard's content, or null. */
internal fun UIPasteboard.readCopyId(): Long? = utf8(COPY_ID_TYPE)?.toLongOrNull()

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
