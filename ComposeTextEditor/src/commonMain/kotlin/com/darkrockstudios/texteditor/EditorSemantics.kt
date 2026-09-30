package com.darkrockstudios.texteditor

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.cutText
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.pasteText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.textCompositionRange
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.selectionAsTextRange
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.applyStyleForEditAt
import com.darkrockstudios.texteditor.state.insertTypedNewline
import com.darkrockstudios.texteditor.state.typedInput

/**
 * Publishes the editor as a text field, following `BasicTextField`: accessibility
 * services (TalkBack, VoiceOver) read and edit the content through it, and on iOS it
 * is what exposes the focused editor as a keyboard-focused text element, without which
 * XCUITest cannot type into it. A disabled editor is reported as such and offers
 * nothing that edits.
 *
 * There is no `onImeAction`: the editor is multi-line, where `BasicTextField`'s default
 * action does nothing either.
 */
internal fun Modifier.editorSemantics(
	state: TextEditorState,
	enabled: Boolean,
	focusRequester: FocusRequester,
	actions: ContextMenuActions,
	contentDescription: String?,
	onLinkClick: (String) -> Unit,
): Modifier {
	val document = SemanticsDocument(state, onLinkClick)
	return semantics {
		editableText = document.text()
		textSelectionRange = state.selectionAsTextRange()
		state.composingRange?.let {
			textCompositionRange = TextRange(state.getCharacterIndex(it.start), state.getCharacterIndex(it.end))
		}
		isEditable = enabled
		contentDescription?.let { this.contentDescription = it }
		getTextLayoutResult { results -> document.addLayoutTo(results) }
		clipboardActions(actions)
		onLongClick {
			focusRequester.requestFocus()
			actions.canPerform(Action.ShowContextMenu).also { if (it) actions.perform(Action.ShowContextMenu) }
		}
		editorSemanticsEdits(state, enabled)
		selectionSemantics(state)
		onClick { focusRequester.requestFocus(); true }
	}
}

private fun SemanticsPropertyReceiver.editorSemanticsEdits(state: TextEditorState, enabled: Boolean) {
	if (!enabled) {
		disabled()
	} else {
		setText { newText ->
			state.replaceAllAsEdit(newText)
			true
		}
		insertTextAtCursor { inserted ->
			val newText = inserted.normalizeLineEndings()
			if (newText.text == "\n") {
				state.insertTypedNewline()
			} else {
				// Dictated or assistive text: one step that is not typing, since
				// whole phrases are not something a following keystroke should
				// join, then told to the behaviors like any typed text.
				state.typedInput(newText.text) {
					state.editGroup {
						state.selector.deleteSelection()
						state.editManager.recordingAsTyping(false) {
							state.insertStringAtCursor(newText)
						}
					}
				}
			}
			true
		}
	}
}

/** Copy while there is a selection; cut and paste while the editor may be edited. */
internal fun SemanticsPropertyReceiver.clipboardActions(actions: ContextMenuActions) {
	if (actions.canCopy()) copyText { actions.copy(); true }
	if (actions.canCut()) cutText { actions.cut(); true }
	if (actions.canPaste()) pasteText { actions.paste(); true }
}

/** Places the caret, or selects, at the flat character offsets a screen reader asks for. */
internal fun SemanticsPropertyReceiver.selectionSemantics(state: TextEditorState) {
	setSelection { start, end, _ ->
		val length = state.getTextLength()
		val from = start.coerceIn(0, length)
		val to = end.coerceIn(0, length)
		if (from == to) {
			state.cursor.updatePosition(state.getOffsetAtCharacter(from))
			state.selector.clearSelection()
		} else {
			state.selector.updateSelection(
				state.getOffsetAtCharacter(from),
				state.getOffsetAtCharacter(to),
			)
			state.cursor.updatePosition(state.getOffsetAtCharacter(to))
		}
		true
	}
}

/**
 * The document as accessibility services see it, each part cached per revision (a
 * snapshot, compared by identity, since every edit publishes a new one).
 */
