package e2e

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.AnnotatedString
import utils.EditorUiTestScope
import utils.editorUiTest
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import kotlin.test.Test

class ScratchUndoProbeTest {

	@OptIn(ExperimentalComposeUiApi::class)
	private fun EditorUiTestScope.pasteHtml(html: String) {
		clipboard.seed(ClipEntry(ProbeHtmlTransferable(html)))
		press(Key.V, ctrl = true)
	}

	private fun EditorUiTestScope.dump(label: String) {
		println("=== $label")
		println("text: ${state.getAllText().text.replace("\n", "\\n")}")
		state.textLines.forEachIndexed { i, line ->
			println("  line $i: '${line.text}' para=${line.paragraphStyles.map { "${it.start}..${it.end}:${it.item.textIndent}" }}")
		}
		println("  spans: " + state.richSpanManager.getAllRichSpans().map {
			"${it.style::class.simpleName}@${it.range.start.line}[${it.range.start.char},${it.range.end.char})"
		})
	}

	@Test
	fun `probe A mid line list paste then undo`() = editorUiTest(
		initialText = AnnotatedString("intro"),
	) {
		press(Key.MoveEnd, ctrl = true)
		pasteHtml("<ul><li>one</li><li>two</li></ul>")
		dump("A after paste")
		press(Key.Z, ctrl = true)
		dump("A after undo")
	}

	@Test
	fun `probe B line start single block paste then undo`() = editorUiTest(
		initialText = AnnotatedString("intro"),
	) {
		pasteHtml("<blockquote><p>quoted</p></blockquote>")
		dump("B after paste")
		press(Key.Z, ctrl = true)
		dump("B after undo")
	}

	@Test
	fun `probe C line start list paste then undo`() = editorUiTest(
		initialText = AnnotatedString("intro"),
	) {
		pasteHtml("<ul><li>one</li><li>two</li></ul>")
		dump("C after paste")
		press(Key.Z, ctrl = true)
		dump("C after undo")
	}

	@Test
	fun `probe D paste into second line of doc with existing bullet elsewhere`() = editorUiTest(
		initialText = AnnotatedString("intro\ntail"),
	) {
		press(Key.MoveEnd, ctrl = true)
		pasteHtml("<ul><li>one</li><li>two</li></ul>")
		dump("D after paste")
		press(Key.Z, ctrl = true)
		dump("D after undo")
	}
}

private class ProbeHtmlTransferable(private val html: String) : Transferable {
	private val htmlFlavor = DataFlavor("text/html;class=java.lang.String;charset=Unicode")

	override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(htmlFlavor)

	override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
		transferDataFlavors.any { it.match(flavor) }

	override fun getTransferData(flavor: DataFlavor): Any =
		if (flavor.match(htmlFlavor)) html else throw UnsupportedFlavorException(flavor)
}
