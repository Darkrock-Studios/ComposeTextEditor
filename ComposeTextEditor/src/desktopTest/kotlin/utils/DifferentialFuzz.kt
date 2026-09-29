package utils

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.fail

/**
 * Roadmap items the editor does not meet yet. A fuzz divergence that one of these
 * explains (see [explainDivergence]) is tolerated: the editor is reset to the
 * reference's state and the script continues. Delete an item here when it lands,
 * and the fuzzer starts failing on that class of divergence.
 */
val OPEN_PARITY_ITEMS: Set<String> = setOf("1.1", "1.2", "1.3", "1.4", "1.5", "1.6", "1.7", "1.19")

/** Starting text for the Unicode fuzzers: an emoji, a combining mark, and a right-to-left word. */
const val FUZZ_START_TEXT = "seed line\nsecond line of words\n\uD83D\uDE00 e\u0301 שלום end"

/**
 * Text the fuzzer types: plain words, and every kind of multi-unit grapheme
 * cluster, plus right-to-left and CJK words.
 */
private val PIECES = listOf(
	"alpha", "beta", "word", "x", " ", "a b", "longunbrokenword",
	"é", "日本語",
	"\uD83D\uDE00", // grinning face, a surrogate pair
	"\uD83D\uDC4D\uD83C\uDFFD", // thumbs up with a skin tone modifier
	"\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", // family, a ZWJ sequence
	"\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08", // rainbow flag, ZWJ with a variation selector
	"\uD83C\uDDEF\uD83C\uDDF5", // flag of Japan, two regional indicators
	"e\u0301", // e with a combining acute
	"n\u0303o", // n with a combining tilde
	"שלום", // Hebrew
	"مرحبا", // Arabic
)

private val NAVIGATION = listOf(Left, Right, Up, Down, Home, End, CtrlHome, CtrlEnd)
private val SELECTION = listOf(ShiftLeft, ShiftRight, ShiftUp, ShiftDown, ShiftHome, ShiftEnd)
private val WORD = listOf(CtrlLeft, CtrlRight, CtrlShiftLeft, CtrlShiftRight, CtrlBackspace, CtrlDelete)

/** A seeded keystroke script over [PIECES], navigation, selection, word motion, and deletion. */
fun generateStrokeScript(seed: Long, count: Int): List<Stroke> {
	val random = Random(seed)
	return List(count) {
		when (random.nextInt(100)) {
			in 0 until 26 -> type(PIECES.random(random))
			in 26 until 30 -> Enter
			in 30 until 38 -> Backspace
			in 38 until 42 -> Delete
			in 42 until 70 -> NAVIGATION.random(random)
			in 70 until 86 -> SELECTION.random(random)
			in 86 until 96 -> WORD.random(random)
			else -> if (random.nextBoolean()) PageUp else PageDown
		}
	}
}

/** Whether deleting one of this string's newlines can give [other]. */
private fun Stroke.isVertical(): Boolean =
	this is Stroke.Press && key in setOf(Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown)

private fun Stroke.dependsOnRows(): Boolean =
	isVertical() || (this is Stroke.Press && !ctrl && (key == Key.MoveHome || key == Key.MoveEnd))

/**
 * The roadmap items that explain the editor reaching [editor] where the reference
 * reached [native], both from [before] by [stroke]. Empty means unexplained.
 */
fun explainDivergence(
	before: EditSnapshot,
	stroke: Stroke,
	native: EditSnapshot,
	editor: EditSnapshot,
): Set<String> = buildSet {
	val splitsCluster = editor.text.firstLoneSurrogate() != null ||
		!editor.text.isGraphemeBoundary(editor.caret) ||
		!editor.text.isGraphemeBoundary(editor.anchor)
	if (splitsCluster) add("1.1")
	if (stroke !is Stroke.Press) return@buildSet
	when (stroke.key) {
		Key.DirectionLeft, Key.DirectionRight -> if (stroke.ctrl) {
			add("1.5")
			if (stroke.key == Key.DirectionRight) add("1.19")
		} else {
			val collapses = !stroke.shift && before.hasSelection
			if (collapses) add("1.4")
			// The reference stepped over a cluster of more than one UTF-16 unit.
			if (!collapses && abs(native.caret - before.caret) > 1) add("1.1")
		}

		Key.Backspace, Key.Delete -> if (stroke.ctrl) {
			add("1.5")
			if (stroke.key == Key.Delete) add("1.19")
		} else if (!before.hasSelection && before.text.length - native.text.length > 1) {
			add("1.1")
		}

		Key.DirectionUp, Key.DirectionDown -> {
			val edge = if (stroke.key == Key.DirectionUp) 0 else native.text.length
			if (native.caret == edge && editor.caret == before.caret) add("1.3") else add("1.2")
		}

		Key.PageUp, Key.PageDown -> add("1.7")

		Key.MoveEnd -> if (!stroke.ctrl && native.caret != editor.caret) {
			val paragraphEnd = editor.caret == before.text.length || before.text[editor.caret] == '\n'
			val pastWrapSpaces = native.caret < editor.caret && !paragraphEnd &&
				before.text.substring(native.caret, editor.caret).isBlank()
			// One short of a mid-word wrap, or past spaces the reference leaves at a wrap.
			if (editor.caret == native.caret - 1 || pastWrapSpaces) add("1.6")
		}
	}
}

