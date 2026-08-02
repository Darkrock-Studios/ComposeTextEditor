package utils

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A deterministic, seed-driven edit script that replays identically through the
 * pure-state interpreter and the UI harness, so a seed that finds a bug in one
 * reproduces in the other. Override the committed seeds with the FUZZ_SEED
 * environment variable to replay a specific failure.
 */
sealed class FuzzOp {
	data class TypeText(val text: String) : FuzzOp()
	data object Enter : FuzzOp()
	data object Backspace : FuzzOp()
	data object DeleteForward : FuzzOp()
	data class MoveCursor(val slot: Int) : FuzzOp()
	data class SelectRange(val a: Int, val b: Int) : FuzzOp()
	data class ToggleBlock(val styleIndex: Int, val lineSeed: Int, val extent: Int) : FuzzOp()
	data class ToggleBold(val a: Int, val b: Int) : FuzzOp()
	data class PastePlain(val text: String) : FuzzOp()
	data class UndoBurst(val count: Int) : FuzzOp()
	data class RedoBurst(val count: Int) : FuzzOp()
	data class SelectAllType(val text: String) : FuzzOp()
}

fun fuzzSeed(default: Long): Long =
	System.getenv("FUZZ_SEED")?.toLongOrNull() ?: default

private val WORDS = listOf(
	"alpha", "beta", "gamma", "delta", "words", "editor",
	"café", "日本", "a b", "x",
)

private fun FuzzOp.isMutating(): Boolean = when (this) {
	is FuzzOp.MoveCursor, is FuzzOp.SelectRange,
	is FuzzOp.UndoBurst, is FuzzOp.RedoBurst -> false
	else -> true
}

// Worst-case history entries an op can record. The UI harness types character by
// character, so a word costs one entry per keystroke, not one per op.
private fun FuzzOp.historyCost(): Int = when (this) {
	is FuzzOp.TypeText -> text.length
	is FuzzOp.SelectAllType -> text.length + 1
	is FuzzOp.MoveCursor, is FuzzOp.SelectRange,
	is FuzzOp.UndoBurst, is FuzzOp.RedoBurst -> 0
	else -> 1
}

/**
 * Generates [count] ops. Mutating ops stop once their worst-case history entries
 * (per keystroke, see [historyCost]) reach [mutatingBudget], which keeps a whole
 * script inside the 100-entry undo cap so the undo-to-origin invariant is honest;
 * the rest are navigation and undo/redo.
 *
 * [includeBlocks] excludes block toggles from the vocabulary. The undo-to-origin
 * scripts need this because block undo has known red-test defects (cross-block
 * restore, unrecorded demotions); those stay covered by the hand-written tests.
 *
 * [includeBold] excludes character styling. The fixpoint scripts need this
 * because any typing near a bold edge can grow the span onto a space, and
 * emphasis with edge spaces exports as invalid markdown (a known red-test
 * defect, see MarkdownRoundTripTortureE2eTest `a bold run ending in a space
 * round trips`). Lift both when the underlying defects are fixed.
 */
fun generateFuzzScript(
	seed: Long,
	count: Int,
	mutatingBudget: Int = Int.MAX_VALUE,
	includeBlocks: Boolean = true,
	includeBold: Boolean = true,
): List<FuzzOp> {
	val random = Random(seed)
	var budget = mutatingBudget
	val script = mutableListOf<FuzzOp>()
	while (script.size < count) {
		var op = randomOp(random)
		if (!includeBlocks && op is FuzzOp.ToggleBlock) {
			op = FuzzOp.TypeText(WORDS.random(random))
		}
		if (!includeBold && op is FuzzOp.ToggleBold) {
			op = FuzzOp.TypeText(WORDS.random(random))
		}
		if (op.isMutating()) {
			val cost = op.historyCost()
			if (cost > budget) {
				script.add(randomNonMutatingOp(random))
				continue
			}
			budget -= cost
		}
		script.add(op)
	}
	return script
}

