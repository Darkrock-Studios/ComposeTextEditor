package com.darkrockstudios.texteditor

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.cutText
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.maxTextLength
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onImeAction
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.cursor.getWrapForDrawing
import com.darkrockstudios.texteditor.html.sanitizeLinkUrl
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.selectionAsTextRange
import com.darkrockstudios.texteditor.input.startsLine
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.CaretAffinity
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import com.darkrockstudios.texteditor.state.FocusedEditor
import com.darkrockstudios.texteditor.state.SpanIndex
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.applyStyleForEditAt
import com.darkrockstudios.texteditor.state.insertStyledAtCursor
import com.darkrockstudios.texteditor.state.landingOutsideText
import com.darkrockstudios.texteditor.state.insertTypedNewline
import com.darkrockstudios.texteditor.state.screenAtSelection
import com.darkrockstudios.texteditor.state.typedInput

/**
 * Publishes the editor as a text field, following `BasicTextField`: accessibility
 * services (TalkBack, VoiceOver) read and edit the content through it, and on iOS it
 * is what exposes the focused editor as a keyboard-focused text element, without which
 * XCUITest cannot type into it. A disabled editor is reported as such and offers
 * nothing that edits.
 *
 * `onImeAction` is offered only for an action key other than Enter, the one the
 * keyboard shows ([TextEditorState.effectiveImeAction]) for this editor's own line limit:
 * a multi-line editor's default is Enter, a new line, where `BasicTextField`'s default
 * action does nothing either, and a single line's is Done.
 *
 * An edit or action here is aimed at this editor, focused or not, so it follows this
 * [editor]'s line limit and default action rather than the focused one's.
 *
 * Nothing autofill reads is published (no content type, data type or `onFillData`), so
 * password managers leave the editor alone. `ContentDataType.None` would not be quieter:
 * Android's autofill structure would then carry the node with the whole document as its
 * value.
 */
internal fun Modifier.editorSemantics(
	state: TextEditorState,
	enabled: Boolean,
	editable: Boolean,
	singleLine: Boolean,
	editor: () -> FocusedEditor?,
	focusRequester: FocusRequester,
	actions: ContextMenuActions,
	contentDescription: String?,
	document: SemanticsDocument,
): Modifier {
	return semantics {
		editableText = document.text()
		textSelectionRange = state.selectionAsTextRange()
		state.composingRange?.let {
			textCompositionRange = TextRange(state.getCharacterIndex(it.start), state.getCharacterIndex(it.end))
		}
		isEditable = editable
		state.inputFilter?.maxLength?.let { maxTextLength = it }
		contentDescription?.let { this.contentDescription = it }
		getTextLayoutResult { results -> document.addLayoutTo(results) }
		this[CharacterBoundsKey] = document
		clipboardActions(actions)
		longPressOpensMenu(focusRequester, actions)
		editorSemanticsEdits(state, enabled, editable, editor)
		val imeAction = state.effectiveImeAction(singleLine)
		if (editable && !imeAction.startsLine) {
			onImeAction(imeAction) { state.asEditor(editor()) { state.performImeAction(imeAction) } }
		}
		selectionSemantics(state)
		onClick { focusRequester.requestFocus(); true }
	}
}

private fun SemanticsPropertyReceiver.editorSemanticsEdits(
	state: TextEditorState,
	enabled: Boolean,
	editable: Boolean,
	editor: () -> FocusedEditor?,
) {
	if (!enabled) {
		disabled()
	} else if (editable) {
		setText { newText ->
			state.asEditor(editor()) { state.replaceAllAsEdit(newText) }
			true
		}
		insertTextAtCursor { inserted -> state.asEditor(editor()) { insertAtCursor(state, inserted) } }
	}
}

private fun insertAtCursor(state: TextEditorState, inserted: AnnotatedString): Boolean {
	val newText = inserted.normalizeLineEndings()
	if (newText.text == "\n") {
		val before = state.revision
		state.insertTypedNewline()
		return state.revision != before
	}
	// Dictated or assistive text: one step that is not typing, since whole phrases are
	// not something a following keystroke should join, then told to the behaviors like
	// any typed text.
	val admitted = state.screenAtSelection(newText) ?: return false
	state.typedInput {
		state.editGroup {
			state.selector.deleteSelection()
			state.editManager.alreadyScreened {
				state.editManager.recordingAsTyping(false) {
					state.insertStyledAtCursor(admitted)
				}
			}
		}
	}
	return true
}

