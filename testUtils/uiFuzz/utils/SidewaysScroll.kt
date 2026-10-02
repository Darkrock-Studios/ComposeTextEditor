package utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.darkrockstudios.texteditor.cursor.CursorMetrics
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.fail

/*
 * Wrapping off, view coordinates are content coordinates less the sideways scroll.
 * With wrapping on that scroll is always 0, so code that pairs a row's content x with a
 * view or pointer x and forgets it passes every wrapped test. The sideways storms keep
 * the scroll moving and check, after every op, that what the view answers follows it.
 */

/** Start text for the sideways storms: lines wider than a narrow editor, so there is a sideways range from the first op. */
const val SIDEWAYS_FUZZ_START_TEXT =
	"seed line with words enough to run well past the right edge of the editor\n" +
		"second line\n" +
		"third line, long again, so that the view has somewhere to scroll sideways to"

/** A storm's sideways scrolls, a stream of their own so they do not follow the script's choices. */
fun sidewaysScrolls(seed: Long): Random = Random(seed xor SIDEWAYS_SALT)

/** Scrolls sideways to a seeded point of the range, as a user might between keystrokes. */
fun FuzzUiDriver.scrollSidewaysAtRandom(random: Random) {
	runOnIdle {
		val sideways = state.horizontalScrollState
		if (sideways.maxValue > 0) sideways.scrollTo(random.nextInt(sideways.maxValue + 1))
	}
	waitForIdle()
}

/** [assertFollowsSidewaysScroll] on the UI thread. */
fun FuzzUiDriver.assertStateFollowsSidewaysScroll() {
	runOnIdle { state.assertFollowsSidewaysScroll() }
	waitForIdle()
}

/**
 * Runs [script] as [runFuzzScript] does, with [checkCheapInvariants] after each op. With
 * [sideways], the view is scrolled sideways to a seeded point before the first op and
 * after each, and [followsScroll] checks it after each.
 */
fun FuzzUiDriver.runUiFuzzScript(
	seed: Long,
	script: List<FuzzOp>,
	sideways: Boolean = false,
	followsScroll: () -> Unit = { assertStateFollowsSidewaysScroll() },
) {
	val scrolls = sidewaysScrolls(seed)
	if (sideways) scrollSidewaysAtRandom(scrolls)
	try {
		runFuzzScript(seed, script) { op ->
			applyFuzzOpUi(op)
			checkCheapInvariants(state)
			if (sideways) {
				scrollSidewaysAtRandom(scrolls)
				followsScroll()
			}
		}
	} catch (failure: AssertionError) {
		if (!sideways) throw failure
		throw AssertionError("sideways storm, wrapping off: ${failure.message}", failure)
	}
}

/**
 * One read of the view at a sideways [scroll]: [answer]s given in content x, so they are
 * the same at any scroll. [samples] are content xs around the caret, clear of every edge
 * and centre of the glyphs on the caret's line, and [y] the middle of the caret's row in
 * view coordinates.
 */
class SidewaysRead internal constructor(
	val scroll: Float,
	val samples: List<Float>,
	val y: Float,
) {
	internal val answers = linkedMapOf<String, Any?>()

	/** Content x [x] in the view's coordinates at this scroll. */
	fun view(x: Float): Float = x - scroll

	/** View x [x] in content coordinates. */
	fun content(x: Float): Float = x + scroll

	fun content(rect: Rect): Rect = rect.translate(scroll, 0f)

	fun answer(name: String, value: Any?) {
		answers[name] = value
	}
}

/**
 * Asserts that what the view answers follows the sideways scroll: read at the current
 * scroll and at another, and moved back into content x, the caret's metrics, the caret
 * position [TextEditorState.getPositionForOffset] gives, what points along the caret's
 * row hit, and whatever [read] adds agree. Nothing to check without a sideways range.
 * The scroll is left where it was.
 */
