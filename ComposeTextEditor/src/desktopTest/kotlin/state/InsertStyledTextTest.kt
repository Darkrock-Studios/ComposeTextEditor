package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
}
