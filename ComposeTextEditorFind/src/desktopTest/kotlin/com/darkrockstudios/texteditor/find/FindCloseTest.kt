package com.darkrockstudios.texteditor.find

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FindCloseTest {

	@Test
	fun `Escape closes the bar and clears the highlights`() = findUiTest("cat and cat") {
		typeQuery("cat")
		assertEquals(2, highlightCount)

		press(Key.Escape)

		assertFalse(barVisible)
		assertEquals(0, highlightCount)
		assertEquals("cat", selectedText, "the last match stays selected")
	}

	@Test
	fun `Ctrl+F in the bar closes it and clears the highlights`() = findUiTest("cat and cat") {
		typeQuery("cat")

		press(Key.F, ctrl = true)

		assertFalse(barVisible)
		assertEquals(0, highlightCount)
	}

	@Test
	fun `the host hiding the bar clears the highlights`() = findUiTest("cat and cat") {
		typeQuery("cat")
		assertEquals(2, highlightCount)

		hideBarFromHost()

		assertEquals(0, highlightCount)
	}

	/** A host can hide the bar without removing it, or reopen it before its exit animation ends. */
	@Test
	fun `closing while the bar stays composed empties the search field`() = findUiTest("cat and cat") {
		typeQuery("cat")
		assertTrue(test.onAllNodes(hasSetTextAction() and hasText("cat")).fetchSemanticsNodes().isNotEmpty())

		test.runOnUiThread { findState.close() }
		test.waitForIdle()

		assertTrue(test.onAllNodes(hasSetTextAction() and hasText("cat")).fetchSemanticsNodes().isEmpty())
	}

	@Test
	fun `Ctrl+F in the editor opens the bar`() = findUiTest("cat", barInitiallyVisible = false) {
		press(Key.F, ctrl = true)

		assertTrue(barVisible)
	}

	@Test
	fun `AltGr+F does not open the bar`() = findUiTest("cat", barInitiallyVisible = false) {
		press(Key.F, ctrl = true, alt = true)

		assertFalse(barVisible)
	}

	@Test
	fun `closing ends the session so later edits add no highlights`() = findUiTest("cat and cat") {
		typeQuery("cat")
		press(Key.Escape)

		test.runOnUiThread { textState.setText("cat cat cat") }
		test.mainClock.advanceTimeBy(500)
		test.waitForIdle()

		assertEquals(0, highlightCount)
		assertEquals(0, findState.matchCount)
	}
}
