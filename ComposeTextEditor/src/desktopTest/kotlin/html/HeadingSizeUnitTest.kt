package html

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.html.toHtml
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A heading is matched by its configured size, unit included: 2.em is not 2.sp. */
class HeadingSizeUnitTest {

	private val styles = RichTextStyles(header1Style = SpanStyle(fontSize = 2.em, fontWeight = FontWeight.Bold))

	@Test
	fun `a bold run at the heading's em size is written as the heading`() {
		val text = buildAnnotatedString { withStyle(styles.header1Style) { append("Title") } }

		assertTrue(text.toHtml(styles).contains("<h1>"))
	}

	@Test
	fun `a bold run whose sp size shares the number is not a heading`() {
		val text = buildAnnotatedString {
			withStyle(SpanStyle(fontSize = 2.sp, fontWeight = FontWeight.Bold)) { append("tiny") }
		}

		val html = text.toHtml(styles)
		assertFalse(html.contains("<h1>"))
		assertTrue(html.contains("<strong>"))
	}
}