private fun randomOp(random: Random): FuzzOp = when (random.nextInt(100)) {
	in 0 until 30 -> FuzzOp.TypeText(WORDS.random(random))
	in 30 until 38 -> FuzzOp.Enter
	in 38 until 48 -> FuzzOp.Backspace
	in 48 until 52 -> FuzzOp.DeleteForward
	in 52 until 62 -> FuzzOp.MoveCursor(random.nextInt(1024))
	in 62 until 68 -> FuzzOp.SelectRange(random.nextInt(1024), random.nextInt(1024))
	in 68 until 74 -> FuzzOp.ToggleBlock(random.nextInt(4), random.nextInt(1024), random.nextInt(3))
	in 74 until 79 -> FuzzOp.ToggleBold(random.nextInt(1024), random.nextInt(1024))
	in 79 until 82 -> FuzzOp.PastePlain(WORDS.random(random))
	in 82 until 86 -> FuzzOp.UndoBurst(1 + random.nextInt(5))
	in 86 until 88 -> FuzzOp.RedoBurst(1 + random.nextInt(3))
	in 88 until 89 -> FuzzOp.SelectAllType(WORDS.random(random))
	else -> FuzzOp.TypeText(WORDS.random(random))
}

private fun randomNonMutatingOp(random: Random): FuzzOp = when (random.nextInt(4)) {
	0 -> FuzzOp.MoveCursor(random.nextInt(1024))
	1 -> FuzzOp.SelectRange(random.nextInt(1024), random.nextInt(1024))
	2 -> FuzzOp.UndoBurst(1 + random.nextInt(5))
	else -> FuzzOp.RedoBurst(1 + random.nextInt(3))
}

/** Immutable capture of everything the undo-to-origin invariant compares. */
data class FuzzSnapshot(
	val text: String,
	val spanStyles: List<Triple<Int, Int, SpanStyle>>,
	val richSpans: Set<Pair<TextEditorRange, String>>,
)

fun snapshotOf(state: TextEditorState): FuzzSnapshot = FuzzSnapshot(
	text = state.getAllText().text,
	spanStyles = state.getAllText().spanStyles
		.map { Triple(it.start, it.end, it.item) }
		.sortedWith(compareBy({ it.first }, { it.second })),
	richSpans = state.richSpanManager.getAllRichSpans()
		.map { it.range to it.style::class.simpleName.orEmpty() }
		.toSet(),
)

fun checkCheapInvariants(state: TextEditorState) {
	state.assertRichSpanInvariants()
	assertEquals(
		state.textLines.joinToString("\n") { it.text },
		state.getAllText().text,
		"the flat text and the line list must agree",
	)
	val cursor = state.cursorPosition
	assertTrue(cursor.line in state.textLines.indices, "cursor line out of bounds: $cursor")
	assertTrue(
		cursor.char in 0..state.textLines[cursor.line].length,
		"cursor char out of bounds: $cursor",
	)
}

/** Runs [applyOp] over [script], decorating any failure with a replayable transcript. */
fun runFuzzScript(
	seed: Long,
	script: List<FuzzOp>,
	applyOp: (FuzzOp) -> Unit,
) {
	script.forEachIndexed { index, op ->
		try {
			applyOp(op)
		} catch (failure: Throwable) {
			throw AssertionError(
				"fuzz seed=$seed failed at op[$index]=$op\nhistory=${script.take(index + 1)}",
				failure,
			)
		}
	}
}

private val FUZZ_BOLD = SpanStyle(fontWeight = FontWeight.Bold)

private val BLOCK_TOGGLES: List<Pair<String, MarkdownExtension.(IntRange) -> Unit>> = listOf(
	"bullet" to { lines -> toggleBulletList(lines) },
	"ordered" to { lines -> toggleOrderedList(lines) },
	"quote" to { lines -> toggleBlockquote(lines) },
	"fence" to { lines -> toggleCodeFence(lines) },
)

