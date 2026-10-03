package dragdrop

import androidx.compose.ui.draganddrop.DragAndDropStartTransferScope
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.dragdrop.TextDragAndDrop
import com.darkrockstudios.texteditor.dragdrop.dragPictureText
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A drag a finger starts shows the start of the dragged text, as `TextView`'s drag shadow
 * does: up to the cluster at its 21st character, at the large text size, in the editor's
 * text colour and the text's own styles. A mouse drag keeps a 1 pixel decoration, as the
 * pointer shows that drag.
 */
class FingerDragPictureTest {

	private val density = Density(2f)
	private val measurer = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)

	private class RecordingScope : DragAndDropStartTransferScope {
		var size: Size? = null
		var draw: (DrawScope.() -> Unit)? = null
		override fun startDragAndDropTransfer(
			transferData: DragAndDropTransferData,
			decorationSize: Size,
			drawDragDecoration: DrawScope.() -> Unit,
		): Boolean {
			size = decorationSize
			draw = drawDragDecoration
			return true
		}
	}

	private fun dragged(
		text: AnnotatedString,
		byFinger: Boolean,
		textColor: Color = Color.Blue,
		textStyle: TextStyle = TextStyle(fontSize = 14.sp),
	): RecordingScope {
		val state = TextEditorState(scope = TestScope(), measurer = measurer, initialText = text)
		state.textStyle = textStyle
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, text.length))
		val scope = RecordingScope()
		val dragAndDrop = TextDragAndDrop(state)
		dragAndDrop.textColor = textColor
		dragAndDrop.requestTransfer = { dragAndDrop.platformStartsTransfer(scope) }
		assertTrue(dragAndDrop.start(Offset.Zero, byFinger = byFinger))
		return scope
	}

	private fun pixels(scope: RecordingScope): List<Color> {
		val size = scope.size!!
		val bitmap = ImageBitmap(size.width.toInt(), size.height.toInt())
		CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), size, scope.draw!!)
		val map = bitmap.toPixelMap()
		return (0 until map.width).flatMap { x -> (0 until map.height).map { y -> map[x, y] } }
	}

	private fun Color.near(other: Color) =
		abs(red - other.red) < 0.1f && abs(green - other.green) < 0.1f && abs(blue - other.blue) < 0.1f && alpha > 0.9f

	@Test
	fun `the picture shows the text through the cluster at its 21st character`() {
		assertEquals("abcdefghijklmnopqrstu", AnnotatedString("abcdefghijklmnopqrstuvwxyz").dragPictureText().text)
		assertEquals("short", AnnotatedString("short").dragPictureText().text)
		assertEquals("abcdefghijklmnopqrstu", AnnotatedString("abcdefghijklmnopqrstu").dragPictureText().text)
		// A family emoji from the 20th character on is shown whole, never split.
		val family = "👨‍👩‍👧"
		assertEquals("a".repeat(19) + family, AnnotatedString("a".repeat(19) + family + "tail").dragPictureText().text)
	}

	@Test
	fun `a finger drag shows the text at the large size`() {
		val scope = dragged(AnnotatedString("abcdefghijklmnopqrstuvwxyz"), byFinger = true)

		val expected = measurer.measure("abcdefghijklmnopqrstu", TextStyle(fontSize = 22.sp)).size
		assertEquals(Size(expected.width.toFloat(), expected.height.toFloat()), scope.size)
	}

	/** A list item's indent would push the text off the finger, and a short line height clip it. */
	@Test
	fun `block indents and line heights are left out`() {
		val indented = buildAnnotatedString {
			withStyle(ParagraphStyle(textIndent = TextIndent(48.sp, 48.sp))) { append("item") }
		}
		val scope = dragged(indented, byFinger = true, textStyle = TextStyle(fontSize = 14.sp, lineHeight = 10.sp))

		val expected = measurer.measure("item", TextStyle(fontSize = 22.sp)).size
		assertEquals(Size(expected.width.toFloat(), expected.height.toFloat()), scope.size)
	}

	@Test
	fun `the picture draws in the editor's text colour and keeps the text's own`() {
		val text = buildAnnotatedString {
			append("plain ")
			withStyle(SpanStyle(color = Color.Red)) { append("red") }
		}
		val drawn = pixels(dragged(text, byFinger = true, textColor = Color.Blue))

		assertTrue(drawn.any { it.near(Color.Blue) }, "the text in the editor's colour")
		assertTrue(drawn.any { it.near(Color.Red) }, "the span's own colour")
		assertTrue(drawn.any { it.alpha == 0f }, "nothing behind the text")
	}

	@Test
	fun `a mouse drag keeps a one pixel decoration`() {
		assertEquals(Size(1f, 1f), dragged(AnnotatedString("abcdefghijklmnopqrstuvwxyz"), byFinger = false).size)
	}
}