fun TextEditorState.assertFollowsSidewaysScroll(read: SidewaysRead.() -> Unit = {}) {
	val sideways = horizontalScrollState
	val range = sideways.maxValue
	if (range == 0) return
	val start = sideways.value
	val other = if (start > range / 2) start / 3 else range - (range - start) / 3
	val caret = calculateCursorPosition()
	val caretX = caret.position.x + start
	// Off the caret's glyph boundary and stepping off whole pixels, then moved clear of the
	// line's glyph edges and centres, where an answer flips. A probe goes to view x and back
	// to content x through float additions that round differently at the two scrolls, so one
	// within a float step of an edge or centre answers differently for no fault of the
	// editor's: a storm's landed 0.000015 px from an l's centre in the macOS and Windows
	// fonts. The answers are still compared exactly.
	val flips = answerFlips()
	val samples = (-SAMPLES..SAMPLES).map { caretX + SAMPLE_OFFSET + it * SAMPLE_STEP }
		.filter { it >= 0f }
		.map { clearOf(it, flips) }
	val y = caret.position.y + caret.height / 2f

	fun readAt(scroll: Int): Map<String, Any?> {
		sideways.scrollTo(scroll)
		val at = SidewaysRead(sideways.value.toFloat(), samples, y)
		at.answer("caret metrics", calculateCursorPosition().inContent(at))
		at.answer("getPositionForOffset", getPositionForOffset(cursorPosition).inContent(at))
		at.answer("getOffsetAtPosition", samples.map { getOffsetAtPosition(Offset(at.view(it), y)) })
		at.read()
		return at.answers
	}

	val (atStart, atOther) = try {
		readAt(start) to readAt(other)
	} finally {
		sideways.scrollTo(start)
	}
	for ((name, value) in atStart) {
		val theirs = atOther[name]
		if (!sameAnswer(value, theirs)) {
			fail(
				"$name does not follow the sideways scroll: in content x it is $value at scroll $start " +
					"and $theirs at scroll $other (range $range)"
			)
		}
	}
}

/**
 * The content xs on the caret's line where what a point hits changes: each glyph's left
 * edge, centre and right edge, on every row of the line.
 */
private fun TextEditorState.answerFlips(): FloatArray {
	val line = cursorPosition.line
	val xs = ArrayList<Float>()
	for (row in lineOffsets) {
		if (row.line != line) continue
		val layout = row.textLayoutResult
		for (index in 0 until layout.layoutInput.text.length) {
			val box = layout.getBoundingBox(index)
			xs += row.offset.x + box.left
			xs += row.offset.x + (box.left + box.right) / 2f
			xs += row.offset.x + box.right
		}
	}
	return xs.toFloatArray()
}

/** [x], or the nearest point right of it at least [SAMPLE_CLEARANCE] from each of [flips]. */
private fun clearOf(x: Float, flips: FloatArray): Float {
	var at = x
	// Bounded, for a line of glyphs too narrow to stand clear of.
	repeat(SAMPLE_NUDGES) {
		if (flips.none { abs(it - at) < SAMPLE_CLEARANCE }) return at
		at += SAMPLE_NUDGE
	}
	return at
}

private fun CursorMetrics.inContent(read: SidewaysRead): List<Float> =
	listOf(read.content(position.x), position.y, height, lineTop, lineBaseline, lineBottom)

private fun sameAnswer(a: Any?, b: Any?): Boolean = when {
	a is Float && b is Float -> abs(a - b) <= TOLERANCE
	a is Offset && b is Offset -> sameAnswer(a.x, b.x) && sameAnswer(a.y, b.y)
	a is Rect && b is Rect -> sameAnswer(a.left, b.left) && sameAnswer(a.top, b.top) &&
		sameAnswer(a.right, b.right) && sameAnswer(a.bottom, b.bottom)

	a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { sameAnswer(a[it], b[it]) }
	else -> a == b
}

private const val TOLERANCE = 0.05f
private const val SAMPLES = 10
/** A sample's distance from the next: off whole pixels, so samples do not keep to glyph edges. */
internal const val SAMPLE_STEP = 23.13f

/** The middle sample's distance right of the caret, which stands on a glyph's edge. */
internal const val SAMPLE_OFFSET = 0.37f

/**
 * How near a glyph's edge or centre a sample may be: hundreds of float steps at these
 * magnitudes (one is 0.00006 px at 1,000 px), and far under a glyph's width.
 */
private const val SAMPLE_CLEARANCE = 0.02f
private const val SAMPLE_NUDGE = 0.07f
private const val SAMPLE_NUDGES = 32
private const val SIDEWAYS_SALT = 0x51DE_3A75L
