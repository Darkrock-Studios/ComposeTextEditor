package e2e

import androidx.compose.ui.input.key.Key
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.contextmenu.ContextMenuStrings
import com.darkrockstudios.texteditor.spellcheck.diagnostics.LineDiagnostic
import com.darkrockstudios.texteditor.spellcheck.diagnostics.TextDiagnosticsChecker
import utils.CountingSpellChecker
import utils.spellCheckUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

/** Shift+F10 and the Menu key open the spell check menu for the flag at the caret. */
class SpellCheckKeyboardMenuE2eTest {

	private val checker = CountingSpellChecker(correctWords = setOf("fine"), suggestions = listOf("zorb"))

	@Test
	fun `shift+f10 in a flagged word offers its suggestions and Ignore`() {
		spellCheckUiTest(spellChecker = checker, initialText = "fine zorp fine") {
			state.textState.cursor.updatePosition(CharLineOffset(0, 7))
			press(Key.F10, shift = true)

			awaitMenuItem("zorb")
			assertTrue(hasMenuItem("Ignore"))

			clickMenuItem("zorb")
			assertEquals("fine zorb fine", state.textState.getAllText().text)
		}
	}

	@Test
	fun `the menu key in a flagged word offers Ignore`() {
		spellCheckUiTest(spellChecker = checker, initialText = "fine zorp fine") {
			state.textState.cursor.updatePosition(CharLineOffset(0, 9))
			press(Key(AwtKeyEvent.VK_CONTEXT_MENU))

			awaitMenuItem("Ignore")
			clickMenuItem("Ignore")
			assertEquals(0, spellCheckSpanCount)
		}
	}

	@Test
	fun `shift+f10 elsewhere opens the standard menu`() {
		spellCheckUiTest(spellChecker = checker, initialText = "fine zorp fine") {
			state.textState.cursor.updatePosition(CharLineOffset(0, 2))
			press(Key.F10, shift = true)

			assertTrue(hasMenuItem(ContextMenuStrings.Default.selectAll))
			assertFalse(hasMenuItem("Ignore"))
		}
	}

	@Test
	fun `shift+f10 on a diagnostic offers its fixes`() {
		val repeats = TextDiagnosticsChecker { lines ->
			lines.map { line ->
				Regex("the the").findAll(line).map { LineDiagnostic(it.range.first, it.range.last + 1, "Repeated word", listOf("the")) }.toList()
			}
		}
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("over", "the", "hill")),
			initialText = "over the the hill",
			diagnosticsChecker = repeats,
		) {
			state.textState.cursor.updatePosition(CharLineOffset(0, 10))
			press(Key.F10, shift = true)

			awaitMenuItem("Repeated word")
			assertTrue(hasMenuItem("the"))
		}
	}

	@Test
	fun `a disabled editor opens the standard menu from the keyboard`() {
		spellCheckUiTest(spellChecker = checker, initialText = "fine zorp fine", enabled = false) {
			// A disabled editor takes focus only when asked.
			clickAtCharacter(7)
			state.textState.cursor.updatePosition(CharLineOffset(0, 7))
			press(Key.F10, shift = true)

			assertTrue(hasMenuItem(ContextMenuStrings.Default.selectAll))
			assertFalse(hasMenuItem("Ignore"))
		}
	}

	@Test
	fun `a read-only editor offers Ignore from the keyboard but no corrections`() {
		spellCheckUiTest(spellChecker = checker, initialText = "fine zorp fine", readOnly = true) {
			state.textState.cursor.updatePosition(CharLineOffset(0, 7))
			press(Key.F10, shift = true)

			awaitMenuItem("Ignore")
			assertFalse(hasMenuItem("zorb"))
		}
	}

	@Test
	fun `a selection within the flagged word keeps its suggestions`() {
		spellCheckUiTest(spellChecker = checker, initialText = "fine zorp fine") {
			state.textState.selector.updateSelection(CharLineOffset(0, 5), CharLineOffset(0, 9))
			state.textState.cursor.updatePosition(CharLineOffset(0, 9))
			press(Key.F10, shift = true)

			awaitMenuItem("zorb")
		}
	}

	@Test
	fun `a selection past the flagged word opens the standard menu`() {
		spellCheckUiTest(spellChecker = checker, initialText = "fine zorp fine") {
			state.textState.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 9))
			state.textState.cursor.updatePosition(CharLineOffset(0, 9))
			press(Key.F10, shift = true)

			assertTrue(hasMenuItem(ContextMenuStrings.Default.copy))
			assertFalse(hasMenuItem("Ignore"))
		}
	}
}
