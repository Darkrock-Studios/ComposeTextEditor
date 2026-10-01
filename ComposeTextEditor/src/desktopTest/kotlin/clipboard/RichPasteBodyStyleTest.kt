package clipboard

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.html.withHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import utils.editorUiTest
import utils.pasteHtml

/**
 * Styled text pasted from markup takes the styles that size text where it lands
 * beneath its own spans, so it renders at the size of the text around it (6.18).
 * Its own styles still win.
 */
class RichPasteBodyStyleTest {

	private val body = RichTextStyles.DEFAULT.defaultTextStyle

	@Test
	fun `bold markup pasted into body text keeps the body size`() = editorUiTest {
		state.withHtml().importHtml("<p>hello</p>")
		press(Key.MoveEnd, ctrl = true)
		pasteHtml("<b>bold</b> plain")
		assertEquals("hellobold plain", text)
		val bold = text.indexOf("bold")
		assertTrue(stylesAt(bold).any { it.fontWeight == FontWeight.Bold })
		assertTrue(stylesAt(bold).contains(body), "got ${stylesAt(bold)}")
		assertTrue(stylesAt(text.indexOf("plain")).contains(body))
	}

	@Test
	fun `a pasted heading keeps its own size`() = editorUiTest {
		state.withHtml().importHtml("<p>hello</p>")
		press(Key.MoveEnd, ctrl = true)
		press(Key.Enter)
		pasteHtml("<h2>Title</h2>")
		val title = text.indexOf("Title")
		val size = state.getAllText().spanStyles
			.filter { title >= it.start && title < it.end }
			.fold(androidx.compose.ui.text.SpanStyle()) { acc, range -> acc.merge(range.item) }
			.fontSize
		assertEquals(RichTextStyles.DEFAULT.header2Style.fontSize, size)
	}

	@Test
	fun `bold markup pasted into a heading takes the heading's size`() = editorUiTest {
		state.withHtml().importHtml("<h2>Title</h2>")
		press(Key.MoveEnd, ctrl = true)
		pasteHtml("<b>x</b>")
		assertEquals("Titlex", text)
		assertEquals(RichTextStyles.DEFAULT.header2Style.fontSize, fontSizeAt(text.indexOf("x")))
	}

	/** The size the character at [index] renders at: its spans merged in order. */
	private fun utils.EditorUiTestScope.fontSizeAt(index: Int) = state.getAllText().spanStyles
		.filter { index >= it.start && index < it.end }
		.fold(androidx.compose.ui.text.SpanStyle()) { acc, range -> acc.merge(range.item) }
		.fontSize

	@Test
	fun `html import puts the body style under its text`() = editorUiTest {
		state.withHtml().importHtml("<p>plain <b>bold</b></p>")
		assertTrue(stylesAt(0).contains(body), "got ${stylesAt(0)}")
		assertTrue(stylesAt(text.indexOf("bold")).contains(body))
	}

	@Test
	fun `bold markup pasted into text a host sized takes that size`() = editorUiTest(
		initialText = androidx.compose.ui.text.buildAnnotatedString {
			append("sized")
			addStyle(androidx.compose.ui.text.SpanStyle(fontSize = 24.sp), 0, length)
		},
	) {
		press(Key.MoveEnd, ctrl = true)
		pasteHtml("<b>x</b>")
		assertEquals(24.sp, fontSizeAt(text.indexOf("x")))
	}

	@Test
	fun `an editor without a markdown configuration adds nothing`() = editorUiTest {
		pasteHtml("<b>bold</b>")
		assertFalse(stylesAt(0).contains(body), "got ${stylesAt(0)}")
	}
}
