package e2e

import androidx.compose.ui.input.key.Key
import com.darkrockstudios.texteditor.behaviors.SmartPunctuation
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [SmartPunctuation] driven by real desktop key events, undone with Ctrl+Z. */
class SmartPunctuationE2eTest {

	@Test
	fun `typed keys are substituted`() = editorUiTest {
		state.editBehaviors += SmartPunctuation()

		typeText("\"Hi,\" she said -- it's 'fine' ... 1990 - 2000")

		assertEquals("\u201CHi,\u201D she said \u2014 it\u2019s \u2018fine\u2019 \u2026 1990 \u2013 2000", text)
	}

	@Test
	fun `Ctrl+Z gives back the typed hyphens`() = editorUiTest {
		state.editBehaviors += SmartPunctuation()

		typeText("a--")
		assertEquals("a\u2014", text)

		press(Key.Z, ctrl = true)
		assertEquals("a--", text)
		assertEquals(3, cursorIndex)

		press(Key.Z, ctrl = true)
		assertEquals("", text)
	}

	@Test
	fun `typing on after an undone substitution keeps the hyphens`() = editorUiTest {
		state.editBehaviors += SmartPunctuation()

		typeText("a--")
		press(Key.Z, ctrl = true)
		typeText("b")

		assertEquals("a--b", text)
	}
}