private fun blockToggleTarget(
	op: FuzzOp.ToggleBlock,
	state: TextEditorState,
	markdown: MarkdownExtension,
): Pair<IntRange, MarkdownExtension.(IntRange) -> Unit>? {
	val lineCount = state.textLines.size
	val start = op.lineSeed % lineCount
	val range = start..minOf(start + op.extent, lineCount - 1)
	val (name, toggle) = BLOCK_TOGGLES[op.styleIndex % BLOCK_TOGGLES.size]
	// D3-shaped documents (quote stacked on a list) corrupt on round trip today, so
	// the fuzzers refuse to create them. Lift this guard when D2-D4 are fixed.
	val wouldStack = when (name) {
		"quote" -> range.any { markdown.isBulletList(it) || markdown.isOrderedList(it) }
		"bullet", "ordered" -> range.any { markdown.isBlockquote(it) }
		else -> false
	}
	return if (wouldStack) null else range to toggle
}

/**
 * True when a Backspace at the current cursor, or an Enter on the current line,
 * would trigger the smart block demotion. Demotions bypass undo history (a known
 * red-test defect), so the undo-to-origin fuzz scripts step around them.
 */
private fun wouldDemote(state: TextEditorState, markdown: MarkdownExtension, enter: Boolean): Boolean {
	val line = state.cursorPosition.line
	val hasBlock = markdown.isBlockquote(line) || markdown.isBulletList(line) ||
		markdown.isOrderedList(line) || markdown.isCodeFence(line)
	if (!hasBlock) return false
	return if (enter) {
		state.textLines[line].text.isEmpty()
	} else {
		state.cursorPosition.char == 0
	}
}

/**
 * Trims edge spaces off a candidate style range and rejects multi-line or empty
 * results. Emphasis wrapped around an edge space exports as invalid markdown (a
 * known red-test defect, see MarkdownRoundTripTortureE2eTest `a bold run ending
 * in a space round trips`), so the fuzzers only bold clean single-line ranges.
 */
private fun trimmedStyleRange(text: String, rawA: Int, rawB: Int): Pair<Int, Int>? {
	val clampedA = rawA % (text.length + 1)
	val clampedB = rawB % (text.length + 1)
	var start = minOf(clampedA, clampedB)
	var end = maxOf(clampedA, clampedB)
	while (start < end && (text[start] == ' ' || text[start] == '\n')) start++
	while (end > start && (text[end - 1] == ' ' || text[end - 1] == '\n')) end--
	if (start >= end) return null
	if (text.substring(start, end).contains('\n')) return null
	return start to end
}

/** Applies [op] directly to the state, the pure-state twin of [applyFuzzOpUi]. */
class StateFuzzInterpreter(
	private val state: TextEditorState,
	private val markdown: MarkdownExtension,
	private val skipDemotions: Boolean,
) {
	private fun clampIndex(raw: Int): Int = raw % (state.getAllText().text.length + 1)

	private fun offset(raw: Int): CharLineOffset = state.getOffsetAtCharacter(clampIndex(raw))

	private fun typeOrReplace(text: String) {
		val selection = state.selector.selection
		if (selection != null) {
			state.replace(selection, AnnotatedString(text), inheritStyle = true)
			state.selector.clearSelection()
		} else {
			state.insertStringAtCursor(text)
		}
	}

	fun apply(op: FuzzOp) {
		when (op) {
			is FuzzOp.TypeText -> typeOrReplace(op.text)

			is FuzzOp.Enter -> {
				if (skipDemotions && wouldDemote(state, markdown, enter = true)) return
				state.selector.clearSelection()
				state.insertNewlineAtCursor()
			}

			is FuzzOp.Backspace -> {
				if (skipDemotions && state.selector.selection == null &&
					wouldDemote(state, markdown, enter = false)
				) return
				val selection = state.selector.selection
				if (selection != null) {
					state.delete(selection)
					state.selector.clearSelection()
				} else {
					state.backspaceAtCursor()
				}
			}

			is FuzzOp.DeleteForward -> {
				val selection = state.selector.selection
				if (selection != null) {
					state.delete(selection)
					state.selector.clearSelection()
				} else {
					state.deleteAtCursor()
				}
			}

			is FuzzOp.MoveCursor -> {
				state.selector.clearSelection()
				state.cursor.updatePosition(offset(op.slot))
			}

			is FuzzOp.SelectRange -> {
				val a = clampIndex(op.a)
				val b = clampIndex(op.b)
				if (a != b) {
					state.selector.updateSelection(offset(minOf(a, b)), offset(maxOf(a, b)))
				}
			}

			is FuzzOp.ToggleBlock -> {
				val target = blockToggleTarget(op, state, markdown) ?: return
				target.second(markdown, target.first)
			}

			is FuzzOp.ToggleBold -> {
				trimmedStyleRange(state.getAllText().text, op.a, op.b)?.let { (start, end) ->
					state.addStyleSpan(
						TextEditorRange(state.getOffsetAtCharacter(start), state.getOffsetAtCharacter(end)),
						FUZZ_BOLD,
					)
				}
			}

			is FuzzOp.PastePlain -> typeOrReplace(op.text)

			// canUndo/canRedo only refresh during layout bookkeeping, which never runs
			// against the 1x1 mock viewport; undo()/redo() no-op safely on empty stacks.
			is FuzzOp.UndoBurst -> repeat(op.count) { state.undo() }

			is FuzzOp.RedoBurst -> repeat(op.count) { state.redo() }

			is FuzzOp.SelectAllType -> {
				state.selector.selectAll()
				typeOrReplace(op.text)
			}
		}
	}
}

