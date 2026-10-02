package clipboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.EditorLineLimits
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.clipboard.AwtPrimarySelection
import com.darkrockstudios.texteditor.clipboard.LocalPrimarySelection
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.EditorUiTestScope
import utils.editorUiTest
import utils.linesWith
import utils.positionOfCharacter
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The X11 primary selection: selecting offers it, a middle click pastes it. */
@OptIn(ExperimentalTestApi::class)
class PrimarySelectionTest {

	private val document = AnnotatedString("alpha beta gamma delta")

	/** An AWT clipboard standing in for X11's primary selection, counting the offers. */
	private class Primary : Clipboard("primary") {
		var offers = 0

		override fun setContents(contents: Transferable, owner: ClipboardOwner?) {
			offers++
			super.setContents(contents, owner)
		}

		fun text(): String? = getContents(null)?.getTransferData(DataFlavor.stringFlavor) as String?

		/** Another application selects [value]. */
		fun selectElsewhere(value: String) {
			val selection = StringSelection(value)
			setContents(selection, selection)
			// The previous owner hears of the loss on the event thread.
			SwingUtilities.invokeAndWait {}
		}
	}

	/** Middle-clicks [charIndex] and waits for the paste, which reads the selection off the UI thread. */
	private fun EditorUiTestScope.middleClickAndWait(charIndex: Int, landed: EditorUiTestScope.() -> Boolean) {
		middleClickAtCharacter(charIndex)
		test.waitUntil(timeoutMillis = 5_000) { landed() }
		// The paste lands from a coroutine after an off-thread read; let it settle before reading.
		waitForIdle()
	}

	@Test
	fun `a drag offers the selected text once, read when asked`() {
		val primary = Primary()
		editorUiTest(initialText = document, primarySelection = AwtPrimarySelection(primary)) {
			// A frame between the steps, so each step's selection is seen.
			mouse {
				moveTo(positionOfCharacter(6))
				press()
			}
			for (char in 8..16 step 2) mouse(fresh = false) { moveTo(positionOfCharacter(char)) }
			mouse(fresh = false) { release() }

			assertEquals(1, primary.offers)
			assertEquals("beta gamma", primary.text())
		}
	}

	@Test
	fun `a keyboard selection is offered`() {
		val primary = Primary()
		editorUiTest(initialText = document, primarySelection = AwtPrimarySelection(primary)) {
			clickAtCharacter(0)
			press(Key.DirectionRight, ctrl = true, shift = true)

			assertEquals("alpha", primary.text())
		}
	}

	@Test
	fun `clearing the selection keeps the last one on offer`() {
		val primary = Primary()
		editorUiTest(initialText = document, primarySelection = AwtPrimarySelection(primary)) {
			dragSelect(fromChar = 6, toChar = 10)
			clickAtCharacter(0)
			typeText("x")

			assertEquals("beta", primary.text())
		}
	}

	@Test
	fun `a selection after another application took the primary offers again`() {
		val primary = Primary()
		editorUiTest(initialText = document, primarySelection = AwtPrimarySelection(primary)) {
			dragSelect(fromChar = 6, toChar = 10)
			primary.selectElsewhere("elsewhere")
			waitForIdle()

			dragSelect(fromChar = 0, toChar = 5)

			assertEquals("alpha", primary.text())
		}
	}

	/** Showing a state again, as a tab switch does, is not the user selecting. */
	@Test
	fun `an editor shown again with its selection does not take the primary back`() = runComposeUiTest {
		val primary = Primary()
		val selection = AwtPrimarySelection(primary)
		val state = TextEditorState(document)
		var shown by mutableStateOf(true)
		setContent {
			CompositionLocalProvider(LocalPrimarySelection provides selection) {
				if (shown) BasicTextEditor(state = state, modifier = Modifier.width(400.dp))
			}
		}
		waitForIdle()
		state.selector.updateSelection(state.getOffsetAtCharacter(6), state.getOffsetAtCharacter(10))
		waitForIdle()
		assertEquals("beta", primary.text())

		shown = false
		waitForIdle()
		primary.selectElsewhere("elsewhere")
		shown = true
		waitForIdle()

		assertEquals("beta", state.selector.getSelectedText().text)
		assertEquals("elsewhere", primary.text())
	}

	@Test
	fun `leaving composition keeps the selected text on offer`() = runComposeUiTest {
		val primary = Primary()
		val selection = AwtPrimarySelection(primary)
		val state = TextEditorState(document)
		var shown by mutableStateOf(true)
		setContent {
			CompositionLocalProvider(LocalPrimarySelection provides selection) {
				if (shown) BasicTextEditor(state = state, modifier = Modifier.width(400.dp))
			}
		}
		waitForIdle()
		state.selector.updateSelection(state.getOffsetAtCharacter(6), state.getOffsetAtCharacter(10))
		waitForIdle()

		shown = false
		waitForIdle()

		assertEquals("beta", primary.text())
	}

	@Test
	fun `middle click pastes another application's selection at the pointer`() {
		val primary = Primary()
		editorUiTest(initialText = document, primarySelection = AwtPrimarySelection(primary)) {
			clickAtCharacter(0)
			primary.selectElsewhere("new ")

			middleClickAndWait(6) { text != document.text }

			assertEquals("alpha new beta gamma delta", text)
			assertEquals(10, cursorIndex)
			assertNull(state.selector.selection)

			press(Key.Z, ctrl = true)
			assertEquals("alpha beta gamma delta", text)
		}
	}

