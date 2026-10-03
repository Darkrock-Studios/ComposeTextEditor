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
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.html.sanitizeLinkUrl
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.selectionAsTextRange
import com.darkrockstudios.texteditor.input.startsLine
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import com.darkrockstudios.texteditor.state.FocusedEditor
import com.darkrockstudios.texteditor.state.RowList
import com.darkrockstudios.texteditor.state.SpanIndex
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.applyStyleForEditAt
import com.darkrockstudios.texteditor.state.insertStyledAtCursor
import com.darkrockstudios.texteditor.state.landingOutsideText
import com.darkrockstudios.texteditor.state.insertTypedNewline
import com.darkrockstudios.texteditor.state.screenAtSelection
import com.darkrockstudios.texteditor.state.typedInput
import kotlin.math.abs

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
	onLinkClick: ((String) -> Unit)?,
): Modifier {
	val document = SemanticsDocument(state, onLinkClick)
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
 * The document as accessibility services see it, each part cached per revision (a
 * snapshot, compared by identity, since every edit publishes a new one). Links are
 * published only with an [onLinkClick] to open them.
 */
internal class SemanticsDocument(
	private val state: TextEditorState,
	onLinkClick: ((String) -> Unit)?,
) {
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

	private var layoutInput: LayoutInput? = null
	private var layout: TextLayoutResult? = null

	/** What [layoutInput] was built from: while it stands, it would build the same input. */
	private var layoutSources: LayoutSources? = null

	/** Compared by identity for the document and rows, since comparing rows by content would build every row. */
	private class LayoutSources(
		val content: DocumentSnapshot,
		val rows: List<LineWrap>,
		val width: Float,
		val style: TextStyle,
		val measurer: TextMeasurer,
		val density: Density?,
	) {
		fun same(other: LayoutSources?): Boolean = other != null && content === other.content && rows === other.rows &&
			width == other.width && style == other.style && measurer === other.measurer && density == other.density
	}

	/** What the semantics layout is measured from; an equal one measures the same. */
	private data class LayoutInput(
		val text: AnnotatedString,
		val style: TextStyle,
		val placeholders: List<AnnotatedString.Range<Placeholder>>,
		/** For each placeholder, the line whose last row it stretches and how far below that row's top the next line starts. */
		val steps: List<RowStep>,
		val width: Int,
		val measurer: TextMeasurer,
		val density: Density?,
	)

	private data class RowStep(val line: Int, val height: Float)

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

	/**
	 * The whole document laid out as one text, for `getTextLayoutResult`: screen readers
	 * read line boundaries and character bounds from it. A `TextLayoutResult` is one
	 * measured text and cannot be put together from the editor's per-line layouts, so
	 * this is measured on request from the editor's rows: each line as the editor shaped
	 * it (its block's or format's paragraph style, the baked outer indent) at the same
	 * width, so the rows break and align as drawn, and the space the editor leaves
	 * between rows (a block's height, paragraph spacing) as a placeholder on the last row
	 * above it. A request after a change that left all of that alone (a span pass) reuses
	 * the last layout; any text edit measures the whole document again.
	 *
	 * What cannot match: the layout starts at the first row's top and the text's left
	 * edge, so it leaves out the content padding, the space above the first paragraph and
	 * in the editor the scroll offset (Compose has no way to move a layout, and moving the
	 * semantics node would move the field's bounds with it); a block shorter than its
	 * line's text, a block on any row but its line's last, and a block on the last line
	 * (which has no line break to carry a placeholder) keep the text's height.
	 */
	fun addLayoutTo(results: MutableList<TextLayoutResult>): Boolean {
		val content = state.snapshot()
		val sources = LayoutSources(content, state.lineOffsets, state.viewportSize.width, state.textStyle, state.textMeasurer, state.density)
		val current = layout?.takeIf { sources.same(layoutSources) } ?: run {
			val input = layoutInput(content)
			// A text edit always measures again, so only a span change is worth comparing.
			val sameText = content.lines === layoutSources?.content?.lines
			layout?.takeIf { sameText && input == layoutInput } ?: measure(input)?.also {
				layout = it
				layoutInput = input
			}
		} ?: return false
		layoutSources = sources
		results.add(current)
		return true
	}

	private fun layoutInput(content: DocumentSnapshot): LayoutInput {
		val width = maxOf(1, state.viewportSize.width.toInt())
		val style = state.textStyle
		val outerIndent = style.textIndent?.takeIf { it != TextIndent.None }
		val measureStyle = if (outerIndent == null) style else style.copy(textIndent = TextIndent.None)
		val lines = content.lines
		// Rows laid out for another revision (a collapsed viewport leaves them behind) are not used.
		val rows = (state.lineOffsets as? RowList)?.takeIf { it.lineCount == lines.size && it.spans === content.spanIndex }
		// Each line as the editor measured it, unless its rows are behind the text.
		val shaped = List(lines.size) { index ->
			val line = lines[index]
			rows?.layoutOf(index)?.layout?.layoutInput?.text?.takeIf { it.text == line.text } ?: line
		}
		val density = state.density
		var steps = emptyList<RowStep>()
		var placeholders = emptyList<AnnotatedString.Range<Placeholder>>()
		if (rows != null && density != null) {
			steps = rowSteps(rows)
			placeholders = steps.map { step ->
				val lineBreak = content.lineStart(step.line + 1) - 1
				AnnotatedString.Range(placeholder(step.height, density), lineBreak, lineBreak + 1)
			}
		}
		if (outerIndent == null && placeholders.isEmpty() && shaped.none { it.paragraphStyles.isNotEmpty() }) {
			return LayoutInput(content.getAllText(), measureStyle, placeholders, steps, width, state.textMeasurer, density)
		}
		// Every line becomes its own paragraph, with the style it was measured with or the
		// baked indent. A paragraph style already breaks the line, so the line break
		// between two becomes a zero-width space inside the first: a line break there
		// would add an empty row, and the offsets must stay the document's.
		val plain = outerIndent?.let { ParagraphStyle(textIndent = it) } ?: ParagraphStyle()
		val text = buildAnnotatedString {
			shaped.forEachIndexed { index, line ->
				withStyle(line.paragraphStyles.firstOrNull()?.item ?: plain) {
					append(AnnotatedString(line.text, line.spanStyles))
					if (index < shaped.lastIndex) append(ZERO_WIDTH_SPACE)
				}
			}
		}
		return LayoutInput(text, measureStyle, placeholders, steps, width, state.textMeasurer, density)
	}

	/**
	 * The last row of each line that the editor draws taller than its text, or leaves
	 * space under, with how far below its top the next line's first row starts. Each
	 * gets a top-aligned placeholder that tall on the line break after it, which
	 * stretches the row down to there.
	 */
	private fun rowSteps(rows: RowList): List<RowStep> {
		val steps = ArrayList<RowStep>()
		for (line in 0 until rows.lineCount - 1) {
			val layout = rows.layoutOf(line)
			val next = rows.layoutOf(line + 1)
			val lastRow = layout.rowCount - 1
			val step = layout.rowTops[lastRow + 1] - layout.rowTops[lastRow] + layout.spaceAfter + next.spaceBefore
			// A placeholder can only make a row taller than its text.
			if (step > layout.layout.multiParagraph.getLineHeight(lastRow) + 0.5f) steps += RowStep(line, step)
		}
		return steps
	}

	private fun placeholder(height: Float, density: Density) =
		Placeholder(0.sp, with(density) { height.toSp() }, PlaceholderVerticalAlign.Top)

	/**
	 * Measures [input], then once more with any placeholder that missed its step
	 * corrected by what it missed: a paragraph's line height spreads over a placeholder
	 * as over text, so a row with one can come out taller than the placeholder.
	 */
	private fun measure(input: LayoutInput): TextLayoutResult? {
		val first = measure(input, input.placeholders) ?: return null
		val density = input.density ?: return first
		var missed = false
		val corrected = input.placeholders.mapIndexed { index, range ->
			val step = input.steps[index]
			val row = first.getLineForOffset(range.start)
			if (row + 1 >= first.lineCount) return@mapIndexed range
			val miss = step.height - (first.getLineTop(row + 1) - first.getLineTop(row))
			// A first row's top can sit a pixel above zero.
			if (abs(miss) <= 1f || step.height + miss <= 0f) return@mapIndexed range
			missed = true
			AnnotatedString.Range(placeholder(step.height + miss, density), range.start, range.end)
		}
		return if (missed) measure(input, corrected) ?: first else first
	}

	private fun measure(input: LayoutInput, placeholders: List<AnnotatedString.Range<Placeholder>>): TextLayoutResult? = try {
		input.measurer.measure(
			text = input.text,
			style = input.style,
			constraints = Constraints.fixedWidth(input.width),
			placeholders = placeholders,
			skipCache = true,
		)
	} catch (_: IllegalArgumentException) {
		null
	}
}

private const val ZERO_WIDTH_SPACE = '​'

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
