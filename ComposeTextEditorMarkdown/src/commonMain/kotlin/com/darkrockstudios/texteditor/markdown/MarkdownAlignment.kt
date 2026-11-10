package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.text.style.TextAlign
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getRichSpansAtPosition
import com.darkrockstudios.texteditor.state.isBlockquote
import com.darkrockstudios.texteditor.state.isBulletList
import com.darkrockstudios.texteditor.state.isCodeFence
import com.darkrockstudios.texteditor.state.isInlineOnlyLine
import com.darkrockstudios.texteditor.state.isOrderedList

/**
 * A paragraph's alignment, which CommonMark has no syntax for, round-trips as an HTML
 * wrapper around a run of lines, blank lines inside its tags:
 *
 * ```
 * <div align="center">
 *
 * Some *centred* text
 *
 * </div>
 * ```
 *
 * A renderer reads each tag line as an HTML block of its own and the lines between as
 * markdown, so the run keeps its styles outside this editor; GitHub keeps `align`. Only
 * `left`, `center`, `right` and `justify` have a form: a start or end alignment is not
 * written.
 */

/**
 * Whether markdown keeps [line]'s alignment: a paragraph's or a heading's. A list item's, a quote's,
 * a code line's, a rule's, an image's and a table cell's are not written, so a toolbar offers
 * alignment on the lines this accepts.
 */
fun TextEditorState.markdownKeepsAlignment(line: Int): Boolean {
	if (line !in textLines.indices) return false
	if (isInlineOnlyLine(line) || isBlockquote(line) || isBulletList(line) || isOrderedList(line) || isCodeFence(line)) return false
	return getRichSpansAtPosition(CharLineOffset(line, 0)).none {
		it.range.start.line == line && (it.style === HorizontalRuleSpanStyle || it.style is ImageBlockSpanStyle)
	}
}

/** One line of a document read without its alignment wrappers. */
internal class UnwrappedLine(
	/** The index of the source line this came from. */
	val source: Int,
	val text: String,
	val align: TextAlign?,
	/** Whether [text] is the source line as it was. */
	val whole: Boolean,
)

/**
 * [lines] with their alignment wrappers taken out, or null when they have none. A wrapper opens on
 * a `<div>` or `<p>` at the start of a line, with an `align` attribute or a `style` with
 * `text-align` of one of the alignments written, and closes on the first unescaped `</div>` or
 * `</p>` of the same name after it, outside a code span. Text after the opening tag or before the
 * closing one is inside; text after the closing tag is a line of its own after the wrapper. A blank
 * line just inside a tag that stands alone goes, and with [dropBlankAfterClose] the one after a
 * closing tag that does. An opening tag with no closing one is text, and so is either tag on a
 * [fenced] line.
 */
internal fun unwrapAlignment(
	lines: List<String>,
	fenced: (Int) -> Boolean,
	dropBlankAfterClose: Boolean,
): List<UnwrappedLine>? {
	if (lines.none { '<' in it }) return null
	fun blank(index: Int) = !fenced(index) && lines.getOrNull(index)?.isBlank() == true
	val out = ArrayList<UnwrappedLine>(lines.size)
	var found = false
	var index = 0
	while (index < lines.size) {
		val line = lines[index]
		val opener = if (fenced(index)) null else readAlignmentOpener(line)
		val close = opener?.let { findClosingTag(lines, fenced, it.tag, index, it.contentStart) }
		if (opener == null || close == null) {
			out += UnwrappedLine(index, line, null, whole = true)
			index++
			continue
		}
		found = true
		val closeLine = lines[close.line]
		if (close.line == index) {
			line.substring(opener.contentStart, close.start).trim().takeIf { it.isNotEmpty() }
				?.let { out += UnwrappedLine(index, it, opener.align, whole = false) }
		} else {
			val rest = line.substring(opener.contentStart)
			val lead = closeLine.substring(0, close.start)
			var first = index + 1
			var last = close.line - 1
			if (rest.isNotBlank()) {
				out += UnwrappedLine(index, rest.trimStart(), opener.align, whole = false)
			} else if (first <= last && blank(first)) {
				first++
			}
			if (lead.isBlank() && first <= last && blank(last)) last--
			for (inside in first..last) out += UnwrappedLine(inside, lines[inside], opener.align, whole = true)
			if (lead.isNotBlank()) out += UnwrappedLine(close.line, lead.trimEnd(), opener.align, whole = false)
		}
		val trail = closeLine.substring(close.end)
		index = close.line + 1
		if (trail.isNotBlank()) {
			out += UnwrappedLine(close.line, trail.trimStart(), null, whole = false)
		} else if (dropBlankAfterClose && index < lines.lastIndex && blank(index)) {
			index++
		}
	}
	return if (found) out else null
}

