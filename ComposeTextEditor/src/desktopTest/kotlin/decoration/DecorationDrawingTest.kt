package decoration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.decoration.Decoration
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.setDecorations
import com.darkrockstudios.texteditor.forEachTintable
import com.darkrockstudios.texteditor.richstyle.RichSpan
import utils.EDITOR_TEST_TAG
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A decoration's text colour tints its own glyphs, no others, and leaves emoji alone. */
@OptIn(ExperimentalTestApi::class)
class DecorationDrawingTest {

	private val layer = DecorationLayer("test")

	/** The x of every pixel drawn mostly red. */
	private fun EditorUiTestScope.redColumns(): Set<Int> {
		test.waitForIdle()
		val pixels = test.onNodeWithTag(EDITOR_TEST_TAG).captureToImage().toPixelMap()
		val columns = HashSet<Int>()
		for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
			val c = pixels[x, y]
			if (c.red > 0.5f && c.green < 0.3f && c.blue < 0.3f) columns += x
		}
		return columns
	}

	@Test
	fun `a text colour tints its range's glyphs only`() = editorUiTest(initialText = AnnotatedString("val total = 1"), autoFocus = false) {
		assertTrue(redColumns().isEmpty())
		test.runOnIdle {
			state.setDecorations(layer, listOf(RichSpan(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 9)), Decoration(layer, textColor = Color.Red))))
		}
		val layout = state.lineOffsets.first().textLayoutResult
		val left = layout.getBoundingBox(4).left
		val right = layout.getBoundingBox(8).right

		val red = redColumns()

		assertTrue(red.isNotEmpty(), "nothing was tinted")
		assertTrue(red.all { it >= left - 1 && it <= right + 1 }, "tinted outside $left..$right: ${red.sorted()}")
		assertTrue(red.max() - red.min() > (right - left) / 2, "only part of the word was tinted: ${red.sorted()}")
	}

	@Test
	fun `a tint follows the text scrolled sideways`() = editorUiTest(
		initialText = AnnotatedString((0 until 40).joinToString(" ") { "word$it" } + " target end"),
		softWrap = false,
	) {
		val start = state.textLines[0].text.indexOf("target")
		test.runOnIdle {
			state.setDecorations(layer, listOf(RichSpan(TextEditorRange(CharLineOffset(0, start), CharLineOffset(0, start + 6)), Decoration(layer, textColor = Color.Red))))
			state.cursor.updatePosition(CharLineOffset(0, start + 6))
		}
		test.waitForIdle()
		val scrollX = state.horizontalScrollState.value
		assertTrue(scrollX > 0, "the line did not scroll")
		val layout = state.lineOffsets.first().textLayoutResult
		val left = layout.getBoundingBox(start).left - scrollX
		val right = layout.getBoundingBox(start + 5).right - scrollX

		val red = redColumns()

		assertTrue(red.isNotEmpty(), "nothing was tinted")
		assertTrue(red.all { it >= left - 1 && it <= right + 1 }, "tinted outside $left..$right: ${red.sorted()}")
	}

	private fun stretches(text: AnnotatedString): List<String> {
		val found = mutableListOf<String>()
		forEachTintable(text, 0, text.length) { from, to -> found += text.text.substring(from, to) }
		return found
	}

	private fun stretches(text: String) = stretches(AnnotatedString(text))

	@Test
	fun `a tint leaves colour glyphs out`() {
		assertEquals(listOf("\"hi "), stretches("\"hi 😀"))
		assertEquals(listOf("a", "b"), stretches("a👍🏽b"))
		assertEquals(listOf("x ", " y"), stretches("x 👨\u200D👩\u200D👧 y"))
		assertEquals(listOf("n=", ";"), stretches("n=1\uFE0F\u20E3;"))
		assertEquals(listOf("ok ", " no "), stretches("ok ✅ no ❌"))
		assertEquals(listOf("plain 漢字 ✓ 𠀀"), stretches("plain 漢字 ✓ 𠀀"))
		assertEquals(emptyList(), stretches("🙂"))
	}

	@Test
	fun `a selector after a pair leaves the pair whole`() {
		assertEquals(listOf("a", "b"), stretches("a𠀀\uFE0Fb"))
	}

	@Test
	fun `a tint leaves text with a background of its own out`() {
		val text = buildAnnotatedString {
			append("val ")
			withStyle(SpanStyle(background = Color.LightGray)) { append("code") }
			append(" x")
		}
		assertEquals(listOf("val ", " x"), stretches(text))
	}
}
