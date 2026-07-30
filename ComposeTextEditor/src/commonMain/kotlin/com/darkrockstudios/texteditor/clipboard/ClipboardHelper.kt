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
 * - Android: Plain text only (ClipData limitation)
 * - iOS: Plain text only (UIPasteboard limitation)
 * - WASM: Plain text only (web clipboard limitation)
 *
 * `configuration` supplies the styling that header levels are matched against
 * when converting to and from HTML, so pass the editor's own or custom header
 * sizes will not survive the round trip.
 */
expect object ClipboardHelper {
	/**
	 * Reads text from the clipboard.
	 * On Desktop, reads styled text, preferring an in-process copy and falling back
	 * to the HTML flavor other applications provide.
	 * On other platforms, returns plain text as AnnotatedString.
	 */
	suspend fun getText(
		clipboard: Clipboard,
		configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	): AnnotatedString?

	/**
	 * Writes text to the clipboard.
	 * On Desktop, offers the selection as HTML for other applications and as an
	 * exact copy within this process.
	 * On other platforms, writes plain text only.
	 */
	suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	)
}
