package e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the editor takes focus, and when it deliberately does not.
 *
 * Focus is what raises the soft keyboard on Android, so every case here is about
 * whether a keyboard slides up over the content. A finger has to lift near where
 * it landed for the gesture to count as a tap, and a tap focuses unless it left a
 * popup showing that the keyboard would cover. What any span click listener
 * returned never enters into it: hosts return `true` liberally just to observe
 * clicks, so the listener's answer cannot mean "do not focus".
 */
@OptIn(ExperimentalTestApi::class)
class TouchFocusE2eTest {

	private val document = AnnotatedString("hello world, this is the document text")

	@Test
	fun `a finger tap focuses the editor`() = editorUiTest(
		initialText = document,
		autoFocus = false,
	) {
		assertFalse(state.isFocused, "precondition: not focused before the tap")

		tapAtCharacter(4)

		assertTrue(state.isFocused)
	}

	/** The scroll case: a finger down that travels is a pan, not a request to type. */
	@Test
	fun `a finger pan does not focus the editor`() = editorUiTest(
		initialText = document,
		autoFocus = false,
	) {
		panFrom(positionOfCharacter(4))

		assertFalse(state.isFocused)
	}

	/** A mouse press is unambiguous and has no keyboard to raise, so it focuses at once. */
	@Test
	fun `a mouse click focuses the editor`() = editorUiTest(
		initialText = document,
		autoFocus = false,
	) {
		clickAtCharacter(4)

		assertTrue(state.isFocused)
	}

	/**
	 * The menu a right-click opens acts on the editor, so the editor must hold focus
	 * behind it and keep it once the menu goes. Skiko's `awaitFirstDown` answers only
	 * the primary button, so this is the case a focus handler built on it misses.
	 */
	@Test
	fun `a right-click focuses the editor`() {
		val menuState = TextEditorContextMenuState()
		editorUiTest(
			initialText = document,
			autoFocus = false,
			contextMenuState = menuState,
		) {
			rightClickAtCharacter(4)

			assertTrue(menuState.isVisible, "precondition: the right-click opened the menu")
			assertTrue(state.isFocused)

			test.runOnIdle { menuState.dismissMenu() }
			waitForIdle()
			typeText("X")

			assertTrue(state.isFocused)
			assertTrue(text.contains("X"), "expected the typed character to land: $text")
		}
	}

	/** Any mouse button is an unambiguous press at the editor. */
	@Test
	fun `a middle click focuses the editor`() = editorUiTest(
		initialText = document,
		autoFocus = false,
	) {
		middleClickAtCharacter(4)

		assertTrue(state.isFocused)
	}

	/**
	 * The spell-check shape: the tap opened a suggestions popup, and a keyboard
	 * must not slide up over it. The listener opens the menu through the editor's
	 * own context menu state, exactly as SpellCheckingTextEditor does.
	 */
	@Test
	fun `a tap that opens a popup does not focus the editor`() {
		val menuState = TextEditorContextMenuState()
		editorUiTest(
			initialText = document,
			autoFocus = false,
			contextMenuState = menuState,
			onRichSpanClick = { _, _, offset ->
				menuState.showMenu(offset)
				true
			},
		) {
			state.addRichSpan(0, 5, SpellCheckStyle)

			tapAtCharacter(2)

			assertTrue(menuState.isVisible, "precondition: the tap opened the popup")
			assertFalse(state.isFocused, "the keyboard would cover the popup this tap opened")
		}
	}

	/**
	 * The bullet/blockquote regression: hosts return `true` for every click just
	 * to observe them, and list or quote lines are covered by rich spans, so a
	 * "listener said true" gate makes those lines unfocusable by touch. A claimed
	 * tap that opened nothing is an ordinary tap and must focus.
	 */
	@Test
	fun `a tap on a span whose listener claims it but opens nothing still focuses`() = editorUiTest(
		initialText = document,
		autoFocus = false,
		onRichSpanClick = { _, _, _ -> true },
	) {
		state.addRichSpan(0, 5, BulletListSpanStyle)

		tapAtCharacter(2)

		assertTrue(state.isFocused, "no popup opened, so the tap is a request to type here")
	}

	/**
	 * The counterpart, and the case that breaks if consumption is drawn too wide:
	 * a span that declines leaves an ordinary tap, which must still focus.
	 */
	@Test
	fun `a tap a rich span declines still focuses the editor`() = editorUiTest(
		initialText = document,
		autoFocus = false,
		onRichSpanClick = { _, _, _ -> false },
	) {
		state.addRichSpan(0, 5, SpellCheckStyle)

		tapAtCharacter(2)

		assertTrue(state.isFocused)
	}

	@Test
	fun `a tap outside any span focuses even when a span exists elsewhere`() = editorUiTest(
		initialText = document,
		autoFocus = false,
		onRichSpanClick = { _, _, _ -> true },
	) {
		state.addRichSpan(0, 5, SpellCheckStyle)

		tapAtCharacter(20)

		assertTrue(state.isFocused)
	}