internal class SemanticsDocument(
	private val state: TextEditorState,
	onLinkClick: (String) -> Unit,
) {
	private val linkListener = LinkInteractionListener { link -> onLinkClick((link as LinkAnnotation.Url).url) }

	private var textContent: DocumentSnapshot? = null
	private var text = AnnotatedString("")

	private var layoutContent: DocumentSnapshot? = null
	private var layoutWidth = 0
	private var layoutStyle: TextStyle? = null
	private var layoutMeasurer: TextMeasurer? = null
	private var layout: TextLayoutResult? = null

	/**
	 * The text, with each link as a [LinkAnnotation.Url] that TalkBack lists and opens
	 * through the host's link handler. Reads [TextEditorState.lineOffsets] as well: the
	 * document is not snapshot state, but every edit publishes a new layout, which is,
	 * so a semantics block calling this re-runs after an edit that leaves the caret in
	 * place.
	 */
	fun text(): AnnotatedString {
		state.lineOffsets
		val content = state.snapshot()
		if (content !== textContent) {
			text = content.textWithLinks()
			textContent = content
		}
		return text
	}

	private fun DocumentSnapshot.textWithLinks(): AnnotatedString {
		val all = getAllText()
		val links = richSpans.filter { it.style is LinkSpanStyle }
		if (links.isEmpty()) return all
		val starts = lineStartOffsets
		fun indexOf(position: CharLineOffset) =
			(starts.getOrElse(position.line) { all.length } + position.char).coerceIn(0, all.length)
		return buildAnnotatedString {
			append(all)
			for (link in links) {
				val start = indexOf(link.range.start)
				val end = indexOf(link.range.end)
				if (start < end) {
					addLink(LinkAnnotation.Url((link.style as LinkSpanStyle).url, linkInteractionListener = linkListener), start, end)
				}
			}
		}
	}

	/**
	 * The whole document laid out as one text, for `getTextLayoutResult`: screen readers
	 * read line boundaries and character bounds from it. The editor shapes each line on
	 * its own, so this is measured separately, on request, the way the editor measures a
	 * line (the same width, and the outer indent baked into plain lines). Its line breaks
	 * match the editor's rows; its geometry leaves out the content padding, the scroll
	 * offset and the height of block spans (7.36).
	 */
	fun addLayoutTo(results: MutableList<TextLayoutResult>): Boolean {
		val width = maxOf(1, state.viewportSize.width.toInt())
		val content = state.snapshot()
		val current = layout?.takeIf {
			content === layoutContent && width == layoutWidth &&
				state.textStyle == layoutStyle && state.textMeasurer === layoutMeasurer
		} ?: measure(content, width)?.also {
			layout = it
			layoutContent = content
			layoutWidth = width
			layoutStyle = state.textStyle
			layoutMeasurer = state.textMeasurer
		} ?: return false
		results.add(current)
		return true
	}

	private fun measure(content: DocumentSnapshot, width: Int): TextLayoutResult? {
		val style = state.textStyle
		val outerIndent = style.textIndent?.takeIf { it != TextIndent.None }
		val lines = content.lines
		val text = if (outerIndent == null && lines.none { it.paragraphStyles.isNotEmpty() }) {
			content.getAllText()
		} else {
			// Every line becomes its own paragraph, with its block's style or the baked
			// indent. A paragraph style already breaks the line, so the line break between
			// two becomes a zero-width space inside the first: a line break there would add
			// an empty row, and the offsets must stay the document's.
			val plain = outerIndent?.let { ParagraphStyle(textIndent = it) } ?: ParagraphStyle()
			buildAnnotatedString {
				lines.forEachIndexed { index, line ->
					withStyle(line.paragraphStyles.firstOrNull()?.item ?: plain) {
						append(AnnotatedString(line.text, line.spanStyles))
						if (index < lines.lastIndex) append(ZERO_WIDTH_SPACE)
					}
				}
			}
		}
		return try {
			state.textMeasurer.measure(
				text = text,
				style = if (outerIndent == null) style else style.copy(textIndent = TextIndent.None),
				constraints = Constraints.fixedWidth(width),
				skipCache = true,
			)
		} catch (_: IllegalArgumentException) {
			null
		}
	}
}

private const val ZERO_WIDTH_SPACE = '​'

/**
 * Replaces the whole text with [newText] as one undoable edit of only the part that
 * differs. The common prefix and suffix are left in place, so their character styles
 * and rich spans survive, and a line's block marker stays on an insertion at its
 * start. The new part takes the style typing there would, unless it carries
 * formatting of its own; [newText]'s formatting elsewhere is not applied. The edit
 * is never typing, so a following keystroke is a step of its own, and the new part
 * is then offered to the [com.darkrockstudios.texteditor.state.EditBehavior]s like
 * any inserted text. Unlike [TextEditorState.setText] this keeps the undo history.
 */
internal fun TextEditorState.replaceAllAsEdit(newText: AnnotatedString) {
	val old = getAllText().text
	val new = newText.text
	val shorter = minOf(old.length, new.length)
	var prefix = 0
	while (prefix < shorter && old[prefix] == new[prefix]) prefix++
	if (prefix == old.length && prefix == new.length) return
	if (prefix > 0 && old[prefix - 1].isHighSurrogate()) prefix--
	var suffix = 0
	while (suffix < shorter - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
	if (suffix > 0 && old[old.length - suffix].isLowSurrogate()) suffix--

	val start = getOffsetAtCharacter(prefix)
	val range = TextEditorRange(start, getOffsetAtCharacter(old.length - suffix))
	val middle = applyStyleForEditAt(start, newText.subSequence(prefix, new.length - suffix))
	editManager.recordingAsTyping(false) {
		when {
			middle.isEmpty() -> delete(range)
			// An insertion, not a replace of nothing: only an insert keeps a line's
			// block marker at its start.
			range.start == range.end -> editManager.applyOperation(
				TextEditOperation.Insert(
					position = start,
					text = middle,
					cursorBefore = cursorPosition,
					cursorAfter = start.after(middle.text),
				)
			)
			else -> replace(range, middle)
		}
	}
	if (middle.isNotEmpty()) textInputLanded(middle.text, TextEditorRange(start, start.after(middle.text)))
}

/** Where [text] inserted at this position ends. */
private fun CharLineOffset.after(text: String): CharLineOffset {
	val lastBreak = text.lastIndexOf('\n')
	return if (lastBreak < 0) {
		CharLineOffset(line, char + text.length)
	} else {
		CharLineOffset(line + text.count { it == '\n' }, text.length - lastBreak - 1)
	}
}