/** Copy while there is a selection; cut and paste while the editor may be edited. */
internal fun SemanticsPropertyReceiver.clipboardActions(actions: ContextMenuActions) {
	if (actions.canCopy()) copyText { actions.copy(); true }
	if (actions.canCut()) cutText { actions.cut(); true }
	if (actions.canPaste()) pasteText { actions.paste(); true }
}

/** Focuses, then opens the context menu, as a long press does. */
internal fun SemanticsPropertyReceiver.longPressOpensMenu(focusRequester: FocusRequester, actions: ContextMenuActions) {
	onLongClick {
		focusRequester.requestFocus()
		actions.canPerform(Action.ShowContextMenu).also { if (it) actions.perform(Action.ShowContextMenu) }
	}
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
 * Where each character of a text node is drawn, answered from the editor's rows, for a
 * platform bridge that can ask for it instead of measuring `getTextLayoutResult`'s
 * whole-document layout, which cannot carry the content padding or the scroll.
 */
internal interface CharacterBounds {
	/**
	 * The character at flat [index] as drawn, in root coordinates, or null when the index
	 * is out of range or the text is not laid out. A line break is a zero-width box at
	 * the end of its row.
	 */
	fun boundsOf(index: Int): Rect?

	/** [boundsOf] for each of [indices]. */
	fun boundsOf(indices: IntRange): List<Rect?> = indices.map(::boundsOf)

	/**
	 * The caret position nearest [position], in root coordinates, as a flat character
	 * index, or -1 when the text is not laid out.
	 */
	fun indexAt(position: Offset): Int
}

/** Never merged into an ancestor: the bounds are only this node's text's. */
internal val CharacterBoundsKey = SemanticsPropertyKey<CharacterBounds>(
	name = "CharacterBounds",
	mergePolicy = { parentValue, _ -> parentValue },
)

/**
 * What a platform's request for the boxes of [length] characters from [start] of node
 * [id], somewhere under this one, gets from the node's [CharacterBounds]: as Compose
 * answers it from `getTextLayoutResult`, each box in root coordinates clipped to the
 * node's bounds, and null where it falls outside them or past the text. Null when the
 * node publishes no [CharacterBounds], or for a negative start, no length or a start
 * past the text, which leaves the request to Compose.
 *
 * The start is checked against the node's text, where Compose checks it against the
 * content description when there is one, and so refuses most of an editor's text.
 */
internal fun SemanticsNode.characterLocations(id: Int, start: Int, length: Int): Array<Rect?>? {
	if (start < 0 || length <= 0) return null
	val node = findById(id) ?: return null
	val bounds = node.config.getOrNull(CharacterBoundsKey) ?: return null
	val text = node.config.getOrNull(SemanticsProperties.EditableText)
		?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()
		?: return null
	if (start >= text.length) return null
	val visible = node.boundsInRoot
	val boxes = arrayOfNulls<Rect>(length)
	val inText = bounds.boundsOf(start until minOf(text.length, start + length))
	inText.forEachIndexed { i, box -> boxes[i] = box?.takeIf { it.overlaps(visible) }?.intersect(visible) }
	return boxes
}

private fun SemanticsNode.findById(id: Int): SemanticsNode? {
	val pending = ArrayDeque<SemanticsNode>()
	pending.addLast(this)
	while (pending.isNotEmpty()) {
		val node = pending.removeLast()
		if (node.id == id) return node
		pending.addAll(node.children)
	}
	return null
}

/**
 * Where the canvas the rows are drawn on was last placed, its origin at the content
 * origin. Kept apart from the [SemanticsDocument], which is rebuilt when links start
 * or stop being published while the canvas stays where it is.
 */
internal class CanvasPlacement {
	var coordinates: LayoutCoordinates? = null
}

/**
 * The document as accessibility services see it, each part cached per revision (a
 * snapshot, compared by identity, since every edit publishes a new one). Links are
 * published only with an [onLinkClick] to open them.
 */
internal class SemanticsDocument(
	private val state: TextEditorState,
	private val canvas: CanvasPlacement = CanvasPlacement(),
	onLinkClick: ((String) -> Unit)?,
) : CharacterBounds {
	private val linkListener = onLinkClick?.let { open ->
		LinkInteractionListener { link -> open((link as LinkAnnotation.Url).url) }
	}

	private var textContent: DocumentSnapshot? = null
	private var text = AnnotatedString("")

	/**
	 * The links found in each span chunk of the last revision read, by chunk identity:
	 * a revision shares every chunk an edit did not rewrite, so only the rewritten ones
	 * are scanned again.
	 */
	private var chunkLinks: Map<SpanIndex.Chunk, List<ChunkLink>> = emptyMap()

	/** The allowlist [chunkLinks] was found under. */
	private var linkSchemes: Set<String>? = null

	/** How many span chunks [text] has scanned for links, for the cost tests. */
	internal var chunksScanned = 0

	/** A link on line [line] of its chunk, over columns [start] to [end]. */
	private class ChunkLink(val line: Int, val start: Int, val end: Int, val link: LinkAnnotation.Url)

	/**
	 * The text, with each link as a [LinkAnnotation.Url] that TalkBack lists and opens
	 * through the host's link handler. Reads [TextEditorState.revision], so a semantics
	 * block calling this re-runs after every edit, even one that leaves the caret in
	 * place.
	 */
	fun text(): AnnotatedString {
		state.revision
		val content = state.snapshot()
		val schemes = state.allowedLinkSchemes
		if (schemes != linkSchemes) {
			linkSchemes = schemes
			chunkLinks = emptyMap()
			textContent = null
		}
		if (content !== textContent) {
			text = content.textWithLinks()
			textContent = content
		}
		return text
	}

	private fun DocumentSnapshot.textWithLinks(): AnnotatedString {
		val all = getAllText()
		val listener = linkListener ?: return all
		fun indexOf(line: Int, char: Int): Int {
			val lineStart = if (line in 0..lines.size) lineStart(line) else all.length
			return (lineStart + char).coerceIn(0, all.length)
		}

		val links = ArrayList<AnnotatedString.Range<LinkAnnotation.Url>>()
		fun add(link: LinkAnnotation.Url, start: Int, end: Int) {
			if (start < end) links += AnnotatedString.Range(link, start, end)
		}

		val chunks = spanIndex.chunks
		val found = HashMap<SpanIndex.Chunk, List<ChunkLink>>(chunks.size * 4 / 3 + 1)
		var chunkStart = 0
		for (chunk in chunks) {
			val inChunk = chunkLinks[chunk] ?: chunk.links(listener).also { chunksScanned++ }
			found[chunk] = inChunk
			for (link in inChunk) {
				val line = chunkStart + link.line
				add(link.link, indexOf(line, link.start), indexOf(line, link.end))
			}
			chunkStart += chunk.size
		}
		chunkLinks = found
		for (span in spanIndex.loose) {
			val style = span.style as? LinkSpanStyle ?: continue
			val url = sanitizeLinkUrl(style.url, state.allowedLinkSchemes) ?: continue
			add(
				LinkAnnotation.Url(url, linkInteractionListener = listener),
				indexOf(span.range.start.line, span.range.start.char),
				indexOf(span.range.end.line, span.range.end.char),
			)
		}
		if (links.isEmpty()) return all
		return buildAnnotatedString {
			append(all)
			for (link in links) addLink(link.item, link.start, link.end)
		}
	}

	// A destination the allowlist refuses is not published as a link.
	private fun SpanIndex.Chunk.links(listener: LinkInteractionListener): List<ChunkLink> = buildList {
		lines.forEachIndexed { line, spans ->
			for (span in spans) {
				val style = span.style as? LinkSpanStyle ?: continue
				val url = sanitizeLinkUrl(style.url, state.allowedLinkSchemes) ?: continue
				add(ChunkLink(line, span.start, span.end, LinkAnnotation.Url(url, linkInteractionListener = listener)))
			}
		}
	}

	/** The whole document laid out as one text, for `getTextLayoutResult`; see [SemanticsLayout]. */
	fun addLayoutTo(results: MutableList<TextLayoutResult>): Boolean {
		results.add(state.semanticsLayout.get() ?: return false)
		return true
	}

	/**
	 * Measures nothing: a line the draw has not shaped at the current width yet (only
	 * lines out of view, after a width change) answers from its provisional rows. A line
	 * whose block replaces its text answers the block's row.
	 */
	override fun boundsOf(index: Int): Rect? = boundsOf(index..index).single()

	override fun boundsOf(indices: IntRange): List<Rect?> {
		val canvas = canvas.coordinates?.takeIf { it.isAttached }
		if (canvas == null || !state.rowsFollowText) return indices.map { null }
		val toRoot = Matrix()
		canvas.findRootCoordinates().transformFrom(canvas, toRoot)
		val onCanvas = documentToCanvas()
		val length = state.getTextLength()
		return indices.map { index -> if (index in 0 until length) inDocument(index)?.let { toRoot.map(it.translate(onCanvas)) } else null }
	}

	/** The box of the character at [index], in document space. */
	private fun inDocument(index: Int): Rect? {
		val position = state.getOffsetAtCharacter(index)
		val row = state.lineOffsets.getWrapForDrawing(position, CaretAffinity.Downstream)
			?.takeIf { it.line == position.line } ?: return null
		val layout = row.textLayoutResult
		val laidOut = layout.layoutInput.text.length
		return when {
			row.richSpans.any { (it.style as? BlockSpanStyle)?.replacesText() == true } ->
				Rect(0f, row.offset.y, state.viewportSize.width, row.offset.y + row.effectiveHeight)
			// The draw anchors a line's layout at its paragraph's top.
			position.char < minOf(laidOut, state.textLines[position.line].length) ->
				layout.getBoundingBox(position.char).translate(row.offset.x, row.paragraphTop)
			else -> layout.getCursorRect(position.char.coerceAtMost(laidOut))
				.let { Rect(it.left, it.top, it.left, it.bottom) }
				.translate(row.offset.x, row.paragraphTop)
		}
	}

	override fun indexAt(position: Offset): Int {
		val canvas = canvas.coordinates?.takeIf { it.isAttached } ?: return -1
		if (!state.rowsFollowText) return -1
		val local = canvas.localPositionOf(canvas.findRootCoordinates(), position)
		return state.getCharacterIndex(state.getOffsetAtPosition(local))
	}

	/** Where document space's origin sits on the canvas. */
	private fun documentToCanvas() = Offset(0f, -state.scrollState.value.toFloat())
}

/**
 * Replaces the whole text with [text] as one undoable edit of only the part that
 * differs. The common prefix and suffix are left in place, so their character styles
 * and rich spans survive, and a line's block marker stays on an insertion at its
 * start. The new part takes the style typing there would, unless it carries
 * formatting of its own; [text]'s formatting elsewhere is not applied. The edit
 * is never typing, so a following keystroke is a step of its own, and the new part
 * is then offered to the [com.darkrockstudios.texteditor.state.EditBehavior]s like
 * any inserted text. Unlike [TextEditorState.setText] this keeps the undo history.
 */
internal fun TextEditorState.replaceAllAsEdit(text: AnnotatedString) {
	val newText = text.normalizeLineEndings()
	val old = getAllPlainText()
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
	val before = revision
	editManager.recordingAsTyping(false) {
		when {
			middle.isEmpty() -> delete(range)
			// An insertion, not a replace of nothing: only an insert keeps a line's
			// block marker at its start.
			range.start == range.end -> landingOutsideText(middle) {
				val landed = editManager.applyLanded(
					TextEditOperation.Insert(
						position = start,
						text = middle,
						cursorBefore = cursorPosition,
						cursorAfter = start.after(middle.text),
					)
				) as TextEditOperation.Insert?
				landed?.let { TextEditorRange(it.position, it.textEnd) }
			}
			else -> landingOutsideText(middle) { replace(range, middle) }
		}
	}
	// What landed, which an input filter may have changed; the caret ends after it.
	if (middle.isNotEmpty() && revision != before) {
		val landed = TextEditorRange(start, cursorPosition)
		textInputLanded(getStringInRange(landed), landed)
	}
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
