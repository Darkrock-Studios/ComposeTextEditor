package e2e

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.typeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proof-of-concept for end-to-end editor tests: real composable, synthetic
 * input events, assertions on both state and rendered pixels.
 */
@OptIn(ExperimentalTestApi::class)
class EditorEndToEndPocTest {

	@Test
	fun `typing via AWT key events inserts characters`() = runSkikoComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState()
			BasicTextEditor(
				state = state,
				modifier = Modifier.size(width = 400.dp, height = 200.dp),
				autoFocus = true,
			)
		}
		waitForIdle()

		typeText("Hello, World!")

		assertEquals("Hello, World!", state.getAllText().text)
	}

	@Test
	fun `typing via semantics text input inserts text`() = runSkikoComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState()
			BasicTextEditor(
				state = state,
				modifier = Modifier.size(width = 400.dp, height = 200.dp),
				autoFocus = true,
			)
		}
		waitForIdle()

		onNode(hasSetTextAction()).performTextInput("Hello, world")
		waitForIdle()

		assertEquals("Hello, world", state.getAllText().text)
	}

	@Test
	fun `click positions cursor then backspace deletes at that position`() = runSkikoComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(
				initialText = AnnotatedString("Hello World")
			)
			BasicTextEditor(
				state = state,
				modifier = Modifier.size(width = 400.dp, height = 200.dp),
				autoFocus = true,
			)
		}
		waitForIdle()

		// Click far to the right of the single line: cursor goes to end of text.
		onRoot().performMouseInput { click(Offset(300f, 10f)) }
		waitForIdle()

		onRoot().performKeyInput { pressKey(Key.Backspace) }
		waitForIdle()

		assertEquals("Hello Worl", state.getAllText().text)
	}

	@Test
	fun `typed text produces visible glyph pixels`() = runSkikoComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState()
			BasicTextEditor(
				state = state,
				modifier = Modifier.size(width = 200.dp, height = 100.dp),
				style = TextEditorStyle(
					textColor = Color.Black,
					backgroundColor = Color.White,
					cursorColor = Color.White,
				),
				autoFocus = true,
			)
		}
		waitForIdle()

		val before = onRoot().captureToImage().toPixelMap()
		val beforeDark = countDarkPixels(before)

		typeText("Hello")

		val after = onRoot().captureToImage().toPixelMap()
		val afterDark = countDarkPixels(after)

		assertTrue(
			afterDark > beforeDark + 50,
			"expected glyph pixels after typing: before=$beforeDark after=$afterDark",
		)
	}

	private fun countDarkPixels(pixels: PixelMap): Int {
		var count = 0
		for (y in 0 until pixels.height) {
			for (x in 0 until pixels.width) {
				val c = pixels[x, y]
				if (c.red < 0.5f && c.green < 0.5f && c.blue < 0.5f) count++
			}
		}
		return count
	}
}
