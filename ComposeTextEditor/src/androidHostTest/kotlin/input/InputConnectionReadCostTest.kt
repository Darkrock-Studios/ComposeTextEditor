package input

import android.view.View
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The keyboard's reads around the caret read the lines in their window, never the
 * whole document. Counted through a line list that tallies each line it hands out.
 */
class InputConnectionReadCostTest {

	private class CountingLines(private val backing: List<AnnotatedString>) : AbstractList<AnnotatedString>() {
		var reads = 0

		override val size: Int get() = backing.size

		override fun get(index: Int): AnnotatedString {
			reads++
			return backing[index]
		}
	}

	private val lineCount = 500

	@Test
	fun `text around the caret reads only its window`() {
		val state = TextEditorState(
			scope = TestScope(),
			measurer = mockk(relaxed = true),
			initialText = AnnotatedString((0 until lineCount).joinToString("\n") { "line $it has a few words" }),
		)
		val lines = CountingLines(state.textLines)
		state.setLines(lines)
		state.getTextLength()
		state.cursor.updatePosition(CharLineOffset(250, 4))
		val connection = TextEditorInputConnection(state, mockk<View>(relaxed = true))
		lines.reads = 0

		val before = connection.getTextBeforeCursor(30, 0).toString()
		val after = connection.getTextAfterCursor(30, 0).toString()

		assertTrue(lines.reads <= 8, "reading 60 characters read ${lines.reads} of $lineCount lines")
		assertEquals("\nline 249 has a few words\nline", before)
		assertEquals(" 250 has a few words\nline 251 ", after)
	}
}