	/**
	 * Long-press-to-select must focus, or the selection cannot be typed over: the
	 * keyboard never arrives and tapping to summon it clears the selection first.
	 */
	@Test
	fun `a long press selects and focuses the editor`() = editorUiTest(
		initialText = document,
		autoFocus = false,
	) {
		longPressAtCharacter(4)

		assertTrue(selectedText.isNotEmpty(), "expected the long press to select a word")
		assertTrue(state.isFocused)
	}

	/**
	 * A long press on an existing selection opens the context menu rather than
	 * re-selecting, and the keyboard must not rise over that menu. The popup
	 * check covers this for free: the menu is showing when the finger lifts.
	 */
	@Test
	fun `a long press on an existing selection opens the menu and does not focus`() {
		val menuState = TextEditorContextMenuState()
		editorUiTest(
			initialText = document,
			autoFocus = false,
			contextMenuState = menuState,
		) {
			state.selector.updateSelection(
				state.getOffsetAtCharacter(0),
				state.getOffsetAtCharacter(11),
			)
			waitForIdle()

			longPressAtCharacter(4)

			assertTrue(menuState.isVisible, "precondition: the long press opened the context menu")
			assertFalse(state.isFocused, "the keyboard would cover the menu this press opened")
			assertTrue(selectedText.isNotEmpty(), "the existing selection must survive")
		}
	}

	/**
	 * The second tap of a double tap selects on its down, before the focus handler sees
	 * the release. It must still count as a selection: a popup the first tap opened
	 * would otherwise leave a selection with no way to type over it. The clock is held
	 * so the popup's open animation cannot push the second tap out of the window, and
	 * so the popup is still showing when the second tap lifts.
	 */
	@Test
	fun `a second tap under a popup the first tap opened selects and focuses`() {
		val menuState = TextEditorContextMenuState()
		editorUiTest(
			initialText = document,
			autoFocus = false,
			contextMenuState = menuState,
			onRichSpanClick = { _, _, offset ->
				menuState.showMenu(offset)
				true
			},
		) {
			state.addRichSpan(0, 5, SpellCheckStyle)
			test.mainClock.autoAdvance = false
			tapAtCharacter(2)
			assertTrue(menuState.isVisible, "precondition: the first tap opened the popup")

			tapAtCharacter(2)

			assertEquals("hello", selectedText, "the second tap pairs into a double tap")
			assertTrue(state.isFocused)
		}
	}

	/**
	 * Touch handles go with focus (1.18), so there is none to drag on an unfocused
	 * editor: a finger where one stood is a tap, and taps focus.
	 */
	@Test
	fun `with focus gone a finger where the handle stood taps and focuses`() = editorUiTest(
		initialText = document,
		autoFocus = false,
	) {
		test.runOnIdle {
			state.selector.startSelection(state.getOffsetAtCharacter(6), isTouch = true)
			state.selector.updateSelection(state.getOffsetAtCharacter(6), state.getOffsetAtCharacter(11))
		}
		waitForIdle()
		assertFalse(state.isFocused, "precondition: unfocused with a touch selection")
		val formerHandle = handleCenter(isStart = false)

		panFrom(formerHandle)
		assertFalse(state.isFocused, "no handle to drag, so the travel is a pan")

		tapAt(formerHandle)

		assertTrue(state.isFocused)
	}

	/**
	 * A handle drag travels past touch slop, which alone reads as a pan. What lets the
	 * focus handler tell it apart, and ask for a dismissed keyboard back on the drop, is
	 * the touch selection generation, which every move of the drag advances. The handler's
	 * side of that is pinned by the long-press and double-tap drag tests.
	 */
	@Test
	fun `a handle drag advances the touch selection generation`() = editorUiTest(initialText = document) {
		longPressAtCharacter(8)
		val before = state.selector.touchSelectionGeneration

		dragHandle(isStart = false, toChar = 16)

		assertTrue(selectedText.startsWith("world"), "the drag extended the selection: $selectedText")
		assertTrue(state.selector.touchSelectionGeneration > before)
		assertTrue(state.isFocused)
	}

	/** Two fingers are a pinch or a scroll gesture for some ancestor, never a request to type. */
	@Test
	fun `a two-finger tap does not focus the editor`() = editorUiTest(
		initialText = document,
		autoFocus = false,
	) {
		touch {
			down(0, positionOfCharacter(4))
			down(1, positionOfCharacter(20))
			up(0)
			up(1)
		}

		assertFalse(state.isFocused)
	}

	/** Focus survives the gesture that placed it, so typing right after a tap works. */
	@Test
	fun `a tap leaves the editor focused and editable`() = editorUiTest(
		initialText = document,
		autoFocus = false,
	) {
		tapAtCharacter(5)
		typeText("X")

		assertTrue(state.isFocused)
		assertTrue(text.contains("X"), "expected the typed character to land: $text")
	}
}