/** The opening tag of an alignment wrapper: its tag name, the alignment it sets, and where its line's text resumes. */
internal class AlignmentOpener(val tag: String, val align: TextAlign, val contentStart: Int)

/** [line] as starting with an alignment wrapper's opening tag. */
internal fun readAlignmentOpener(line: String): AlignmentOpener? {
	val match = OPENING_TAG.find(line) ?: return null
	val attributes = match.groupValues[2]
	val value = ALIGN_ATTRIBUTE.find(attributes)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
		?: TEXT_ALIGN_DECLARATION.find(attributes)?.groupValues?.get(1)
		?: return null
	val align = ALIGNMENTS[value.lowercase()] ?: return null
	return AlignmentOpener(match.groupValues[1].lowercase(), align, match.range.last + 1)
}

private class ClosingTag(val line: Int, val start: Int, val end: Int)

/** The first closing [tag] from [from] on line [openLine], skipping [fenced] lines after it. */
private fun findClosingTag(lines: List<String>, fenced: (Int) -> Boolean, tag: String, openLine: Int, from: Int): ClosingTag? {
	for (index in openLine until lines.size) {
		if (index != openLine && fenced(index)) continue
		val line = lines[index]
		for (match in CLOSING_TAG.findAll(line, if (index == openLine) from else 0)) {
			val at = match.range.first
			if (match.groupValues[1].lowercase() != tag || isEscaped(line, at) || inCodeSpan(line, at)) continue
			return ClosingTag(index, at, match.range.last + 1)
		}
	}
	return null
}

/** Whether an odd run of backslashes comes just before [at]. */
private fun isEscaped(line: String, at: Int): Boolean {
	var slashes = 0
	while (at - slashes - 1 >= 0 && line[at - slashes - 1] == '\\') slashes++
	return slashes % 2 == 1
}

/** Whether [at] falls after an unclosed backtick on [line]: inside a code span. */
private fun inCodeSpan(line: String, at: Int): Boolean = (0 until at).count { line[it] == '`' } % 2 == 1

/** The opening tag export writes for [align], or null when it has no markdown form. */
internal fun alignmentOpeningTag(align: TextAlign?): String? =
	ALIGNMENTS.entries.firstOrNull { it.value == align }?.let { "<div align=\"${it.key}\">" }

internal const val ALIGNMENT_CLOSING_TAG = "</div>"

private val OPENING_TAG = Regex("""^ {0,3}<(div|p)(\s[^<>]*)>""", RegexOption.IGNORE_CASE)
private val CLOSING_TAG = Regex("""</(div|p)\s*>""", RegexOption.IGNORE_CASE)
private val ALIGN_ATTRIBUTE = Regex("""(?:^|\s)align\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", RegexOption.IGNORE_CASE)
private val TEXT_ALIGN_DECLARATION = Regex("""text-align\s*:\s*([A-Za-z]+)""", RegexOption.IGNORE_CASE)

private val ALIGNMENTS = mapOf(
	"left" to TextAlign.Left,
	"center" to TextAlign.Center,
	"right" to TextAlign.Right,
	"justify" to TextAlign.Justify,
)
