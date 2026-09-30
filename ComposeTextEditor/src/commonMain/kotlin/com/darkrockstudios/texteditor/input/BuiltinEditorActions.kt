package com.darkrockstudios.texteditor.input

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.clipboard.applyHtmlPasteBlocks
import com.darkrockstudios.texteditor.clipboard.readHtmlPasteDocument
import com.darkrockstudios.texteditor.clipboard.withSizeForPasteAt
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.richstyle.listBlockAt
import com.darkrockstudios.texteditor.richstyle.listLevel
import com.darkrockstudios.texteditor.richstyle.nestListItems
import com.darkrockstudios.texteditor.richstyle.unnestListItems
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.applyStyleForEditAt
import com.darkrockstudios.texteditor.state.clearFormatting
import com.darkrockstudios.texteditor.state.linksAtSelection
import com.darkrockstudios.texteditor.state.unlink
import com.darkrockstudios.texteditor.state.insertTypedNewline
import com.darkrockstudios.texteditor.state.moveToNextWord
import com.darkrockstudios.texteditor.state.moveToPreviousWord
import com.darkrockstudios.texteditor.state.moveToPreviousWordStart
import com.darkrockstudios.texteditor.state.moveToWordEnd
import com.darkrockstudios.texteditor.state.screenAtSelection
import com.darkrockstudios.texteditor.state.toggleSpanStyle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

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
	register(EditorActionSpec(Action.Paste) { it.pasteClipboard(plainText = false) })
	register(EditorActionSpec(Action.PasteAsPlainText) { it.pasteClipboard(plainText = true) })

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
	register(EditorActionSpec(Action.DeleteToWordEnd) { ctx ->
		ctx.state.deleteByMotion { ctx.state.moveToWordEnd() }
	})
	register(EditorActionSpec(Action.DeleteToPreviousWordStart) { ctx ->
		ctx.state.deleteByMotion { ctx.state.moveToPreviousWordStart() }
	})
	register(EditorActionSpec(Action.DeleteToLineStart) { ctx ->
		ctx.state.deleteByMotion(kill = Kill.Backward) { ctx.state.cursor.moveToLineStart() }
	})
	register(EditorActionSpec(Action.DeleteToLineEnd) { ctx ->
		ctx.state.deleteByMotion(kill = Kill.Forward) { ctx.state.moveCursorToVisualRowEnd() }
	})
	register(EditorActionSpec(Action.DeleteToParagraphEnd) { it.state.deleteToParagraphEnd() })
	register(
		EditorActionSpec(
			action = Action.Yank,
			isEnabled = { it.state.killRing.text != null },
			perform = { it.state.yank() },
		)
	)

	register(EditorActionSpec(Action.Indent) { it.state.handleIndent() })
	register(EditorActionSpec(Action.Outdent) { it.state.handleOutdent() })
	register(EditorActionSpec(Action.NewLine) { it.state.handleEnter() })

	register(
		EditorActionSpec(
			action = Action.ShowContextMenu,
			isEnabled = { it.state.contextMenuOpeners.isNotEmpty() },
			perform = { it.state.contextMenuOpeners.lastOrNull()?.invoke() },
		)
	)

	registerFormattingToggle(Action.ToggleBold) { it.boldStyle }
	registerFormattingToggle(Action.ToggleItalic) { it.italicStyle }
	registerFormattingToggle(Action.ToggleUnderline) { it.underlineStyle }
	registerFormattingToggle(Action.ToggleStrikethrough) { it.strikethroughStyle }
	registerFormattingToggle(Action.ToggleInlineCode) { it.codeStyle }
	register(EditorActionSpec(Action.ClearFormatting) { it.state.clearFormatting() })
	register(
		EditorActionSpec(
			action = Action.Unlink,
			isEnabled = { it.state.linksAtSelection().isNotEmpty() },
			perform = { it.state.unlink() },
		)
	)
}

/** Reads the style at invocation, so a later markdown configuration change is honoured. */
private fun EditorActionRegistry.registerFormattingToggle(
	action: Action,
	style: (MarkdownConfiguration) -> SpanStyle,
) {
	register(EditorActionSpec(action) { ctx ->
		ctx.state.toggleSpanStyle(style(ctx.state.markdownConfiguration))
	})
}

private fun EditorActionContext.copySelection() {
	val selection = state.selector.selection ?: return
	val write = writeSelection(selection)
	scope.launch(start = CoroutineStart.UNDISPATCHED) { write() }
}

