package com.darkrockstudios.texteditor.clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.html.toHtml
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

@OptIn(ExperimentalComposeUiApi::class)
actual object ClipboardHelper {
	private val annotatedStringFlavor = DataFlavor(AnnotatedString::class.java, "AnnotatedString")
	internal val copyIdFlavor = DataFlavor(java.lang.Long::class.java, "ComposeTextEditorCopyId")

	actual suspend fun getText(
		clipboard: Clipboard,
		configuration: MarkdownConfiguration,
	): AnnotatedString? {
		val transferable = clipboard.getClipEntry()?.nativeClipEntry as? Transferable ?: return null
		return transferable.readAnnotatedString()
			?: transferable.readHtml(configuration)
			?: transferable.readPlainText()
	}

	actual suspend fun setText(
		clipboard: Clipboard,
		text: AnnotatedString,
		configuration: MarkdownConfiguration,
		copyId: Long?,
		html: String?,
	) {
		clipboard.setClipEntry(
			ClipEntry(AnnotatedStringTransferable(text, configuration, copyId, html))
		)
	}

	actual suspend fun readCopyId(clipboard: Clipboard): Long? {
		val transferable = clipboard.getClipEntry()?.nativeClipEntry as? Transferable ?: return null
		return runCatching {
			if (!transferable.isDataFlavorSupported(copyIdFlavor)) return null
			transferable.getTransferData(copyIdFlavor) as? Long
		}.getOrNull()
	}

	actual val supportsCopyProvenance: Boolean get() = true

	private fun Transferable.readAnnotatedString(): AnnotatedString? = runCatching {
		if (!isDataFlavorSupported(annotatedStringFlavor)) return null
		getTransferData(annotatedStringFlavor) as? AnnotatedString
	}.getOrNull()

	private fun Transferable.readHtml(configuration: MarkdownConfiguration): AnnotatedString? =
		runCatching {
			val flavor = transferDataFlavors.firstOrNull { it.isHtmlStringFlavor() } ?: return null
			val html = getTransferData(flavor) as? String ?: return null
			html.toAnnotatedStringFromHtml(configuration).takeIf { it.text.isNotEmpty() }
		}.getOrNull()

	private fun Transferable.readPlainText(): AnnotatedString? = runCatching {
		if (!isDataFlavorSupported(DataFlavor.stringFlavor)) return null
		(getTransferData(DataFlavor.stringFlavor) as? String)?.let { AnnotatedString(it) }
	}.getOrNull()

	private fun DataFlavor.isHtmlStringFlavor(): Boolean =
		mimeType.startsWith("text/html") && representationClass == String::class.java
}

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
	private val configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	private val copyId: Long? = null,
	private val blockHtml: String? = null,
) : Transferable {

	private val annotatedStringFlavor = DataFlavor(AnnotatedString::class.java, "AnnotatedString")
	private val htmlFlavor = DataFlavor("text/html;class=java.lang.String;charset=Unicode")

	private val html by lazy { blockHtml ?: annotatedString.toHtml(configuration) }

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
