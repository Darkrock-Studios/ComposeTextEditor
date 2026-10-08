package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.headerLevel
import com.darkrockstudios.texteditor.state.isBlockquote
import com.darkrockstudios.texteditor.state.isBulletList
import com.darkrockstudios.texteditor.state.isCodeFence
import com.darkrockstudios.texteditor.state.isOrderedList
import com.darkrockstudios.texteditor.state.isInlineOnlyLine
import com.darkrockstudios.texteditor.state.setCodeFenceLanguage
import com.darkrockstudios.texteditor.state.toggleBlockquote
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleCodeFence
import com.darkrockstudios.texteditor.state.toggleHeader
import com.darkrockstudios.texteditor.state.toggleOrderedList

/**
 * Markdown typed into a rich text editor becomes the formatting it stands for, the
 * markers disappearing, as Notion, Typora and Google Docs' autoformat do:
 *
 * - [blocks]: at a line's start, `- `, `* ` or `+ ` make a bullet item, a number and
 *   `. ` or `) ` an ordered item, one to six `#` and a space a heading of that level,
 *   and `> ` a quote, as soon as the space is typed. A marker the line's blocks refuse
 *   (a heading on a list item, any block in a table cell) stays text. Enter on a line
 *   of three backticks and an optional language makes the line a code block in that
 *   language, unless the line is a table cell or a code block is next to it.
 * - [inline]: a closing `**` or `__` makes the text since its opener bold, `*` or `_`
 *   italic, `` ` `` inline code, `~~` struck through, and `==` highlighted, once the
 *   closer is typed. The opener is the nearest run of the delimiter before the closer
 *   and as long as it, at a word's start, and neither has whitespace just inside it,
 *   so `snake_case`, `2*3*` and `a * b *` stay text.
 *
 * Opt-in: add it to [TextEditorState.editBehaviors], ahead of the line block
 * behavior so it sees Enter first (`editBehaviors.add(0, MarkdownShortcuts())`).
 * It edits on top of the typed text, so one undo gives back exactly what was typed.
 * Nothing converts in a code block or inline code, and text typed after an inline
 * conversion is not in its style. The styles are the state's
 * [TextEditorState.richTextStyles].
 */
