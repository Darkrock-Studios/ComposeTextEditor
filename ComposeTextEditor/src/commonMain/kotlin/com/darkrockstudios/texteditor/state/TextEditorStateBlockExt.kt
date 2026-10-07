package com.darkrockstudios.texteditor.state

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.sanitizeLinkUrl
import com.darkrockstudios.texteditor.richstyle.Blockquote
import com.darkrockstudios.texteditor.richstyle.BulletList
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFence
import com.darkrockstudios.texteditor.richstyle.CodeFenceLanguageSpanStyle
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedList
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.hasLineBlock
import com.darkrockstudios.texteditor.richstyle.headerBlock
import com.darkrockstudios.texteditor.richstyle.listBlockAt
import com.darkrockstudios.texteditor.richstyle.listLevel

/**
 * The block API: the line blocks a rich text editor's toolbar toggles and asks
 * about (blockquotes, bullet and ordered lists at any nesting level, code fences
 * and their language, headings), and links. Nesting is
 * [nestListItems][com.darkrockstudios.texteditor.richstyle.nestListItems] and
 * [unnestListItems][com.darkrockstudios.texteditor.richstyle.unnestListItems].
 * The rules every toggle follows are in `docs/design/line-blocks.md`, "Toggle
 * semantics"; each is one undo step.
 */

/**
 * Adds blockquote rendering (left bar and indented text) to each line in [lines]
 * that lacks it; removes it where every line already has it.
 */
fun TextEditorState.toggleBlockquote(lines: IntRange) = editManager.toggleLineBlock(lines, Blockquote)

/**
 * Adds bullet-list rendering (gutter dot and hanging indent) to each line in
 * [lines] that lacks it; removes it where every line already has it. A line that
 * is an ordered item becomes a bullet at the same level.
 */
fun TextEditorState.toggleBulletList(lines: IntRange) = editManager.toggleLineBlock(lines, BulletList)

/**
 * Adds ordered-list rendering (gutter numeral and hanging indent) to each line
 * in [lines] that lacks it; removes it where every line already has it. Numbering
 * follows the contiguous run, so it is never stored.
 */
fun TextEditorState.toggleOrderedList(lines: IntRange) = editManager.toggleLineBlock(lines, OrderedList)

/**
 * Adds fenced-code rendering (monospace text on a tinted card) to each line in
 * [lines] that lacks it; removes it where every line already has it. A fence
 * demotes any quote, list or heading on the same line.
 */
fun TextEditorState.toggleCodeFence(lines: IntRange) = editManager.toggleLineBlock(lines, CodeFence)

/**
 * Makes each line in [lines] a heading of [level] (1 to 6), or removes the
 * heading where every line already carries that exact level. Applying over a
 * different level swaps the level. The heading is semantic: its
 * [HeaderSpanStyle] span survives a change of [TextEditorState.richTextStyles].
 */
fun TextEditorState.toggleHeader(lines: IntRange, level: Int) {
	editManager.toggleLineBlock(lines, headerBlock(level, richTextStyles))
}

/** Whether [line] is rendered as a blockquote. */
fun TextEditorState.isBlockquote(line: Int): Boolean = hasLineBlock(line, Blockquote)

/** Whether [line] is rendered as a bullet-list item, at any nesting level. */
fun TextEditorState.isBulletList(line: Int): Boolean = listBlockAt(line)?.spanStyle is BulletListSpanStyle

/** Whether [line] is rendered as an ordered-list item, at any nesting level. */
fun TextEditorState.isOrderedList(line: Int): Boolean = listBlockAt(line)?.spanStyle is OrderedListSpanStyle

/** Whether [line] is rendered as a fenced code line. */
fun TextEditorState.isCodeFence(line: Int): Boolean = hasLineBlock(line, CodeFence)

/** Whether [line] holds inline content alone, as a table cell does; see [RichSpanStyle.inlineOnly]. */
fun TextEditorState.isInlineOnlyLine(line: Int): Boolean =
	richSpanManager.getRichSpansStartingOn(line).any { it.style.inlineOnly }

