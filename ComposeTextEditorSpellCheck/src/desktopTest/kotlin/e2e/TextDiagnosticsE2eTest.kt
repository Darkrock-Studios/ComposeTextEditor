package e2e

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.spellcheck.diagnostics.LineDiagnostic
import com.darkrockstudios.texteditor.spellcheck.diagnostics.TextDiagnosticsChecker
import utils.CountingSpellChecker
import utils.spellCheckUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Diagnostics through the real editor: loading, typing, the menu, and spell check alongside. */
class TextDiagnosticsE2eTest {

	private val checked = mutableListOf<String>()

	// Flags every "the the", offering "the".
	private val repeats = TextDiagnosticsChecker { lines ->
		checked += lines
		lines.map { line ->
			Regex("the the").findAll(line).map { LineDiagnostic(it.range.first, it.range.last + 1, "Repeated word", listOf("the")) }.toList()
		}
	}

	private fun range(line: Int, start: Int, end: Int) = TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, end))

	@Test
	fun `a loaded document is underlined, and a typed issue after the edit settles`() {
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("over", "the", "hill", "fine")),
			initialText = "over the the hill\nfine",
			diagnosticsChecker = repeats,
		) {
			assertEquals(listOf(range(0, 5, 12)), diagnosticSpans.map { it.range })

			state.textState.cursor.updatePosition(CharLineOffset(1, 4))
			typeText(" the the")
			letSpellCheckSettle()

			assertEquals(listOf(range(0, 5, 12), range(1, 5, 12)), diagnosticSpans.map { it.range })
			assertEquals(1, checked.count { it == "over the the hill" }, "an unchanged line is checked once")
			assertEquals("fine the the", checked.last())
		}
	}

	@Test
	fun `a right-click offers the message and fixes, and a fix replaces the text`() {
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("over", "the", "hill")),
			initialText = "over the the hill",
			diagnosticsChecker = repeats,
		) {
			rightClickAtCharacter(7)
			awaitMenuItem("Repeated word")
			clickMenuItem("the")

			assertEquals("over the hill", state.textState.getAllText().text)
			letSpellCheckSettle()
			assertEquals(emptyList(), diagnosticSpans)
		}
	}

	@Test
	fun `spelling and diagnostics underline side by side`() {
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("over", "the", "hill"), suggestions = listOf("hill")),
			initialText = "over the the hilll",
			diagnosticsChecker = repeats,
		) {
			assertEquals(1, spellCheckSpanCount)
			assertEquals(1, diagnosticSpans.size)

			rightClickAtCharacter(15)
			awaitMenuItem("hill")
		}
	}
}