data class MarkdownShortcuts(
	val blocks: Boolean = true,
	val inline: Boolean = true,
) : EditBehavior {

	private enum class Block { Bullet, Ordered, Heading, Quote }

	override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
		if (range.end != state.cursorPosition || state.isCodeFence(range.end.line)) return false
		val codeStyles = state.codeStyles()
		return (blocks && convertBlockMarker(state, range, codeStyles)) ||
			(inline && convertInline(state, range, codeStyles))
	}

	override fun onNewline(state: TextEditorState): Boolean {
		if (!blocks) return false
		val line = state.cursorPosition.line
		val text = state.textLines[line].text
		val fence = FENCE_LINE.matchEntire(text) ?: return false
		// A neighbouring code block would take the line into its run and its language.
		val besideFence = (line > 0 && state.isCodeFence(line - 1)) ||
			(line < state.textLines.lastIndex && state.isCodeFence(line + 1))
		if (state.cursorPosition.char != text.length || besideFence || state.hasAnyBlock(line) ||
			state.inCode(line, 0, text.length, state.codeStyles())
		) {
			return false
		}
		val language = fence.groupValues[1].ifEmpty { null }
		state.editGroup {
			state.replace(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, text.length)), "")
			state.toggleCodeFence(line..line)
			if (language != null) state.setCodeFenceLanguage(line, language)
			state.cursor.updatePosition(CharLineOffset(line, 0))
		}
		return true
	}

	/** A block marker that ends with the space just typed and starts the line. */
	private fun convertBlockMarker(state: TextEditorState, range: TextEditorRange, codeStyles: Set<SpanStyle>): Boolean {
		val line = range.end.line
		val end = range.end.char
		val lineText = state.textLines[line].text
		if (end == 0 || end > MAX_MARKER_LENGTH || lineText[end - 1] != ' ') return false
		if (state.isInlineOnlyLine(line) || state.inCode(line, 0, end, codeStyles)) return false
		val marker = lineText.substring(0, end)
		val isList = state.isList(line)
		val heading = state.headerLevel(line)
		val block = when {
			BULLET_MARKER.matches(marker) && !isList && heading == null -> Block.Bullet
			ORDERED_MARKER.matches(marker) && !isList && heading == null -> Block.Ordered
			HEADING_MARKER.matches(marker) && !isList && heading != marker.length - 1 -> Block.Heading
			marker == QUOTE_MARKER && !state.isBlockquote(line) -> Block.Quote
			else -> return false
		}
		state.editGroup {
			state.replace(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, end)), "")
			when (block) {
				Block.Bullet -> state.toggleBulletList(line..line)
				Block.Ordered -> state.toggleOrderedList(line..line)
				Block.Heading -> state.toggleHeader(line..line, marker.length - 1)
				Block.Quote -> state.toggleBlockquote(line..line)
			}
			state.cursor.updatePosition(CharLineOffset(line, 0))
		}
		return true
	}

	/** An inline span closed by the delimiter run just typed. */
	private fun convertInline(state: TextEditorState, range: TextEditorRange, codeStyles: Set<SpanStyle>): Boolean {
		val line = range.end.line
		val text = state.textLines[line].text
		val closeEnd = range.end.char
		if (closeEnd == 0) return false
		val delimiter = text[closeEnd - 1]
		if (delimiter !in DELIMITERS) return false
		var closeStart = closeEnd
		while (closeStart > 0 && text[closeStart - 1] == delimiter) closeStart--
		val length = closeEnd - closeStart
		val style = styleFor(state.richTextStyles, delimiter, length) ?: return false
		// The closer ends its text: right after text, and not followed by more of a word.
		if (closeStart == 0 || text[closeStart - 1].isWhitespace()) return false
		if (text.getOrNull(closeEnd)?.isLetterOrDigit() == true) return false

		val openStart = findOpener(text, delimiter, length, closeStart) ?: return false
		val openEnd = openStart + length
		// The delimiters themselves are not in code; what they enclose may hold some.
		if (state.inCode(line, openStart, openEnd, codeStyles) || state.inCode(line, closeStart, closeEnd, codeStyles)) {
			return false
		}
		// An unclosed backtick before the closer opens a code span the closer is inside.
		if (delimiter != '`' && text.substring(0, closeStart).count { it == '`' } % 2 == 1) return false

		val contentEnd = closeStart - length
		state.editGroup {
			state.replace(TextEditorRange(CharLineOffset(line, closeStart), CharLineOffset(line, closeEnd)), "")
			state.replace(TextEditorRange(CharLineOffset(line, openStart), CharLineOffset(line, openEnd)), "")
			state.addStyleSpan(
				TextEditorRange(CharLineOffset(line, openStart), CharLineOffset(line, contentEnd)),
				style,
			)
			state.cursor.updatePosition(CharLineOffset(line, contentEnd))
			state.cursor.removeStyle(style)
		}
		return true
	}

	/**
	 * The start of the nearest run of [delimiter]s before [closeStart] when it is
	 * [length] long and can open: text, not whitespace, right after it, and before it
	 * the line's start, whitespace or punctuation. A `*` may also follow a letter of a
	 * script written without spaces; an ASCII letter or digit before it (`2*3*`) and
	 * any letter or digit before a `_` (`snake_case`) keep it text. Null otherwise,
	 * so a run of another length in between (`*a **b*`) waits for its own closer.
	 */
	private fun findOpener(text: String, delimiter: Char, length: Int, closeStart: Int): Int? {
		val end = text.lastIndexOf(delimiter, closeStart - 1) + 1
		if (end == 0) return null
		var start = end - 1
		while (start > 0 && text[start - 1] == delimiter) start--
		if (end - start != length || text[end].isWhitespace()) return null
		val before = text.getOrNull(start - 1) ?: return start
		val opens = when {
			before.isWhitespace() -> true
			!before.isLetterOrDigit() -> true
			delimiter == '*' -> before.code > 0x7F
			else -> false
		}
		return start.takeIf { opens }
	}

	private fun styleFor(styles: RichTextStyles, delimiter: Char, length: Int): SpanStyle? = when (delimiter) {
		'*', '_' -> when (length) {
			1 -> styles.italicStyle
			2 -> styles.boldStyle
			else -> null
		}

		'`' -> styles.codeStyle.takeIf { length == 1 }
		'~' -> styles.strikethroughStyle.takeIf { length == 2 }
		'=' -> styles.highlightStyle.takeIf { length == 2 }
		else -> null
	}

	private fun TextEditorState.isList(line: Int) = isBulletList(line) || isOrderedList(line)

	private fun TextEditorState.hasAnyBlock(line: Int) =
		isList(line) || headerLevel(line) != null || isBlockquote(line) || isCodeFence(line) || isInlineOnlyLine(line)

	/** The inline code styles of this document: the current one and every retired one. */
	private fun TextEditorState.codeStyles(): Set<SpanStyle> =
		(retiredRichTextStyles + richTextStyles).mapTo(HashSet()) { it.codeStyle }

	/** Whether any character of [line] from [start] until [end] is inline code. */
	private fun TextEditorState.inCode(line: Int, start: Int, end: Int, codeStyles: Set<SpanStyle>): Boolean =
		textLines[line].spanStyles.any { it.item in codeStyles && it.start < end && start < it.end }

	private companion object {
		val BULLET_MARKER = Regex("""[-*+] """)
		val ORDERED_MARKER = Regex("""\d{1,9}[.)] """)
		val HEADING_MARKER = Regex("""#{1,6} """)
		const val QUOTE_MARKER = "> "

		/** The longest marker: nine digits, a period and a space. */
		const val MAX_MARKER_LENGTH = 11
		val FENCE_LINE = Regex("""```([^`\s]*)""")
		const val DELIMITERS = "*_`~="
	}
}
