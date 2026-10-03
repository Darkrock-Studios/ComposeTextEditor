package com.darkrockstudios.texteditor.clipboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/**
 * Turns the primary selection off for the process when set to `false`. The test suites
 * set it, so selecting text in a test never replaces the desktop's primary selection.
 */
internal const val PRIMARY_SELECTION_PROPERTY = "composetexteditor.primarySelection"

/** AWT's system selection: X11's primary selection, null on Windows and macOS. */
internal actual fun platformPrimarySelection(): PrimarySelection? = systemPrimarySelection

private val systemPrimarySelection: PrimarySelection? by lazy {
	if (System.getProperty(PRIMARY_SELECTION_PROPERTY) == "false") return@lazy null
	// Throws when headless.
	runCatching { Toolkit.getDefaultToolkit().systemSelection }.getOrNull()?.let(::AwtPrimarySelection)
}

internal class AwtPrimarySelection(private val selection: Clipboard) : PrimarySelection {
	@Volatile
	private var claim: Claim? = null

	override fun offer(source: PrimarySelectionSource) {
		if (claim?.source === source) return
		val next = Claim(source)
		claim = next
		try {
			selection.setContents(LazyText(source), next)
		} catch (e: Exception) {
			// AWT refuses while another application holds the selection open.
			if (claim === next) claim = null
		}
	}

	/** Off the event thread: an X11 transfer waits on the owner, for seconds if it hangs. */
	override suspend fun readText(): String? = withContext(Dispatchers.IO) {
		try {
			(selection.getData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotEmpty() }
		} catch (e: Exception) {
			null
		}
	}

	/** One offer: losing it, to another application or another offer, lets [source] offer again. */
	private inner class Claim(val source: PrimarySelectionSource) : ClipboardOwner {
		override fun lostOwnership(clipboard: Clipboard, contents: Transferable) {
			if (claim === this) claim = null
		}
	}
}

private class LazyText(private val source: PrimarySelectionSource) : Transferable {
	override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)

	override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor.match(DataFlavor.stringFlavor)

	override fun getTransferData(flavor: DataFlavor): Any {
		if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
		return source.text() ?: ""
	}
}
