package scrollmanager

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.effectiveHeight
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.editorUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How far the editor scrolls, and what happens past its ends. */
@OptIn(ExperimentalTestApi::class)
class ScrollRangeE2eTest {

	private val longDocument = AnnotatedString((0 until 100).joinToString("\n") { "line $it" })

	private val TextEditorState.contentHeight: Float
		get() = lineOffsets.last().let { it.offset.y + it.effectiveHeight }

	@Test
	fun `a document that fits does not scroll`() = editorUiTest(
		initialText = AnnotatedString("one line"),
	) {
		assertEquals(state.scrollState.minValue, state.scrollState.maxValue)
	}

	@Test
	fun `a document that fits with its padding does not scroll`() = editorUiTest(
		initialText = AnnotatedString("one line"),
		contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp),
	) {
		assertEquals(state.scrollState.minValue, state.scrollState.maxValue)
		assertEquals(-20, state.scrollState.value)
	}

	@Test
	fun `a long document scrolls to its last line and bottom padding, no further`() = editorUiTest(
		initialText = longDocument,
		contentPadding = PaddingValues(bottom = 20.dp),
	) {
		val expected = kotlin.math.ceil(state.contentHeight).toInt() + 20 - state.viewportSize.height.toInt()
		assertTrue(expected > 0, "precondition: the document is taller than the viewport")

		assertEquals(expected, state.scrollState.maxValue)
	}

	@Test
	fun `the scroll state reports which ways it can scroll`() = editorUiTest(initialText = longDocument) {
		assertTrue(state.scrollState.canScrollForward)
		assertEquals(false, state.scrollState.canScrollBackward, "at the top")

		state.scrollState.scrollTo(state.scrollState.maxValue)
		assertEquals(false, state.scrollState.canScrollForward, "at the bottom")
		assertTrue(state.scrollState.canScrollBackward)
	}

	@Test
	fun `pulling past the top feeds the platform overscroll effect`() = runSkikoComposeUiTest {
		val effect = RecordingOverscroll()
		setContent {
			CompositionLocalProvider(LocalOverscrollFactory provides SingleOverscroll(effect)) {
				BasicTextEditor(
					state = rememberTextEditorState(initialText = longDocument),
					modifier = Modifier.size(300.dp, 200.dp).testTag("editor"),
				)
			}
		}
		waitForIdle()

		onNodeWithTag("editor").performTouchInput {
			down(Offset(50f, 40f))
			moveTo(Offset(50f, 120f))
			moveTo(Offset(50f, 160f))
			up()
		}
		waitForIdle()

		assertTrue(effect.overscrolled > 0f, "at the top, the pull down is left for the overscroll effect")
	}

	private class RecordingOverscroll : OverscrollEffect {
		var overscrolled = 0f

		override fun applyToScroll(
			delta: Offset,
			source: NestedScrollSource,
			performScroll: (Offset) -> Offset,
		): Offset {
			val consumed = performScroll(delta)
			overscrolled += kotlin.math.abs(delta.y - consumed.y)
			return consumed
		}

		override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
			performFling(velocity)
		}

		override val isInProgress: Boolean get() = false
	}

	private class SingleOverscroll(private val effect: OverscrollEffect) : OverscrollFactory {
		override fun createOverscrollEffect(): OverscrollEffect = effect
		override fun hashCode(): Int = effect.hashCode()
		override fun equals(other: Any?): Boolean = other is SingleOverscroll && other.effect === effect
	}
}
