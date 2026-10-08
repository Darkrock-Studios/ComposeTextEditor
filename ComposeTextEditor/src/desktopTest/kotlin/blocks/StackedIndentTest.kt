package blocks

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import utils.setBlockLines
import kotlin.test.assertEquals

/** A line stacking blocks is indented by all of them, not by the innermost alone. */
class StackedIndentTest {

	private fun textLeft(blockLines: String, line: Int): Float {
		val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f, 1f), LayoutDirection.Ltr)
		val state = TextEditorState(scope = TestScope(), measurer = measurer).apply {
			onViewportSizeChange(Size(400f, 300f))
			setBlockLines(blockLines)
		}
		return state.getPositionForOffset(CharLineOffset(line, 0)).position.x
	}

	@Test
	fun `a quoted list item is indented by the quote and the list`() {
		val quote = textLeft("> text", 0)
		val item = textLeft("- item", 0)

		assertEquals(quote + item, textLeft("> - item", 0), 0.5f)
		assertEquals(quote + textLeft("  - nested", 0), textLeft("> - a\n>   - nested", 1), 0.5f)
	}
}
