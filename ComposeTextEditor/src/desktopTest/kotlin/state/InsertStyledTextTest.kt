package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InsertStyledTextTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)
	private val italic = SpanStyle(fontStyle = FontStyle.Italic)

	private fun TestScope.createState(text: String): TextEditorState =
		TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(text),
		)

	private fun AnnotatedString.boldRanges(): List<IntRange> =
		spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
			.map { it.start until it.end }

	@Test
	fun `inserting styled text keeps its spans`() = runTest {
		val state = createState("")
		val styled = buildAnnotatedString {
			append("Hello ")
			pushStyle(bold)
			append("world")
			pop()
		}

		state.insertStringAtCursor(styled)

		val line = state.textLines[0]
		assertEquals("Hello world", line.text)
		assertTrue(
			line.boldRanges().any { 6 in it && 10 in it },
			"expected bold covering 'world', got ${line.spanStyles}",
		)
		assertTrue(
			line.boldRanges().none { 0 in it },
			"did not expect bold on 'Hello', got ${line.spanStyles}",
		)
	}

	@Test
	fun `inserting multi style text keeps every span`() = runTest {
		val state = createState("")
		val styled = buildAnnotatedString {
			pushStyle(bold)
			append("bold")
			pop()
			append(" ")
			pushStyle(italic)
			append("italic")
			pop()
		}

		state.insertStringAtCursor(styled)

		val line = state.textLines[0]
		assertEquals("bold italic", line.text)
		assertTrue(
			line.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 0 },
			"expected bold at 'bold', got ${line.spanStyles}",
		)
		assertTrue(
			line.spanStyles.any { it.item.fontStyle == FontStyle.Italic && it.start == 5 },
			"expected italic at 'italic', got ${line.spanStyles}",
		)
	}

	@Test
	fun `styled text does not inherit the cursor style`() = runTest {
		val state = createState("bold start")
		state.addStyleSpan(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 10)),
			bold,
		)
		state.cursor.updatePosition(CharLineOffset(0, 10))

		val styled = buildAnnotatedString {
			pushStyle(italic)
			append("italic")
			pop()
			append(" plain")
		}
		state.insertStringAtCursor(styled)

		val line = state.textLines[0]
		assertEquals("bold startitalic plain", line.text)

		val plainStart = line.text.indexOf(" plain")
		assertTrue(
			line.boldRanges().none { plainStart in it },
			"pasted unstyled text inherited the cursor's bold: ${line.spanStyles}",
		)
	}

	@Test
	fun `inserting plain text stays plain`() = runTest {
		val state = createState("")
		state.insertStringAtCursor(AnnotatedString("just text"))

		val line = state.textLines[0]
		assertEquals("just text", line.text)
		assertTrue(line.boldRanges().isEmpty(), "expected no styling, got ${line.spanStyles}")
	}

	@Test
	fun `pasted bold survives the caret sitting next to existing bold`() = runTest {
		val state = createState("X")
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 1)), bold)
		state.cursor.updatePosition(CharLineOffset(0, 1))

		val pasted = """<b style="font-weight:normal"><span style="font-weight:700">bold</span>plain</b>"""
			.toAnnotatedStringFromHtml()
		state.insertStringAtCursor(pasted)

		val line = state.textLines[0]
		assertEquals("Xboldplain", line.text)

		val boldAt = { i: Int ->
			line.spanStyles.filter { i >= it.start && i < it.end }
				.fold(SpanStyle()) { acc, r -> acc.merge(r.item) }
				.fontWeight == FontWeight.Bold
		}
		assertTrue(boldAt(line.text.indexOf("bold")), "pasted bold was lost: ${line.spanStyles}")
		assertTrue(!boldAt(line.text.indexOf("plain")), "unstyled run became bold: ${line.spanStyles}")
	}

	@Test
	fun `an inserted paragraph style does not split the line`() = runTest {
		val state = createState("line")
		state.cursor.updatePosition(CharLineOffset(0, 2))

		val pasted = buildAnnotatedString {
			pushStyle(ParagraphStyle(textIndent = TextIndent(firstLine = 12.sp)))
			append("xx")
			pop()
		}
		state.insertStringAtCursor(pasted)

		val line = state.textLines[0]
		assertEquals("lixxne", line.text)
		assertTrue(
			line.paragraphStyles.isEmpty(),
			"a pasted paragraph style nested inside the line: ${line.paragraphStyles}",
		)
	}
}
