@file:OptIn(ExperimentalTestApi::class)

package e2e

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.toggleOrderedList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StyleToStateE2eTest {
	private val views: Map<String, @Composable (TextEditorState, TextEditorStyle) -> Unit> = mapOf(
		"BasicTextEditor" to { state, style ->
			BasicTextEditor(state = state, modifier = Modifier.size(300.dp, 200.dp), style = style)
		},
		"RichTextView" to { state, style ->
			RichTextView(state = state, modifier = Modifier.size(300.dp, 200.dp), style = style)
		},
	)

	private fun styleWith(marker: Color) = TextEditorStyle(
		textColor = Color.Black,
		bulletColor = Color.Green,
		blockquoteBarColor = Color.Blue,
		blockquoteBackgroundColor = Color.Cyan,
		orderedListMarkerColor = marker,
		codeFenceBackgroundColor = Color.Magenta,
		codeFenceBorderColor = Color.Yellow,
		tableBorderColor = Color.Gray,
		tableHeaderBackgroundColor = Color.DarkGray,
	)

	@Test
	fun `each view hands the style's colors to its state, and follows a change`() {
		for ((name, view) in views) runSkikoComposeUiTest(density = Density(1f)) {
			val state = TextEditorState(AnnotatedString("one"))
			var style by mutableStateOf(styleWith(marker = Color.Red))
			setContent { view(state, style) }
			waitForIdle()
			assertEquals(Color.Black, state.textColor, name)
			assertEquals(Color.Green, state.bulletColor, name)
			assertEquals(Color.Blue, state.blockquoteBarColor, name)
			assertEquals(Color.Cyan, state.blockquoteBackgroundColor, name)
			assertEquals(Color.Red, state.orderedListMarkerColor, name)
			assertEquals(Color.Magenta, state.codeFenceBackgroundColor, name)
			assertEquals(Color.Yellow, state.codeFenceBorderColor, name)
			assertEquals(Color.Gray, state.tableBorderColor, name)
			assertEquals(Color.DarkGray, state.tableHeaderBackgroundColor, name)

			style = styleWith(marker = Color.White)
			waitForIdle()
			assertEquals(Color.White, state.orderedListMarkerColor, name)
		}
	}

	@Test
	fun `a read-only view draws the numeral in its marker color`() = runSkikoComposeUiTest(density = Density(1f)) {
		val pixels = drawOrderedList(TextEditorStyle(textColor = Color.White, orderedListMarkerColor = Color.Red))
		assertTrue(pixels.any { it.red > 0.8f && it.green < 0.3f && it.blue < 0.3f }, "a red numeral")
	}

	@Test
	fun `with no marker color the numeral takes the text color`() = runSkikoComposeUiTest(density = Density(1f)) {
		// On black, a numeral left at the measurer's default black adds no bright pixels.
		val bright = { pixels: List<Color> -> pixels.count { it.red > 0.5f } }
		val withList = bright(drawOrderedList(TextEditorStyle(textColor = Color.White)))
		val withoutList = bright(drawOrderedList(TextEditorStyle(textColor = Color.White), list = false))
		assertTrue(withList > withoutList, "the numeral draws in white")
	}

	private fun ComposeUiTest.drawOrderedList(
		style: TextEditorStyle,
		list: Boolean = true,
	): List<Color> {
		val state = TextEditorState(AnnotatedString("one"))
		if (list) state.toggleOrderedList(0..0)
		setContent {
			Box(Modifier.background(Color.Black)) {
				RichTextView(state = state, modifier = Modifier.size(200.dp, 60.dp), style = style)
			}
		}
		waitForIdle()
		val map = onRoot().captureToImage().toPixelMap()
		return buildList { for (y in 0 until map.height) for (x in 0 until map.width) add(map[x, y]) }
	}
}