/** The nesting level (0 for a top-level item) of the list item on [line], or null when it is not one. */
fun TextEditorState.listLevel(line: Int): Int? = listBlockAt(line)?.listLevel

/** The heading level (1 to 6) of [line], read from its [HeaderSpanStyle] span, or null. */
fun TextEditorState.headerLevel(line: Int): Int? =
	richSpanManager.getRichSpansStartingOn(line)
		.firstNotNullOfOrNull { it.style as? HeaderSpanStyle }
		?.level

/**
 * The info string (` ```kotlin `) of the fenced code block containing [line], or
 * null when the line is not fenced or its fence has none. A fence's language is
 * its first line's, the one a format writes.
 */
fun TextEditorState.codeFenceLanguage(line: Int): String? {
	val run = fenceRunContaining(line) ?: return null
	return richSpanManager.getRichSpansStartingOn(run.first)
		.firstNotNullOfOrNull { it.style as? CodeFenceLanguageSpanStyle }
		?.language
}

/**
 * Sets the info string of the fenced code block containing [line], or removes it
 * for a null or blank [language]. The value is trimmed; one holding a backtick or
 * a line break cannot be written after a fence marker and is refused. One undo
 * step; a no-op off a fence.
 */
fun TextEditorState.setCodeFenceLanguage(line: Int, language: String?) {
	val run = fenceRunContaining(line) ?: return
	val info = language?.trim()?.ifEmpty { null }
	if (info != null && !CodeFenceLanguageSpanStyle.isWritable(info)) return
	fun languageSpansOn(member: Int) = richSpanManager.getRichSpansStartingOn(member)
		.filter { it.style is CodeFenceLanguageSpanStyle }
	fun holdsInfo(member: Int) =
		languageSpansOn(member).map { (it.style as CodeFenceLanguageSpanStyle).language } == listOfNotNull(info)
	if (run.all(::holdsInfo)) return
	editGroup {
		run.forEach { member ->
			if (holdsInfo(member)) return@forEach
			languageSpansOn(member).forEach { removeRichSpan(it) }
			if (info != null) {
				val length = textLines[member].length
				addRichSpan(
					TextEditorRange(CharLineOffset(member, 0), CharLineOffset(member, length)),
					CodeFenceLanguageSpanStyle(info),
				)
			}
		}
	}
}

/** The lines of the fence run containing [line], or null when [line] is not fenced. */
private fun TextEditorState.fenceRunContaining(line: Int): IntRange? {
	if (line !in textLines.indices || !isCodeFence(line)) return null
	var first = line
	while (first > 0 && isCodeFence(first - 1)) first--
	var last = line
	while (last + 1 < textLines.size && isCodeFence(last + 1)) last++
	return first..last
}

/**
 * Makes [range] a hyperlink to [url]: bakes the configured link style over the
 * text and attaches the [LinkSpanStyle] that carries the destination through
 * serialization, as one undo step. A link to elsewhere over some of [range] keeps
 * only its parts outside it. A destination [sanitizeLinkUrl] refuses under
 * [TextEditorState.allowedLinkSchemes] (`javascript:`, `data:`, `vbscript:`, `file:`
 * always) is not set, and answers false.
 */
fun TextEditorState.setLink(range: TextEditorRange, url: String): Boolean {
	if (sanitizeLinkUrl(url, allowedLinkSchemes) == null) return false
	editGroup {
		addStyleSpan(range, richTextStyles.linkStyle)
		val link = LinkSpanStyle(url)
		takeOutOfOtherLinks(range, link)
		addRichSpan(range, link)
	}
	return true
}

/** The destination URL of the link covering [position], or null when the position is not inside a link. */
fun TextEditorState.linkAt(position: CharLineOffset): String? =
	richSpanManager.getRichSpansStartingOn(position.line)
		.firstOrNull { it.style is LinkSpanStyle && it.containsPosition(position) }
		?.let { (it.style as LinkSpanStyle).url }
