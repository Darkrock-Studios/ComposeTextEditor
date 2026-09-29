package selection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LocalNativeTextToolbar
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.RecordingTextToolbar
import utils.editorUiTest
import utils.positionOfCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a finger reaches Cut, Copy, Paste and Select all: through the platform's text
 * toolbar where there is one (Android, iOS), and through the editor's context menu where
 * there is not (desktop, web). The toolbar here is a recording stand-in for the platform's.
 */
@OptIn(ExperimentalTestApi::class)
class TouchToolbarTest {

	private val document = AnnotatedString("alpha beta gamma delta")

	@Test
	fun `a long press shows the toolbar with every item`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			longPressAtCharacter(8)

			val menu = assertNotNull(toolbar.menu, "expected the toolbar after the long press")
			assertNotNull(menu.onCut)
			assertNotNull(menu.onCopy)
			assertNotNull(menu.onPaste)
			assertNotNull(menu.onSelectAll)
		}
	}

	/** The toolbar comes once the finger lifts, so it does not sit under a drag. */
	@Test
	fun `the toolbar waits for the finger to lift`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			longPressAt(positionOfCharacter(8))
			assertNull(toolbar.menu, "still held")

			touch { up() }

			assertNotNull(toolbar.menu)
		}
	}

	@Test
	fun `the toolbar rect covers the selection`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			longPressAtCharacter(8)

			val rect = assertNotNull(toolbar.menu).rect
			assertTrue(rect.contains(positionOfCharacter(6)), "start of 'beta' in $rect")
			assertTrue(rect.contains(positionOfCharacter(9)), "end of 'beta' in $rect")
			assertFalse(rect.contains(positionOfCharacter(2)), "'alpha' outside $rect")
		}
	}

	/** Paste has to be reachable at a bare caret: no word means no Cut or Copy. */
	@Test
	fun `a long press on empty space offers paste and select all`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = AnnotatedString("alpha\n\nbeta"), textToolbar = toolbar) {
			longPressAtCharacter(6)

			assertNull(state.selector.selection)
			val menu = assertNotNull(toolbar.menu)
			assertNull(menu.onCut)
			assertNull(menu.onCopy)
			assertNotNull(menu.onPaste)
			assertNotNull(menu.onSelectAll)
		}
	}

	@Test
	fun `a long press in an empty editor offers paste`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(textToolbar = toolbar) {
			longPressAt(Offset(10f, 10f))
			touch { up() }

			val menu = assertNotNull(toolbar.menu)
			assertNotNull(menu.onPaste)
			assertNull(menu.onCopy)
		}
	}

	@Test
	fun `a double tap shows the toolbar`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			doubleTapAtCharacter(8)

			assertEquals("beta", selectedText)
			assertNotNull(toolbar.menu)
		}
	}

	@Test
	fun `tapping the caret handle offers paste and select all`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			tapAtCharacter(8)
			assertNull(toolbar.menu, "a plain tap shows no toolbar")

			tapAt(caretHandleCenter())

			val menu = assertNotNull(toolbar.menu)
			assertNull(menu.onCut)
			assertNotNull(menu.onPaste)
			assertNotNull(menu.onSelectAll)
			assertEquals(8, cursorIndex)
		}
	}

	@Test
	fun `tapping the caret handle again hides the toolbar`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			tapAtCharacter(8)
			tapAt(caretHandleCenter())
			assertNotNull(toolbar.menu)

			tapAt(caretHandleCenter())

			assertNull(toolbar.menu)
			assertTrue(state.selector.isCaretHandleVisible, "the handle stays")
		}
	}

	@Test
	fun `dragging the caret handle shows no toolbar`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			tapAtCharacter(8)

			dragCaretHandle(toChar = 17)

			assertNull(toolbar.menu)
		}
	}

	@Test
	fun `a handle drag hides the toolbar and shows it again on release`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			longPressAtCharacter(8)
			val shown = toolbar.showCount
			val grab = handleCenter(isStart = false)
			val delta = positionOfCharacter(16) - positionOfCharacter(10)

			touch {
				down(grab)
				moveTo(grab + delta)
			}
			assertNull(toolbar.menu, "hidden while the handle is dragged")

			touch { up() }

			assertEquals("beta gamma", selectedText)
			assertNotNull(toolbar.menu)
			assertEquals(shown + 1, toolbar.showCount)
		}
	}

	@Test
	fun `typing hides the toolbar`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			longPressAtCharacter(8)

			typeText("x")

			assertNull(toolbar.menu)
		}
	}

	@Test
	fun `a tap elsewhere hides the toolbar`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			longPressAtCharacter(8)

			tapAtCharacter(18)

			assertNull(toolbar.menu)
		}
	}

	/**
	 * The editor scrolls on its own to keep the caret in view, still settling when the
	 * finger lifts, so a scroll cannot dismiss the toolbar; it moves with the text instead.
	 */
	@Test
	fun `scrolling moves the toolbar with the text`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(
			initialText = AnnotatedString((0 until 200).joinToString("\n") { "line $it" }),
			textToolbar = toolbar,
		) {
			state.scrollState.scrollTo(400)
			waitForIdle()
			// A row well inside the viewport, so a small scroll cannot clamp the rect.
			val row = state.scrollManager.firstVisibleOffset.line + 6
			longPressAtCharacter(state.getCharacterIndex(CharLineOffset(row, 2)))
			val before = assertNotNull(toolbar.menu).rect

			state.scrollState.scrollTo(state.scrollState.value + 40)
			waitForIdle()

			val after = assertNotNull(toolbar.menu, "still up after the scroll").rect
			assertEquals(before.top - 40f, after.top, 0.5f)
		}
	}

	/** Far ends of a selection are off screen; the platform places the toolbar by visible rows. */
	@Test
	fun `the toolbar rect stays inside the viewport`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(
			initialText = AnnotatedString((0 until 200).joinToString("\n") { "line $it" }),
			textToolbar = toolbar,
		) {
			longPressAtCharacter(2)

			test.runOnIdle { toolbar.click(assertNotNull(toolbar.menu).onSelectAll) }
			waitForIdle()

			val rect = assertNotNull(toolbar.menu).rect
			assertTrue(rect.top >= 0f && rect.bottom <= state.viewportSize.height, "$rect")
			assertTrue(rect.bottom > rect.top, "$rect")
		}
	}

	/** Android's action mode finishes itself after the click, so the toolbar has to come back. */
	@Test
	fun `the toolbar's select all selects everything with handles`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			longPressAtCharacter(8)

			test.runOnIdle { toolbar.click(assertNotNull(toolbar.menu).onSelectAll) }
			waitForIdle()

			assertEquals(document.text, selectedText)
			assertTrue(state.selector.isTouchSelection, "handles on the selection")
			val menu = assertNotNull(toolbar.menu, "the toolbar stays for the new selection")
			assertNotNull(menu.onCopy)
		}
	}

	@Test
	fun `the toolbar's paste inserts the clipboard at the caret`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = AnnotatedString("alpha\n\nbeta"), textToolbar = toolbar) {
			setPlainClipboardText("pasted")
			longPressAtCharacter(6)

			test.runOnIdle { toolbar.click(assertNotNull(toolbar.menu).onPaste) }
			waitForIdle()

			assertEquals("alpha\npasted\nbeta", text)
			assertNull(toolbar.menu, "gone once used")
		}
	}

	@Test
	fun `the toolbar's copy copies the selection`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			longPressAtCharacter(8)

			test.runOnIdle { toolbar.click(assertNotNull(toolbar.menu).onCopy) }
			waitForIdle()
			press(Key.MoveEnd)
			press(Key.V, ctrl = true)

			assertEquals("alpha beta gamma deltabeta", text)
		}
	}

	@Test
	fun `a mouse press hides the toolbar`() {
		val toolbar = RecordingTextToolbar()
		editorUiTest(initialText = document, textToolbar = toolbar) {
			longPressAtCharacter(8)

			clickAtCharacter(18)

			assertNull(toolbar.menu)
		}
	}

	@Test
	fun `a long press on the selection shows the toolbar on lift rather than the context menu`() {
		val toolbar = RecordingTextToolbar()
		val menuState = TextEditorContextMenuState()
		editorUiTest(initialText = document, textToolbar = toolbar, contextMenuState = menuState) {
			longPressAtCharacter(8)
			toolbar.hide()

			longPressAt(positionOfCharacter(7))
			assertNull(toolbar.menu, "still held")
			touch { up() }

			assertEquals("beta", selectedText)
			assertNotNull(toolbar.menu)
			assertFalse(menuState.isVisible)
		}
	}

	/** A selectable view sits anywhere on screen; the platform needs the rect in root coordinates. */
	@Test
	fun `a rich text view places the toolbar in root coordinates`() = runComposeUiTest {
		val toolbar = RecordingTextToolbar()
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = document)
			CompositionLocalProvider(
				LocalTextToolbar provides toolbar,
				LocalNativeTextToolbar provides true,
			) {
				Column {
					Spacer(Modifier.height(100.dp))
					RichTextView(
						state = state,
						modifier = Modifier.width(400.dp).testTag("view"),
						isSelectable = true,
					)
				}
			}
		}
		waitForIdle()

		onNodeWithTag("view").performTouchInput { down(state.positionOfCharacter(8)) }
		mainClock.advanceTimeBy(800)
		waitForIdle()
		onNodeWithTag("view").performTouchInput { up() }
		waitForIdle()

		assertEquals("beta", state.selector.getSelectedText().text)
		val menu = toolbar.menu
		assertTrue(menu != null && menu.onCopy != null && menu.onCut == null, "copy only in a read-only view")
		assertTrue(menu.rect.top >= 100f, "below the spacer: ${menu.rect}")
	}

	/** Without a platform toolbar the context menu stands in, so Paste is still reachable. */
	@Test
	fun `without a platform toolbar a long press in an empty editor opens the context menu`() {
		val menuState = TextEditorContextMenuState()
		editorUiTest(contextMenuState = menuState) {
			longPressAt(Offset(10f, 10f))
			touch { up() }

			assertTrue(menuState.isVisible)
		}
	}

	@Test
	fun `without a platform toolbar tapping the caret handle opens the context menu`() {
		val menuState = TextEditorContextMenuState()
		editorUiTest(initialText = document, contextMenuState = menuState) {
			tapAtCharacter(8)
			assertFalse(menuState.isVisible)

			tapAt(caretHandleCenter())

			assertTrue(menuState.isVisible)
			assertEquals(8, cursorIndex)
		}
	}

	/**
	 * The context menu is modal and would eat the next tap, so a selection does not open
	 * it; a long press on the selection still does.
	 */
	@Test
	fun `without a platform toolbar a long press selection opens no menu until pressed again`() {
		val menuState = TextEditorContextMenuState()
		editorUiTest(initialText = document, contextMenuState = menuState) {
			longPressAtCharacter(8)
			assertEquals("beta", selectedText)
			assertFalse(menuState.isVisible)

			longPressAtCharacter(7)

			assertTrue(menuState.isVisible)
			assertEquals("beta", selectedText)
		}
	}

	@Test
	fun `without a platform toolbar dropping a handle opens no menu`() {
		val menuState = TextEditorContextMenuState()
		editorUiTest(initialText = document, contextMenuState = menuState) {
			longPressAtCharacter(8)

			dragHandle(isStart = false, toChar = 16)

			assertEquals("beta gamma", selectedText)
			assertFalse(menuState.isVisible)
		}
	}
}
