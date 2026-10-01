package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.html.toHtml
import com.darkrockstudios.texteditor.RichTextStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalComposeUiApi::class)
actual object ClipboardHelper {
	internal val copyIdFlavor = DataFlavor(java.lang.Long::class.java, "ComposeTextEditorCopyId")

	actual suspend fun getText(
		clipboard: Clipboard,
		styles: RichTextStyles,
		allowedLinkSchemes: Set<String>,
	): AnnotatedString? = clipboard.readClipboard { it.readStyledText(styles, allowedLinkSchemes) }

	actual suspend fun getPlainText(clipboard: Clipboard): String? = clipboard.readClipboard { transferable ->
		transferable.readPlainText()?.text?.takeIf { it.isNotEmpty() }
			?: transferable.readHtml(RichTextStyles.DEFAULT)?.text
	}

	actual suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		styles: RichTextStyles,
		copyId: Long?,
		html: String?,
	): Boolean = try {
		clipboard.setClipEntry(ClipEntry(AnnotatedStringTransferable(text, styles, copyId, html)))
		true
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		// AWT cannot open the system clipboard while another application holds it.
		System.err.println("ComposeTextEditor: could not write the clipboard: $e")
		false
	}

	actual suspend fun readCopyId(clipboard: Clipboard): Long? = clipboard.readClipboard { it.readCopyId() }

	actual val supportsCopyProvenance: Boolean get() = true
}

private val annotatedStringFlavor = DataFlavor(AnnotatedString::class.java, "AnnotatedString")

/** Null when headless. */
private val awtSystemClipboard: java.awt.datatransfer.Clipboard? by lazy {
	runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull()
}

/**
 * What [decode] makes of the clipboard's content, or null when it cannot be read. AWT
 * throws while another application holds the system clipboard open (Windows), and
 * Compose passes that on. The system clipboard is read and decoded off the calling
 * thread: an X11 transfer waits on the owner, for seconds if it hangs.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal suspend fun <T> Clipboard.readClipboard(decode: (Transferable) -> T?): T? = try {
	if (nativeClipboard === awtSystemClipboard) {
		withContext(Dispatchers.IO) { getClipEntry()?.asAwtTransferable?.let(decode) }
	} else {
		getClipEntry()?.asAwtTransferable?.let(decode)
	}
} catch (e: CancellationException) {
	throw e
} catch (e: Exception) {
	System.err.println("ComposeTextEditor: could not read the clipboard: $e")
	null
}

/**
 * The styled text on offer: an in-process copy exactly, else the text of the HTML
 * flavor other applications provide, else the plain text.
 */
internal fun Transferable.readStyledText(styles: RichTextStyles, allowedLinkSchemes: Set<String>): AnnotatedString? =
	readAnnotatedString()
		?: readHtmlMarkup()?.let { parsePasteHtml(it, styles, allowedLinkSchemes) }?.text
		?: readPlainText()

/** [readStyledText] with the markup, as parsed where the text came from it, and the copy id. */
internal fun Transferable.readPaste(styles: RichTextStyles, allowedLinkSchemes: Set<String>): ClipboardPaste? {
	val html = readHtmlMarkup()
	val copyId = readCopyId()
	readAnnotatedString()?.let { return ClipboardPaste(it, html, copyId) }
	val document = html?.let { parsePasteHtml(it, styles, allowedLinkSchemes) }
	val text = document?.text ?: readPlainText() ?: return null
	return ClipboardPaste(text, html, copyId, document)
}

/** Whether this offers text in any flavor [readStyledText] takes. */
internal fun Transferable.offersText(): Boolean =
	transferDataFlavors.any { it.match(annotatedStringFlavor) || it.isHtmlStringFlavor() || it.match(DataFlavor.stringFlavor) }

/** The copy id this editor attached, or null when another application wrote this. */
internal fun Transferable.readCopyId(): Long? = runCatching {
	if (!isDataFlavorSupported(ClipboardHelper.copyIdFlavor)) return null
	getTransferData(ClipboardHelper.copyIdFlavor) as? Long
}.getOrNull()

/** The markup on the `text/html` flavor, or null when there is none. */
internal fun Transferable.readHtmlMarkup(): String? = runCatching {
	val flavor = transferDataFlavors.firstOrNull { it.isHtmlStringFlavor() } ?: return null
	getTransferData(flavor) as? String
}.getOrNull()

private fun Transferable.readAnnotatedString(): AnnotatedString? = runCatching {
	if (!isDataFlavorSupported(annotatedStringFlavor)) return null
	getTransferData(annotatedStringFlavor) as? AnnotatedString
}.getOrNull()

private fun Transferable.readHtml(styles: RichTextStyles): AnnotatedString? =
	readHtmlMarkup()?.toAnnotatedStringFromHtml(styles)?.takeIf { it.text.isNotEmpty() }

private fun Transferable.readPlainText(): AnnotatedString? = runCatching {
	if (!isDataFlavorSupported(DataFlavor.stringFlavor)) return null
	(getTransferData(DataFlavor.stringFlavor) as? String)?.let { AnnotatedString(it) }
}.getOrNull()

private fun DataFlavor.isHtmlStringFlavor(): Boolean =
	mimeType.startsWith("text/html") && representationClass == String::class.java

/**
 * Offers the selection as HTML, as an in-process [AnnotatedString], and as plain
 * text. External applications take the HTML and keep the formatting; another
 * editor in this process takes the [AnnotatedString] and keeps it exactly.
 * [copyId] identifies the copy that produced this content: pasting consults it
 * before re-applying the in-editor rich-span buffer, so identical text written
 * by any other source can never resurrect stale spans.
 *
 * [blockHtml] is the markup to offer. An [AnnotatedString] carries character
 * styling alone, so deriving the fragment from it describes a selection with no
 * lists, quotes or headings; a caller copying out of an editor passes markup
 * built from the document's line-anchored spans instead.
 */
internal class AnnotatedStringTransferable(
	private val annotatedString: AnnotatedString,
	private val styles: RichTextStyles = RichTextStyles.DEFAULT,
	private val copyId: Long? = null,
	private val blockHtml: String? = null,
) : Transferable {

	private val annotatedStringFlavor = DataFlavor(AnnotatedString::class.java, "AnnotatedString")
	private val htmlFlavor = DataFlavor("text/html;class=java.lang.String;charset=Unicode")

	private val html by lazy { blockHtml ?: annotatedString.toHtml(styles) }

	override fun getTransferDataFlavors(): Array<DataFlavor> = buildList {
		add(annotatedStringFlavor)
		add(htmlFlavor)
		add(DataFlavor.stringFlavor)
		if (copyId != null) add(ClipboardHelper.copyIdFlavor)
	}.toTypedArray()

	override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
		transferDataFlavors.any { it.match(flavor) }

	override fun getTransferData(flavor: DataFlavor): Any = when {
		flavor.match(annotatedStringFlavor) -> annotatedString
		flavor.match(htmlFlavor) -> html
		copyId != null && flavor.match(ClipboardHelper.copyIdFlavor) -> copyId
		flavor.match(DataFlavor.stringFlavor) -> annotatedString.text
		else -> throw UnsupportedFlavorException(flavor)
	}
}
