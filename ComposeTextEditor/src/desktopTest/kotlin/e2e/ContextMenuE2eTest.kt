package e2e

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.contextmenu.ContextMenuStrings
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

/** The built-in context menu: its items, how they show, the keys that open it, and where. */
@OptIn(ExperimentalTestApi::class)
class ContextMenuE2eTest {

	private fun EditorUiTestScope.item(label: String): SemanticsNodeInteraction = test.onNodeWithText(label)

	@Test
	fun `items that cannot act now show disabled rather than hidden`() = editorUiTest(
		initialText = AnnotatedString("hello"),
	) {
		rightClickAtCharacter(2)
		item("Undo").assertIsNotEnabled()
		item("Redo").assertIsNotEnabled()
		item("Cut").assertIsNotEnabled()
		item("Copy").assertIsNotEnabled()
		item("Paste").assertIsEnabled()
		item("Paste as Plain Text").assertIsEnabled()
		item("Select All").assertIsEnabled()
	}

	@Test
	fun `undo and redo from the menu`() = editorUiTest {
		typeText("abc")
		rightClickAtCharacter(1)
		item("Undo").performClick()
		waitForIdle()
		assertEquals("", text)

		rightClickAtCharacter(0)
		item("Redo").assertIsEnabled().performClick()
		waitForIdle()
		assertEquals("abc", text)
	}

	@Test
	fun `a read-only editor offers only copy and select all`() {
		val menu = TextEditorContextMenuState()
		editorUiTest(initialText = AnnotatedString("hello"), enabled = false, contextMenuState = menu) {
			rightClickAtCharacter(2)
			assertTrue(menu.isVisible)
			for (label in listOf("Undo", "Redo", "Cut", "Paste", "Paste as Plain Text")) {
				item(label).assertDoesNotExist()
			}
			item("Copy").assertIsNotEnabled()
			item("Select All").assertIsEnabled()
		}
	}

	@Test
	fun `paste as plain text from the menu drops the copied styling`() = editorUiTest(
		initialText = buildAnnotatedString {
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bold") }
			append(" plain")
		},
	) {
		dragSelect(0, 4)
		press(Key.C, ctrl = true)
		press(Key.MoveEnd, ctrl = true)
		rightClickAtCharacter(text.length)
		item("Paste as Plain Text").performClick()
		waitForIdle()
		assertEquals("bold plainbold", text)
		assertTrue(stylesAt(11).none { it.fontWeight == FontWeight.Bold })
	}

	@Test
	fun `the menu opens where the pointer is, past the start padding`() {
		val menu = TextEditorContextMenuState()
		editorUiTest(
			initialText = AnnotatedString("hello world"),
			contentPadding = PaddingValues(start = 40.dp, top = 10.dp),
			contextMenuState = menu,
		) {
			val click = positionOfCharacter(6)
			rightClickAtCharacter(6)
			val at = assertNotNull(menu.menuPosition.value)
			assertTrue(abs(at.x - click.x) < 1f, "menu x ${at.x}, click x ${click.x}")
			assertTrue(abs(at.y - click.y) < 1f, "menu y ${at.y}, click y ${click.y}")
		}
	}

	/** The host's modifier sits inside the menu's provider, so its padding shifts the text too. */
	@Test
	fun `the menu opens at the pointer past the host's own padding`() = runComposeUiTest {
		lateinit var state: TextEditorState
		val menu = TextEditorContextMenuState()
		setContent {
			state = rememberTextEditorState(initialText = AnnotatedString("hello world"))
			BasicTextEditor(
				state = state,
				modifier = Modifier.padding(start = 30.dp, top = 12.dp).size(300.dp, 200.dp).testTag("editor"),
				contextMenuState = menu,
			)
		}
		waitForIdle()
		val click = Offset(40f, 5f)
		onNodeWithTag("editor").performMouseInput { rightClick(click) }
		waitForIdle()
		val padding = with(density) { Offset(30.dp.toPx(), 12.dp.toPx()) }
		val at = assertNotNull(menu.menuPosition.value)
		assertTrue((at - (click + padding)).getDistance() < 1f, "menu at $at, click at ${click + padding}")
	}

	@Test
	fun `shift+f10 opens the menu under the caret`() {
		val menu = TextEditorContextMenuState()
		editorUiTest(
			initialText = AnnotatedString("hello world"),
			contentPadding = PaddingValues(start = 40.dp),
			contextMenuState = menu,
		) {
			clickAtCharacter(6)
			press(Key.F10, shift = true)
			val at = assertNotNull(menu.menuPosition.value, "Shift+F10 opened the menu")
			val caret = positionOfCharacter(6)
			assertTrue(abs(at.x - caret.x) < 1f, "menu x ${at.x}, caret x ${caret.x}")
			assertTrue(at.y > caret.y, "below the caret's middle")
			assertEquals(6, cursorIndex, "the caret stays")
		}
	}

	@Test
	fun `the menu key opens the menu`() {
		val menu = TextEditorContextMenuState()
		editorUiTest(initialText = AnnotatedString("hello"), contextMenuState = menu) {
			press(Key(AwtKeyEvent.VK_CONTEXT_MENU))
			assertTrue(menu.isVisible)
		}
	}

	@Test
	fun `macos has no menu chord`() {
		val menu = TextEditorContextMenuState()
		editorUiTest(initialText = AnnotatedString("hello"), contextMenuState = menu, keyBindings = MacKeyBindings) {
			press(Key.F10, shift = true)
			assertFalse(menu.isVisible)
		}
	}

	@Test
	fun `the menu opened from the keyboard is driven by the keyboard`() {
		val menu = TextEditorContextMenuState()
		editorUiTest(contextMenuState = menu) {
			typeText("abc")
			press(Key.F10, shift = true)
			assertTrue(menu.isVisible)
			item("Undo").performKeyInput {
				pressKey(Key.DirectionDown)
				pressKey(Key.Enter)
			}
			waitForIdle()
			assertFalse(menu.isVisible)
			assertEquals("", text, "Down reached Undo, the first item, and Enter ran it")
		}
	}

	@Test
	fun `the labels are the host's`() = editorUiTest(
		initialText = AnnotatedString("hello"),
		contextMenuStrings = German,
	) {
		rightClickAtCharacter(2)
		item("Rückgängig").assertIsNotEnabled()
		item("Einfügen").assertIsEnabled()
		item("Alles auswählen").assertIsEnabled()
	}

	@Test
	fun `a read-only view takes the host's labels too`() = runComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = AnnotatedString("hello world"))
			RichTextView(
				state = state,
				modifier = Modifier.width(400.dp).padding(8.dp).testTag("view"),
				isSelectable = true,
				contextMenuStrings = German,
			)
		}
		waitForIdle()
		onNodeWithTag("view").performMouseInput { rightClick(center) }
		waitForIdle()
		onNodeWithText("Kopieren").assertIsNotEnabled()
		onNodeWithText("Alles auswählen").assertIsEnabled()
	}

	private companion object {
		val German = ContextMenuStrings(
			cut = "Ausschneiden",
			copy = "Kopieren",
			paste = "Einfügen",
			selectAll = "Alles auswählen",
			undo = "Rückgängig",
			redo = "Wiederholen",
			pasteAsPlainText = "Als reinen Text einfügen",
		)
	}
}
