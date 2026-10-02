package com.darkrockstudios.texteditor.richstyle

import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Smart editing for lines carrying a [LineBlockStyle]: Enter on an empty item
 * exits the block (a nested list item un-nests instead), backspace at its
 * start demotes it (a nested list item un-nests), and a split keeps the
 * gutter marker on both halves. See the "Smart editing" and "Nested lists"
 * sections of `docs/design/line-blocks.md`.
 *
 * Registered on every [TextEditorState] by default. Remove it from
 * [TextEditorState.editBehaviors] for an editor that wants plain line breaks.
 */
object LineBlockEditBehavior : EditBehavior {

	override fun onNewline(state: TextEditorState): Boolean {
		val line = state.cursorPosition.line
		val block = state.detectLineBlock(line) ?: return false

		if (state.textLines.getOrNull(line)?.text.isNullOrEmpty()) {
			// Enter on an empty nested item un-nests it; on an empty top-level
			// item it exits the block (matches Notion / Google Docs). Both go
			// through recording paths so the change lands in undo history. The
			// list block is read on its own: a quoted item's first block is the quote.
			if ((state.listBlockAt(line)?.listLevel ?: 0) > 0) {
				state.unnestListItems(line..line)
			} else {
				state.editManager.toggleLineBlock(line..line, block)
			}
			return true
		}

		// The split and the block markers are one revision. Published separately, a
		// reader between them sees the new half-line with its marker missing.
		state.withAtomicEdit {
			state.insertNewlineRaw()

			// A split at a span boundary keeps the span on only one side, so apply
			// to both halves; applyLineBlock is idempotent.
			state.applyLineBlock(line, block)
			state.applyLineBlock(line + 1, block)
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
