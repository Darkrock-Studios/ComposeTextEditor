package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * replace() with a multi-line range and inheritStyle used to drop the new text
 * entirely: the styled string was built but never appended, leaving prefix+suffix.
 * Undoing that broken replace then crashed captureMetadata with offsets pointing
 * past the shrunken document.
 */
class MultiLineReplaceInheritTest {

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)

	private fun editor(initial: AnnotatedString): TextEditorState =
		TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = initial)

	@Test
	fun `multi line range with inherit keeps the replacement text`() {
		val state = editor(AnnotatedString("abc\ndef"))

		state.replace(
			TextEditorRange(CharLineOffset(0, 1), CharLineOffset(1, 1)),
			AnnotatedString("XX"),
			inheritStyle = true,
		)

		assertEquals("aXXef", state.getAllText().text)
	}

	@Test
	fun `whole document replace with inherit keeps the replacement text`() {
		val state = editor(AnnotatedString("abc\ndef"))

		state.replace(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 3)),
			AnnotatedString("XX"),
			inheritStyle = true,
		)

		assertEquals("XX", state.getAllText().text)
	}

	@Test
	fun `inherited style covers the whole replacement including the last character`() {
		val state = editor(
			AnnotatedString(
				"abc\ndef",
				spanStyles = listOf(AnnotatedString.Range(bold, 0, 3)),
			)
		)

		state.replace(
			TextEditorRange(CharLineOffset(0, 1), CharLineOffset(1, 1)),
			AnnotatedString("XX"),
			inheritStyle = true,
		)

		assertEquals("aXXef", state.getAllText().text)
		val boldSpans = state.getAllText().spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
		assertTrue(
			boldSpans.any { it.start <= 1 && it.end >= 3 },
			"both inserted characters must inherit bold, got $boldSpans",
		)
	}

	@Test
	fun `multi line replacement text with inherit styles every line`() {
		val state = editor(
			AnnotatedString(
				"abc\ndef",
				spanStyles = listOf(AnnotatedString.Range(bold, 0, 3)),
			)
		)

		state.replace(
			TextEditorRange(CharLineOffset(0, 1), CharLineOffset(1, 1)),
			AnnotatedString("X\nY"),
			inheritStyle = true,
		)

		assertEquals("aX\nYef", state.getAllText().text)
	}

	@Test
	fun `undo of a whole document inherit replace restores the original`() {
		val state = editor(AnnotatedString("abc\ndef"))

		state.replace(
			TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 3)),
			AnnotatedString("XX"),
			inheritStyle = true,
		)
		state.undo()

		assertEquals("abc\ndef", state.getAllText().text)
	}
}
