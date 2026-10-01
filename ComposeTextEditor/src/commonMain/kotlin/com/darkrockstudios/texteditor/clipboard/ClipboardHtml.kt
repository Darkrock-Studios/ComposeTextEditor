package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.RichTextStyles

/**
 * The raw markup on the clipboard's `text/html` flavor, or null when the
 * platform does not offer one.
 *
 * [ClipboardHelper.getText] already turns this into styled text; this exists so
 * a paste can recover the block structure — lists, blockquotes, code fences —
 * that styling alone cannot carry. Callers re-parse the same markup and match
 * the result against the text they were given, which is what tells them the text
 * really did come from this flavor rather than an in-process copy.
 */
internal expect suspend fun readClipboardHtml(clipboard: Clipboard): String?

/** What a paste took off the clipboard: the text, the markup it came with, and the copy id. */
internal class ClipboardPaste(val text: AnnotatedString, val html: String?, val copyId: Long?)

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

/**
 * [readClipboardPaste] through [ClipboardHelper.getText], [readClipboardHtml] and
 * [ClipboardHelper.readCopyId], for a platform whose helper hands the later two what
 * [ClipboardHelper.getText] read.
 */
internal suspend fun readClipboardPasteFromHelper(
	clipboard: Clipboard,
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
): ClipboardPaste? {
	val text = ClipboardHelper.getText(clipboard, styles, allowedLinkSchemes) ?: return null
	return ClipboardPaste(text, readClipboardHtml(clipboard), ClipboardHelper.readCopyId(clipboard))
}
