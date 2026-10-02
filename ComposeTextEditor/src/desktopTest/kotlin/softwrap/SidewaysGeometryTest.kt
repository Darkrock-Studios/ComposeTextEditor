package softwrap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.RichSpanClick
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.rowAt
import com.darkrockstudios.texteditor.state.caretX
import utils.EditorUiTestScope
import utils.RecordingTextToolbar
import utils.assertRectEquals
import utils.assertViewFollowsSidewaysScroll
import utils.drawnHandles
import utils.editorUiTest
import utils.setBlockLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The geometry harness with wrapping off (7.41), through one fixture: each scene is
 * checked at several points of the sideways range, by [assertViewFollowsSidewaysScroll]
 * (the conversions, the caret and selection drawn, decorators, input method, stylus),
 * and by pointer input placed from the rows' own layout less the scroll, never through
 * the conversions under test: clicks, a link, touch handles, the toolbar, the magnifier.
 * With wrapping on the sideways scroll is always 0, so these are what fail when code
 * that pairs a row with a view or pointer x forgets it.
 */
@OptIn(ExperimentalTestApi::class)
class SidewaysGeometryTest {
	private val long = "word ".repeat(80).trimEnd()

	private fun sideways(
		text: String,
		textStyle: TextStyle = TextStyle.Default,
		textToolbar: RecordingTextToolbar? = null,
		onRichSpanClickEvent: ((RichSpanClick) -> Boolean)? = null,
		block: EditorUiTestScope.() -> Unit,
	) = editorUiTest(
		initialText = AnnotatedString(text),
		width = 200.dp,
		softWrap = false,
		textStyle = textStyle,
		textToolbar = textToolbar,
		onRichSpanClickEvent = onRichSpanClickEvent,
		block = block,
	)

	/** Runs [check] at the left edge of the sideways range, at its right end, and at two points between. */
	private fun EditorUiTestScope.atSidewaysScrolls(check: (scroll: Int) -> Unit) {
		val range = state.horizontalScrollState.maxValue
		assertTrue(range > 0, "precondition: the text is wider than the editor")
		for (scroll in listOf(0, range / 3, range * 2 / 3 + 7, range)) {
			scrollSideways(scroll)
			check(state.horizontalScrollState.value)
		}
	}

	private fun EditorUiTestScope.scrollSideways(to: Int) {
		test.runOnIdle { state.horizontalScrollState.scrollTo(to) }
		test.waitForIdle()
	}

	private fun EditorUiTestScope.placeCaret(position: CharLineOffset) {
		test.runOnIdle {
			state.selector.clearSelection()
			state.cursor.updatePosition(position)
		}
		test.waitForIdle()
	}

	private fun EditorUiTestScope.row(position: CharLineOffset): LineWrap = assertNotNull(state.lineOffsets.rowAt(position))

	/** The middle of [row]'s height, in the canvas. */
	private fun EditorUiTestScope.middleY(row: LineWrap): Float =
		row.offset.y - state.scrollState.value + row.textLayoutResult.multiParagraph.getLineHeight(row.virtualLineIndex) / 2f

	/** Where [position]'s glyph boundary is drawn in the canvas, from its row's layout and the sideways scroll. */
	private fun EditorUiTestScope.drawnX(position: CharLineOffset): Float =
		row(position).run { offset.x + caretX(position.char) } - state.horizontalScrollState.value

	@Test
	fun `the caret follows the sideways scroll on long, short and right-to-left lines`() =
		sideways("$long\nshort\n${"שלום עולם ".repeat(30).trimEnd()}\n$long") {
			for (caret in listOf(CharLineOffset(0, 10), CharLineOffset(0, 230), CharLineOffset(1, 3), CharLineOffset(2, 150), CharLineOffset(3, 399))) {
				placeCaret(caret)
				atSidewaysScrolls { assertViewFollowsSidewaysScroll() }
			}
		}

	@Test
	fun `a selection over blocks follows the sideways scroll`() = sideways("") {
		state.setBlockLines("$long\n``` $long\n- $long\n> $long")
		test.waitForIdle()
		test.runOnIdle { state.selector.updateSelection(CharLineOffset(0, 120), CharLineOffset(3, 200)) }
		atSidewaysScrolls { assertViewFollowsSidewaysScroll() }
	}

	@Test
	fun `a click lands on the character drawn under it`() = sideways("$long\n$long") {
		atSidewaysScrolls { scroll ->
			for (target in inView(line = 1)) {
				val x = drawnX(target)
				// Just right of the boundary, so it is the nearest one.
				clickAt(canvasToNode(Offset(x + 1f, middleY(row(target)))))
				assertEquals(target, state.cursorPosition, "a click at canvas x ${x + 1f}, scrolled $scroll")
				assertEquals(scroll, state.horizontalScrollState.value, "the clicked caret was in view")
			}
		}
	}

