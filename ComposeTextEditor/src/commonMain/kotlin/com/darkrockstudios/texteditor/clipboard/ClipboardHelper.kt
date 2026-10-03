package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration

/**
 * Platform-specific clipboard helper for text operations.
 *
 * Since ClipEntry cannot be constructed from commonMain and the new Clipboard API
 * uses suspend functions, this expect/actual pattern provides platform-specific
 * implementations for clipboard text operations.
 *
 * - Desktop: styled, as HTML for other applications and exactly within this process
 * - Android: styled, as HTML beside plain text, with the copy id in the clip's extras
 * - iOS: styled, as HTML beside plain text, with the copy id in a private type
 * - Web: styled, as HTML beside plain text, where the browser allows it
 *
 * `configuration` supplies the styling that header levels are matched against
 * when converting to and from HTML, so pass the editor's own or custom header
 * sizes will not survive the round trip.
 */
expect object ClipboardHelper {
	/**
	 * Reads text from the clipboard.
	 * On Desktop, reads styled text, preferring an in-process copy and falling back
	 * to the HTML flavor other applications provide. On the web, reads the HTML
	 * flavor where the browser offers it. On Android, reads each item's HTML.
	 * On iOS, reads the pasteboard's `public.html`, else its plain text.
	 */
	suspend fun getText(
		clipboard: Clipboard,
		configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	): AnnotatedString?

	/**
	 * Reads the clipboard as plain text, for a paste without formatting: the plain
	 * text the source offered, which is what it means its content to read as
	 * unformatted. Where the platform exposes one and the source offered no plain
	 * text, the text of its markup.
	 */
	suspend fun getPlainText(clipboard: Clipboard): String?

	/**
	 * Writes text to the clipboard.
	 * On Desktop, offers the selection as HTML for other applications and as an
	 * exact copy within this process; [copyId] rides along so a later paste can
	 * prove the clipboard content is still this copy.
	 * On the web, offers HTML beside plain text where the browser allows it. On
	 * Android, offers HTML beside the text and carries [copyId] in the clip's extras.
	 * On iOS, offers `public.html` beside the text and [copyId] in a private type.
	 * [copyId] is ignored on the web.
	 *
	 * [html] is the markup to offer, which callers copying out of an editor supply
	 * so the fragment carries the selection's block structure. Null falls back to
	 * markup derived from [text] alone, which describes its character styling only.
	 */
	suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
		copyId: Long? = null,
		html: String? = null,
	)

	/**
	 * The [copyId] this editor attached to the current clipboard content, or null
	 * when another application wrote the clipboard or the platform cannot carry it.
	 */
	suspend fun readCopyId(clipboard: Clipboard): Long?

	/**
	 * Whether this platform's clipboard can carry a [copyId]. Where it cannot
	 * (plain-text-only clipboards), paste falls back to matching copied text.
	 */
	val supportsCopyProvenance: Boolean
}
