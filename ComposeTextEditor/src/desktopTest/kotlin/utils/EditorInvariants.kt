package utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import com.darkrockstudios.texteditor.bandBottom
import com.darkrockstudios.texteditor.bandTop
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import com.darkrockstudios.texteditor.state.caretParagraphIsRtl
import com.darkrockstudios.texteditor.utils.hasRightToLeft
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A property of the editor checked after every stroke of [invariantFuzz].
 *
 * [needs] names the gaps that must close before the invariant holds. While any of
 * them is still in [KNOWN_PARITY_GAPS] the invariant is off by default, so deleting
 * a gap there switches on its invariants as well as its fuzz checks.
 */
enum class EditorInvariant(vararg val needs: ParityGap) {
	/** The document never holds half of a surrogate pair. */
	NoLoneSurrogate(ParityGap.GraphemeClusters),

	/** The caret and the selection anchor sit on grapheme cluster boundaries. */
	CaretOnGraphemeBoundary(ParityGap.GraphemeClusters),

	/**
	 * Without a selection, Down moves to the next visual row, or stays on the last
	 * one. Rows are the ones the caret is drawn on
	 * ([com.darkrockstudios.texteditor.state.TextEditorState.cursorRowIndex]), so a
	 * caret at the end of a wrapped row counts on that row. In a document with a
	 * table, whose cells sit side by side, Down goes by position instead: to a row
	 * lower down, when there is one below the caret's table row or in its cell.
	 */
	DownMovesOneRow(ParityGap.VerticalMotion),

	/**
	 * Without a selection and away from the document start, Left then Right puts the
	 * caret back: to the same offset, or in a line with right-to-left text to the same
	 * place in its layout, unless Left found nothing further left.
	 */
	LeftThenRightReturns,

	/**
	 * With wrapping off, what the view answers follows the sideways scroll
	 * ([assertViewFollowsSidewaysScroll]). With wrapping on there is no sideways range.
	 */
	ViewFollowsSidewaysScroll,
	;

	val onByDefault: Boolean get() = needs.none { it in KNOWN_PARITY_GAPS }

	companion object {
		/**
		 * The invariants to check: those [onByDefault], or the comma-separated names in
		 * the FUZZ_INVARIANTS environment variable (`all` for every one) when it is set.
		 */
		fun active(): Set<EditorInvariant> {
			val requested = System.getenv("FUZZ_INVARIANTS")?.trim()
			return when {
				requested.isNullOrEmpty() -> entries.filter { it.onByDefault }.toSet()
				requested == "all" -> entries.toSet()
				else -> requested.split(',').map { valueOf(it.trim()) }.toSet()
			}
		}
	}
}

/**
 * Checks [invariants] against the editor's current state. The Left-then-Right and
 * Down probes press keys, then put the caret back where it was.
 */
