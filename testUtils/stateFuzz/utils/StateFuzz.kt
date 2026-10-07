package utils

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.EditorActionContext
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.TableAlignment
import com.darkrockstudios.texteditor.richstyle.TableCellSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.convertTableToText
import com.darkrockstudios.texteditor.state.deleteTable
import com.darkrockstudios.texteditor.state.deleteTableColumn
import com.darkrockstudios.texteditor.state.deleteTableRow
import com.darkrockstudios.texteditor.state.insertTable
import com.darkrockstudios.texteditor.state.insertTableColumn
import com.darkrockstudios.texteditor.state.insertTableRow
import com.darkrockstudios.texteditor.state.setTableColumnAlignment
import com.darkrockstudios.texteditor.state.tableAt
import com.darkrockstudios.texteditor.state.tableCellAt
import com.darkrockstudios.texteditor.state.toggleBlockquote
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleCodeFence
import com.darkrockstudios.texteditor.state.toggleOrderedList
import kotlinx.coroutines.test.TestScope
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

	/** Only in a script generated with tables: a table of [rows] by [columns] at the caret. */
	data class InsertTable(val rows: Int, val columns: Int) : FuzzOp()

	/** Only in a script generated with tables: a row, column or table operation on the table at [slot], if any. */
	data class TableEdit(val kind: Int, val slot: Int) : FuzzOp()

	/** Only in a script generated with tables: Tab, or Shift+Tab when [backward]. */
	data class Tab(val backward: Boolean) : FuzzOp()

	/** Only in a script generated with tables: copies, or cuts, [a, b) and pastes it at [slot], inside the editor. */
	data class CopyPaste(val a: Int, val b: Int, val slot: Int, val cut: Boolean) : FuzzOp()
}

/** The number of [FuzzOp.TableEdit] kinds; see [applyTableEdit]. */
private const val TABLE_EDIT_KINDS = 9

/**
 * A document with tables to start a table storm from, in block lines: a header row of
 * two columns, the second right-aligned, a body row, and a one-column table.
 */
const val TABLE_FUZZ_START = "intro line\n|0| Name\n|1>| Age\n|0| Ada Lovelace\n|1>| 36\nbetween\n|0^| solo\nend line"

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
	is FuzzOp.CopyPaste -> if (cut) 2 else 1
	else -> 1
}

/**
 * Generates [count] ops. Mutating ops stop once their worst-case history entries
 * (per keystroke, see [historyCost]) reach [mutatingBudget], which keeps a whole
 * script inside the 100-entry undo cap so the undo-to-origin invariant is honest;
 * the rest are navigation and undo/redo. With [tables], some ops are table operations,
 * Tab and in-editor copy and paste; without, a seed's script is as it always was.
 */
