package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.html.HtmlDocument
import com.darkrockstudios.texteditor.html.parseHtmlDocument

/**
 * What a paste took off the clipboard: the text, the markup it came with, and the copy id.
 * [document] is the markup as parsed for [text], where [text] came from it, so the paste's
 * blocks need not parse it again.
 */
internal class ClipboardPaste(
	val text: AnnotatedString,
	val html: String?,
	val copyId: Long?,
	val document: HtmlDocument? = null,
)

/**
 * Reads the clipboard for a paste. The text, markup and copy id come from one read of
 * it, so a clipboard that changes meanwhile cannot pair the text with another copy's
 * blocks or spans. Null when it holds no text.
 */
internal expect suspend fun readClipboardPaste(
	clipboard: Clipboard,
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
): ClipboardPaste?

/** [html] parsed as a paste's markup, or null when it holds no text. */
internal fun parsePasteHtml(html: String, styles: RichTextStyles, allowedLinkSchemes: Set<String>): HtmlDocument? =
	parseHtmlDocument(html, styles, allowedLinkSchemes = allowedLinkSchemes).takeIf { it.text.isNotEmpty() }
