package com.darkrockstudios.texteditor.behaviors

import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.sanitizeLinkUrl
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getRichSpansInRange
import com.darkrockstudios.texteditor.state.isCodeFence
import com.darkrockstudios.texteditor.state.setLink

/**
 * Makes URLs links as they are completed: [typed] ones when a space, Enter, or a
 * closing bracket that cannot belong to the URL follows them, and [pasted] ones,
 * a paste that is a URL and every URL in pasted text, once the paste lands.
 *
 * A URL starts with `http://`, `https://`, `ftp://` or `mailto:`, or with `www.`
 * (linked as `https://`); an email address links as `mailto:`. A bare domain
 * (`example.com`) is not linked. Trailing punctuation (`.,;:!?`, quotes) and a
 * closing bracket with no opening one inside the URL are left out of it, as
 * Word, Google Docs and GitHub do, so `(see https://example.com).` links only the
 * address. Only a destination [sanitizeLinkUrl] allows is linked.
 *
 * The link is [setLink]'s, in the state's link style. Opt-in: add it to
 * [TextEditorState.editBehaviors] ahead of the line block behavior, with
 * `add(0, AutoLink())`, so Enter on a list item or quote links too. A typed or
 * pasted link is its own undo step, so one undo takes the link off and keeps the
 * text; one made by Enter shares the Enter's step. Text in inline code or a code
 * block, and text already linked, is left alone.
 */
data class AutoLink(
	val typed: Boolean = true,
	val pasted: Boolean = true,
) : EditBehavior {

	override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
		if (!typed) return false
		val trigger = text.last()
		val found = mutableListOf<Pair<Int, FoundUrl>>()
		for (line in range.start.line until range.end.line) {
			val lineText = state.textLines[line].text
			val start = if (line == range.start.line) tokenStart(lineText, range.start.char) else 0
			urlsIn(lineText, start, lineText.length).mapTo(found) { line to it }
		}
		val line = range.end.line
		val lineText = state.textLines[line].text
		val end = range.end.char
		if (trigger.isWhitespace()) {
			val start = tokenStart(lineText, if (range.start.line == line) range.start.char else 0)
			urlsIn(lineText, start, end).mapTo(found) { line to it }
		} else if (trigger in CLOSERS_TO_OPENERS && (end == lineText.length || lineText[end].isWhitespace())) {
			// The bracket completes the URL only when it is left out of it.
			urlIn(lineText, tokenStart(lineText, end), end)?.takeIf { it.end < end }?.let { found += line to it }
		}
		link(state, found)
		// A link leaves the text as typed, so the next behavior still gets it.
		return false
	}

	override fun onNewline(state: TextEditorState): Boolean {
		if (!typed || state.selector.selection != null) return false
		val caret = state.cursorPosition
		val lineText = state.textLines[caret.line].text
		if (caret.char < lineText.length && !lineText[caret.char].isWhitespace()) return false
		urlIn(lineText, tokenStart(lineText, caret.char), caret.char)?.let { link(state, listOf(caret.line to it)) }
		// The line break still goes in, through the rest of the chain.
		return false
	}

	override fun onPaste(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
		if (!pasted) return false
		// Out to the runs the paste joined, so a URL pasted against text is judged whole.
		val found = (range.start.line..range.end.line).flatMap { line ->
			val lineText = state.textLines[line].text
			val start = if (line == range.start.line) tokenStart(lineText, range.start.char) else 0
			val end = if (line == range.end.line) tokenEnd(lineText, range.end.char) else lineText.length
			urlsIn(lineText, start, end).map { line to it }
		}
		link(state, found)
		return false
	}

	/** Links each URL [found] that is not in code or already linked, as one undo step. */
	private fun link(state: TextEditorState, found: List<Pair<Int, FoundUrl>>) {
		if (found.isEmpty()) return
		val codeStyles = state.codeStyles()
		val links = found.filter { (line, url) ->
			val range = TextEditorRange(CharLineOffset(line, url.start), CharLineOffset(line, url.end))
			!state.isCodeFence(line) &&
				state.textLines[line].spanStyles.none { it.item in codeStyles && it.start < url.end && it.end > url.start } &&
				state.getRichSpansInRange(range).none {
					it.style is LinkSpanStyle && it.range.start < range.end && range.start < it.range.end
				}
		}
		if (links.isEmpty()) return
		state.editGroup {
			links.forEach { (line, url) ->
				state.setLink(TextEditorRange(CharLineOffset(line, url.start), CharLineOffset(line, url.end)), url.url)
			}
		}
	}
}

