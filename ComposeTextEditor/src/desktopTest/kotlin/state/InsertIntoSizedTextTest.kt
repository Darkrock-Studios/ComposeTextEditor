package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.applyStyleForEditAt
import com.darkrockstudios.texteditor.state.getSpanStylesAtPosition
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A document whose body text carries its font size as a span, the way a
 * `MarkdownConfiguration` with a scaled `defaultTextStyle` produces. Text inserted
 * into such a document has to pick that size up or it renders at the bare default.
 */
class InsertIntoSizedTextTest {

	private val base = SpanStyle(fontSize = 24.sp)

	private fun TestScope.sizedState(vararg lines: String): TextEditorState {
		val text = buildAnnotatedString {
			append(lines.joinToString("\n"))
			addStyle(base, 0, length)
		}
		return TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = text)
	}

	private fun TextEditorState.assertSizedAt(line: Int, char: Int) {
		assertTrue(
			getSpanStylesAtPosition(CharLineOffset(line, char)).any { it.fontSize == 24.sp },
			"expected the base font size at [$line:$char], got ${textLines[line].spanStyles}",
		)
	}

	@Test
	fun `text inserted at the start of the document takes the surrounding size`() = runTest {
		val state = sizedState("The quick brown fox jumps")
		state.cursor.updatePosition(CharLineOffset(0, 0))

		state.insertStringAtCursor(AnnotatedString("PASTED"))

		assertEquals("PASTEDThe quick brown fox jumps", state.textLines[0].text)
		state.assertSizedAt(line = 0, char = 2)
	}

	@Test
	fun `multi line text inserted at the start of the document takes the surrounding size`() =
		runTest {
			val state = sizedState("First paragraph here", "Second paragraph here")
			state.cursor.updatePosition(CharLineOffset(0, 0))

			state.insertStringAtCursor(AnnotatedString("AAA\n\nBBB\n\n"))

			assertEquals(
				listOf("AAA", "", "BBB", "", "First paragraph here", "Second paragraph here"),
				state.textLines.map { it.text },
			)
			state.assertSizedAt(line = 0, char = 1)
			state.assertSizedAt(line = 2, char = 1)
		}

	@Test
	fun `text inserted after a blank line takes the surrounding size`() = runTest {
		val state = sizedState("First paragraph", "", "Second paragraph")
		state.cursor.updatePosition(CharLineOffset(2, 0))

		state.insertStringAtCursor(AnnotatedString("PASTED"))

		assertEquals("PASTEDSecond paragraph", state.textLines[2].text)
		state.assertSizedAt(line = 2, char = 2)
	}

	@Test
	fun `text inserted at the end of the document takes the surrounding size`() = runTest {
		val state = sizedState("The quick brown fox jumps")
		state.cursor.updatePosition(CharLineOffset(0, 25))

		state.insertStringAtCursor(AnnotatedString("PASTED"))

		state.assertSizedAt(line = 0, char = 27)
	}

	@Test
	fun `the typing style is available before the caret has been moved`() = runTest {
		val state = sizedState("The quick brown fox jumps")

		assertTrue(
			state.cursor.styles.any { it.fontSize == 24.sp },
			"a freshly loaded document must expose its style without the caret moving first",
		)
	}

	@Test
	fun `replacing the document refreshes the typing style`() = runTest {
		val state = sizedState("The quick brown fox jumps")

		state.setText(AnnotatedString("plain text"))

		assertTrue(
			state.cursor.styles.isEmpty(),
			"replacing the document must drop the previous document's style, got ${state.cursor.styles}",
		)
	}

	@Test
	fun `the style for an edit comes from the edit position, not the caret`() = runTest {
		val heading = SpanStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold)
		val text = buildAnnotatedString {
			append("Chapter One\nBody text here")
			addStyle(base, 0, length)
			addStyle(heading, 0, 11)
		}
		val state =
			TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = text)
		state.cursor.updatePosition(CharLineOffset(0, 11))

		val styled = state.applyStyleForEditAt(CharLineOffset(1, 5), AnnotatedString("PASTED"))

		assertEquals(
			listOf(24.sp),
			styled.spanStyles.map { it.item.fontSize },
			"an edit in the body must not pick up the heading style the caret is parked on",
		)
	}

	@Test
	fun `text inserted at the start of the document continues a styled opening run`() = runTest {
		val bold = SpanStyle(fontWeight = FontWeight.Bold)
		val text = buildAnnotatedString {
			append("Warning: check this")
			addStyle(base, 0, length)
			addStyle(bold, 0, 8)
		}
		val state =
			TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = text)
		state.cursor.updatePosition(CharLineOffset(0, 0))

		state.insertStringAtCursor(AnnotatedString("Note "))

		// The caret has no character before it here, so it continues the run it sits in
		// front of, exactly as it would one character further along.
		assertTrue(
			state.getSpanStylesAtPosition(CharLineOffset(0, 2))
				.any { it.fontWeight == FontWeight.Bold },
			"expected the opening bold run to continue, got ${state.textLines[0].spanStyles}",
		)
	}
}
