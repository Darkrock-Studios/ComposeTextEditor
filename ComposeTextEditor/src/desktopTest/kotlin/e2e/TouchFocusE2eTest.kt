package e2e

import androidx.compose.ui.text.AnnotatedString
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
 * it landed for the gesture to count as a tap, and a tap a rich span answered
 * opened something the keyboard would cover.
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

	@Test
	fun `a tap claimed by a rich span does not focus the editor`() = editorUiTest(
		initialText = document,
		autoFocus = false,
		onRichSpanClick = { _, _, _ -> true },
	) {
		state.addRichSpan(0, 5, SpellCheckStyle)

		tapAtCharacter(2)

		assertFalse(state.isFocused, "a span answered the tap, so it must not also raise the keyboard")
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
