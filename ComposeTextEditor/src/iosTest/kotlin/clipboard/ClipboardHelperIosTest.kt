package clipboard

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.clipboard.readPlain
import com.darkrockstudios.texteditor.clipboard.readStyled
import com.darkrockstudios.texteditor.clipboard.writeStyled
import com.darkrockstudios.texteditor.html.toHtml
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.UIKit.UIPasteboard

/**
 * The iOS clipboard's reads and writes against a real UIPasteboard. A
 * private one: a test binary is not an app, and the general pasteboard ignores it.
 */
class ClipboardHelperIosTest {
	private val pasteboard = UIPasteboard.pasteboardWithUniqueName()
	private val styles = RichTextStyles.DEFAULT

	@AfterTest
	fun removePasteboard() {
		UIPasteboard.removePasteboardWithName(pasteboard.name)
	}

	private val bold = buildAnnotatedString {
		append("plain ")
		withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bold") }
	}

	@Suppress("CAST_NEVER_SUCCEEDS")
	private fun offer(html: String?, text: String?) {
		val item = buildMap<Any?, Any?> {
			text?.let { put("public.utf8-plain-text", it) }
			html?.let { put("public.html", (it as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!) }
		}
		pasteboard.setItems(listOf(item))
	}

	private fun offerItems(vararg texts: String) {
		pasteboard.setItems(texts.map { mapOf<Any?, Any?>("public.utf8-plain-text" to it) })
	}

	private fun AnnotatedString.isBold(word: String): Boolean {
		val start = text.indexOf(word)
		return spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start <= start && it.end >= start + word.length }
	}

	@Test
	fun a_copy_offers_markup_beside_its_text_in_one_item() {
		pasteboard.writeStyled(bold.text, bold.toHtml(styles))

		assertEquals(1L, pasteboard.numberOfItems)
		assertEquals("plain bold", pasteboard.string)
		assertTrue(pasteboard.containsPasteboardTypes(listOf("public.html")))
	}

	@Test
	fun a_copy_pastes_back_bold() {
		pasteboard.writeStyled(bold.text, bold.toHtml(styles))

		val pasted = assertNotNull(pasteboard.readStyled(styles).text)

		assertEquals("plain bold", pasted.text)
		assertTrue(pasted.isBold("bold"), "bold survives: ${pasted.spanStyles}")
	}

	@Test
	fun another_apps_markup_is_preferred_and_kept_for_the_block_paste() {
		val markup = "<ul><li>one <b>two</b></li></ul>"
		offer(html = markup, text = "one two")

		val paste = pasteboard.readStyled(styles)

		assertTrue(assertNotNull(paste.text).isBold("two"))
		assertEquals(markup, paste.html)
	}

	@Test
	fun plain_text_alone_pastes_as_it_is() {
		offer(html = null, text = "just text")

		val paste = pasteboard.readStyled(styles)

		assertEquals("just text", paste.text?.text)
		assertNull(paste.html)
	}

	@Test
	fun a_plain_paste_takes_the_plain_flavor_over_the_markup() {
		offer(html = "<p>Rendered <b>markup</b></p>", text = "the source's own text")

		assertEquals("the source's own text", pasteboard.readPlain())
	}

	@Test
	fun a_plain_paste_falls_back_to_the_markup_text() {
		offer(html = "<p>only <b>markup</b></p>", text = null)

		assertEquals("only markup", pasteboard.readPlain())
	}

	@Test
	fun a_copy_carries_its_copy_id() {
		pasteboard.writeStyled(bold.text, bold.toHtml(styles), copyId = 42L)

		assertEquals(42L, pasteboard.readStyled(styles).copyId)
	}

	@Test
	fun another_apps_content_has_no_copy_id() {
		offer(html = "<p>theirs</p>", text = "theirs")

		assertNull(pasteboard.readStyled(styles).copyId)
	}

	/** The in-editor span buffer matches the copied characters, which the markup must not change. */
	@Test
	fun an_own_copy_whose_markup_reparses_to_other_text_pastes_its_own_text() {
		pasteboard.writeStyled("a  b", "<p>a  b</p>", copyId = 7L)

		assertEquals("a  b", pasteboard.readStyled(styles).text?.text)
	}

	@Test
	fun several_items_paste_one_per_line() {
		offerItems("first", "second")

		assertEquals("first\nsecond", pasteboard.readStyled(styles).text?.text)
		assertEquals("first\nsecond", pasteboard.readPlain())
	}

	@Test
	fun an_empty_pasteboard_pastes_nothing() {
		pasteboard.setItems(emptyList<Any?>())

		assertNull(pasteboard.readStyled(styles).text)
		assertNull(pasteboard.readPlain())
	}
}
