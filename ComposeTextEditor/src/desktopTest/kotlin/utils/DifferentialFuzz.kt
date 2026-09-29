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
val OPEN_PARITY_ITEMS: Set<String> = setOf("1.5", "1.6", "7.5")

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

private fun Stroke.isVertical(): Boolean =
	this is Stroke.Press && key in setOf(Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown)

private fun Stroke.isPage(): Boolean = this is Stroke.Press && (key == Key.PageUp || key == Key.PageDown)

private fun Stroke.isTyping(): Boolean = this is Stroke.Type || (this is Stroke.Press && key == Key.Enter && !ctrl)

private fun Stroke.dependsOnRows(): Boolean =
	isVertical() || (this is Stroke.Press && !ctrl && (key == Key.MoveHome || key == Key.MoveEnd))

/**
 * The roadmap items that explain the editor reaching [editor] where the reference
 * reached [native], both from [before] by [stroke]. Empty means unexplained.
 * [rows] are the flat offsets where the editor's visual rows start.
 */
fun explainDivergence(
	before: EditSnapshot,
	stroke: Stroke,
	native: EditSnapshot,
	editor: EditSnapshot,
	rows: List<Int> = emptyList(),
): Set<String> = buildSet {
	val splitsCluster = editor.text.firstLoneSurrogate() != null ||
		!editor.text.isGraphemeBoundary(editor.caret) ||
		!editor.text.isGraphemeBoundary(editor.anchor)
	if (splitsCluster) add("1.1")
	if (stroke !is Stroke.Press) return@buildSet
	when (stroke.key) {
		Key.DirectionLeft, Key.DirectionRight -> if (stroke.ctrl) {
			if (stroke.key == Key.DirectionLeft || crossesWordBreakText(before, native, editor)) add("1.5")
		} else {
			val collapses = !stroke.shift && before.hasSelection
			if (collapses) add("1.4")
			// The reference stepped over a cluster of more than one UTF-16 unit.
			if (!collapses && abs(native.caret - before.caret) > 1) add("1.1")
		}

		Key.Backspace, Key.Delete -> if (stroke.ctrl) {
			if (stroke.key == Key.Backspace || crossesWordBreakText(before, native, editor)) add("1.5")
		} else if (!before.hasSelection && before.text.length - native.text.length > 1) {
			add("1.1")
		}

		Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown -> {
			if (!stroke.isPage()) {
				val edge = if (stroke.key == Key.DirectionUp) 0 else native.text.length
				if (native.caret == edge && editor.caret == before.caret) add("1.3") else add("1.2")
			}
			// One cluster short of a wrap, where only affinity can hold the caret on the upper row.
			val text = before.text
			val atWrap = native.caret in rows && native.caret > 0 && text[native.caret - 1] != '\n'
			if (atWrap && text.isOneGrapheme(editor.caret, native.caret)) add("1.6")
			// The caret's x, and the row edge a far goal x snaps to, depend on direction.
			if (listOf(before.caret, native.caret, editor.caret).any { text.paragraphHasRightToLeft(it) }) {
				add("7.5")
			}
		}

		Key.MoveEnd -> if (!stroke.ctrl && native.caret != editor.caret) {
			val paragraphEnd = editor.caret == before.text.length || before.text[editor.caret] == '\n'
			val pastWrapSpaces = native.caret < editor.caret && !paragraphEnd &&
				before.text.substring(native.caret, editor.caret).isBlank()
			// One cluster short of a mid-word wrap, or past spaces the reference leaves at a wrap.
			if (before.text.isOneGrapheme(editor.caret, native.caret) || pastWrapSpaces) add("1.6")
		}
	}
}

private fun DifferentialScope.sameRow(a: EditSnapshot, b: EditSnapshot): Boolean {
	val rows = editorRows()
	return rows.indexOfLast { it <= a.caret } == rows.indexOfLast { it <= b.caret }
}

/**
 * Whether a forward word stroke passed text where the editor's word rule and the
 * reference's word break iterator can disagree (1.5): anything but letters and digits
 * below the CJK blocks, spaces and line breaks.
 */
