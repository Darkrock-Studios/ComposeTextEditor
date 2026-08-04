package com.darkrockstudios.texteditor.input

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.clipboard.applyHtmlPasteBlocks
import com.darkrockstudios.texteditor.clipboard.readHtmlPasteDocument
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.applyStyleForEditAt
import com.darkrockstudios.texteditor.state.moveToNextWord
import com.darkrockstudios.texteditor.state.moveToPreviousWord
import kotlinx.coroutines.launch

/** One outdent level: a single hard tab, else up to this many spaces. */
private const val TAB_SIZE = 4

/**
 * Registers the actions the editor ships with. Every one goes through the
 * public [EditorActionRegistry.register], so the built-ins are exactly as
 * replaceable as anything a host adds.
 */
internal fun EditorActionRegistry.registerBuiltinActions() {
	register(EditorActionSpec(Action.SelectAll) { it.state.selector.selectAll() })

	register(
		EditorActionSpec(
			action = Action.Copy,
			isEnabled = { it.state.selector.hasSelection() },
			perform = { it.copySelection() },
		)
	)
	register(
		EditorActionSpec(
			action = Action.Cut,
			isEnabled = { it.state.selector.hasSelection() },
			perform = { it.cutSelection() },
		)
	)
	register(EditorActionSpec(Action.Paste) { it.pasteClipboard() })

	register(
		EditorActionSpec(
			action = Action.Undo,
			isEnabled = { it.state.canUndo },
			perform = { it.state.undo() },
		)
	)
	register(
		EditorActionSpec(
			action = Action.Redo,
			isEnabled = { it.state.canRedo },
			perform = { it.state.redo() },
		)
	)

	register(EditorActionSpec(Action.DeleteBackward) { it.state.handleBackspace() })
	register(EditorActionSpec(Action.DeleteForward) { it.state.handleDelete() })
	register(EditorActionSpec(Action.DeleteWordBackward) { ctx ->
		ctx.state.deleteByMotion { ctx.state.moveToPreviousWord() }
	})
	register(EditorActionSpec(Action.DeleteWordForward) { ctx ->
		ctx.state.deleteByMotion { ctx.state.moveToNextWord() }
	})
	register(EditorActionSpec(Action.DeleteToLineStart) { ctx ->
		ctx.state.deleteByMotion { ctx.state.cursor.moveToLineStart() }
	})

	register(EditorActionSpec(Action.Indent) { it.state.handleIndent() })
	register(EditorActionSpec(Action.Outdent) { it.state.handleOutdent() })
	register(EditorActionSpec(Action.NewLine) { it.state.handleEnter() })
}

private fun EditorActionContext.copySelection() {
	state.selector.selection?.let { selection ->
		val selectedText = state.selector.getSelectedText()
		val html = state.selectionAsHtml(selection)
		val copyId = state.copyRichSpans(selection)
		scope.launch {
			ClipboardHelper.setText(clipboard, selectedText, state.markdownConfiguration, copyId, html)
		}
	}
}

private fun EditorActionContext.cutSelection() {
	state.selector.selection?.let { selection ->
		val selectedText = state.selector.getSelectedText()
		// Both reads describe the document as it stands, so they have to happen
		// before the delete takes the selection out from under them.
		val html = state.selectionAsHtml(selection)
		val copyId = state.copyRichSpans(selection)
		state.preserveCopiedRichSpansThroughNextEdit()
		state.selector.deleteSelection()
		scope.launch {
			ClipboardHelper.setText(clipboard, selectedText, state.markdownConfiguration, copyId, html)
		}
	}
}

private fun EditorActionContext.pasteClipboard() {
	scope.launch {
		ClipboardHelper.getText(clipboard, state.markdownConfiguration)?.let { text ->
			val curSelection = state.selector.selection
			val insertPosition = curSelection?.start ?: state.cursorPosition
			// Read the clipboard's HTML before mutating: the text, the in-editor
			// rich spans and the pasted block structure then land as one revision.
			val htmlDocument = state.readHtmlPasteDocument(clipboard, text)
			val clipboardCopyId = ClipboardHelper.readCopyId(clipboard)
			state.preserveCopiedRichSpansThroughNextEdit()
			state.withAtomicEdit {
				if (curSelection != null) {
					state.replace(curSelection, state.applyStyleForEditAt(curSelection.start, text))
				} else {
					state.insertStringAtCursor(text)
				}
				state.pasteRichSpans(
					insertPosition,
					text,
					clipboardCopyId,
					requireCopyIdMatch = ClipboardHelper.supportsCopyProvenance,
				)
				htmlDocument?.let { state.applyHtmlPasteBlocks(it, insertPosition, text) }
			}
			state.selector.clearSelection()
		}
	}
}

