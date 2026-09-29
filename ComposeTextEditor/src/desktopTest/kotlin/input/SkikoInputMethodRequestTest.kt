@file:OptIn(ExperimentalComposeUiApi::class)

package input

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteAllCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextInCodePointsCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.MoveCursorCommand
import androidx.compose.ui.text.input.SetComposingRegionCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.cursor.CursorMetrics
import com.darkrockstudios.texteditor.input.SkikoTextEditorInputMethodRequest
import com.darkrockstudios.texteditor.input.imeSetComposingRegion
import com.darkrockstudios.texteditor.input.startSkikoInputSession
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one input request desktop, iOS, and web share. Desktop and iOS drive it through
 * `editText`, web through `onEditCommand`; both must land in the shared IME logic, and
 * the state it exposes must be what the platforms' `snapshotFlow`s can observe.
 */
class SkikoInputMethodRequestTest {

	private lateinit var state: TextEditorState
	private lateinit var request: SkikoTextEditorInputMethodRequest

	@BeforeTest
	fun setup() {
		state = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString(""),
		)
		request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default)
	}

	private fun text() = state.getAllText().text

	private fun cursorCharIndex() = state.getCharacterIndex(state.cursorPosition)

	private fun moveCursorToCharIndex(index: Int) {
		state.cursor.updatePosition(state.getOffsetAtCharacter(index))
	}

	private fun typeViaCommit(s: String) = request.editText { commitText(s, 1) }

	// --- editText: the desktop and iOS route ---

	@Test
	fun `editText dead key composition composes then commits`() {
		request.editText { setComposingText("´", 1) }
		assertEquals("´", text())
		assertNotNull(state.composingRange)

		request.editText { commitText("é", 1) }

		assertEquals("é", text())
		assertNull(state.composingRange)
		assertEquals(1, cursorCharIndex())
	}

	@Test
	fun `editText deletes surrounding code points and sets selection`() {
		typeViaCommit("ab😀cd")
		moveCursorToCharIndex(4)

		request.editText { deleteSurroundingTextInCodePoints(1, 1) }
		assertEquals("abd", text())

		request.editText { setSelection(0, 2) }
		assertEquals(TextRange(0, 2), request.state.selection)

		request.editText { setComposingRegion(1, 3) }
		assertEquals(TextRange(1, 3), request.state.composition)

		request.editText { finishComposingText() }
		assertNull(request.state.composition)
	}

	// --- onEditCommand: the web route ---

	@Test
	fun `onEditCommand routes commit and composing commands`() {
		request.onEditCommand(listOf(SetComposingTextCommand("wor", 1)))
		assertEquals("wor", text())
		assertNotNull(state.composingRange)

		request.onEditCommand(listOf(CommitTextCommand("world", 1)))
		assertEquals("world", text())
		assertNull(state.composingRange)

		request.onEditCommand(listOf(SetComposingRegionCommand(0, 2)))
		assertEquals(TextRange(0, 2), request.state.composition)

		request.onEditCommand(listOf(FinishComposingTextCommand()))
		assertNull(request.state.composition)
	}

	/** The shape the web autocorrect path sends: select the old word, then commit the new one. */
	@Test
	fun `onEditCommand applies a batch in order`() {
		typeViaCommit("teh cat")

		request.onEditCommand(listOf(SetSelectionCommand(0, 3), CommitTextCommand("the", 1)))

		assertEquals("the cat", text())
		assertEquals(3, cursorCharIndex())
	}

	@Test
	fun `onEditCommand routes surrounding deletes`() {
		typeViaCommit("abcdef")
		moveCursorToCharIndex(3)

		request.onEditCommand(listOf(DeleteSurroundingTextCommand(1, 1)))
		assertEquals("abef", text())

		request.onEditCommand(listOf(DeleteSurroundingTextInCodePointsCommand(1, 0)))
		assertEquals("aef", text())
	}

	@Test
	fun `backspace removes a composition whole`() {
		typeViaCommit("ab")
		request.onEditCommand(listOf(SetComposingTextCommand("cd", 1)))

		request.onEditCommand(listOf(BackspaceCommand()))

		assertEquals("ab", text())
		assertNull(state.composingRange)
	}

	@Test
	fun `backspace removes a selection`() {
		typeViaCommit("abcd")
		request.onEditCommand(listOf(SetSelectionCommand(1, 3)))

		request.onEditCommand(listOf(BackspaceCommand()))

		assertEquals("ad", text())
		assertEquals(1, cursorCharIndex())
		// One operation, so one undo brings the selection's text back.
		state.undo()
		assertEquals("abcd", text())
	}

	@Test
	fun `backspace with a caret deletes one character`() {
		typeViaCommit("abc")

		request.onEditCommand(listOf(BackspaceCommand()))

		assertEquals("ab", text())
	}

	/** A composing range that outlived its document must not swallow the keystroke. */
	@Test
	fun `backspace with a stale composing range still deletes`() {
		typeViaCommit("abc")
		state.composingRange = TextEditorRange(CharLineOffset(4, 0), CharLineOffset(4, 2))

		request.onEditCommand(listOf(BackspaceCommand()))

		assertEquals("ab", text())
		assertNull(state.composingRange)
	}

	@Test
	fun `backspace with a caret deletes a whole surrogate pair`() {
		typeViaCommit("ab😀")

		request.onEditCommand(listOf(BackspaceCommand()))

		assertEquals("ab", text())
	}

	@Test
	fun `backspace with a caret deletes a zwj sequence whole and a combining mark alone`() {
		typeViaCommit("ab\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67e\u0301")

		request.onEditCommand(listOf(BackspaceCommand()))
		assertEquals("ab\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67e", text())

		request.onEditCommand(listOf(BackspaceCommand()))
		request.onEditCommand(listOf(BackspaceCommand()))
		assertEquals("ab", text())
	}

	/** The web backspace must reach edit behaviors the same way the hardware key does. */
	@Test
	fun `backspace at the start of a bullet demotes it`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		MarkdownExtension(state, MarkdownConfiguration.DEFAULT).importMarkdown("plain\n- item")
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default)
		state.cursor.updatePosition(CharLineOffset(1, 0))

		request.onEditCommand(listOf(BackspaceCommand()))

		assertEquals(listOf("plain", "item"), state.textLines.map { it.text })
		assertTrue(state.richSpanManager.getAllRichSpans().none { it.style === BulletListSpanStyle })
	}

	@Test
	fun `move cursor and delete all`() {
		typeViaCommit("abcd")
		request.onEditCommand(listOf(SetSelectionCommand(1, 3)))

		// Compose collapses to the selection start, then steps: 1 - 2 clamps to 0.
		request.onEditCommand(listOf(MoveCursorCommand(-2)))
		assertNull(state.selector.selection)
		assertEquals(0, cursorCharIndex())

		request.onEditCommand(listOf(MoveCursorCommand(3)))
		assertEquals(3, cursorCharIndex())

		request.onEditCommand(listOf(DeleteAllCommand()))
		assertEquals("", text())
		assertEquals(0, cursorCharIndex())
	}

	// --- state out ---

	@Test
	fun `state and value mirror the editor`() {
		typeViaCommit("hello world")
		state.imeSetComposingRegion(6, 11)
		request.editText { setSelection(0, 5) }

		assertEquals(11, request.state.length)
		assertEquals('w', request.state[6])
		assertEquals("lo w", request.state.subSequence(3, 7).toString())
		assertEquals("hello world", request.state.text)
		assertEquals(TextRange(0, 5), request.state.selection)
		assertEquals(TextRange(6, 11), request.state.composition)
		assertEquals(TextFieldValue("hello world", TextRange(0, 5)), request.value())
	}

	@Test
	fun `geometry is null until the editor is attached`() {
		assertNull(request.focusedRectInRoot())
		assertNull(request.textFieldRectInRoot())
		assertNull(request.textClippingRectInRoot())
		assertNull(request.unclippedTextOffsetInRoot())
	}

	@Test
	fun `geometry maps the caret and the editor bounds into root coordinates`() {
		val coords = mockk<LayoutCoordinates>()
		every { coords.isAttached } returns true
		every { coords.localToRoot(Offset.Zero) } returns Offset(10f, 20f)
		every { coords.size } returns IntSize(300, 200)
		state.canvasLayoutCoordinates = coords
		state.lastCursorMetrics = CursorMetrics(position = Offset(5f, 40f), height = 16f)

		assertEquals(Rect(15f, 60f, 15f, 76f), request.focusedRectInRoot())
		assertEquals(Rect(10f, 20f, 310f, 220f), request.textFieldRectInRoot())
		assertEquals(Rect(10f, 20f, 310f, 220f), request.textClippingRectInRoot())
		assertEquals(Offset(10f, 20f), request.unclippedTextOffsetInRoot())
	}

	/**
	 * Web and iOS watch `value()` / `state.text` through `snapshotFlow`. The document is
	 * not snapshot state, so the session must signal edits that move nothing observable.
	 */
	@Test
	fun `a session makes an edit without a caret move visible to a snapshot observer`() = runTest {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString("abc"))
		val captured = CompletableDeferred<PlatformTextInputMethodRequest>()
		val session = object : PlatformTextInputSession {
			override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
				captured.complete(request)
				awaitCancellation()
			}
		}
		val sessionJob = launch { state.startSkikoInputSession(session, ImeOptions.Default) }
		val request = captured.await()

		val seen = mutableListOf<String>()
		val observer = launch { snapshotFlow { request.value().text }.collect { seen += it } }
		Snapshot.sendApplyNotifications()
		testScheduler.runCurrent()
		assertEquals(listOf("abc"), seen)

		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.deleteAtCursor()
		testScheduler.runCurrent()
		Snapshot.sendApplyNotifications()
		testScheduler.runCurrent()

		assertEquals(listOf("abc", "bc"), seen)
		observer.cancel()
		sessionJob.cancel()
	}

	// --- the typed-text hook ---

	@Test
	fun `a commit through editText reaches the text input behavior`() {
		val offered = mutableListOf<String>()
		state.editBehaviors += object : EditBehavior {
			override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
				offered += text
				return false
			}
		}

		request.editText { setComposingText("a", 1) }
		request.editText { commitText("ab", 1) }

		assertEquals(listOf("ab"), offered, "only the commit is offered")
		assertEquals("ab", text())
	}

	@Test
	fun `a commit through onEditCommand reaches the text input behavior`() {
		val offered = mutableListOf<String>()
		state.editBehaviors += object : EditBehavior {
			override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
				offered += text
				return false
			}
		}

		request.onEditCommand(listOf(CommitTextCommand("x", 1)))

		assertEquals(listOf("x"), offered)
	}
}
