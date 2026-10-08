package com.darkrockstudios.texteditor.richstyle

import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Smart editing for lines carrying a [LineBlockStyle]: Enter on an empty item
 * exits the block (a nested list item un-nests instead), Enter at a heading's
 * end, empty or not, starts body text, backspace at its start demotes it (a
 * nested list item un-nests), and a split keeps the gutter marker on both
 * halves. An inline-only line (a table cell) is left to its own behavior. See the "Smart
 * editing" and "Nested lists" sections of `docs/design/line-blocks.md`.
 *
 * Registered on every [TextEditorState] by default. Remove it from
 * [TextEditorState.editBehaviors] for an editor that wants plain line breaks.
 */
object LineBlockEditBehavior : EditBehavior {

	override fun onNewline(state: TextEditorState): Boolean {
		val line = state.cursorPosition.line
		val blocks = state.lineBlocks(line)
		val block = blocks.firstOrNull() ?: return false
		if (block.spanStyle.inlineOnly) return false
		val text = state.textLines.getOrNull(line)?.text.orEmpty()
		val atHeadingEnd = blocks.any { it.isHeading } && state.cursorPosition.char >= text.length

		if (text.isEmpty() && !atHeadingEnd) {
			// Enter on an empty nested item un-nests it; on an empty top-level
			// item it exits the block (matches Notion / Google Docs). Both go
			// through recording paths so the change lands in undo history. The
			// list block is read on its own: a quoted item's first block is the quote.
			if ((state.listBlockAt(line)?.listLevel ?: 0) > 0) {
				state.unnestListItems(line..line)
			} else {
				// A quoted item leaves the list and stays quoted.
				state.editManager.toggleLineBlock(line..line, state.listBlockAt(line) ?: block)
			}
			return true
		}

		// The split and the block markers are one revision and one undo step, the
		// markers recorded so that a redo puts them back too.
		state.withAtomicEdit {
			val lineCount = state.textLines.size
			state.insertNewlineRaw()
			// An input filter can turn the line break into something else.
			if (state.textLines.size != lineCount + 1) return@withAtomicEdit
			state.editManager.recordLineBlockChanges(listOf(line, line + 1)) {
				// A split at a span boundary, or of an empty line, leaves a marker on
				// only one side, so apply to both halves; applyLineBlock is idempotent.
				// A heading ends at its end: the line after it is body text, empty
				// heading or not (Word's and Google Docs' next-paragraph style).
				blocks.forEach { state.applyLineBlock(line, it) }
				blocks.forEach {
					if (atHeadingEnd && it.isHeading) {
						state.demoteLineBlock(line + 1, it)
					} else {
						state.applyLineBlock(line + 1, it.continuesAs ?: it)
					}
				}
			}
		}
		return true
	}

	override fun onBackspace(state: TextEditorState): Boolean {
		// Backspace at column 0 of a block line first demotes; a second backspace
		// then merges (matches Notion / Google Docs). Exception: when the previous
		// line is the SAME block, fall through and merge directly, or joining two
		// adjacent items becomes a two-keystroke operation. A nested list item
		// un-nests first, whatever the previous line.
		val position = state.cursorPosition
		if (position.char != 0) return false
		val activeBlock = state.detectLineBlock(position.line) ?: return false
		if (activeBlock.spanStyle.inlineOnly) return false
		// A task's box goes first, whatever the item is nested in or follows.
		if (activeBlock.isTask) {
			state.editManager.toggleLineBlock(position.line..position.line, activeBlock)
			return true
		}
		if ((state.listBlockAt(position.line)?.listLevel ?: 0) > 0) {
			state.unnestListItems(position.line..position.line)
			return true
		}
		if (state.detectLineBlock(position.line - 1) == activeBlock) return false

		// Routed through the toggle so the demotion lands in undo history.
		state.editManager.toggleLineBlock(position.line..position.line, activeBlock)
		return true
	}
}
