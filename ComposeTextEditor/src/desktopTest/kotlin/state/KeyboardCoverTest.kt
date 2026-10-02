package state

import com.darkrockstudios.texteditor.state.keyboardCover
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyboardCoverTest {
	@Test
	fun `a keyboard over the bottom of the canvas covers the overlap`() {
		// The window ends at 1000, keyboard 300: its top is at 700. The canvas ends at 900.
		assertEquals(200, keyboardCover(canvasBottomInRoot = 900f, canvasHeight = 800, windowBottomInRoot = 1000f, keyboardHeight = 300))
	}

	@Test
	fun `a canvas that ends above the keyboard is not covered`() {
		// The host padded the editor above the keyboard.
		assertEquals(0, keyboardCover(canvasBottomInRoot = 700f, canvasHeight = 600, windowBottomInRoot = 1000f, keyboardHeight = 300))
	}

	@Test
	fun `no keyboard covers nothing even where the canvas runs past the window`() {
		// An editor taller than the window, in a scrolled page: what lies past the
		// window is not a keyboard's doing and must not move the caret.
		assertEquals(0, keyboardCover(canvasBottomInRoot = 1400f, canvasHeight = 1200, windowBottomInRoot = 1000f, keyboardHeight = 0))
	}

	@Test
	fun `the cover never exceeds the canvas`() {
		assertEquals(200, keyboardCover(canvasBottomInRoot = 900f, canvasHeight = 200, windowBottomInRoot = 1000f, keyboardHeight = 800))
	}
}
