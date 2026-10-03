package input

import android.view.View
import com.darkrockstudios.texteditor.input.WindowBottom
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

/** Roadmap 4.36: the keyboard rises from the window's bottom, which an embedded view may not reach. */
class WindowBottomInRootTest {
	private fun composeView(topInWindow: Int, windowHeight: Int): View {
		val window = mockk<View> { every { height } returns windowHeight }
		return mockk {
			every { rootView } returns window
			every { getLocationInWindow(any()) } answers { firstArg<IntArray>()[1] = topInWindow }
		}
	}

	@Test
	fun `a view filling the window ends at its bottom`() {
		assertEquals(2400f, WindowBottom(composeView(topInWindow = 0, windowHeight = 2400)).inView())
	}

	@Test
	fun `a view embedded higher up sees the window's bottom below its own`() {
		// Its top 300 down a 2400 window: the window ends 2100 below the view's top.
		assertEquals(2100f, WindowBottom(composeView(topInWindow = 300, windowHeight = 2400)).inView())
	}
}
