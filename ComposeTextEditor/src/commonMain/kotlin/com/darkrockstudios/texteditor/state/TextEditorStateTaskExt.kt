package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.richstyle.BulletList
import com.darkrockstudios.texteditor.richstyle.TaskSpanStyle
import com.darkrockstudios.texteditor.richstyle.TaskUnchecked
import com.darkrockstudios.texteditor.richstyle.allowedOn
import com.darkrockstudios.texteditor.richstyle.listBlockAt
import com.darkrockstudios.texteditor.richstyle.placeholderKindOf
import com.darkrockstudios.texteditor.richstyle.planDemoteLineBlock
import com.darkrockstudios.texteditor.richstyle.planLineBlock
import com.darkrockstudios.texteditor.richstyle.planLineBlocks
import com.darkrockstudios.texteditor.richstyle.taskBlock
import com.darkrockstudios.texteditor.richstyle.writeLineBlocks

/**
 * The task list API: a task is a list item's checkbox (see [TaskSpanStyle] and
 * `docs/design/line-blocks.md`, "Task lists"). Each edit is one undo step.
 */

/** Whether the task on [line] is checked, or null when [line] is no task item. */
fun TextEditorState.taskCheckedAt(line: Int): Boolean? =
	richSpanManager.getRichSpansStartingOn(line).firstNotNullOfOrNull { (it.style as? TaskSpanStyle)?.checked }

/** Whether [line] is a task item, checked or not. */
fun TextEditorState.isTask(line: Int): Boolean = taskCheckedAt(line) != null

/**
 * Makes [lines] unchecked tasks, a line that is no list item a bullet item first, or,
 * when every one already is a task, takes their boxes off, leaving the list items. A
 * task keeps its state. A rule, an image or a table cell keeps its line.
 */
fun TextEditorState.toggleTaskList(lines: IntRange) {
	val targets = lines.filter { line ->
		line in textLines.indices && TaskUnchecked.allowedOn(placeholderKindOf(workingContent, line)) && !isInlineOnlyLine(line)
	}
	if (targets.isEmpty()) return
	val off = targets.all { isTask(it) }
	editManager.recordLineBlockChanges(targets) {
		writeLineBlocks(
			targets.mapNotNull { line ->
				when {
					off -> planDemoteLineBlock(line, taskBlock(taskCheckedAt(line)!!))
					isTask(line) -> null
					else -> planLineBlocks(line, listOfNotNull(BulletList.takeIf { listBlockAt(line) == null }, TaskUnchecked))
				}
			}
		)
	}
}

/** Checks or unchecks the task on [line]. Does nothing off a task. */
fun TextEditorState.setTaskChecked(line: Int, checked: Boolean) {
	if (taskCheckedAt(line) != !checked) return
	editManager.recordLineBlockChanges(listOf(line)) {
		planLineBlock(line, taskBlock(checked))?.let { writeLineBlocks(listOf(it)) }
	}
}

/**
 * Checks every task in [lines], or unchecks them when every one is checked, as one
 * undo step. Lines that are no task are left alone.
 */
fun TextEditorState.toggleTasksChecked(lines: IntRange) {
	val tasks = lines.filter { it in textLines.indices && isTask(it) }
	if (tasks.isEmpty()) return
	val checked = tasks.any { taskCheckedAt(it) == false }
	editGroup { tasks.forEach { setTaskChecked(it, checked) } }
}

/** The lines the selection covers, or the caret's. */
internal fun TextEditorState.selectedLines(): IntRange =
	selector.selection?.let { it.start.line..it.end.line } ?: cursorPosition.line..cursorPosition.line

/** Checks the task on [line] when it is unchecked, and unchecks it when it is checked. */
fun TextEditorState.toggleTaskChecked(line: Int) {
	val checked = taskCheckedAt(line) ?: return
	setTaskChecked(line, !checked)
}
