package com.darkrockstudios.texteditor.richstyle

import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Smart editing for lines carrying a [LineBlockStyle]: Enter on an empty item
 * exits the block, backspace at its start demotes it, and a split keeps the
 * gutter marker on both halves. See the "Smart editing" section of
 * `docs/design/line-blocks.md`.
 *
 * Registered on every [TextEditorState] by default. Remove it from
 * [TextEditorState.editBehaviors] for an editor that wants plain line breaks.
 */
object LineBlockEditBehavior : EditBehavior {

	override fun onNewline(state: TextEditorState): Boolean {
		val line = state.cursorPosition.line
		val block = state.detectLineBlock(line) ?: return false

		// Enter on an empty item exits the block (matches Notion / Google Docs);
		// routed through the toggle so the demotion lands in undo history.
		if (state.textLines.getOrNull(line)?.text.isNullOrEmpty()) {
			state.editManager.toggleLineBlock(line..line, block)
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
		// adjacent items becomes a two-keystroke operation.
		val position = state.cursorPosition
		if (position.char != 0) return false
		val activeBlock = state.detectLineBlock(position.line) ?: return false
		if (state.detectLineBlock(position.line - 1) == activeBlock) return false

		// Routed through the toggle so the demotion lands in undo history.
		state.editManager.toggleLineBlock(position.line..position.line, activeBlock)
		return true
	}
}
