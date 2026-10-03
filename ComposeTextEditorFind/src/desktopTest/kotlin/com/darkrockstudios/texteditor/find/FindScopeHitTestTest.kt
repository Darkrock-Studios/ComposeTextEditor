package com.darkrockstudios.texteditor.find

import androidx.compose.ui.test.ExperimentalTestApi
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FindScopeHitTestTest {

	@Test
	fun `find in selection's scope leaves clicks to the line markers inside it`() = findUiTest("one\ntwo\nthree") {
		val quoted = TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 3))
		test.runOnUiThread { textState.updateRichSpans(emptyList(), listOf(RichSpan(quoted, BlockquoteSpanStyle))) }
		selectInEditor(CharLineOffset(0, 0), CharLineOffset(2, 5))
		test.runOnUiThread { findState.toggleInSelection(true) }
		test.waitForIdle()
		assertTrue(findState.inSelection, "precondition: the scope is on")

		assertEquals(BlockquoteSpanStyle, textState.findSpanAtPosition(CharLineOffset(1, 1))?.style)
	}

	@Test
	fun `find highlights leave clicks to the line markers under them`() = findUiTest("one\ntwo cat\nthree") {
		val quoted = TextEditorRange(CharLineOffset(1, 0), CharLineOffset(1, 7))
		test.runOnUiThread { textState.updateRichSpans(emptyList(), listOf(RichSpan(quoted, BlockquoteSpanStyle))) }
		typeQuery("cat")
		assertEquals(1, findState.matchCount, "precondition: the match is highlighted")

		assertEquals(BlockquoteSpanStyle, textState.findSpanAtPosition(CharLineOffset(1, 5))?.style)
	}
}