/**
 * Deletes the selection only once the clipboard holds it, so a refused write (the
 * web's permission, AWT's busy clipboard) leaves the text in place. Undispatched, so
 * where the write does not suspend the delete lands before the action returns. A
 * change to the text or the selection while it does suspend makes the cut a copy.
 */
private fun EditorActionContext.cutSelection() {
	val selection = state.selector.selection ?: return
	val textRevision = state.textRevision
	val write = writeSelection(selection)
	scope.launch(start = CoroutineStart.UNDISPATCHED) {
		if (!write()) return@launch
		if (state.textRevision != textRevision || state.selector.selection != selection) return@launch
		state.preserveCopiedRichSpansThroughNextEdit()
		state.selector.deleteSelection()
	}
}

/**
 * Reads [selection] for the clipboard as the document stands, and answers a write of
 * it that reports whether the clipboard took it. A refused write puts back the
 * rich-span buffer this copy replaced, which still describes the clipboard's content.
 */
private fun EditorActionContext.writeSelection(selection: TextEditorRange): suspend () -> Boolean {
	val selectedText = state.selector.getSelectedText()
	val html = state.selectionAsHtml(selection)
	val previousBuffer = state.richSpanBuffer
	val copyId = state.copyRichSpans(selection)
	val buffer = state.richSpanBuffer
	val textRevision = state.textRevision
	return {
		val written = ClipboardHelper.setText(clipboard, selectedText, state.markdownConfiguration, copyId, html)
		if (!written && state.richSpanBuffer === buffer && state.textRevision == textRevision) {
			state.richSpanBuffer = previousBuffer
		}
		written
	}
}

/**
 * [plainText] keeps only the clipboard's characters: no copied styling, rich spans or
 * block structure, so the text takes the styling of wherever it lands.
 */
