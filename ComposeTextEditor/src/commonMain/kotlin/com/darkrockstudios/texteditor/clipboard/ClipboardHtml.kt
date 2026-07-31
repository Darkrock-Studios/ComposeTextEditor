package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard

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
