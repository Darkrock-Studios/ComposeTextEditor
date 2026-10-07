package blocks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.richstyle.TaskSpanStyle
import com.darkrockstudios.texteditor.state.taskCheckedAt
import org.junit.Test
import utils.EditorUiTestScope
import utils.editorUiTest
import utils.setBlockLines
import kotlin.test.assertEquals

/** A click or a tap on a task's box checks it; on its text it places the caret as anywhere. */
@OptIn(ExperimentalTestApi::class)
class TaskClickTest {

	/** The middle of the box of the task on [line], in editor node coordinates. */
	private fun EditorUiTestScope.boxOf(line: Int): Offset = test.runOnIdle {
		val row = state.lineOffsets.first { it.line == line }
		val (topLeft, size) = TaskSpanStyle.checkboxBounds(row.textLayoutResult, state.density!!)
		canvasToNode(row.offset + topLeft + Offset(size.width / 2f, size.height / 2f) - Offset(0f, state.scrollState.value.toFloat()))
	}

	@Test
	fun `a click on a task's box checks and unchecks it, a tap too`() = editorUiTest(initialText = AnnotatedString("")) {
		test.runOnIdle { state.setBlockLines("- [ ] first\n- [x] second") }

		mouse { click(boxOf(0)) }
		assertEquals(true, test.runOnIdle { state.taskCheckedAt(0) })

		tapAt(boxOf(1))
		assertEquals(false, test.runOnIdle { state.taskCheckedAt(1) })

		mouse { click(positionOfCharacter(4)) }
		assertEquals(true, test.runOnIdle { state.taskCheckedAt(0) })
	}

	@Test
	fun `a read only editor's boxes do not change`() = editorUiTest(initialText = AnnotatedString(""), readOnly = true) {
		test.runOnIdle { state.setBlockLines("- [ ] first") }

		mouse { click(boxOf(0)) }
		tapAt(boxOf(0))

		assertEquals(false, test.runOnIdle { state.taskCheckedAt(0) })
	}
}
