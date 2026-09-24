package com.darkrockstudios.texteditor.find

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class FindStateTest {

	private fun editor(text: String) = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	)

	private fun range(start: Int, end: Int) =
		TextEditorRange(CharLineOffset(0, start), CharLineOffset(0, end))

	/** A replacement emits no edit, so only the document generation can say the matches are stale. */
	@Test
	fun `replacing the whole document re-runs the search`() = runTest {
		val textState = editor("cat and cat")
		val find = FindState(textState, backgroundScope)
		runCurrent()
		find.search("cat")
		assertEquals(2, find.matchCount)

		textState.setText("one cat")
		runCurrent()

		assertEquals(listOf(range(4, 7)), find.matches)
		assertEquals(0, find.currentMatchIndex)
	}

	@Test
	fun `a replacement that leaves no match clears the results`() = runTest {
		val textState = editor("cat")
		val find = FindState(textState, backgroundScope)
		runCurrent()
		find.search("cat")

		textState.setText("dog")
		runCurrent()

		assertEquals(0, find.matchCount)
		assertEquals(-1, find.currentMatchIndex)
	}
}
