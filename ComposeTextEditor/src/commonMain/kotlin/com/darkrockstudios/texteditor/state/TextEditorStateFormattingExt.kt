package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.bakedLooks
import com.darkrockstudios.texteditor.richstyle.everyBakedLook
import com.darkrockstudios.texteditor.richstyle.lineBlocks

/**
 * Toggles [style] the way the built-in formatting actions do, for toolbars and host
 * code that want the same behaviour:
 *
 * - A selection carrying [style] on every character loses it.
 * - Any other selection, including a partly styled one, gains it throughout.
 * - A collapsed caret toggles [style] for the text typed next, leaving the document alone.
 *
 * Styles match by equality, as [addStyleSpan] and [removeStyleSpan] do, so pass the
 * exact style the document uses (the state's configured one).
 */
fun TextEditorState.toggleSpanStyle(style: SpanStyle) {
	// A drag that ends where it began leaves an empty selection rather than none.
	val selection = selector.selection?.takeIf { it.start != it.end }
	when {
		selection == null -> cursor.toggleStyle(style)
		hasStyleThroughout(selection, style) -> removeStyleSpan(selection, style)
		else -> addStyleSpan(selection, style)
	}
}

/**
 * Whether every character in [range] carries [style]. Line breaks and empty lines
 * are not characters, so they never count against it; a range with no characters
 * at all has no style throughout.
 */
fun TextEditorState.hasStyleThroughout(range: TextEditorRange, style: SpanStyle): Boolean {
	if (range.end.line > textLines.lastIndex) return false
	var sawCharacter = false
	forEachLineSegment(range) { lineIndex, start, end ->
		sawCharacter = true
		val covering = textLines[lineIndex].spanStyles
			.filter { it.item == style && it.start < end && it.end > start }
			.sortedBy { it.start }
		var reached = start
		for (span in covering) {
			if (span.start > reached) return false
			reached = maxOf(reached, span.end)
			if (reached >= end) break
		}
		if (reached < end) return false
	}
	return sawCharacter
}

/**
 * Takes the character formatting off the selection, as the built-in clear formatting
 * action does, in one undo step. At a collapsed caret it sets the style of the text
 * typed next to the plain style of its line instead, leaving the document alone.
 *
 * What structure puts in the text stays: the style a heading or code block gives its
 * line, and, once the styles are installed, the body text style and the link style on
 * links. Links stay links; [unlink] takes them off.
 */
fun TextEditorState.clearFormatting() {
	val selection = selector.selection?.takeIf { it.start != it.end }
	if (selection == null) {
		cursor.replaceStyles(lineStyles(cursorPosition.line) + listOfNotNull(fallbackBodyStyle))
		return
	}
	val found = mutableSetOf<SpanStyle>()
	forEachLineSegment(selection) { lineIndex, start, end ->
		textLines[lineIndex].spanStyles
			.filter { it.start < end && it.end > start }
			.mapTo(found) { it.item }
	}
	val lineStyles = (selection.start.line..selection.end.line).flatMapTo(mutableSetOf()) { lineStyles(it) }
	val linkStyle = richTextStyles.linkStyle.takeIf { richTextStylesSet }
	editGroup {
		for (style in found - listOfNotNull(bodyStyle).toSet()) {
			when {
				style == linkStyle -> removeLinkStyleOutsideLinks(selection, style)
				// Kept on a heading or code block line, formatting on any other.
				style in lineStyles -> forEachLineSegment(selection) { lineIndex, start, end ->
					if (style !in lineStyles(lineIndex)) {
						removeStyleSpan(TextEditorRange(CharLineOffset(lineIndex, start), CharLineOffset(lineIndex, end)), style)
					}
				}

				else -> removeStyleSpan(selection, style)
			}
		}
	}
}

/** The body text style, which the importers put on every line, once the styles are installed. */
internal val TextEditorState.bodyStyle: SpanStyle?
	get() = richTextStyles.defaultTextStyle.takeIf { richTextStylesSet }

/**
 * The style for text with none of its own to adopt: the body style, unless the document
 * has text and none of it carries the body style (current or retired), as when a host
 * filled it at its own size, so new text there lands at that size too.
 */
internal val TextEditorState.fallbackBodyStyle: SpanStyle?
	get() {
		val body = bodyStyle ?: return null
		val lines = textLines
		val retired = retiredRichTextStyles
		val scan = bodyStyleScan?.takeIf { it.lines === lines && it.body == body && it.retired === retired }
			?: BodyStyleScan(lines, body, retired).also { bodyStyleScan = it }
		return body.takeIf { scan.takesBody }
	}