private fun crossesWordBreakText(before: EditSnapshot, native: EditSnapshot, editor: EditSnapshot): Boolean {
	val deleted = before.text.length - minOf(native.text.length, editor.text.length)
	val from = minOf(before.caret, native.caret, editor.caret)
	val to = minOf(before.text.length, maxOf(native.caret, editor.caret, before.caret + deleted) + 1)
	return before.text.substring(from, to).any {
		!(it == ' ' || it == '\n' || (it.isLetterOrDigit() && it < '\u2E80'))
	}
}

private fun String.paragraphHasRightToLeft(index: Int): Boolean {
	val start = lastIndexOf('\n', index - 1) + 1
	val end = indexOf('\n', index).let { if (it < 0) length else it }
	return substring(start, end).any {
		val direction = Character.getDirectionality(it)
		direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
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
	rows: List<Int> = emptyList(),
): String? {
	if (stroke !is Stroke.Press || stroke.ctrl) return null
	val text = before.text
	if (stroke.isPage()) {
		// A caret on the first wrap offset still draws on the first row (the reference's affinity).
		val onEdgeRow = if (stroke.key == Key.PageUp) {
			editor.caret == 0 && native.caret <= (rows.getOrNull(1) ?: text.length)
		} else {
			editor.caret == text.length && native.caret >= (rows.lastOrNull() ?: 0)
		}
		return if (onEdgeRow) "reference: PageUp and PageDown stop on the first and last rows" else null
	}
	val nativeBeforeSpaces = native.caret < editor.caret && text.substring(native.caret, editor.caret).isBlank()
	if (stroke.key == Key.DirectionUp || stroke.key == Key.DirectionDown) {
		val atRunStart = native.caret == 0 || text[native.caret - 1] == '\n' || !text[native.caret - 1].isWhitespace()
		return if (nativeBeforeSpaces && atRunStart) "reference: Up and Down stop before a row's trailing spaces" else null
	}
	val home = stroke.key == Key.MoveHome
	if (!home && stroke.key != Key.MoveEnd) return null
	val stopsBeforeTrailingSpaces = nativeBeforeSpaces && (editor.caret == text.length || text[editor.caret] == '\n')
	val measuredFrom = if (home) minOf(before.anchor, before.caret) else maxOf(before.anchor, before.caret)
	return when {
		measuredFrom != before.caret -> "reference: Home and End measure from the selection edge"
		home && text.endsWith('\n') && before.caret == text.length && native.caret == text.length - 1 ->
			"reference: Home on an empty last line goes up"

		!home && stopsBeforeTrailingSpaces -> "reference: End stops before a line's trailing spaces"

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
 * Nor does the reference start or follow a goal column at a page move, as the
 * editor does, so a page move inside a run of vertical moves, and Up and Down in
 * a run that began with a page move, are tolerated. The reference also keeps its
 * goal column across typed text and Enter, where the editor (like native editors)
 * starts afresh, so a vertical move after typing that followed a vertical move is
 * tolerated.
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
		var inVerticalRun = false
		var runStartedByPage = false
		var goalKeptThroughTyping = false
		script.forEachIndexed { index, stroke ->
			send(stroke)
			val native = reference[index]
			val actual = editorSnapshot
			if (!stroke.isVertical()) goalColumnLost = false
			val pageInRun = inVerticalRun && stroke.isPage()
			if (!inVerticalRun) runStartedByPage = stroke.isPage()
			val staleGoal = goalKeptThroughTyping && stroke.isVertical() && !stroke.isPage()
			goalKeptThroughTyping = when {
				stroke.isVertical() -> goalKeptThroughTyping
				stroke.isTyping() -> inVerticalRun || goalKeptThroughTyping
				else -> false
			}
			inVerticalRun = stroke.isVertical()
			if (actual != native) {
				val rows = editorRows()
				val explained = explainDivergence(before, stroke, native, actual, rows) intersect openItems
				val quirk = referenceQuirk(before, stroke, native, actual, rows)
				val cause = when {
					explained.isNotEmpty() -> explained
					quirk != null -> setOf(quirk)
					goalColumnLost && stroke.isVertical() -> setOf("reset")
					// A goal column moves the caret along its row, never onto another one.
					runStartedByPage && stroke.isVertical() && !stroke.isPage() && sameRow(native, actual) ->
						setOf("reference: a page move does not start a goal column")

					pageInRun && sameRow(native, actual) -> setOf("reference: a page move does not follow the goal column")
					staleGoal -> setOf("reference: the goal column survives typing")

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