/**
 * Names the `BasicTextField` quirk, if any, behind a divergence where the editor
 * is the one that behaves natively. Each is a place where the reference rule does
 * not hold, recorded in docs/ROADMAP.md under "The reference rule".
 */
fun referenceQuirk(
	before: EditSnapshot,
	stroke: Stroke,
	native: EditSnapshot,
	editor: EditSnapshot,
): String? {
	if (stroke !is Stroke.Press || stroke.ctrl) return null
	val home = stroke.key == Key.MoveHome
	if (!home && stroke.key != Key.MoveEnd) return null
	val text = before.text
	val measuredFrom = if (home) minOf(before.anchor, before.caret) else maxOf(before.anchor, before.caret)
	return when {
		measuredFrom != before.caret -> "reference: Home and End measure from the selection edge"
		home && text.endsWith('\n') && before.caret == text.length && native.caret == text.length - 1 ->
			"reference: Home on an empty last line goes up"

		!home && native.caret < editor.caret && text.substring(native.caret, editor.caret).isBlank() &&
			(editor.caret == text.length || text[editor.caret] == '\n') ->
			"reference: End stops before a line's trailing spaces"

		else -> null
	}
}

/**
 * Replays a seeded [generateStrokeScript] through the editor and `BasicTextField`
 * from [start], comparing after every stroke.
 *
 * The reference replays the whole script first, undisturbed. The editor then
 * replays it stroke by stroke; on a divergence explained by an item in
 * [openItems] it is reset to the reference's state and continues, so one known
 * bug does not end the run. The same happens at a [referenceQuirk], and for a
 * row-dependent stroke when the two widgets wrap the text into different rows
 * (a word wider than a row breaks differently). Any other divergence fails with
 * a transcript.
 *
 * The reset cannot carry the reference's remembered goal column, so a vertical
 * move straight after a reset is tolerated too, until the next other stroke.
 *
 * To widen the check once a lane A item lands, delete it from [OPEN_PARITY_ITEMS].
 *
 * Returns how often each open item or quirk was tolerated.
 */
internal fun differentialFuzz(
	seed: Long,
	start: EditSnapshot,
	count: Int,
	width: Dp,
	openItems: Set<String> = OPEN_PARITY_ITEMS,
): Map<String, Int> {
	val script = generateStrokeScript(seed, count)
	val tolerated = mutableMapOf<String, Int>()
	differentialUiTest(initialText = start.text, width = width) {
		assertSameRows()
		val reference = replayReference(start, script)
		focusEditor()
		setEditor(start)
		var before = start
		var goalColumnLost = false
		script.forEachIndexed { index, stroke ->
			send(stroke)
			val native = reference[index]
			val actual = editorSnapshot
			if (!stroke.isVertical()) goalColumnLost = false
			if (actual != native) {
				val explained = explainDivergence(before, stroke, native, actual) intersect openItems
				val quirk = referenceQuirk(before, stroke, native, actual)
				val cause = when {
					explained.isNotEmpty() -> explained
					quirk != null -> setOf(quirk)
					goalColumnLost && stroke.isVertical() -> setOf("reset")
					stroke.dependsOnRows() && !rowsAgree() -> setOf("reference: rows wrap differently")
					else -> fail(
						"differential fuzz seed=$seed diverged at stroke[$index]=$stroke " +
							"and no open roadmap item explains it (replay with FUZZ_SEED=$seed)\n" +
							"before  $before\nnative  $native\neditor  $actual\n" +
							"recent strokes: ${script.subList(maxOf(0, index - 12), index + 1)}\n" +
							"tolerated so far: $tolerated"
					)
				}
				cause.forEach { tolerated[it] = (tolerated[it] ?: 0) + 1 }
				setEditor(native)
				goalColumnLost = stroke.isVertical()
			}
			before = native
		}
	}
	return tolerated
}