fun EditorUiTestScope.checkInvariants(invariants: Set<EditorInvariant>) {
	val snapshot = state.editSnapshot()
	if (EditorInvariant.NoLoneSurrogate in invariants) {
		snapshot.text.firstLoneSurrogate()?.let {
			fail("NoLoneSurrogate: unpaired surrogate at $it in $snapshot")
		}
	}
	if (EditorInvariant.CaretOnGraphemeBoundary in invariants) {
		if (!snapshot.text.isGraphemeBoundary(snapshot.caret) || !snapshot.text.isGraphemeBoundary(snapshot.anchor)) {
			fail("CaretOnGraphemeBoundary: caret or anchor inside a grapheme cluster in $snapshot")
		}
	}
	if (EditorInvariant.ViewFollowsSidewaysScroll in invariants) assertViewFollowsSidewaysScroll()
	if (snapshot.hasSelection) return
	if (EditorInvariant.LeftThenRightReturns in invariants && snapshot.caret > 0) {
		val line = state.textLines[state.cursorPosition.line].text
		val visual = line.hasRightToLeft(0, line.length) || state.caretParagraphIsRtl()
		val drawn = caretInContent()
		send(Left)
		// At the first row's left edge Left has nowhere to go, wherever that is in the text.
		val stuckAtLeftEdge = caretInContent().isNear(drawn) && state.cursorRowIndex() == 0
		send(Right)
		if (visual) {
			// The arrows are visual: back to the same place in the layout, which
			// between runs of opposite direction two offsets share.
			val back = caretInContent()
			assertTrue(
				stuckAtLeftEdge || back.isNear(drawn),
				"LeftThenRightReturns: from $snapshot drawn at $drawn, back at $back",
			)
		} else {
			assertEquals(snapshot.caret, cursorIndex, "LeftThenRightReturns: from $snapshot")
		}
	}
	if (EditorInvariant.DownMovesOneRow in invariants) {
		val position = state.cursorPosition
		val affinity = state.cursor.affinity
		val row = state.cursorRowIndex()
		val rows = state.lineOffsets
		send(Down)
		if (rows.any { it.box != null }) {
			val from = rows[row]
			val below = rows.any { it.bandTop >= from.bandBottom - 0.5f || (it.line == from.line && it.offset.y > from.offset.y) }
			val landed = state.lineOffsets[state.cursorRowIndex()]
			assertTrue(
				if (below) landed.offset.y > from.offset.y else landed.offset.y >= from.offset.y,
				"DownMovesOneRow: from row $row (line ${from.line}, y ${from.offset.y}) at $snapshot, " +
					"landed on line ${landed.line} at y ${landed.offset.y}, ${state.editSnapshot()}",
			)
		} else {
			val expected = minOf(row + 1, state.lineOffsets.lastIndex)
			assertEquals(
				expected,
				state.cursorRowIndex(),
				"DownMovesOneRow: from row $row at $snapshot, landed at ${state.editSnapshot()}",
			)
		}
		state.cursor.updatePosition(position, affinity)
		waitForIdle()
	}
}

/** Within half a pixel sideways, on the same row. */
private fun Offset.isNear(other: Offset): Boolean = abs(x - other.x) <= 0.5f && y == other.y

/** Where the caret is drawn, in content x, which a sideways scroll does not move. */
private fun EditorUiTestScope.caretInContent(): Offset =
	state.calculateCursorPosition().position + Offset(state.horizontalScrollState.value.toFloat(), 0f)

/**
 * Replays a seeded [generateStrokeScript] through the editor alone and checks
 * [invariants] after every stroke. Unlike [differentialFuzz] there is no reference,
 * so the probes in [checkInvariants] are free to move the caret. With [sideways]
 * wrapping is off, and the view is scrolled sideways to a seeded point before the
 * first stroke and after each.
 */
internal fun invariantFuzz(
	seed: Long,
	count: Int,
	width: Dp,
	startText: String = FUZZ_START_TEXT,
	invariants: Set<EditorInvariant> = EditorInvariant.active(),
	sideways: Boolean = false,
	startBlockLines: String? = null,
) = editorUiTest(initialText = AnnotatedString(startText), width = width, softWrap = !sideways) {
	startBlockLines?.let {
		state.setBlockLines(it)
		waitForIdle()
	}
	val script = generateStrokeScript(seed, count)
	val scrolls = sidewaysScrolls(seed)
	if (sideways) scrollSidewaysAtRandom(scrolls)
	script.forEachIndexed { index, stroke ->
		send(stroke)
		if (sideways) scrollSidewaysAtRandom(scrolls)
		try {
			checkInvariants(invariants)
		} catch (failure: AssertionError) {
			throw AssertionError(
				"invariant fuzz seed=$seed failed after stroke[$index]=$stroke " +
					"(replay: FUZZ_SEED=$seed FUZZ_INVARIANTS=${invariants.joinToString(",")}, " +
					"$count strokes${if (sideways) ", sideways" else ""})\n${failure.message}\n" +
					"recent strokes: ${script.subList(maxOf(0, index - 12), index + 1)}",
				failure,
			)
		}
	}
}