fun generateFuzzScript(
	seed: Long,
	count: Int,
	mutatingBudget: Int = Int.MAX_VALUE,
	tables: Boolean = false,
): List<FuzzOp> {
	val random = Random(seed)
	var budget = mutatingBudget
	val script = mutableListOf<FuzzOp>()
	while (script.size < count) {
		val op = if (tables && random.nextInt(100) < 20) randomTableOp(random) else randomOp(random)
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

private fun randomTableOp(random: Random): FuzzOp = when (random.nextInt(10)) {
	0, 1 -> FuzzOp.InsertTable(1 + random.nextInt(3), 1 + random.nextInt(3))
	in 2 until 6 -> FuzzOp.TableEdit(random.nextInt(TABLE_EDIT_KINDS), random.nextInt(1024))
	6, 7 -> FuzzOp.Tab(random.nextInt(3) == 0)
	else -> FuzzOp.CopyPaste(random.nextInt(1024), random.nextInt(1024), random.nextInt(1024), random.nextInt(4) == 0)
}

/** Applies a [FuzzOp.TableEdit] of [kind] to the table holding [line]; each is a no-op off a table. */
fun TextEditorState.applyTableEdit(kind: Int, line: Int) {
	when (kind % TABLE_EDIT_KINDS) {
		0 -> insertTableRow(line, below = false)
		1 -> insertTableRow(line, below = true)
		2 -> insertTableColumn(line, after = false)
		3 -> insertTableColumn(line, after = true)
		4 -> deleteTableRow(line)
		5 -> deleteTableColumn(line)
		6 -> setTableColumnAlignment(line, tableCellAt(line)?.column ?: 0, TableAlignment.entries[line % TableAlignment.entries.size])
		7 -> convertTableToText(line)
		else -> deleteTable(line)
	}
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
	state.assertTableInvariants()
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
@Suppress("TooGenericExceptionCaught")
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

internal val FUZZ_BOLD = SpanStyle(fontWeight = FontWeight.Bold)

private val BLOCK_TOGGLES: List<Pair<String, TextEditorState.(IntRange) -> Unit>> = listOf(
	"bullet" to { lines -> toggleBulletList(lines) },
	"ordered" to { lines -> toggleOrderedList(lines) },
	"quote" to { lines -> toggleBlockquote(lines) },
	"fence" to { lines -> toggleCodeFence(lines) },
)

internal fun blockToggleTarget(
	op: FuzzOp.ToggleBlock,
	state: TextEditorState,
): Pair<IntRange, TextEditorState.(IntRange) -> Unit>? {
	val lineCount = state.textLines.size
	val start = op.lineSeed % lineCount
	val range = start..minOf(start + op.extent, lineCount - 1)
	val (_, toggle) = BLOCK_TOGGLES[op.styleIndex % BLOCK_TOGGLES.size]
	return range to toggle
}

/**
 * Trims edge spaces off a candidate style range and rejects multi-line or empty
 * results, so the fuzzers only bold clean single-line ranges.
 */
internal fun trimmedStyleRange(text: String, rawA: Int, rawB: Int): Pair<Int, Int>? {
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

/**
 * Applies [op] directly to the state, the pure-state twin of `FuzzUiDriver.applyFuzzOpUi`
 * (`testUtils/uiFuzz`). Tab and copy and paste run as the editor's actions, through an
 * in-memory clipboard on [scope], the state's own; a script with them needs it.
 */
class StateFuzzInterpreter(
	private val state: TextEditorState,
	private val scope: TestScope? = null,
) {
	private val clipboard = InMemoryClipboard()

	private fun perform(action: EditorCommand.Action) {
		val scope = checkNotNull(scope) { "$action needs the interpreter's scope" }
		state.actions[action]!!.perform(EditorActionContext(state, clipboard, scope))
		scope.testScheduler.advanceUntilIdle()
	}

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
				state.selector.clearSelection()
				state.insertNewlineAtCursor()
			}

			is FuzzOp.Backspace -> {
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
				val target = blockToggleTarget(op, state) ?: return
				target.second(state, target.first)
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

			// undo()/redo() no-op safely on empty stacks.
			is FuzzOp.UndoBurst -> repeat(op.count) { state.undo() }

			is FuzzOp.RedoBurst -> repeat(op.count) { state.redo() }

			is FuzzOp.SelectAllType -> {
				state.selector.selectAll()
				typeOrReplace(op.text)
			}

			is FuzzOp.InsertTable -> state.insertTable(op.rows, op.columns)

			is FuzzOp.TableEdit -> state.applyTableEdit(op.kind, offset(op.slot).line)

			is FuzzOp.Tab -> perform(if (op.backward) EditorCommand.Action.Outdent else EditorCommand.Action.Indent)

			is FuzzOp.CopyPaste -> {
				val a = clampIndex(op.a)
				val b = clampIndex(op.b)
				if (a == b) return
				state.selector.updateSelection(offset(minOf(a, b)), offset(maxOf(a, b)))
				perform(if (op.cut) EditorCommand.Action.Cut else EditorCommand.Action.Copy)
				state.selector.clearSelection()
				state.cursor.updatePosition(offset(op.slot))
				perform(EditorCommand.Action.Paste)
			}
		}
	}
}

/**
 * Every table is whole: a cell line holds one cell marker and no other block, rule,
 * image or paragraph format, and each table row holds the header's columns, 0 on.
 */
fun TextEditorState.assertTableInvariants() {
	val spansByLine = richSpanManager.getAllRichSpans().groupBy { it.range.start.line }
	for ((line, starting) in spansByLine) {
		val cells = starting.count { it.style is TableCellSpanStyle }
		if (cells == 0) continue
		assertEquals(1, cells, "line $line holds $cells cell markers: $starting")
		val others = starting.filter {
			it.style !is TableCellSpanStyle && (it.style.stickyAtStart || it.style is BlockSpanStyle || it.style.boundToParagraph)
		}
		assertTrue(others.isEmpty(), "cell line $line also holds ${others.map { it.style }}")
	}
	var line = 0
	while (line < textLines.size) {
		val table = tableAt(line)
		if (table == null) {
			line++
			continue
		}
		table.rows.forEach { row ->
			assertEquals(
				(0 until table.columnCount).toList(),
				row.map { table.cellAt(it).column },
				"table at ${table.firstLine}, row $row is not whole",
			)
		}
		line = table.lastLine + 1
	}
}

/** Structural sanity of every rich span: ordered, in bounds, no duplicate (range, style) pairs. */
fun TextEditorState.assertRichSpanInvariants() {
	val spans = richSpanManager.getAllRichSpans()
	val lineCount = textLines.size
	for (span in spans) {
		val start = span.range.start
		val end = span.range.end
		assertTrue(
			start.line < end.line || (start.line == end.line && start.char <= end.char),
			"span $span has an inverted range",
		)
		assertTrue(
			start.line in 0 until lineCount && end.line in 0 until lineCount,
			"span $span references lines outside the $lineCount-line document",
		)
		assertTrue(
			start.char in 0..textLines[start.line].length,
			"span $span starts past the end of line ${start.line} " +
				"(char ${start.char}, line length ${textLines[start.line].length})",
		)
		assertTrue(
			end.char in 0..textLines[end.line].length,
			"span $span ends past the end of line ${end.line} " +
				"(char ${end.char}, line length ${textLines[end.line].length})",
		)
	}
	val duplicates = spans
		.groupBy { it.range to it.style::class }
		.filterValues { it.size > 1 }
	assertTrue(
		duplicates.isEmpty(),
		"duplicate rich spans with identical range and style class: ${duplicates.values}",
	)
}
