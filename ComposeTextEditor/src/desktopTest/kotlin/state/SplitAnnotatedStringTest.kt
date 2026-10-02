package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.annotatedstring.splitAnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals

/** Splitting on line breaks puts each span and paragraph style on exactly the characters it covered. */
class SplitAnnotatedStringTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)
	private val italic = SpanStyle(fontStyle = FontStyle.Italic)
	private val indent = ParagraphStyle(textIndent = TextIndent(firstLine = 8.sp))

	private fun AnnotatedString.spans() = spanStyles.map { Triple(it.item, it.start, it.end) }

	@Test
	fun `a span across line breaks lands on each line it covers`() {
		val text = AnnotatedString("ab\ncd\n\nef", spanStyles = listOf(AnnotatedString.Range(bold, 1, 8)))
		val lines = text.splitAnnotatedString()
		assertEquals(listOf("ab", "cd", "", "ef"), lines.map { it.text })
		assertEquals(listOf(Triple(bold, 1, 2)), lines[0].spans())
		assertEquals(listOf(Triple(bold, 0, 2)), lines[1].spans())
		assertEquals(emptyList(), lines[2].spans())
		assertEquals(listOf(Triple(bold, 0, 1)), lines[3].spans())
	}

	@Test
	fun `ranges starting on a line break or empty are placed or dropped as before`() {
		val text = AnnotatedString(
			"ab\ncd",
			spanStyles = listOf(
				AnnotatedString.Range(bold, 2, 4),
				AnnotatedString.Range(italic, 3, 3),
				AnnotatedString.Range(italic, 2, 3),
			),
		)
		val lines = text.splitAnnotatedString()
		assertEquals(emptyList(), lines[0].spans())
		assertEquals(listOf(Triple(bold, 0, 1)), lines[1].spans())
	}

	@Test
	fun `span order is kept on every line`() {
		val text = AnnotatedString(
			"abc\ndef",
			spanStyles = listOf(AnnotatedString.Range(italic, 0, 7), AnnotatedString.Range(bold, 1, 5)),
		)
		val lines = text.splitAnnotatedString()
		assertEquals(listOf(Triple(italic, 0, 3), Triple(bold, 1, 3)), lines[0].spans())
		assertEquals(listOf(Triple(italic, 0, 3), Triple(bold, 0, 1)), lines[1].spans())
	}

	@Test
	fun `a paragraph style covering several lines is on each of them`() {
		val text = AnnotatedString("ab\ncd", paragraphStyles = listOf(AnnotatedString.Range(indent, 0, 5)))
		val lines = text.splitAnnotatedString()
		assertEquals(listOf(indent), lines[0].paragraphStyles.map { it.item })
		assertEquals(0 to 2, lines[1].paragraphStyles.single().let { it.start to it.end })
	}

	@Test
	fun `empty text is one empty line`() {
		assertEquals(listOf(""), AnnotatedString("").splitAnnotatedString().map { it.text })
	}
}