/** A URL in a line's text: the characters from [start] until [end], linking to [url]. */
internal class FoundUrl(val start: Int, val end: Int, val url: String)

/** Where the run of non-whitespace ending at [end] in [text] starts. */
private fun tokenStart(text: String, end: Int): Int {
	var start = end
	while (start > 0 && !text[start - 1].isWhitespace()) start--
	return start
}

/** Where the run of non-whitespace starting at [start] in [text] ends. */
private fun tokenEnd(text: String, start: Int): Int {
	var end = start
	while (end < text.length && !text[end].isWhitespace()) end++
	return end
}

/** Every URL among the whitespace-separated runs of [text] from [start] until [end]. */
internal fun urlsIn(text: String, start: Int, end: Int): List<FoundUrl> {
	val found = mutableListOf<FoundUrl>()
	var index = start
	while (index < end) {
		if (text[index].isWhitespace()) {
			index++
			continue
		}
		var tokenEnd = index
		while (tokenEnd < end && !text[tokenEnd].isWhitespace()) tokenEnd++
		urlIn(text, index, tokenEnd)?.let { found += it }
		index = tokenEnd
	}
	return found
}

/**
 * The URL the run of [text] from [start] until [end] holds once the punctuation
 * around it is left out, or null when it holds none.
 */
internal fun urlIn(text: String, start: Int, end: Int): FoundUrl? {
	var from = start
	var until = end
	while (from < until && text[from] in LEADING) from++
	while (until > from) {
		val last = text[until - 1]
		val opener = CLOSERS_TO_OPENERS[last]
		val trailing = when {
			last in TRAILING -> true
			opener != null -> {
				val token = text.subSequence(from, until)
				token.count { it == last } > token.count { it == opener }
			}

			else -> false
		}
		if (!trailing) break
		until--
	}
	if (from >= until) return null
	val candidate = text.substring(from, until)
	val url = destinationOf(candidate) ?: return null
	return sanitizeLinkUrl(url)?.let { FoundUrl(from, until, url) }
}

/** The link destination [candidate] names, or null when it is not a URL or an email address. */
private fun destinationOf(candidate: String): String? {
	val lower = candidate.lowercase()
	val scheme = SCHEMES.firstOrNull { lower.startsWith(it) }
	return when {
		scheme != null -> candidate.takeIf { candidate.substring(scheme.length).any { it.isLetterOrDigit() } }
		lower.startsWith("mailto:") -> candidate.takeIf { EMAIL.matches(candidate.substring("mailto:".length).substringBefore('?')) }
		lower.startsWith("www.") -> "https://$candidate".takeIf { WWW_HOST.matches(candidate.takeWhile { it !in "/?#" }) }
		EMAIL.matches(candidate) -> "mailto:$candidate"
		else -> null
	}
}

private val SCHEMES = listOf("http://", "https://", "ftp://")
private val WWW_HOST = Regex("www\\.[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(:[0-9]+)?", RegexOption.IGNORE_CASE)
private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
private const val LEADING = "([{<\"'\u201C\u2018\u00AB*_"
private const val TRAILING = ".,;:!?\"'\u201D\u2019\u00BB*_"
private val CLOSERS_TO_OPENERS = mapOf(')' to '(', ']' to '[', '}' to '{', '>' to '<')
