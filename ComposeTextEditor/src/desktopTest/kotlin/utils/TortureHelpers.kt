package utils

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.getRichSpansInRange
import com.darkrockstudios.texteditor.state.isBlockquote
import com.darkrockstudios.texteditor.state.isBulletList
import com.darkrockstudios.texteditor.state.isCodeFence
import com.darkrockstudios.texteditor.state.isOrderedList
import kotlin.test.assertEquals

/** Pastes [html] the way a foreign application would: a text/html clipboard flavor, then Ctrl+V. */
@OptIn(ExperimentalComposeUiApi::class)
fun EditorUiTestScope.pasteHtml(html: String) {
	clipboard.seed(ClipEntry(ForeignHtmlTransferable(html)))
	press(Key.V, ctrl = true)
}

/** Rich spans overlapping the flat character range [startChar, endChar). */
fun EditorUiTestScope.richSpansIn(startChar: Int, endChar: Int): Set<RichSpan> =
	state.getRichSpansInRange(
		TextEditorRange(
			state.getOffsetAtCharacter(startChar),
			state.getOffsetAtCharacter(endChar),
		)
	)

/**
 * Selects the flat character range [fromChar, toChar) directly through the selection
 * manager. Use [EditorUiTestScope.dragSelect] only when the mouse gesture itself is
 * under test; it costs a real 350ms sleep per call.
 */
fun EditorUiTestScope.selectChars(fromChar: Int, toChar: Int) {
	state.selector.updateSelection(
		state.getOffsetAtCharacter(fromChar),
		state.getOffsetAtCharacter(toChar),
	)
	waitForIdle()
}

/** Presses Ctrl+Z until the undo stack is empty; returns how many undos ran. */
fun EditorUiTestScope.undoAll(max: Int = 250): Int {
	var count = 0
	while (state.canUndo) {
		check(count < max) { "undoAll exceeded $max undos without exhausting the undo stack" }
		press(Key.Z, ctrl = true)
		count++
	}
	return count
}

/** The number each line's ordered item shows, null off an ordered item. */
fun EditorUiTestScope.orderedNumbers(): List<Int?> =
	state.lineOffsets.filter { it.virtualLineIndex == 0 }.map { it.orderedListNumber }

/** Asserts [line]'s exact block-style membership; every style not passed as true must be absent. */
fun EditorUiTestScope.assertBlockState(
	line: Int,
	quote: Boolean = false,
	bullet: Boolean = false,
	ordered: Boolean = false,
	fence: Boolean = false,
) {
	assertEquals(quote, state.isBlockquote(line), "line $line blockquote state")
	assertEquals(bullet, state.isBulletList(line), "line $line bullet-list state")
	assertEquals(ordered, state.isOrderedList(line), "line $line ordered-list state")
	assertEquals(fence, state.isCodeFence(line), "line $line code-fence state")
}

/** The block styles present on [line], as readable names; empty set means a plain line. */
fun EditorUiTestScope.blockFlags(line: Int): Set<String> = buildSet {
	if (state.isBlockquote(line)) add("quote")
	if (state.isBulletList(line)) add("bullet")
	if (state.isOrderedList(line)) add("ordered")
	if (state.isCodeFence(line)) add("fence")
}

/** Structural sanity of every rich span: ordered, in bounds, no duplicate (range, style) pairs. */
fun EditorUiTestScope.assertRichSpanInvariants() = state.assertRichSpanInvariants()
