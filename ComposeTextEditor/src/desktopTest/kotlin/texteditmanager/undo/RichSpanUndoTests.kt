package texteditmanager.undo

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RichSpanUndoTests {
	private lateinit var scope: TestScope
	private lateinit var state: TextEditorState

	@BeforeTest
	fun setup() {
		scope = TestScope()
		state = TextEditorState(
			scope = scope.backgroundScope,
			measurer = mockk(relaxed = true)
		)
	}

	private fun hasOrderedListSpan() = state.richSpanManager.getAllRichSpans()
		.any { it.style == OrderedListSpanStyle }

	@Test
	fun `test undo add rich span removes only the span`() {
		state.setText("Hello World")

		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 11)
		)
		state.addRichSpan(range, OrderedListSpanStyle)

		assertTrue(hasOrderedListSpan())

		state.undo()

		assertTrue(state.richSpanManager.getAllRichSpans().isEmpty())
		assertEquals("Hello World", state.textLines[0].text)
	}

	@Test
	fun `test undo add rich span preserves prior edit`() {
		state.setText("Hello World")

		// Prior edit: insert a character
		state.editManager.applyOperation(
			TextEditOperation.Insert(
				position = CharLineOffset(0, 11),
				text = AnnotatedString("!"),
				cursorBefore = CharLineOffset(0, 11),
				cursorAfter = CharLineOffset(0, 12)
			)
		)
		assertEquals("Hello World!", state.textLines[0].text)

		// Now apply a rich span (the list)
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 12)
		)
		state.addRichSpan(range, OrderedListSpanStyle)
		assertTrue(hasOrderedListSpan())

		// Undo should remove only the list, leaving the typed character intact
		state.undo()
		assertTrue(state.richSpanManager.getAllRichSpans().isEmpty())
		assertEquals("Hello World!", state.textLines[0].text)
	}

	@Test
	fun `test undo remove rich span restores it`() {
		state.setText("Hello World")

		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 11)
		)
		state.addRichSpan(range, OrderedListSpanStyle)
		assertTrue(hasOrderedListSpan())

		state.removeRichSpan(range.start, range.end, OrderedListSpanStyle)
		assertTrue(state.richSpanManager.getAllRichSpans().isEmpty())

		state.undo()
		assertTrue(hasOrderedListSpan())
	}

	@Test
	fun `undo skips a decoration span and reverts the real edit`() {
		state.setText("Hello World")

		// Real edit: type a character
		state.editManager.applyOperation(
			TextEditOperation.Insert(
				position = CharLineOffset(0, 11),
				text = AnnotatedString("!"),
				cursorBefore = CharLineOffset(0, 11),
				cursorAfter = CharLineOffset(0, 12)
			)
		)
		assertEquals("Hello World!", state.textLines[0].text)

		// A spell-check underline applied afterward is a decoration, not an edit
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 12)
		)
		state.addRichSpan(range, SpellCheckStyle)

		// Undo must revert the typed character, not merely peel the decoration
		state.undo()
		assertEquals("Hello World", state.textLines[0].text)
	}

	@Test
	fun `decoration span does not clobber a pending redo`() {
		state.setText("Hello World")

		// Real edit, then undo: a redo is now pending
		state.editManager.applyOperation(
			TextEditOperation.Insert(
				position = CharLineOffset(0, 11),
				text = AnnotatedString("!"),
				cursorBefore = CharLineOffset(0, 11),
				cursorAfter = CharLineOffset(0, 12)
			)
		)
		state.undo()
		assertEquals("Hello World", state.textLines[0].text)

		// A spell-check decoration must not wipe out the pending redo
		val range = TextEditorRange(
			start = CharLineOffset(0, 0),
			end = CharLineOffset(0, 11)
		)
		state.addRichSpan(range, SpellCheckStyle)

		// Redo still restores the typed character
		state.redo()
		assertEquals("Hello World!", state.textLines[0].text)
	}
}