	/** Two positions on [line] drawn well inside the canvas, clear of the caret's room at its right edge. */
	private fun EditorUiTestScope.inView(line: Int): List<CharLineOffset> {
		val positions = (0..state.textLines[line].length).map { CharLineOffset(line, it) }
			.filter { drawnX(it) in 20f..state.viewportSize.width - 30f }
		assertTrue(positions.size > 4, "precondition: the line is in view")
		return listOf(positions[1], positions[positions.size - 2])
	}

	@Test
	fun `a right-to-left line is hit where it is drawn`() = sideways(
		"שלום עולם ".repeat(40).trimEnd(),
		textStyle = TextStyle(textDirection = TextDirection.Content),
	) {
		atSidewaysScrolls { scroll ->
			for (target in inView(line = 0)) {
				// Just left of the boundary, inside the glyph after it in right-to-left order.
				val x = drawnX(target) - 1f
				clickAt(canvasToNode(Offset(x, middleY(row(target)))))
				assertEquals(target, state.cursorPosition, "a click at canvas x $x, scrolled $scroll")
			}
		}
	}

	@Test
	fun `a click on a link scrolled into view is the link's`() {
		val clicks = mutableListOf<RichSpanClick>()
		sideways("$long\nshort", onRichSpanClickEvent = { clicks += it; true }) {
			test.runOnIdle { state.addRichSpan(300, 304, LinkSpanStyle("https://example.com")) }
			val start = CharLineOffset(0, 300)
			val left = row(start).caretX(300)
			val right = row(start).caretX(304)
			scrollSideways((left - 50f).toInt())

			clickAt(canvasToNode(Offset(drawnX(start) + (right - left) / 2f, middleY(row(start)))))

			assertEquals(1, clicks.size, "the click was on the link")
		}
	}

	@Test
	fun `touch handles and the toolbar move with the sideways scroll`() {
		val toolbar = RecordingTextToolbar()
		sideways("$long\n$long", textToolbar = toolbar) {
			val word = CharLineOffset(1, 106)
			val scroll = row(word).caretX(word.char).toInt() - 80
			scrollSideways(scroll)
			longPressAt(canvasToNode(Offset(drawnX(word) + 2f, middleY(row(word)))))
			touch { up() }
			val handles = drawnHandles().map { it.bounds }
			val menu = assertNotNull(toolbar.menu, "the toolbar is up").rect
			assertEquals(2, handles.size)
			assertViewFollowsSidewaysScroll()

			for (by in listOf(-13, 9)) {
				scrollSideways(scroll + by)
				val moved = drawnHandles().map { it.bounds }
				assertEquals(2, moved.size, "both handles are still in view")
				handles.zip(moved).forEach { (before, after) -> assertRectEquals(before.translate(-by.toFloat(), 0f), after) }
				assertRectEquals(menu.translate(-by.toFloat(), 0f), assertNotNull(toolbar.menu, "still up").rect, message = "the toolbar")
				assertViewFollowsSidewaysScroll()
			}
		}
	}

	@Test
	fun `a selection held past the right edge ends in view as it scrolls`() = sideways("$long\n$long") {
		test.mainClock.autoAdvance = false
		val start = CharLineOffset(0, 2)
		val y = middleY(row(start))
		mouse {
			moveTo(canvasToNode(Offset(drawnX(start), y)))
			press()
			moveTo(canvasToNode(Offset(state.viewportSize.width + 40f, y)))
		}

		repeat(4) {
			test.mainClock.advanceTimeBy(250)
			waitForIdle()
			val end = assertNotNull(state.selector.selection).end
			val x = drawnX(end)
			assertTrue(x in 0f..state.viewportSize.width, "the dragged end at canvas x $x is in view")
		}
		assertTrue(state.horizontalScrollState.value > 0, "the held drag scrolled sideways")
		mouse(fresh = false) { release() }
	}

	@Test
	fun `the magnifier stays on the row's text where it is drawn`() = sideways("$long\n${"word ".repeat(12)}end") {
		val endOfShort = CharLineOffset(1, state.textLines[1].length)
		scrollSideways(row(endOfShort).caretX(endOfShort.char).toInt() - 60)
		val lineEnd = drawnX(endOfShort)
		val word = CharLineOffset(1, state.textLines[1].length - 8)
		longPressAt(canvasToNode(Offset(drawnX(word) + 2f, middleY(row(word)))))
		touch { up() }
		val grab = handleCenter(isStart = false)

		touch {
			down(grab)
			moveTo(Offset(grab.x + 80f, grab.y))
		}

		val center = assertNotNull(state.selector.magnifierCenter)
		assertEquals(lineEnd, center.x, 1f, "the magnifier stops at the line's end, at canvas x $lineEnd")
		touch { up() }
	}
}
