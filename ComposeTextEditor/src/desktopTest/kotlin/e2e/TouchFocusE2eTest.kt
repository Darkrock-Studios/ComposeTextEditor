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
 * whether a keyboard slides up over the content. Only a plain tap, or a mouse
 * press, asks to start typing; a pan scrolls, a long press selects, and a tap a
 * rich span answered opened something the keyboard would cover.
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
	 * A long press selects a word and can open the context menu. Focusing on lift
	 * would raise the keyboard over both.
	 */
	@Test
	fun `a long press does not focus the editor`() = editorUiTest(
		initialText = document,
		autoFocus = false,
	) {
		longPressAtCharacter(4)

		assertFalse(state.isFocused)
		assertTrue(selectedText.isNotEmpty(), "expected the long press to select a word")
	}

	/**
	 * An external mouse on Android reports as touch with buttons set, and there is a
	 * real keyboard to raise there, so the span check has to apply to mouse too.
	 */
	@Test
	fun `a mouse click claimed by a rich span does not focus the editor`() = editorUiTest(
		initialText = document,
		autoFocus = false,
		onRichSpanClick = { _, _, _ -> true },
	) {
		state.addRichSpan(0, 5, SpellCheckStyle)

		clickAtCharacter(2)

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
