package clipboard

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.clipboard.AnnotatedStringTransferable
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnnotatedStringTransferableTest {

	private val config = MarkdownConfiguration.DEFAULT

	private fun styled(): AnnotatedString = buildAnnotatedString {
		append("Hello ")
		pushStyle(config.boldStyle)
		append("world")
		pop()
	}

	private fun htmlFlavorOf(transferable: AnnotatedStringTransferable): DataFlavor? =
		transferable.transferDataFlavors.firstOrNull {
			it.mimeType.startsWith("text/html") && it.representationClass == String::class.java
		}

	@Test
	fun `offers an html flavor backed by a string`() {
		val transferable = AnnotatedStringTransferable(styled())
		val flavor = htmlFlavorOf(transferable)

		assertNotNull(flavor, "expected a text/html String flavor among ${transferable.transferDataFlavors.toList()}")
		assertTrue(transferable.isDataFlavorSupported(flavor))
	}

	@Test
	fun `html flavor carries the serialized markup`() {
		val transferable = AnnotatedStringTransferable(styled())
		val flavor = assertNotNull(htmlFlavorOf(transferable))

		val html = transferable.getTransferData(flavor) as String
		assertEquals("Hello <strong>world</strong>", html)
	}

	@Test
	fun `html flavor round trips back to a styled string`() {
		val transferable = AnnotatedStringTransferable(styled())
		val flavor = assertNotNull(htmlFlavorOf(transferable))

		val html = transferable.getTransferData(flavor) as String
		val parsed = html.toAnnotatedStringFromHtml(config)

		assertEquals("Hello world", parsed.text)
		assertTrue(
			parsed.spanStyles.any { it.start == 6 && it.end == 11 },
			"expected a span covering 'world', got ${parsed.spanStyles}",
		)
	}

	@Test
	fun `plain text flavor carries unstyled text`() {
		val transferable = AnnotatedStringTransferable(styled())
		val text = transferable.getTransferData(DataFlavor.stringFlavor) as String
		assertEquals("Hello world", text)
	}

	@Test
	fun `annotated string flavor is offered first for in-process fidelity`() {
		val transferable = AnnotatedStringTransferable(styled())
		val first = transferable.transferDataFlavors.first()

		assertEquals(AnnotatedString::class.java, first.representationClass)
		assertEquals(styled(), transferable.getTransferData(first))
	}
}