private fun EditorActionContext.pasteClipboard(plainText: Boolean) {
	scope.launch {
		val clipboardText = if (plainText) {
			ClipboardHelper.getPlainText(clipboard)?.let(::AnnotatedString)
		} else {
			ClipboardHelper.getText(clipboard, state.markdownConfiguration)
		}
		clipboardText?.let {
			val curSelection = state.selector.selection
			val insertPosition = curSelection?.start ?: state.cursorPosition
			val sized = state.withSizeForPasteAt(insertPosition, it.normalizeLineEndings())
			// Screened first: the copied spans and blocks are placed by the text's own
			// layout, so text the filter changed pastes plain, and refused text not at all.
			val text = state.screenAtSelection(sized) ?: return@launch
			val screened = text != sized
			// Read the clipboard's HTML before mutating: the text, the in-editor
			// rich spans and the pasted block structure then land as one revision.
			val htmlDocument = if (plainText) null else state.readHtmlPasteDocument(clipboard, text)
			val clipboardCopyId = if (plainText) null else ClipboardHelper.readCopyId(clipboard)
			state.preserveCopiedRichSpansThroughNextEdit()
			state.withAtomicEdit {
				if (curSelection != null) {
					state.replace(curSelection, state.applyStyleForEditAt(curSelection.start, text))
				} else {
					state.insertStringAtCursor(text)
				}
				if (!plainText && !screened) {
					state.pasteRichSpans(
						insertPosition,
						text,
						clipboardCopyId,
						requireCopyIdMatch = ClipboardHelper.supportsCopyProvenance,
					)
				}
				if (!screened) htmlDocument?.let { state.applyHtmlPasteBlocks(it, insertPosition, text) }
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

private enum class Kill { Forward, Backward }

/**
 * Deletes between the caret and wherever [locateRangeEdge] moves it, or the selection when
 * there is one, keeping what goes in the kill ring when this is a [kill]. The caret the
 * user had is handed to [TextEditorState.delete] explicitly: [locateRangeEdge] has already
 * moved it off that position, and delete otherwise records wherever the caret currently
 * sits as the position undo returns to.
 */
private fun TextEditorState.deleteByMotion(kill: Kill? = null, locateRangeEdge: () -> Unit) {
	val continuesKill = kill != null && killRing.continuesAt(this)
	selector.selection?.let { selection ->
		val killed = kill?.let { getTextInRange(selection) }
		selector.deleteSelection()
		// Selecting came between, so a selection starts a kill of its own.
		if (killed != null) killRing.add(this, killed, kill == Kill.Backward, continues = false)
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
	val killed = kill?.let { getTextInRange(range) }
	// Never typing, even over one character: a backspace after it is its own step.
	editManager.recordingAsTyping(false) { delete(range, cursorBefore = origin) }
	if (killed != null) killRing.add(this, killed, kill == Kill.Backward, continuesKill)
}

/** Inserts the kill ring's text over the selection, or at the caret, as one step. */
private fun TextEditorState.yank() {
	val text = killRing.text ?: return
	editManager.recordingAsTyping(false) {
		val selection = selector.selection
		if (selection != null) {
			replace(selection, applyStyleForEditAt(selection.start, text))
		} else {
			insertStringAtCursor(text)
		}
	}
	selector.clearSelection()
}

/**
 * Past the last character of the caret's visual row, as a plain position: on a
 * wrapped row that is the wrap offset, which End places upstream and this leaves
 * downstream, since it only bounds a delete.
 */
private fun TextEditorState.moveCursorToVisualRowEnd() {
	val position = cursorPosition
	val row = cursorRowIndex()
	val nextRow = lineOffsets.getOrNull(row + 1)
	val end = if (row >= 0 && nextRow != null && nextRow.line == position.line) {
		nextRow.wrapStartsAtIndex
	} else {
		textLines[position.line].length
	}
	cursor.updatePosition(position.copy(char = end))
}

private fun TextEditorState.deleteToParagraphEnd() {
	val position = cursorPosition
	val lineLength = textLines[position.line].length
	if (selector.selection == null && position.char == lineLength) {
		val continuesKill = killRing.continuesAt(this)
		val lineCount = textLines.size
		deleteAtCursor()
		// A behavior may claim the delete and keep the line break.
		if (textLines.size < lineCount) {
			killRing.add(this, AnnotatedString("\n"), backward = false, continues = continuesKill)
		}
	} else {
		deleteByMotion(kill = Kill.Forward) { cursor.updatePosition(position.copy(char = lineLength)) }
	}
}

private fun TextEditorState.handleIndent() = editGroup {
	val selection = selector.selection
	if (selection != null && selection.start.line != selection.end.line) {
		indentLineRange(selection.start.line, selection.end.line)
	} else {
		val at = selection?.start ?: cursorPosition
		// At a list item's start Tab nests the item one level (5.6); inside its
		// text it still inserts, as Word has it. Leading spaces in an item do not
		// survive a markdown round trip, so a nest that is not allowed does nothing.
		if (at.char == 0 && isListItem(at.line)) {
			nestListItems(at.line..at.line)
			return@editGroup
		}
		if (selection != null) {
			selector.deleteSelection()
		}
		insertStringAtCursor(tabSettings.indentText)
	}
}

private fun TextEditorState.isListItem(line: Int): Boolean = listBlockAt(line) != null

private fun TextEditorState.handleOutdent() = editGroup {
	val selection = selector.selection
	if (selection != null) {
		unnestListItems(selection.start.line..selection.end.line)
		outdentLineRange(selection.start.line, selection.end.line)
	} else if ((listBlockAt(cursorPosition.line)?.listLevel ?: 0) > 0) {
		// Shift+Tab anywhere in a nested item un-nests it, as Google Docs has it;
		// a top-level item has only its leading spaces to give (2.9).
		unnestListItems(cursorPosition.line..cursorPosition.line)
	} else {
		outdentCurrentLine()
	}
}

/** Nests the list items in the range and indents the other lines, as Tab does on each alone. */
private fun TextEditorState.indentLineRange(startLine: Int, endLine: Int) {
	nestListItems(startLine..endLine)
	val lines = (startLine..endLine).filterNot { isListItem(it) }
	if (lines.isEmpty()) return
	val prefix = tabSettings.indentText
	for (line in lines) {
		val start = CharLineOffset(line, 0)
		replace(TextEditorRange(start, start), prefix)
	}
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
			val remove = leadingOutdentWidth(line, tabSettings.size)
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
	val remove = leadingOutdentWidth(textLines[line], tabSettings.size)
	if (remove == 0) return

	val cursorChar = cursorPosition.char
	delete(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, remove)))
	cursor.updatePosition(CharLineOffset(line, (cursorChar - remove).coerceAtLeast(0)))
}

/** Leading indentation to strip for one outdent level: a single hard tab, else up to [tabSize] spaces. */
private fun leadingOutdentWidth(line: AnnotatedString, tabSize: Int): Int {
	if (line.isEmpty()) return 0
	if (line[0] == '\t') return 1
	var count = 0
	while (count < tabSize && count < line.length && line[count] == ' ') count++
	return count
}

private fun TextEditorState.handleEnter() = insertTypedNewline()
