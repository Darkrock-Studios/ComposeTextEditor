@file:OptIn(ExperimentalTestApi::class)

package e2e

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import utils.typeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Roadmap 7.24: a host (a view model) creates and loads a state outside composition,
 * and the editor that shows it lends it the scope and measurer it needs.
 */
class HostCreatedStateE2eTest {
	private val doc = (0 until 80).joinToString("\n") { "Line $it" }

	@Test
	fun `a state made outside composition takes edits before it is shown`() {
		val state = TextEditorState(AnnotatedString("hello"))

		state.replace(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 5)), " world")
		assertEquals("hello world", state.getAllText().text)
		state.undo()
		assertEquals("hello", state.getAllText().text)
		state.setText("one\ntwo")
		assertEquals(2, state.textLines.size)
		assertFalse(state.scope.isActive, "no editor has lent it a scope yet")
		// A scroll with nothing showing the state is dropped, not an error.
		state.scrollManager.scrollToBottom()
	}

	@Test
	fun `the editor showing it lends its scope and measurer, again after a new one shows it`() =
		runSkikoComposeUiTest(density = Density(1f)) {
			val state = TextEditorState(AnnotatedString(doc))
			var shown by mutableStateOf(true)
			setContent {
				if (shown) {
					BasicTextEditor(state = state, modifier = Modifier.size(300.dp, 200.dp), autoFocus = true)
				}
			}
			waitForIdle()
			waitUntil(timeoutMillis = 5_000) { state.isFocused }
			assertTrue(state.scope.isActive)
			assertTrue(state.lineOffsets.isNotEmpty(), "laid out with the editor's measurer")

			typeText("A")
			waitForIdle()
			assertEquals("ALine 0", state.textLines[0].text)

			runOnIdle { state.scrollManager.scrollToBottom() }
			waitForIdle()
			assertTrue(state.scrollState.value > 0, "an animated scroll runs on the lent scope")

			shown = false
			waitForIdle()
			assertFalse(state.scope.isActive, "the scope left with its editor")
			runOnIdle { state.setText("kept") }

			shown = true
			waitForIdle()
			assertTrue(state.scope.isActive)
			runOnIdle { state.scrollManager.scrollToTop() }
			waitForIdle()
			assertEquals(0, state.scrollState.value)
			assertEquals("kept", state.getAllText().text)
		}

	@Test
	fun `with two showing it, the one that stays keeps lending when the other leaves`() =
		runSkikoComposeUiTest(density = Density(1f)) {
			val state = TextEditorState(AnnotatedString(doc))
			var previewShown by mutableStateOf(true)
			setContent {
				Column {
					BasicTextEditor(state = state, modifier = Modifier.size(300.dp, 200.dp))
					if (previewShown) RichTextView(state = state, modifier = Modifier.size(300.dp, 100.dp))
				}
			}
			waitForIdle()

			previewShown = false
			waitForIdle()

			assertTrue(state.scope.isActive, "the editor still lends its scope")
			var ran = false
			runOnIdle { state.scope.launch { ran = true } }
			waitForIdle()
			assertTrue(ran)
			assertTrue(state.lineOffsets.isNotEmpty())
		}

	@Test
	fun `a read-only view lends them too`() = runSkikoComposeUiTest(density = Density(1f)) {
		val state = TextEditorState(AnnotatedString("hello"))
		setContent { RichTextView(state = state, modifier = Modifier.size(300.dp, 200.dp)) }
		waitForIdle()
		assertTrue(state.scope.isActive)
		assertTrue(state.lineOffsets.isNotEmpty())
	}
}
