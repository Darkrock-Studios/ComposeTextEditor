package texteditor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.utils.lineTextLeft
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The platforms report a line's indented text position differently, and the
 * gutter markers, span decorations, and the cursor all have to land on the same
 * x regardless: Compose Android reports 0 from `getLineLeft` whatever the indent
 * is, and applies the first-line indent to an empty paragraph; desktop reports
 * the indent from `getLineLeft` but nothing at all for an empty paragraph.
 */
class LineTextLeftTest {
	private val density = Density(1f, 1f)
	private val indent = TextIndent(firstLine = 24.sp)

	private fun layout(
		text: AnnotatedString,
		lineLeft: Float,
		horizontalAtLineStart: Float,
	): TextLayoutResult = mockk<TextLayoutResult>().also { layout ->
		every { layout.getLineStart(any()) } returns 0
		every { layout.getLineLeft(any()) } returns lineLeft
		every { layout.getHorizontalPosition(0, true) } returns horizontalAtLineStart
		every { layout.multiParagraph.getParagraphDirection(any()) } returns ResolvedTextDirection.Ltr
		every { layout.layoutInput.text } returns text
		every { layout.layoutInput.style } returns TextStyle.Default
	}

	private fun indentedEmptyLine(): AnnotatedString = buildAnnotatedString {
		withStyle(ParagraphStyle(textIndent = indent)) { append("") }
	}

	private fun indentedLine(): AnnotatedString = buildAnnotatedString {
		withStyle(ParagraphStyle(textIndent = indent)) { append("hello") }
	}

	@Test
	fun `empty line where the platform already applied the indent is not indented twice`() {
		val layout = layout(indentedEmptyLine(), lineLeft = 0f, horizontalAtLineStart = 24f)

		assertEquals(24f, layout.lineTextLeft(0, density), 0.5f)
	}

	@Test
	fun `empty line where the platform ignored the indent falls back to the declared indent`() {
		val layout = layout(indentedEmptyLine(), lineLeft = 0f, horizontalAtLineStart = 0f)

		assertEquals(24f, layout.lineTextLeft(0, density), 0.5f)
	}

	@Test
	fun `line start glyph wins over a lineLeft that ignores the indent`() {
		val layout = layout(indentedLine(), lineLeft = 0f, horizontalAtLineStart = 24f)

		assertEquals(24f, layout.lineTextLeft(0, density), 0.5f)
	}

	@Test
	fun `unindented line stays at zero`() {
		val layout = layout(AnnotatedString("hello"), lineLeft = 0f, horizontalAtLineStart = 0f)

		assertEquals(0f, layout.lineTextLeft(0, density), 0.5f)
	}

	@Test
	fun `without a density an empty line reports only what the layout measured`() {
		val layout = layout(indentedEmptyLine(), lineLeft = 0f, horizontalAtLineStart = 0f)

		assertEquals(0f, layout.lineTextLeft(0, density = null), 0.5f)
	}

	@Test
	fun `rtl lines keep the platform's lineLeft`() {
		val layout = mockk<TextLayoutResult>().also { layout ->
			every { layout.getLineStart(any()) } returns 0
			every { layout.getLineLeft(any()) } returns 12f
			every { layout.multiParagraph.getParagraphDirection(any()) } returns ResolvedTextDirection.Rtl
		}

		assertEquals(12f, layout.lineTextLeft(0, density), 0.5f)
	}
}
