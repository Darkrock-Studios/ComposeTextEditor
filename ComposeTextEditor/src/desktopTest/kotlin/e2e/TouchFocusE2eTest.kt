package e2e

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import utils.editorUiTest
import kotlin.test.Test
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
