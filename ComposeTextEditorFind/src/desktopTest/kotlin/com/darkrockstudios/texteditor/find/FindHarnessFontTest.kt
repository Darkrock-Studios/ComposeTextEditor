package com.darkrockstudios.texteditor.find

import androidx.compose.ui.text.TextStyle
import com.darkrockstudios.texteditor.CharLineOffset
import utils.TestFontFamily
import utils.measureLineWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The find harness lays the editor's text out in the bundled test font whatever the host's fonts are (0.9). */
class FindHarnessFontTest {
	@Test
	fun `the harness lays text out in the test font`() {
		val sample = "The quick brown fox jumps"
		findUiTest(initialText = sample) {
			assertSame(TestFontFamily, textState.lineOffsets.first().textLayoutResult.layoutInput.style.fontFamily)
			val end = textState.getPositionForOffset(CharLineOffset(0, sample.length)).position.x
			assertEquals(measureLineWidth(sample, TextStyle(fontFamily = TestFontFamily)), end, 0.5f)
		}
	}
}