/** Applies [op] through real key events and the clipboard, the UI twin of [StateFuzzInterpreter]. */
fun EditorUiTestScope.applyFuzzOpUi(op: FuzzOp, skipDemotions: Boolean) {
	fun clampIndex(raw: Int): Int = raw % (text.length + 1)

	when (op) {
		is FuzzOp.TypeText -> typeText(op.text)

		is FuzzOp.Enter -> {
			if (skipDemotions && wouldDemote(state, markdown, enter = true)) return
			press(Key.Enter)
		}

		is FuzzOp.Backspace -> {
			if (skipDemotions && state.selector.selection == null &&
				wouldDemote(state, markdown, enter = false)
			) return
			press(Key.Backspace)
		}

		is FuzzOp.DeleteForward -> press(Key.Delete)

		is FuzzOp.MoveCursor -> when (op.slot % 8) {
			0 -> press(Key.MoveHome, ctrl = true)
			1 -> press(Key.MoveEnd, ctrl = true)
			2 -> press(Key.MoveHome)
			3 -> press(Key.MoveEnd)
			4 -> press(Key.DirectionLeft)
			5 -> press(Key.DirectionRight)
			6 -> press(Key.DirectionUp)
			else -> press(Key.DirectionDown)
		}

		is FuzzOp.SelectRange -> {
			val a = clampIndex(op.a)
			val b = clampIndex(op.b)
			if (a != b) selectChars(minOf(a, b), maxOf(a, b))
		}

		is FuzzOp.ToggleBlock -> {
			val target = blockToggleTarget(op, state, markdown) ?: return
			target.second(markdown, target.first)
			waitForIdle()
		}

		is FuzzOp.ToggleBold -> {
			trimmedStyleRange(text, op.a, op.b)?.let { (start, end) ->
				state.addStyleSpan(
					TextEditorRange(
						state.getOffsetAtCharacter(start),
						state.getOffsetAtCharacter(end),
					),
					FUZZ_BOLD,
				)
				waitForIdle()
			}
		}

		is FuzzOp.PastePlain -> {
			setPlainClipboardText(op.text)
			press(Key.V, ctrl = true)
		}

		is FuzzOp.UndoBurst -> repeat(op.count) { press(Key.Z, ctrl = true) }

		is FuzzOp.RedoBurst -> repeat(op.count) { press(Key.Y, ctrl = true) }

		is FuzzOp.SelectAllType -> {
			press(Key.A, ctrl = true)
			typeText(op.text)
		}
	}
}