private fun TextEditorState.handleDelete() {
	if (selector.selection != null) {
		selector.deleteSelection()
	} else {
		deleteAtCursor()
	}
}

private fun TextEditorState.handleBackspace() {
	if (selector.selection != null) {
		selector.deleteSelection()
	} else {
		backspaceAtCursor()
	}
}

/**
 * Deletes between the caret and wherever [locateRangeEdge] moves it, or the selection when
 * there is one. The caret the user had is handed to [TextEditorState.delete] explicitly:
 * [locateRangeEdge] has already moved it off that position, and delete otherwise records
 * wherever the caret currently sits as the position undo returns to.
 */
private fun TextEditorState.deleteByMotion(locateRangeEdge: () -> Unit) {
	if (selector.selection != null) {
		selector.deleteSelection()
		return
	}
	val origin = cursorPosition
	locateRangeEdge()
	val edge = cursorPosition
	if (edge == origin) return

	val range = if (edge < origin) {
		TextEditorRange(edge, origin)
	} else {
		TextEditorRange(origin, edge)
	}
	delete(range, cursorBefore = origin)
}

private fun TextEditorState.handleIndent() {
	val selection = selector.selection
	if (selection != null && selection.start.line != selection.end.line) {
		indentLineRange(selection.start.line, selection.end.line)
	} else {
		if (selection != null) {
			selector.deleteSelection()
		}
		insertStringAtCursor(" ".repeat(TAB_SIZE))
	}
}

private fun TextEditorState.handleOutdent() {
	val selection = selector.selection
	if (selection != null) {
		outdentLineRange(selection.start.line, selection.end.line)
	} else {
		outdentCurrentLine()
	}
}

private fun TextEditorState.indentLineRange(startLine: Int, endLine: Int) {
	val prefix = " ".repeat(TAB_SIZE)
	val newText = buildAnnotatedString {
		for (i in startLine..endLine) {
			if (i > startLine) append('\n')
			append(prefix)
			append(textLines[i])
		}
	}
	val range = TextEditorRange(
		CharLineOffset(startLine, 0),
		CharLineOffset(endLine, textLines[endLine].length)
	)
	replace(range, newText)
	selector.updateSelection(
		CharLineOffset(startLine, 0),
		CharLineOffset(endLine, textLines[endLine].length)
	)
}

private fun TextEditorState.outdentLineRange(startLine: Int, endLine: Int) {
	var changed = false
	val newText = buildAnnotatedString {
		for (i in startLine..endLine) {
			if (i > startLine) append('\n')
			val line = textLines[i]
			val remove = leadingOutdentWidth(line)
			if (remove > 0) changed = true
			append(line.subSequence(remove, line.length))
		}
	}
	if (!changed) return

	val range = TextEditorRange(
		CharLineOffset(startLine, 0),
		CharLineOffset(endLine, textLines[endLine].length)
	)
	replace(range, newText)
	selector.updateSelection(
		CharLineOffset(startLine, 0),
		CharLineOffset(endLine, textLines[endLine].length)
	)
}

private fun TextEditorState.outdentCurrentLine() {
	val line = cursorPosition.line
	val remove = leadingOutdentWidth(textLines[line])
	if (remove == 0) return

	val cursorChar = cursorPosition.char
	delete(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, remove)))
	cursor.updatePosition(CharLineOffset(line, (cursorChar - remove).coerceAtLeast(0)))
}

/** Leading indentation to strip for one outdent level: a single hard tab, else up to [TAB_SIZE] spaces. */
private fun leadingOutdentWidth(line: AnnotatedString): Int {
	if (line.isEmpty()) return 0
	if (line[0] == '\t') return 1
	var count = 0
	while (count < TAB_SIZE && count < line.length && line[count] == ' ') count++
	return count
}

private fun TextEditorState.handleEnter() {
	if (selector.selection != null) {
		selector.deleteSelection()
	}
	insertNewlineAtCursor()
}
