@file:OptIn(ExperimentalWasmJsInterop::class)

package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise
import kotlinx.coroutines.await

actual object ClipboardHelper {
	actual suspend fun getText(
		clipboard: Clipboard,
		configuration: MarkdownConfiguration,
	): AnnotatedString? {
		return try {
			val text = readClipboardText().await<JsString>().toString()
			AnnotatedString(text)
		} catch (e: Exception) {
			// Clipboard access may be denied or unavailable
			null
		}
	}

	actual suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		configuration: MarkdownConfiguration,
		copyId: Long?,
	) {
		try {
			writeClipboardText(text.text).await<JsAny?>()
		} catch (e: Exception) {
			// Clipboard access may be denied or unavailable
		}
	}

	actual suspend fun readCopyId(clipboard: Clipboard): Long? = null

	actual val supportsCopyProvenance: Boolean get() = false
}

// Top-level external functions for browser clipboard API
private fun readClipboardText(): Promise<JsString> = js("navigator.clipboard.readText()")
private fun writeClipboardText(text: String): Promise<JsAny?> = js("navigator.clipboard.writeText(text)")
