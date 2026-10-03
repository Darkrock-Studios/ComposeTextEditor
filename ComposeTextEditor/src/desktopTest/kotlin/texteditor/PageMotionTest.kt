package texteditor

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.moveCursorPageDown
import com.darkrockstudios.texteditor.state.moveCursorPageUp
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import kotlin.test.assertEquals

/** Page moves in a viewport shorter than a row, where a page is less than one row. */
class PageMotionTest {
	private val density = Density(1f, 1f)

	private fun sliverState(): TextEditorState = TextEditorState(
		scope = TestScope(),
		measurer = TextMeasurer(
			defaultFontFamilyResolver = createFontFamilyResolver(),
			defaultDensity = density,
			defaultLayoutDirection = LayoutDirection.Ltr,
		),
	).also {
		it.density = density
		it.setText("one\ntwo\nthree")
		it.onViewportSizeChange(Size(500f, 2f))
	}

	@Test
	fun `a page shorter than a row still moves one row`() {
		val state = sliverState()
		state.cursor.updatePosition(CharLineOffset(1, 1))

		state.moveCursorPageDown()
		assertEquals(2, state.cursorPosition.line)

		state.moveCursorPageUp()
		assertEquals(1, state.cursorPosition.line)
	}
}