/** Whether [lines] are empty or carry [body] or a [retired] configuration's body style. */
internal class BodyStyleScan(
	val lines: List<AnnotatedString>,
	val body: SpanStyle,
	val retired: List<RichTextStyles>,
) {
	val takesBody: Boolean = run {
		val bodies = retired.mapTo(hashSetOf(body)) { it.defaultTextStyle }
		lines.all { it.isEmpty() } || lines.any { line -> line.spanStyles.any { it.item in bodies } }
	}
}

/**
 * Runs [insert] of [text], then takes off what landed, in the same undo step, the looks
 * styled text from outside the document brings without what gives them: each block look
 * (a heading's, a fence's monospace) its line does not bake, and the link look where no
 * link holds it (yanked text whose link went with the kill, an assistive service's text).
 * A block look the text beside what landed carries of its own (a host's monospace text)
 * stays, so text matching its neighbours keeps their look. Paste and drop place blocks
 * and links of their own and settle after.
 */
internal inline fun TextEditorState.landingOutsideText(
	text: AnnotatedString,
	crossinline insert: () -> TextEditorRange?,
): TextEditorRange? {
	val looks = blockLooksIn(text)
	val linkLook = carriesLinkLook(text)
	if (looks.isEmpty() && !linkLook) return insert()
	return editGroup {
		insert()?.also { landed ->
			if (looks.isNotEmpty()) removeBlockLooksOffTheirBlocks(landed, looks - ownLooksBeside(landed))
			if (linkLook) removeLinkLookOutsideLinks(landed)
		}
	}
}

/**
 * The styles the characters either side of [range] carry that their lines' blocks do not
 * bake into them.
 */
internal fun TextEditorState.ownLooksBeside(range: TextEditorRange): Set<SpanStyle> {
	val before = when {
		range.start.char > 0 -> range.start.copy(char = range.start.char - 1)
		range.start.line > 0 -> CharLineOffset(range.start.line - 1, textLines[range.start.line - 1].length - 1)
		else -> null
	}?.takeIf { it.char >= 0 }
	val after = range.end.takeIf { it.char < (textLines.getOrNull(it.line)?.length ?: 0) }
	return listOfNotNull(before, after).flatMapTo(HashSet()) { getSpanStylesAtPosition(it) - bakedLooks(it.line) }
}

/** The styles a heading or code block on [line] gives it. */
internal fun TextEditorState.lineStyles(line: Int): Set<SpanStyle> =
	lineBlocks(line).mapNotNullTo(mutableSetOf()) { it.textStyle }

/**
 * Takes the link style off the parts of [text], just placed at [at], that no link covers:
 * pasted or dropped text whose link this editor did not take (an in-process copy carries a
 * link's look but not its destination) would otherwise look like a link and go nowhere.
 */
internal fun TextEditorState.removeLinkLookOutsideLinks(at: CharLineOffset, text: AnnotatedString) {
	if (!carriesLinkLook(text)) return
	removeLinkLookOutsideLinks(TextEditorRange(at, text.endWhenInsertedAt(at)))
}

/** Whether [text]'s own spans carry the link style. */
internal fun TextEditorState.carriesLinkLook(text: AnnotatedString): Boolean =
	text.spanStyles.any { it.item == richTextStyles.linkStyle }

/** Takes the link style off the parts of [range] that no link covers, as above. */
internal fun TextEditorState.removeLinkLookOutsideLinks(range: TextEditorRange) {
	val linkStyle = richTextStyles.linkStyle
	if (linkStyle !in getSpanStylesInRange(range)) return
	// Inside a paste, it keeps the copied rich spans the paste kept.
	keepingCopiedRichSpans {
		if (richSpanManager.getSpansInRange(range).none { it.style is LinkSpanStyle }) {
			removeStyleSpan(range, linkStyle)
		} else {
			removeLinkStyleOutsideLinks(range, linkStyle)
		}
	}
}

/**
 * Takes off the parts of [text], just placed at [at], each block look (a heading's, a
 * fence's monospace) that the line the part landed on does not bake: text copied from
 * part of a heading, or pasted or dropped inside another line, brings the look without
 * the marker. The markers of the line it lands on decide its look, and publishing bakes
 * theirs over all of it, as for text a join moves. A span equal to a block look
 * goes too: pasted text cannot tell the user's own from one a block baked, and a drop,
 * even of this editor's own text, takes the same rule.
 */
