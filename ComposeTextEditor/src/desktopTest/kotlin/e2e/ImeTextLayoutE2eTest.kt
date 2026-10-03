package e2e

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.input.SkikoTextEditorInputMethodRequest
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.state.setParagraphFormat
import com.darkrockstudios.texteditor.state.toggleHeader
import semantics.editorNode
import utils.editorUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * iOS's spacebar trackpad and VoiceOver's caret outline hit-test the request's text
 * layout, placed at `unclippedTextOffsetInRoot`: the floating cursor starts at
 * `getCursorRect` and follows the finger with `getOffsetForPosition`. The
 * layout is the semantics one, so its rows sit where the editor draws them, and
 * it is not rebuilt while the finger moves.
 */
@OptIn(ExperimentalTestApi::class)
class ImeTextLayoutE2eTest {
	private val doc = AnnotatedString(
		"First line\n" +
			"A second line long enough to wrap in a four hundred pixel wide editor, twice over at least.\n" +
			"Last"
	)

	@Test
	fun `platforms that do not hit-test get no layout`() = editorUiTest(initialText = doc) {
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default)
		assertNull(test.runOnIdle { request.textLayoutResult() })
	}

	@Test
	fun `the layout hit-tests back to the offset it placed the caret at`() = editorUiTest(initialText = doc) {
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default, exposeTextLayout = true)
		test.runOnIdle {
			val layout = request.textLayoutResult()!!
			assertEquals(text, layout.layoutInput.text.text)
			assertTrue(layout.lineCount > 3, "The long line should wrap, got ${layout.lineCount} lines")
			for (offset in listOf(0, 5, 11, 40, 90, text.length - 1)) {
				val caret = layout.getCursorRect(offset)
				assertEquals(offset, layout.getOffsetForPosition(caret.center), "offset $offset")
			}
		}
	}

	@Test
	fun `the layout is kept until the text changes`() = editorUiTest(initialText = doc) {
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default, exposeTextLayout = true)
		val first = test.runOnIdle { request.textLayoutResult() }
		assertSame(first, test.runOnIdle { request.textLayoutResult() })

		typeText("x")
		val edited = test.runOnIdle { request.textLayoutResult() }!!
		assertNotSame(first, edited)
		assertEquals(text, edited.layoutInput.text.text)
	}

	@Test
	fun `the layout is the one screen readers read`() = editorUiTest(initialText = doc) {
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default, exposeTextLayout = true)
		val layouts = mutableListOf<TextLayoutResult>()
		test.runOnIdle {
			assertTrue(editorNode().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts))
			assertSame(layouts.single(), request.textLayoutResult())
		}
	}

	@Test
	fun `a heading's and an indented paragraph's rows sit where they are drawn`() = editorUiTest(
		initialText = AnnotatedString(
			"A heading\n" + doc.text + "\n" + (1..30).joinToString("\n") { "Line $it" }
		),
		height = 200.dp,
		contentPadding = PaddingValues(start = 20.dp, top = 12.dp),
	) {
		state.toggleHeader(0..0, 1)
		state.setParagraphFormat(0..0, ParagraphFormatSpanStyle(spaceBefore = 18.dp))
		state.setParagraphFormat(2..2, ParagraphFormatSpanStyle(indent = 24.sp, firstLineIndent = 10.sp))
		test.runOnIdle { state.scrollState.scrollTo(30) }
		waitForIdle()
		val request = SkikoTextEditorInputMethodRequest(state, ImeOptions.Default, exposeTextLayout = true)
		test.runOnIdle {
			val layout = request.textLayoutResult()!!
			val origin = request.unclippedTextOffsetInRoot()!!
			val canvas = state.canvasLayoutCoordinates!!.positionInRoot()
			val rows = state.lineOffsets
			assertEquals(rows.size, layout.lineCount, "row count")
			for (row in rows.indices) {
				val wrap = rows[row]
				val drawnTop = canvas.y - state.scrollState.value + wrap.offset.y
				val drawnLeft = canvas.x + wrap.offset.x + wrap.textLayoutResult.getLineLeft(wrap.virtualLineIndex)
				val top = origin.y + layout.getLineTop(row)
				val left = origin.x + layout.getLineLeft(row)
				assertTrue(abs(top - drawnTop) <= 1f, "top of row $row: $top, drawn at $drawnTop")
				assertTrue(abs(left - drawnLeft) < 0.5f, "left of row $row: $left, drawn at $drawnLeft")
			}
		}
	}
}
