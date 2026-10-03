package scrollmanager

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorScrollManager
import com.darkrockstudios.texteditor.state.TextEditorScrollState
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TextEditorScrollManagerTest {
	private val testScope = TestScope()

	private fun createMockScrollState(): TextEditorScrollState {
		return mockk(relaxed = true) {
			every { value } returns 0
			every { maxValue } returns 1000
		}
	}

	private fun createMockTextLayoutResult(lineHeight: Float = 20f): TextLayoutResult {
		return mockk {
			every { size } returns IntSize(100, lineHeight.toInt())
			every { multiParagraph } returns mockk {
				every { getLineHeight(any()) } returns lineHeight
			}
		}
	}

	private fun createTestLineWraps(count: Int, lineHeight: Float = 20f): List<LineWrap> {
		val result = mutableListOf<LineWrap>()
		var yOffset = 0f

		repeat(count) { index ->
			result.add(
				LineWrap(
					line = index,
					wrapStartsAtIndex = 0,
					virtualLength = 10,
					virtualLineIndex = 0,
					offset = Offset(0f, yOffset),
					textLayoutResult = createMockTextLayoutResult(lineHeight),
					richSpans = emptyList()
				)
			)
			yOffset += lineHeight
		}
		return result
	}

	@Test
	fun `test scroll to cursor keeps cursor visible when in viewport`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val cursorPosition = CharLineOffset(2, 5)
		val lineWraps = createTestLineWraps(5)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 60f) }, // Shows 3 lines
			getCursorPosition = { cursorPosition },
			getLineOffsets = { lineWraps }
		)
		manager.updateContentHeight(100)

		// Position cursor in middle of viewport (line 2, y=40)
		every { scrollState.value } returns 20

		manager.scrollToCursor()
		testScope.advanceUntilIdle()

		// Verify no scroll animation was triggered
		coVerify(exactly = 0) { scrollState.animateScrollTo(any()) }
	}

	@Test
	fun `test scroll to cursor scrolls up when cursor above viewport`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val cursorPosition = CharLineOffset(1, 5)
		val lineWraps = createTestLineWraps(5)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 60f) }, // Shows 3 lines
			getCursorPosition = { cursorPosition },
			getLineOffsets = { lineWraps }
		)
		manager.updateContentHeight(100)

		// Position viewport below cursor
		every { scrollState.value } returns 40

		val scrollSlot = slot<Int>()
		coEvery { scrollState.animateScrollTo(capture(scrollSlot)) } returns Unit

		manager.scrollToCursor()
		testScope.advanceUntilIdle()

		// Verify scroll was triggered to proper position
		assertTrue(scrollSlot.isCaptured)
		assertEquals(20, scrollSlot.captured) // Line 1 starts at y=20
	}

	@Test
	fun `test scroll to cursor scrolls down when cursor below viewport`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val cursorPosition = CharLineOffset(4, 5)
		val lineWraps = createTestLineWraps(5)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 60f) }, // Shows 3 lines
			getCursorPosition = { cursorPosition },
			getLineOffsets = { lineWraps }
		)
		manager.updateContentHeight(100)

		// Position viewport above cursor
		coEvery { scrollState.value } returns 0

		val scrollSlot = slot<Int>()
		coEvery { scrollState.animateScrollTo(capture(scrollSlot)) } returns Unit

		manager.scrollToCursor()
		testScope.advanceUntilIdle()

		println("Cursor position line: ${cursorPosition.line}")
		println("Lines size: ${lineWraps.size}")
		println("Last line y: ${lineWraps.last().offset.y}")
		println("Cursor top: ${manager.calculateOffsetYPosition(cursorPosition)}")
		println("Cursor height: ${manager.calculateLineHeight(cursorPosition)}")
		println("Viewport height: ${manager.viewportHeight}")
		println("Total content height: ${manager.totalContentHeight}")

		// Verify scroll was triggered to proper position
		assertTrue(scrollSlot.isCaptured)
		assertEquals(
			40,
			scrollSlot.captured
		) // Line 4 ends at y=100, minus viewport height (60)
	}

	@Test
	fun `test scroll to cursor scrolls down when cursor below viewport and adjusts for overscroll`() =
		testScope.runTest {
			val scrollState = createMockScrollState()
			val cursorPosition = CharLineOffset(4, 5)
			val lineWraps = createTestLineWraps(5)

			val manager = TextEditorScrollManager(
				scope = testScope,
				scrollState = scrollState,
				getLines = { List(5) { AnnotatedString("Line $it") } },
				getViewportSize = { Size(100f, 60f) }, // Shows 3 lines
				getCursorPosition = { cursorPosition },
				getLineOffsets = { lineWraps }
			)
			manager.updateContentHeight(100)

			// Position viewport at top
			coEvery { scrollState.value } returns 0

			val scrollSlot = slot<Int>()
			coEvery { scrollState.animateScrollTo(capture(scrollSlot)) } returns Unit

			manager.scrollToCursor()
			testScope.advanceUntilIdle()

			// cursorTop(80) + cursorHeight(20) - viewportHeight(60) = 40, which is also
			// maxScroll = totalContentHeight(100) - viewportHeight(60)
			assertTrue(scrollSlot.isCaptured)
			assertEquals(40, scrollSlot.captured)
		}

	@Test
	fun `test isOffsetVisible correctly identifies visible cursor`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(5)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 60f) }, // Shows 3 lines
			getCursorPosition = { CharLineOffset(2, 5) },
			getLineOffsets = { lineWraps }
		)
		manager.updateContentHeight(100)

		// Test cursor in middle of viewport
		every { scrollState.value } returns 20
		assertTrue(manager.isOffsetVisible(CharLineOffset(2, 5)))

		// Test cursor above viewport
		every { scrollState.value } returns 60
		assertFalse(manager.isOffsetVisible(CharLineOffset(2, 5)))

		// Test cursor below viewport
		every { scrollState.value } returns 0
		assertFalse(manager.isOffsetVisible(CharLineOffset(4, 5)))
	}

	@Test
	fun `test scroll to bottom`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(5)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 60f) },
			getCursorPosition = { CharLineOffset(0, 0) },
			getLineOffsets = { lineWraps }
		)
		manager.updateContentHeight(100)

		val scrollSlot = slot<Int>()
		coEvery { scrollState.animateScrollTo(capture(scrollSlot)) } returns Unit

		manager.scrollToBottom()
		testScope.advanceUntilIdle()

		assertTrue(scrollSlot.isCaptured)
		assertEquals(40, scrollSlot.captured) // Total height (100) minus viewport height (60)
	}

	@Test
	fun `test scroll to top`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(5)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 60f) },
			getCursorPosition = { CharLineOffset(0, 0) },
			getLineOffsets = { lineWraps }
		)
		manager.updateContentHeight(100)

		val scrollSlot = slot<Int>()
		coEvery { scrollState.animateScrollTo(capture(scrollSlot)) } returns Unit

		manager.scrollToTop()
		testScope.advanceUntilIdle()

		assertTrue(scrollSlot.isCaptured)
		assertEquals(0, scrollSlot.captured)
	}

	@Test
	fun `test calculate offset y position`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(5)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 60f) },
			getCursorPosition = { CharLineOffset(0, 0) },
			getLineOffsets = { lineWraps }
		)

		assertEquals(0f, manager.calculateOffsetYPosition(CharLineOffset(0, 5)))
		assertEquals(20f, manager.calculateOffsetYPosition(CharLineOffset(1, 5)))
		assertEquals(40f, manager.calculateOffsetYPosition(CharLineOffset(2, 5)))
	}

	@Test
	fun `test offsetAtYPosition resolves the wrap containing the given y`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(5)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 60f) },
			getCursorPosition = { CharLineOffset(0, 0) },
			getLineOffsets = { lineWraps }
		)

		// Exact tops
		assertEquals(CharLineOffset(0, 0), manager.offsetAtYPosition(0f))
		assertEquals(CharLineOffset(2, 0), manager.offsetAtYPosition(40f))
		// Inside a wrapped line resolves to the wrap that started before it
		assertEquals(CharLineOffset(1, 0), manager.offsetAtYPosition(35f))
		// Past the end clamps to the last wrap
		assertEquals(CharLineOffset(4, 0), manager.offsetAtYPosition(1000f))
	}

	@Test
	fun `test firstVisibleOffset reflects current scroll position`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(5)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 60f) },
			getCursorPosition = { CharLineOffset(0, 0) },
			getLineOffsets = { lineWraps }
		)

		every { scrollState.value } returns 0
		assertEquals(CharLineOffset(0, 0), manager.firstVisibleOffset)

		every { scrollState.value } returns 40
		assertEquals(CharLineOffset(2, 0), manager.firstVisibleOffset)
	}

	@Test
	fun `test scrollToPosition with top aligns offset to viewport top`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(5)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 60f) }, // Shows 3 lines
			getCursorPosition = { CharLineOffset(0, 0) },
			getLineOffsets = { lineWraps }
		)
		manager.updateContentHeight(100)

		every { scrollState.value } returns 0
		every { scrollState.minValue } returns 0

		val scrollSlot = slot<Int>()
		coEvery { scrollState.animateScrollTo(capture(scrollSlot)) } returns Unit

		// Line 1 sits at y=20; top-aligning should scroll there exactly (not just into view).
		manager.scrollToPosition(CharLineOffset(1, 0), top = true)
		testScope.advanceUntilIdle()

		assertTrue(scrollSlot.isCaptured)
		assertEquals(20, scrollSlot.captured)
	}

	@Test
	fun `test scrollToPosition top with animated false scrolls without animation`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(5)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 60f) }, // Shows 3 lines
			getCursorPosition = { CharLineOffset(0, 0) },
			getLineOffsets = { lineWraps }
		)
		manager.updateContentHeight(100)

		every { scrollState.value } returns 0
		every { scrollState.minValue } returns 0

		val scrollSlot = slot<Int>()
		every { scrollState.scrollTo(capture(scrollSlot)) } returns Unit

		manager.scrollToPosition(CharLineOffset(1, 0), top = true, animated = false)
		testScope.advanceUntilIdle()

		// Sync path must scroll instantly, not enqueue a spring animation.
		assertTrue(scrollSlot.isCaptured)
		assertEquals(20, scrollSlot.captured)
		coVerify(exactly = 0) { scrollState.animateScrollTo(any()) }
	}

	@Test
	fun `test calculate line height`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(5)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 60f) },
			getCursorPosition = { CharLineOffset(0, 0) },
			getLineOffsets = { lineWraps }
		)

		assertEquals(20, manager.calculateLineHeight(CharLineOffset(0, 5)))
		assertEquals(20, manager.calculateLineHeight(CharLineOffset(1, 5)))
		assertEquals(20, manager.calculateLineHeight(CharLineOffset(2, 5)))
	}

	@Test
	fun `test last line height is measured not guessed`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(5, lineHeight = 55f)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 165f) },
			getCursorPosition = { CharLineOffset(4, 5) },
			getLineOffsets = { lineWraps }
		)

		assertEquals(55, manager.calculateLineHeight(CharLineOffset(4, 5)))
	}

	@Test
	fun `test cursor clipped by the viewport bottom is not reported visible`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(5, lineHeight = 55f)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 165f) }, // Shows 3 lines
			getCursorPosition = { CharLineOffset(4, 5) },
			getLineOffsets = { lineWraps }
		)
		manager.updateContentHeight(275)
		manager.bottomContentPaddingPx = 20

		// Last line spans y=220..275, viewport bottom lands mid-line at y=250.
		every { scrollState.value } returns 85
		assertFalse(manager.isOffsetVisible(CharLineOffset(4, 5)))

		val scrollSlot = slot<Int>()
		coEvery { scrollState.animateScrollTo(capture(scrollSlot)) } returns Unit

		manager.ensureCursorVisible()
		testScope.advanceUntilIdle()

		// cursorTop(220) + cursorHeight(55) - viewportHeight(165)
		assertTrue(scrollSlot.isCaptured)
		assertEquals(110, scrollSlot.captured)
	}

	@Test
	fun `test a caret under the covered bottom is off screen and scrolls above it`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(10)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(10) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 100f) }, // Shows 5 lines
			getCursorPosition = { CharLineOffset(3, 5) },
			getLineOffsets = { lineWraps }
		)
		manager.updateContentHeight(200)
		// A keyboard over the bottom two rows leaves three uncovered.
		manager.obscuredBottomPx = 40

		// Line 3 spans y=60..80: inside the viewport, but under the keyboard.
		every { scrollState.value } returns 0
		assertFalse(manager.isOffsetVisible(CharLineOffset(3, 5)))

		val scrollSlot = slot<Int>()
		coEvery { scrollState.animateScrollTo(capture(scrollSlot)) } returns Unit

		manager.ensureCursorVisible()
		testScope.advanceUntilIdle()

		// cursorTop(60) + cursorHeight(20) - uncovered height(60)
		assertTrue(scrollSlot.isCaptured)
		assertEquals(20, scrollSlot.captured)
	}

	@Test
	fun `test the covered bottom extends the scroll range so the last line can clear it`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(10)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(10) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 100f) },
			getCursorPosition = { CharLineOffset(9, 5) },
			getLineOffsets = { lineWraps }
		)
		manager.updateContentHeight(200)
		manager.obscuredBottomPx = 40

		// contentHeight(200) + covered(40) - viewportHeight(100)
		verify { scrollState.maxValue = 140 }

		every { scrollState.value } returns 0
		every { scrollState.maxValue } returns 140
		val scrollSlot = slot<Int>()
		coEvery { scrollState.animateScrollTo(capture(scrollSlot)) } returns Unit

		manager.ensureCursorVisible()
		testScope.advanceUntilIdle()

		// The last line (y=180..200) ends at the keyboard's top: 200 - 60.
		assertTrue(scrollSlot.isCaptured)
		assertEquals(140, scrollSlot.captured)
	}

	@Test
	fun `test a covered bottom that leaves no room for the caret row is ignored`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(10)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(10) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 100f) },
			getCursorPosition = { CharLineOffset(3, 5) },
			getLineOffsets = { lineWraps }
		)
		manager.updateContentHeight(200)
		// Ten pixels uncovered cannot hold a 20 pixel row: the platform must move the
		// editor instead, so the caret keeps the whole viewport.
		manager.obscuredBottomPx = 90

		every { scrollState.value } returns 0
		assertTrue(manager.isOffsetVisible(CharLineOffset(3, 5)))
	}

	@Test
	fun `test offset missing from a stale layout uses the nearest wrap above`() = testScope.runTest {
		val scrollState = createMockScrollState()
		val lineWraps = createTestLineWraps(3, lineHeight = 30f)

		val manager = TextEditorScrollManager(
			scope = testScope,
			scrollState = scrollState,
			getLines = { List(5) { AnnotatedString("Line $it") } },
			getViewportSize = { Size(100f, 200f) },
			getCursorPosition = { CharLineOffset(4, 0) },
			getLineOffsets = { lineWraps }
		)

		assertEquals(60f, manager.calculateOffsetYPosition(CharLineOffset(4, 0)))
		assertEquals(30, manager.calculateLineHeight(CharLineOffset(4, 0)))
	}
}