internal fun TextEditorState.removeBlockLooksOffTheirBlocks(at: CharLineOffset, text: AnnotatedString) =
	removeBlockLooksOffTheirBlocks(TextEditorRange(at, text.endWhenInsertedAt(at)), blockLooksIn(text))

/** The block looks [text]'s own spans carry. */
internal fun TextEditorState.blockLooksIn(text: AnnotatedString): Set<SpanStyle> {
	if (text.spanStyles.isEmpty()) return emptySet()
	val everyLook = everyBakedLook
	return text.spanStyles.mapNotNullTo(HashSet()) { run -> run.item.takeIf { it in everyLook } }
}

/** [looks] taken off [range] wherever its line does not bake them, as above. */
internal fun TextEditorState.removeBlockLooksOffTheirBlocks(range: TextEditorRange, looks: Set<SpanStyle>) {
	if (looks.isEmpty()) return
	// Inside a paste, it keeps the copied rich spans the paste kept.
	keepingCopiedRichSpans {
		forEachLineSegment(range) { lineIndex, start, end ->
			val own = bakedLooks(lineIndex)
			val runs = textLines[lineIndex].spanStyles
			val segment = TextEditorRange(CharLineOffset(lineIndex, start), CharLineOffset(lineIndex, end))
			for (look in looks) {
				if (look in own || runs.none { it.item == look && it.start < end && it.end > start }) continue
				removeStyleSpan(segment, look)
			}
		}
	}
}

/** Removes [linkStyle] from the parts of [range] that no link covers. */
private fun TextEditorState.removeLinkStyleOutsideLinks(range: TextEditorRange, linkStyle: SpanStyle) {
	val links = richSpanManager.getSpansInRange(range).filter { it.style is LinkSpanStyle }.map { it.range }
	forEachLineSegment(range) { lineIndex, start, end ->
		var from = start
		val covered = links.mapNotNull { link ->
			val linkStart = if (link.start.line < lineIndex) 0 else if (link.start.line == lineIndex) link.start.char else return@mapNotNull null
			val linkEnd = if (link.end.line > lineIndex) end else if (link.end.line == lineIndex) link.end.char else return@mapNotNull null
			(linkStart.coerceAtLeast(start) until linkEnd.coerceAtMost(end)).takeUnless { it.isEmpty() }
		}.sortedBy { it.first }
		for (link in covered + listOf(end until end)) {
			if (link.first > from) {
				removeStyleSpan(TextEditorRange(CharLineOffset(lineIndex, from), CharLineOffset(lineIndex, link.first)), linkStyle)
			}
			from = maxOf(from, link.last + 1)
		}
	}
}

/** Runs [block] over each line's part of [range] that holds characters, as `start until end`. */
private inline fun TextEditorState.forEachLineSegment(
	range: TextEditorRange,
	block: (line: Int, start: Int, end: Int) -> Unit,
) {
	for (lineIndex in range.start.line..range.end.line) {
		val line = textLines.getOrNull(lineIndex) ?: return
		val start = if (lineIndex == range.start.line) range.start.char else 0
		val end = if (lineIndex == range.end.line) range.end.char else line.length
		if (start < end) block(lineIndex, start, end)
	}
}

/**
 * Takes off every link the selection touches, or the one the caret is in, whole, with its
 * link styling, in one undo step, as the built-in unlink action does.
 */
fun TextEditorState.unlink() {
	val links = linksAtSelection()
	if (links.isEmpty()) return
	editGroup {
		links.forEach { link ->
			removeStyleSpan(link.range, richTextStyles.linkStyle)
			removeRichSpan(link)
		}
	}
}

/** The links the selection touches, or the one the caret is in or at the edge of. */
internal fun TextEditorState.linksAtSelection(): List<RichSpan> {
	val selection = selector.selection?.takeIf { it.start != it.end }
	val caret = cursorPosition
	return richSpanManager.getSpansInRange(selection ?: TextEditorRange(caret, caret)).filter { span ->
		// The index answers edges inclusively; a selection ending where a link starts does not touch it.
		span.style is LinkSpanStyle &&
			(selection == null || (span.range.start < selection.end && selection.start < span.range.end))
	}
}
