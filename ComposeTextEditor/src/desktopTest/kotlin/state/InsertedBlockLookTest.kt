package state

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.input.EditorActionContext
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.insertTypedString
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import utils.InMemoryClipboard
import utils.blockLines
import utils.editorUiTest
import utils.setBlockLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Styled text put in outside paste and drop takes the look of the line it lands on, as
 * pasted text does (6.38): a heading's look it carries stays off a plain line, and a link's
 * look stays off text no link holds (6.45).
 */
@OptIn(ExperimentalTestApi::class)
class InsertedBlockLookTest {

	private val styles = RichTextStyles.DEFAULT
	private val heading = styles.header2Style
	private val titled = buildAnnotatedString { withStyle(heading) { append("Title") } }
	private val linkLooking = buildAnnotatedString { withStyle(styles.linkStyle) { append("site") } }

	private fun TestScope.editor(blockLines: String): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines(blockLines)
		return state
	}

	private fun TextEditorState.carries(line: Int, style: SpanStyle) = textLines[line].spanStyles.any { it.item == style }

	@Test
	fun `text typed with a heading's look into a plain line is body text`() = runTest {
		val state = editor("hello")
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.insertTypedString(titled)

		assertEquals("helloTitle", state.blockLines())
		assertFalse(state.carries(0, heading))
	}

	@Test
	fun `text typed with a heading's look into that heading keeps it`() = runTest {
		val state = editor("## Head")
		state.cursor.updatePosition(CharLineOffset(0, 4))

		state.insertTypedString(titled)

		assertEquals("## HeadTitle", state.blockLines())
		val line = state.textLines[0]
		assertTrue(line.text.indices.all { i -> line.spanStyles.any { it.item == heading && it.start <= i && i < it.end } })
	}

	@Test
	fun `text typed with a heading's look takes the line's look in one undo step`() = runTest {
		val state = editor("hello")
		state.cursor.updatePosition(CharLineOffset(0, 2))

		state.insertTypedString(titled)
		assertEquals("heTitlello", state.blockLines())
		assertFalse(state.carries(0, heading))

		state.undo()
		assertEquals("hello", state.blockLines())
		state.redo()
		assertEquals("heTitlello", state.blockLines())
		assertFalse(state.carries(0, heading))
	}

	/** A host's monospace text is a fence's look too; text matching its neighbours keeps it. */
	@Test
	fun `text typed in the look of the text beside it keeps it`() = runTest {
		val monospace = SpanStyle(fontFamily = FontFamily.Monospace)
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = buildAnnotatedString { withStyle(monospace) { append("code") } },
		)
		state.cursor.updatePosition(CharLineOffset(0, 4))

		state.insertTypedString(buildAnnotatedString { withStyle(monospace) { append("more") } })

		assertEquals(listOf(AnnotatedString.Range(monospace, 0, 8)), state.textLines[0].spanStyles)
	}

	@Test
	fun `text a host inserts itself keeps its styles`() = runTest {
		val state = editor("hello")
		state.cursor.updatePosition(CharLineOffset(0, 2))

		state.insertStringAtCursor(titled)

		assertTrue(state.carries(0, heading))
	}

	@Test
	fun `text typed with a link's look and no link loses the look`() = runTest {
		val state = editor("hello")
		state.cursor.updatePosition(CharLineOffset(0, 5))

		state.insertTypedString(linkLooking)

		assertEquals("hellosite", state.blockLines())
		assertFalse(state.carries(0, styles.linkStyle))
	}

	@Test
	fun `a yanked link's look goes with its destination`() = runTest {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = buildAnnotatedString {
				append("go ")
				append(linkLooking)
				append("\nhello")
			},
		)
		val context = EditorActionContext(state, InMemoryClipboard(), this)
		state.cursor.updatePosition(CharLineOffset(0, 3))
		state.actions[Action.DeleteToLineEnd]!!.perform(context)
		state.cursor.updatePosition(CharLineOffset(1, 5))

		state.actions[Action.Yank]!!.perform(context)

		assertEquals("go \nhellosite", state.getAllText().text)
		assertFalse(state.carries(1, styles.linkStyle))
		state.undo()
		assertEquals("go \nhello", state.getAllText().text)
	}

	@Test
	fun `text an assistive service sets takes the line's look`() = editorUiTest(initialText = AnnotatedString("hello")) {
		val appended = buildAnnotatedString {
			append("hello")
			withStyle(heading) { append("Title") }
			withStyle(styles.linkStyle) { append("site") }
		}

		test.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
			.performSemanticsAction(SemanticsActions.SetText) { it(appended) }

		assertEquals("helloTitlesite", text)
		assertFalse(state.carries(0, heading))
		assertFalse(state.carries(0, styles.linkStyle))
	}

	@Test
	fun `text an assistive service sets over a word takes the line's look`() = editorUiTest(initialText = AnnotatedString("hello")) {
		val changed = buildAnnotatedString {
			append("h")
			withStyle(heading) { append("X") }
			append("llo")
		}

		test.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
			.performSemanticsAction(SemanticsActions.SetText) { it(changed) }

		assertEquals("hXllo", text)
		assertFalse(state.carries(0, heading))
	}

	@Test
	fun `lines typed at a heading's end leave its look on the heading`() = runTest {
		val state = editor("## Head")
		state.cursor.updatePosition(CharLineOffset(0, 4))

		state.insertTypedString(buildAnnotatedString { withStyle(heading) { append("Title\nbody") } })

		assertEquals("## HeadTitle\nbody", state.blockLines())
		assertTrue(state.carries(0, heading))
		assertFalse(state.carries(1, heading))
	}

	@Test
	fun `text an assistive service inserts takes the line's look`() = editorUiTest(initialText = AnnotatedString("hello")) {
		state.cursor.updatePosition(CharLineOffset(0, 5))
		val inserted = buildAnnotatedString {
			withStyle(heading) { append("Title") }
			withStyle(styles.linkStyle) { append("site") }
		}

		test.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
			.performSemanticsAction(SemanticsActions.InsertTextAtCursor) { it(inserted) }

		assertEquals("helloTitlesite", text)
		assertFalse(state.carries(0, heading))
		assertFalse(state.carries(0, styles.linkStyle))
	}
}
