package utils

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * A property of the editor checked after every stroke of [invariantFuzz].
 *
 * [needs] names the roadmap items that must land before the invariant holds. While
 * any of them is still in [OPEN_PARITY_ITEMS] the invariant is off by default, so
 * deleting an item there switches on its invariants as well as its fuzz checks.
 */
enum class EditorInvariant(vararg val needs: String) {
	/** The document never holds half of a surrogate pair. */
	NoLoneSurrogate("1.1"),

	/** The caret and the selection anchor sit on grapheme cluster boundaries. */
	CaretOnGraphemeBoundary("1.1"),

	/**
	 * Without a selection, Down moves to the next visual row, or stays on the last
	 * one. Rows are the ones the caret is drawn on
	 * ([com.darkrockstudios.texteditor.state.TextEditorState.cursorRowIndex]), so a
	 * caret at the end of a wrapped row counts on that row.
	 */
	DownMovesOneRow("1.2"),

	/** Without a selection and away from the document start, Left then Right puts the caret back. */
	LeftThenRightReturns,
	;

	val onByDefault: Boolean get() = needs.none { it in OPEN_PARITY_ITEMS }

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
	if (snapshot.hasSelection) return
	if (EditorInvariant.LeftThenRightReturns in invariants && snapshot.caret > 0) {
		send(Left)
		send(Right)
		assertEquals(snapshot.caret, cursorIndex, "LeftThenRightReturns: from $snapshot")
	}
	if (EditorInvariant.DownMovesOneRow in invariants) {
		val position = state.cursorPosition
		val affinity = state.cursor.affinity
		val row = state.cursorRowIndex()
		send(Down)
		val expected = minOf(row + 1, state.lineOffsets.lastIndex)
		assertEquals(
			expected,
			state.cursorRowIndex(),
			"DownMovesOneRow: from row $row at $snapshot, landed at ${state.editSnapshot()}",
		)
		state.cursor.updatePosition(position, affinity)
		waitForIdle()
	}
}

/**
 * Replays a seeded [generateStrokeScript] through the editor alone and checks
 * [invariants] after every stroke. Unlike [differentialFuzz] there is no reference,
 * so the probes in [checkInvariants] are free to move the caret.
 */
internal fun invariantFuzz(
	seed: Long,
	count: Int,
	width: Dp,
	startText: String = FUZZ_START_TEXT,
	invariants: Set<EditorInvariant> = EditorInvariant.active(),
) = editorUiTest(initialText = AnnotatedString(startText), width = width) {
	val script = generateStrokeScript(seed, count)
	script.forEachIndexed { index, stroke ->
		send(stroke)
		try {
			checkInvariants(invariants)
		} catch (failure: AssertionError) {
			throw AssertionError(
				"invariant fuzz seed=$seed failed after stroke[$index]=$stroke " +
					"(replay: FUZZ_SEED=$seed FUZZ_INVARIANTS=${invariants.joinToString(",")}, " +
					"$count strokes)\n${failure.message}\n" +
					"recent strokes: ${script.subList(maxOf(0, index - 12), index + 1)}",
				failure,
			)
		}
	}
}
