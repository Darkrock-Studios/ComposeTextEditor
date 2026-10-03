package e2e

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.spellcheck.diagnostics.LineDiagnostic
import com.darkrockstudios.texteditor.spellcheck.diagnostics.TextDiagnosticsChecker
import utils.CountingSpellChecker
import utils.SpellCheckUiTestScope
import utils.spellCheckUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the spell check menu opens. A right-click's menu is anchored at the pointer by the
 * editor, so it is the reference a tap's menu is held to.
 */
@OptIn(ExperimentalTestApi::class)
class SpellCheckMenuPlacementE2eTest {

	private val padding = PaddingValues(start = 40.dp, top = 12.dp)

	/**
	 * The left edge of the menu item [label] after [open] in a padded editor over [text]. Only
	 * the left: the menu does not fit below the word in the test window, so the top is pushed up.
	 * The editor's own tests cover the vertical half of the conversion.
	 */
	private fun menuLeft(
		text: String,
		label: String,
		diagnosticsChecker: TextDiagnosticsChecker? = null,
		open: SpellCheckUiTestScope.() -> Unit,
	): Float {
		var left = Float.NaN
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("fine", "over", "the", "hill")),
			initialText = text,
			contentPadding = padding,
			diagnosticsChecker = diagnosticsChecker,
		) {
			open()
			awaitMenuItem(label)
			left = menuItemLeft(label)
		}
		return left
	}

	@Test
	fun `a tap on a flagged word opens the menu at the word, past the start padding`() {
		val rightClick = menuLeft("fine zorp", "Ignore") { rightClickAtCharacter(6) }
		val tap = menuLeft("fine zorp", "Ignore") { tapAtCharacter(6) }

		assertEquals(rightClick, tap, absoluteTolerance = 1f)
	}

	@Test
	fun `a tap on a diagnostic opens the menu at it, past the start padding`() {
		val repeats = TextDiagnosticsChecker { lines ->
			lines.map { line ->
				Regex("the the").findAll(line).map { LineDiagnostic(it.range.first, it.range.last + 1, "Repeated word", listOf("the")) }.toList()
			}
		}
		val text = "over the the hill"
		val rightClick = menuLeft(text, "Repeated word", repeats) { rightClickAtCharacter(10) }
		val tap = menuLeft(text, "Repeated word", repeats) { tapAtCharacter(10) }

		assertEquals(rightClick, tap, absoluteTolerance = 1f)
	}
}
