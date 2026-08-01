package utils

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

/** Offers markup the way a browser or word processor does: HTML, and nothing editor-specific. */
internal class ForeignHtmlTransferable(private val html: String) : Transferable {
	private val htmlFlavor = DataFlavor("text/html;class=java.lang.String;charset=Unicode")

	override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(htmlFlavor)

	override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
		transferDataFlavors.any { it.match(flavor) }

	override fun getTransferData(flavor: DataFlavor): Any =
		if (flavor.match(htmlFlavor)) html else throw UnsupportedFlavorException(flavor)
}
