package e2e

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.RichSpanClick
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.SpanClickType
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.editorUiTest
import utils.positionOfCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When rich span clicks are reported, and the built-in link convention: Ctrl+click
 * (Cmd+click on macOS) opens a link in the editor, a plain click opens one in a
 * read-only view.
 */
@OptIn(ExperimentalTestApi::class)
class SpanClickE2eTest {

	private val document = AnnotatedString("see the docs here and more text")
	private val url = "https://example.com/docs"

	// "docs" is characters 8 until 12.
	private fun TextEditorState.addLink() = addRichSpan(8, 12, LinkSpanStyle(url))

	@Test
	fun `a click is reported on release, not on press`() {
		val clicks = mutableListOf<RichSpanClick>()
		editorUiTest(initialText = document, onRichSpanClickEvent = { clicks += it; true }) {
			state.addLink()

			mouse {
				moveTo(positionOfCharacter(9))
				press()
			}
			assertTrue(clicks.isEmpty(), "a press alone must not report: it may become a drag")

			mouse(fresh = false) { release() }
			assertEquals(listOf(SpanClickType.PRIMARY_CLICK), clicks.map { it.type })
		}
	}

	@Test
	fun `the legacy listener still hears the click`() {
		val types = mutableListOf<SpanClickType>()
		editorUiTest(initialText = document, onRichSpanClick = { _, type, _ -> types += type; true }) {
			state.addLink()

			clickAtCharacter(9)

			assertEquals(listOf(SpanClickType.PRIMARY_CLICK), types)
		}
	}

	@Test
	fun `a drag that starts on a span is not a click`() {
		val clicks = mutableListOf<RichSpanClick>()
		editorUiTest(initialText = document, onRichSpanClickEvent = { clicks += it; true }) {
			state.addLink()

			mouse {
				moveTo(positionOfCharacter(9))
				press()
				moveTo(positionOfCharacter(25))
				moveTo(positionOfCharacter(9))
				release()
			}

			assertTrue(clicks.isEmpty())
		}
	}

	@Test
	fun `pressing on one span and releasing on another reports nothing`() {
		val clicks = mutableListOf<RichSpanClick>()
		editorUiTest(initialText = document, onRichSpanClickEvent = { clicks += it; true }) {
			state.addRichSpan(0, 3, HighlightSpanStyle(androidx.compose.ui.graphics.Color.Yellow))
			state.addLink()

			mouse {
				moveTo(positionOfCharacter(1))
				press()
				moveTo(positionOfCharacter(9))
				release()
			}

			assertTrue(clicks.isEmpty())
		}
	}

	@Test
	fun `a double-click on a span reports one click`() {
		val clicks = mutableListOf<RichSpanClick>()
		editorUiTest(initialText = document, onRichSpanClickEvent = { clicks += it; true }) {
			state.addLink()

			doubleClickAtCharacter(9)

			assertEquals(1, clicks.size)
			assertEquals("docs", selectedText)
		}
	}

	@Test
	fun `the click carries the modifier keys`() {
		val clicks = mutableListOf<RichSpanClick>()
		editorUiTest(initialText = document, onRichSpanClickEvent = { clicks += it; true }) {
			state.addLink()

			clickAt(positionOfCharacter(9), ctrl = true)

			assertTrue(clicks.single().keyboardModifiers.isCtrlPressed)
		}
	}

	@Test
	fun `ctrl+click opens a link and a plain click does not`() {
		val opened = mutableListOf<String>()
		editorUiTest(initialText = document, onLinkClick = { opened += it }) {
			state.addLink()

			clickAtCharacter(9)
			assertTrue(opened.isEmpty(), "a plain click places the caret in an editor")
			assertEquals(9, cursorIndex)

			clickAt(positionOfCharacter(9), ctrl = true)
			assertEquals(listOf(url), opened)
		}
	}

	@Test
	fun `cmd+click opens a link with the macOS bindings, ctrl+click does not`() {
		val opened = mutableListOf<String>()
		editorUiTest(initialText = document, keyBindings = MacKeyBindings, onLinkClick = { opened += it }) {
			state.addLink()

			clickAt(positionOfCharacter(9), ctrl = true)
			assertTrue(opened.isEmpty())

			clickAt(positionOfCharacter(9), meta = true)
			assertEquals(listOf(url), opened)
		}
	}

	@Test
	fun `ctrl+click outside a link opens nothing`() {
		val opened = mutableListOf<String>()
		editorUiTest(initialText = document, onLinkClick = { opened += it }) {
			state.addLink()

			clickAt(positionOfCharacter(20), ctrl = true)

			assertTrue(opened.isEmpty())
		}
	}

	/** Touch has no modifier keys, so a tap in an editor only places the caret. */
	@Test
	fun `a tap does not open a link in an editor`() {
		val opened = mutableListOf<String>()
		editorUiTest(initialText = document, onLinkClick = { opened += it }) {
			state.addLink()

			tapAtCharacter(9)

			assertTrue(opened.isEmpty())
			assertEquals(9, cursorIndex)
		}
	}

	@Test
	fun `a plain click or tap opens a link in a read-only view`() = runComposeUiTest {
		val opened = mutableListOf<String>()
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = document)
			RichTextView(
				state = state,
				modifier = Modifier.width(400.dp).testTag("view"),
				isSelectable = true,
				onLinkClick = { opened += it },
			)
		}
		waitForIdle()
		state.addLink()
		waitForIdle()

		onNodeWithTag("view").performMouseInput { click(state.positionOfCharacter(9)) }
		waitForIdle()
		assertEquals(listOf(url), opened)

		onNodeWithTag("view").performTouchInput {
			down(state.positionOfCharacter(10))
			up()
		}
		waitForIdle()
		assertEquals(listOf(url, url), opened)
	}

	@Test
	fun `selecting across a link in a read-only view does not open it`() = runComposeUiTest {
		val opened = mutableListOf<String>()
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = document)
			RichTextView(
				state = state,
				modifier = Modifier.width(400.dp).testTag("view"),
				isSelectable = true,
				onLinkClick = { opened += it },
			)
		}
		waitForIdle()
		state.addLink()
		waitForIdle()

		onNodeWithTag("view").performMouseInput {
			moveTo(state.positionOfCharacter(9))
			press()
			moveTo(state.positionOfCharacter(25))
			release()
		}
		waitForIdle()

		assertTrue(opened.isEmpty())
		assertTrue(state.selector.hasSelection())
	}
}
