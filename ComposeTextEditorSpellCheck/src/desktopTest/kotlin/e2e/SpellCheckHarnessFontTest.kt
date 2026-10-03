package e2e

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import utils.CountingSpellChecker
import utils.TestFontFamily
import utils.measureLineWidth
import utils.spellCheckUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The spell check harness lays text out in the bundled test font whatever the host's fonts are (0.9). */
class SpellCheckHarnessFontTest {
	@Test
	fun `the harness lays text out in the test font`() {
		val sample = "The quick brown fox jumps over the lazy dog"
		spellCheckUiTest(spellChecker = CountingSpellChecker(), initialText = sample, width = 800.dp) {
			assertSame(TestFontFamily, state.textState.lineOffsets.first().textLayoutResult.layoutInput.style.fontFamily)
			val end = state.textState.getPositionForOffset(CharLineOffset(0, sample.length)).position.x
			assertEquals(measureLineWidth(sample, TextStyle(fontFamily = TestFontFamily)), end, 0.5f)
		}
	}
}