	@Test
	fun `middle click pastes the editor's own selection as a copy`() {
		val primary = Primary()
		editorUiTest(initialText = document, primarySelection = AwtPrimarySelection(primary)) {
			dragSelect(fromChar = 6, toChar = 11)

			middleClickAndWait(22) { text != document.text }

			assertEquals("alpha beta gamma deltabeta ", text)
			assertNull(state.selector.selection)
		}
	}

	@Test
	fun `middle-click paste lands as paste as plain text does, in a list`() {
		val bold = SpanStyle(fontWeight = FontWeight.Bold)
		val pasted = "two\nthree"
		fun landed(paste: EditorUiTestScope.() -> Unit): Triple<List<String>, List<Int>, List<SpanStyle>> {
			lateinit var result: Triple<List<String>, List<Int>, List<SpanStyle>>
			val primary = Primary()
			editorUiTest(
				initialText = buildAnnotatedString { withStyle(bold) { append("one") } },
				primarySelection = AwtPrimarySelection(primary),
			) {
				state.addRichSpan(0, 3, BulletListSpanStyle)
				waitForIdle()
				primary.selectElsewhere(pasted)
				setPlainClipboardText(pasted)
				paste()
				result = Triple(lines, state.linesWith(BulletListSpanStyle), stylesAt(5))
			}
			return result
		}

		val plainPaste = landed {
			clickAtCharacter(3)
			press(Key.V, ctrl = true, shift = true)
		}
		val middleClick = landed { middleClickAndWait(3) { lines.size == 2 } }

		assertEquals(listOf("onetwo", "three"), plainPaste.first)
		assertEquals(plainPaste, middleClick)
	}

	@Test
	fun `middle-click paste is offered to the behaviors`() {
		val primary = Primary()
		val pasted = mutableListOf<Pair<String, TextEditorRange>>()
		editorUiTest(initialText = document, primarySelection = AwtPrimarySelection(primary)) {
			state.editBehaviors += object : EditBehavior {
				override fun onPaste(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
					pasted += text to range
					return false
				}
			}
			primary.selectElsewhere("new ")

			middleClickAndWait(6) { pasted.isNotEmpty() }

			assertEquals(listOf("new " to TextEditorRange(state.getOffsetAtCharacter(6), state.getOffsetAtCharacter(10))), pasted)
		}
	}

	@Test
	fun `a read-only editor ignores the middle click`() {
		val primary = Primary()
		editorUiTest(initialText = document, readOnly = true, primarySelection = AwtPrimarySelection(primary)) {
			clickAtCharacter(2)
			primary.selectElsewhere("new ")

			middleClickAtCharacter(13)

			assertEquals("alpha beta gamma delta", text)
			assertEquals(2, cursorIndex)
		}
	}

	@Test
	fun `an empty primary selection only moves the caret`() {
		val primary = Primary()
		editorUiTest(initialText = document, primarySelection = AwtPrimarySelection(primary)) {
			clickAtCharacter(2)

			middleClickAtCharacter(13)

			assertEquals("alpha beta gamma delta", text)
			assertEquals(13, cursorIndex)
		}
	}

	/** A paste follows the line limit of the editor it lands in. */
	@Test
	fun `middle-click paste into a single-line editor sharing a state keeps to one line`() = runComposeUiTest {
		val primary = Primary()
		val selection = AwtPrimarySelection(primary)
		val state = TextEditorState(AnnotatedString("hello"))
		setContent {
			CompositionLocalProvider(LocalPrimarySelection provides selection) {
				Column {
					BasicTextEditor(
						state = state,
						modifier = Modifier.size(300.dp, 40.dp).testTag("single"),
						lineLimits = EditorLineLimits.SingleLine,
					)
					BasicTextEditor(
						state = state,
						modifier = Modifier.size(300.dp, 100.dp),
						autoFocus = true,
						lineLimits = EditorLineLimits.MultiLine(),
					)
				}
			}
		}
		waitForIdle()
		primary.selectElsewhere("one\ntwo ")

		onNodeWithTag("single").performMouseInput {
			moveTo(state.positionOfCharacter(0))
			press(MouseButton.Tertiary)
			release(MouseButton.Tertiary)
		}
		waitUntil(timeoutMillis = 5_000) { state.getAllText().text != "hello" }

		assertEquals("one two hello", state.getAllText().text)
	}

	@Test
	fun `a selectable view offers its selection`() = runComposeUiTest {
		val primary = Primary()
		val selection = AwtPrimarySelection(primary)
		lateinit var state: TextEditorState
		setContent {
			CompositionLocalProvider(LocalPrimarySelection provides selection) {
				state = rememberTextEditorState(initialText = document)
				RichTextView(state = state, modifier = Modifier.width(400.dp).testTag("view"), isSelectable = true)
			}
		}
		waitForIdle()

		onNodeWithTag("view").performMouseInput {
			moveTo(state.positionOfCharacter(6))
			press()
			moveTo(state.positionOfCharacter(10))
			release()
		}
		waitForIdle()

		assertEquals("beta", primary.text())
		assertEquals(1, primary.offers)
	}
}
