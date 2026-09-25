package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.spellcheck.diagnostics.DiagnosticFix
import com.darkrockstudios.texteditor.spellcheck.diagnostics.DiagnosticSeverity
import com.darkrockstudios.texteditor.spellcheck.diagnostics.DiagnosticStyle
import com.darkrockstudios.texteditor.spellcheck.diagnostics.LineDiagnostic
import com.darkrockstudios.texteditor.spellcheck.diagnostics.TextDiagnosticsChecker
import com.darkrockstudios.texteditor.spellcheck.diagnostics.TextDiagnosticsState
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextDiagnosticsStateTest {
	private lateinit var textState: TextEditorState
	private val checked = mutableListOf<List<String>>()

	// Flags every "the the", suggesting "the".
	private val checker = TextDiagnosticsChecker { lines ->
		checked += lines
		lines.map { line ->
			Regex("the the").findAll(line).map { LineDiagnostic(it.range.first, it.range.last + 1, "Repeated word", listOf("the")) }.toList()
		}
	}

	@Before
	fun setup() {
		val measurer = mockk<TextMeasurer>()
		every { measurer.measure(text = any<AnnotatedString>(), constraints = any()) } answers {
			mockk<TextLayoutResult>().apply {
				every { getLineStart(any()) } returns 0
				every { getLineEnd(any()) } returns 5
				every { lineCount } returns 1
				every { multiParagraph } answers {
					mockk<MultiParagraph>().apply {
						every { lineCount } returns 1
						every { getLineHeight(any()) } returns 10f
					}
				}
			}
		}
		textState = TextEditorState(scope = TestScope(), measurer = measurer, initialText = null)
	}

	private fun diagnostics(with: TextDiagnosticsChecker = checker) =
		TextDiagnosticsState(textState, with, Color.Blue, Color.Yellow, scanContext = EmptyCoroutineContext)

	private val textChecked = mutableListOf<List<String>>()

	// Line by line, the "the the" checker; across lines, a suggestion on each word some earlier line has.
	private val echoChecker = object : TextDiagnosticsChecker {
		override suspend fun check(lines: List<String>) = checker.check(lines)

		override suspend fun checkText(lines: List<String>): List<List<LineDiagnostic>> {
			textChecked += lines
			val seen = mutableSetOf<String>()
			return lines.map { line ->
				val words = Regex("\\w+").findAll(line).toList()
				words.filter { it.value in seen }
					.map { LineDiagnostic(it.range.first, it.range.last + 1, "Echo", severity = DiagnosticSeverity.Suggestion) }
					.also { seen += words.map { it.value } }
			}
		}
	}

	private fun spans(): List<RichSpan> =
		textState.richSpanManager.getAllRichSpans().filter { it.style is DiagnosticStyle }.sortedBy { it.range.start }

	private fun range(line: Int, start: Int, end: Int) = TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, end))

	@Test
	fun `each diagnostic is underlined, carrying its message and fixes`() = runTest {
		textState.setText("Over the the hill.\nFine.\nAnd the the dale.")
		diagnostics().refresh()

		assertEquals(listOf(range(0, 5, 12), range(2, 4, 11)), spans().map { it.range })
		assertEquals(DiagnosticStyle("Repeated word", listOf(DiagnosticFix("the")), Color.Blue), spans().first().style)
	}

	@Test
	fun `only lines not checked before go to the checker`() = runTest {
		textState.setText("Over the the hill.\nFine.\n\nFine.")
		val state = diagnostics()
		state.refresh()
		textState.replace(range(1, 0, 4), "Good")
		state.refresh()

		assertEquals(listOf(listOf("Over the the hill.", "Fine."), listOf("Good.")), checked)
		assertEquals(listOf(range(0, 5, 12)), spans().map { it.range })
	}

	@Test
	fun `an edit removes the underlines it touches, and the rest move with the text`() = runTest {
		textState.setText("the the and the the")
		val state = diagnostics()
		state.refresh()
		val at = CharLineOffset(0, 2)
		textState.replace(TextEditorRange(at, at), "n")
		state.invalidate(TextEditOperation.Insert(at, AnnotatedString("n"), at, at))

		assertEquals(listOf(range(0, 13, 20)), spans().map { it.range })

		state.refresh()
		assertEquals(listOf(range(0, 13, 20)), spans().map { it.range })
	}

	@Test
	fun `a fix replaces the underlined text`() = runTest {
		textState.setText("Over the the hill.")
		val state = diagnostics()
		state.refresh()
		state.applyFix(spans().single(), "the")

		assertEquals("Over the hill.", textState.getAllText().text)
		assertTrue(spans().isEmpty())
	}

	@Test
	fun `without a checker nothing is underlined`() = runTest {
		textState.setText("Over the the hill.")
		val state = diagnostics()
		state.refresh()
		state.setChecker(null)

		assertTrue(spans().isEmpty())
	}

	@Test
	fun `a new color redraws every underline`() = runTest {
		textState.setText("Over the the hill.\nAnd the the dale.")
		val state = diagnostics()
		state.refresh()
		state.setColor(Color.Green)

		assertEquals(listOf(Color.Green, Color.Green), spans().map { (it.style as DiagnosticStyle).color })
	}

	@Test
	fun `the whole text goes to checkText on every refresh, and its issues join each line's`() = runTest {
		textState.setText("Over the the hill.\n\nThe hill.")
		val state = diagnostics(echoChecker)
		state.refresh()
		state.refresh()

		assertEquals(List(2) { listOf("Over the the hill.", "", "The hill.") }, textChecked)
		assertEquals(listOf(range(0, 5, 12), range(2, 4, 8)), spans().map { it.range })
		assertEquals(
			DiagnosticStyle("Echo", emptyList(), Color.Yellow, DiagnosticSeverity.Suggestion),
			spans().last().style,
		)
	}

	@Test
	fun `an edit to one line can clear another line's text-wide issue`() = runTest {
		textState.setText("Over the hill.\nThe hill.")
		val state = diagnostics(echoChecker)
		state.refresh()
		textState.replace(range(0, 9, 13), "dale")
		state.refresh()

		assertTrue(spans().isEmpty())
	}

	@Test
	fun `text-wide issues found while the text changed wait for the next refresh`() = runTest {
		textState.setText("A hill.\nThe hill.")
		var editing = true
		val state = diagnostics(object : TextDiagnosticsChecker {
			override suspend fun check(lines: List<String>) = echoChecker.check(lines)

			override suspend fun checkText(lines: List<String>): List<List<LineDiagnostic>> {
				if (editing) {
					editing = false
					textState.replace(range(0, 0, 0), "New line\n")
				}
				return echoChecker.checkText(lines)
			}
		})
		state.refresh()
		assertTrue(spans().isEmpty())

		state.refresh()
		assertEquals(listOf(range(2, 4, 8)), spans().map { it.range })
	}

	@Test
	fun `each severity has its own color`() = runTest {
		textState.setText("Over the the hill.\nThe hill.")
		val state = diagnostics(echoChecker)
		state.refresh()
		state.setSuggestionColor(Color.Green)

		assertEquals(listOf(Color.Blue, Color.Green), spans().map { (it.style as DiagnosticStyle).color })
	}
}
