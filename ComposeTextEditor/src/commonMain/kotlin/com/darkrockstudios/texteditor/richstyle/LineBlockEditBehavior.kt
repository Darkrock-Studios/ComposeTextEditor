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

		// Enter on an empty bullet/quote item exits the block: drop the gutter
		// marker and indent, eat the keystroke. Matches Notion / Google Docs and
		// gives a discoverable way to leave a list or quote without backspacing.
		// Routed through the toggle so the demotion lands in undo history.
		if (state.textLines.getOrNull(line)?.text.isNullOrEmpty()) {
			state.editManager.toggleLineBlock(line..line, block)
			return true
		}

		// The split and the block markers are one revision. Published separately, a
		// reader between them sees the new half-line with its marker missing.
		state.withAtomicEdit {
			state.insertNewlineRaw()

			// RichSpanManager's newline handling only keeps the span on one side when
			// the cursor was at a span boundary, so apply to both lines so both halves
			// of the split keep the gutter marker. applyLineBlock is idempotent.
			state.applyLineBlock(line, block)
			state.applyLineBlock(line + 1, block)
		}
		return true
	}

	override fun onBackspace(state: TextEditorState): Boolean {
		// Backspace at column 0 of a line-block (blockquote, bullet) first demotes
		// (removes the gutter marker and indent); a follow-up backspace then merges
		// with the previous line. Matches Notion / Google Docs, a discoverable way
		// to exit a block prefix without nuking the line content.
		//
		// Exception: if the previous line is the SAME line-block, fall through to
		// merge directly. Otherwise demote-first turns "join two adjacent items"
		// into a two-keystroke operation, which feels worse than Docs/Notion.
		val position = state.cursorPosition
		if (position.char != 0) return false
		val activeBlock = state.detectLineBlock(position.line) ?: return false
		if (state.detectLineBlock(position.line - 1) == activeBlock) return false

		// Routed through the toggle so the demotion lands in undo history.
		state.editManager.toggleLineBlock(position.line..position.line, activeBlock)
		return true
	}
}
