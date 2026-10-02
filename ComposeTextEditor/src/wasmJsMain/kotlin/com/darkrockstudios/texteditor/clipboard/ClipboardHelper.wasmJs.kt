@file:OptIn(ExperimentalWasmJsInterop::class)

package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.html.toHtml
import com.darkrockstudios.texteditor.RichTextStyles
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsException
import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * A keyboard chord's clipboard event ([ClipboardEventsEffect]) has already moved the
 * data when the action runs: a copy or cut wrote it, and a paste left its content
 * here to take. Anything else (the context menu, a host's own call) uses the
 * browser's async clipboard: where `navigator.clipboard.read` and `write` exist (a
 * secure context with `ClipboardItem`), copies carry `text/html` beside `text/plain`
 * and pastes prefer the markup; elsewhere `readText` and `writeText`. A browser can
 * refuse any of these (no permission, no user gesture, an insecure page); each
 * refusal is logged with `console.warn`, as Compose's own web clipboard does, and
 * the operation does nothing.
 */
actual object ClipboardHelper {
	/** How long an event's work stands in for the action the same key press runs next. */
	private const val EVENT_WINDOW_MS = 1_000.0

	private var eventWroteAt = Double.NEGATIVE_INFINITY
	private var eventWroteText: String? = null
	private var eventPaste: Flavors? = null
	private var eventPastedAt = Double.NEGATIVE_INFINITY

	/**
	 * The markup the last [getText] read, handed once to [readClipboardHtml] so a
	 * paste reads the clipboard once: some browsers ask the user on every read.
	 */
	private var lastReadHtml: String? = null

	internal fun eventWrote(text: String) {
		eventWroteAt = now()
		eventWroteText = text
	}

	internal fun eventPasted(html: String?, text: String?) {
		eventPaste = Flavors(html = html?.takeIf { it.isNotEmpty() }, text = text?.takeIf { it.isNotEmpty() })
		eventPastedAt = now()
	}

	actual suspend fun getText(
		clipboard: Clipboard,
		styles: RichTextStyles,
		allowedLinkSchemes: Set<String>,
	): AnnotatedString? {
		val flavors = takeEventPaste() ?: readFlavors()
		lastReadHtml = flavors?.html
		flavors ?: return null
		flavors.html
			?.toAnnotatedStringFromHtml(styles, allowedLinkSchemes)
			?.takeIf { it.text.isNotEmpty() }
			?.let { return it }
		return flavors.text?.let(::AnnotatedString)
	}

	actual suspend fun getPlainText(clipboard: Clipboard): String? {
		takeEventPaste()?.let { flavors ->
			return flavors.text ?: flavors.html?.toAnnotatedStringFromHtml()?.text?.takeIf { it.isNotEmpty() }
		}
		return readPlainText()
	}

	actual suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		styles: RichTextStyles,
		copyId: Long?,
		html: String?,
	): Boolean {
		// Only the write the event already made for this text: an event whose own
		// action never came must not stand in for another write.
		val eventWrote = now() - eventWroteAt < EVENT_WINDOW_MS && eventWroteText == text.text
		eventWroteAt = Double.NEGATIVE_INFINITY
		eventWroteText = null
		if (eventWrote) return true
		if (hasRichClipboard()) {
			val markup = html ?: text.toHtml(styles)
			return succeeds("write HTML to") { writeClipboardHtml(markup, text.text).await<JsAny?>() }
		}
		return succeeds("write text to") { writeClipboardText(text.text).await<JsAny?>() }
	}

	actual suspend fun readCopyId(clipboard: Clipboard): Long? = null

	actual val supportsCopyProvenance: Boolean get() = false

	internal fun takeLastReadHtml(): String? = lastReadHtml.also { lastReadHtml = null }

	private class Flavors(val html: String?, val text: String?)

	private fun takeEventPaste(): Flavors? {
		val paste = eventPaste?.takeIf { now() - eventPastedAt < EVENT_WINDOW_MS }
		eventPaste = null
		return paste
	}

	/**
	 * Both flavors through `read`, or the text alone through `readText` where `read`
	 * does not exist. A refused `read` is not retried as `readText`, which would ask
	 * the user a second time.
	 */
	private suspend fun readFlavors(): Flavors? {
		if (!hasRichClipboard()) return readPlainText()?.let { Flavors(html = null, text = it) }
		val read = attempt("read") { readClipboardFlavors().await<ClipboardFlavors>() } ?: return null
		return Flavors(html = read.html?.takeIf { it.isNotEmpty() }, text = read.text?.takeIf { it.isNotEmpty() })
			.takeIf { it.html != null || it.text != null }
	}

	private suspend fun readPlainText(): String? =
		attempt("read text from") { readClipboardText().await<JsString>().toString() }?.takeIf { it.isNotEmpty() }

	/** Runs [block], logging a refusal and answering whether it completed. */
	private suspend fun succeeds(what: String, block: suspend () -> Unit): Boolean =
		attempt(what) { block(); true } == true

	/** Runs [block], logging a refusal and answering null for it. */
	private suspend fun <T : Any> attempt(what: String, block: suspend () -> T?): T? = try {
		block()
	} catch (e: CancellationException) {
		throw e
	} catch (e: Throwable) {
		val reason = (e as? JsException)?.thrownValue?.let(::describe) ?: e.message
		warn("ComposeTextEditor: could not $what the clipboard: $reason")
		null
	}
}

private external interface ClipboardFlavors : JsAny {
	val html: String?
	val text: String?
}

private fun now(): Double = js("performance.now()")

private fun describe(value: JsAny): String = js("String(value)")

private fun hasRichClipboard(): Boolean = js(
	"""Boolean(window.isSecureContext && navigator.clipboard && navigator.clipboard.read &&
		navigator.clipboard.write && typeof ClipboardItem !== 'undefined')"""
)

private fun readClipboardText(): Promise<JsString> = js("navigator.clipboard.readText()")

private fun writeClipboardText(text: String): Promise<JsAny?> = js("navigator.clipboard.writeText(text)")

private fun writeClipboardHtml(html: String, text: String): Promise<JsAny?> = js(
	"""navigator.clipboard.write([new ClipboardItem({
		'text/html': new Blob([html], { type: 'text/html' }),
		'text/plain': new Blob([text], { type: 'text/plain' }),
	})])"""
)

/** The first `text/html` and `text/plain` among the clipboard's items, each null when none offers it. */
private fun readClipboardFlavors(): Promise<ClipboardFlavors> = js(
	"""(async () => {
		const items = await navigator.clipboard.read();
		let html = null;
		let text = null;
		for (const item of items) {
			if (html === null && item.types.includes('text/html')) html = await (await item.getType('text/html')).text();
			if (text === null && item.types.includes('text/plain')) text = await (await item.getType('text/plain')).text();
		}
		return { html: html, text: text };
	})()"""
)

private fun warn(message: String) {
	js("console.warn(message)")
}
