package com.darkrockstudios.texteditor.behaviors

import androidx.compose.ui.text.SpanStyle
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.EditBehavior
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.isCodeFence

/**
 * Typographic substitutions as the user types, each switchable on its own:
 *
 * - [doubleQuotes] and [singleQuotes]: straight quotes curl, opening at a line start
 *   or after a space, an opening bracket, or the other kind's opening quote, and
 *   closing anywhere else, so an apostrophe (`it's`) is a closing single quote.
 *   After a dash a quote closes when the line has one of its kind open, so
 *   interrupted dialogue closes. An opening single quote followed by a digit
 *   becomes an apostrophe (`'90s`).
 * - [emDashes]: two hyphens become an em dash as soon as the second is typed,
 *   spaced or not, as macOS and iOS do. A hyphen typed straight after that dash
 *   puts the three hyphens back, so `---` (a markdown rule, a separator) survives.
 * - [enDashes]: a hyphen between spaces after a word becomes an en dash when the
 *   space after it is typed (`1990 - 2000`), as Word does.
 * - [ellipses]: three periods become an ellipsis.
 *
 * Opt-in: add it to [TextEditorState.editBehaviors]. It edits on top of the typed
 * text, so one undo gives back exactly what was typed. Text in inline code
 * ([com.darkrockstudios.texteditor.RichTextStyles.codeStyle]) or a code block is
 * neither rewritten nor read as part of a pattern.
 *
 * iOS keyboards substitute quotes and dashes themselves when the system's Smart
 * Punctuation setting is on, so leave this off on iOS unless the keyboard's is off.
 */
data class SmartPunctuation(
	val doubleQuotes: Boolean = true,
	val singleQuotes: Boolean = true,
	val emDashes: Boolean = true,
	val enDashes: Boolean = true,
	val ellipses: Boolean = true,
) : EditBehavior {

	/**
	 * The em dash or opening single quote the last substitution left just before the
	 * caret, which the next typed character may rewrite (a third hyphen, a digit). Any
	 * other edit in between advances the revision and retires it, so a dash or quote
	 * this did not just make is never rewritten.
	 */
	private var lastMade: Made? = null

	private class Made(val state: TextEditorState, val at: CharLineOffset, val revision: Int)

	/** One rewrite of [oldLength] characters at [start] on [line], in order of application. */
	private class Rewrite(val line: Int, val start: Int, val oldLength: Int, val text: String) {
		val range get() = TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, start + oldLength))

		/** Where [position] lands once this is applied. */
		fun map(position: CharLineOffset): CharLineOffset = when {
			position.line != line || position.char <= start -> position
			position.char >= start + oldLength -> position.copy(char = position.char + text.length - oldLength)
			else -> position.copy(char = start + minOf(text.length, position.char - start))
		}
	}

	private class LineResult(val rewrites: List<Rewrite>, val madeAtEnd: CharLineOffset?)

	override fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean {
		if (text.none { it in TRIGGERS || it.isDigit() }) return false
		val codeStyles = (state.retiredRichTextStyles + state.richTextStyles).mapTo(mutableSetOf()) { it.codeStyle }
		val results = (range.start.line..range.end.line).map { lineRewrites(state, it, range, codeStyles) }
		val rewrites = results.asReversed().flatMap { it.rewrites }
		if (rewrites.isEmpty()) return false
		var caret = state.cursorPosition
		state.editGroup {
			// Lines bottom up, so the lines above keep their indices; each line's in
			// the order they were made, which is the order their offsets assume.
			rewrites.forEach {
				state.replace(it.range, it.text, inheritStyle = true)
				caret = it.map(caret)
			}
			state.cursor.updatePosition(caret)
		}
		lastMade = results.last().madeAtEnd?.let { Made(state, it, state.revision) }
		return true
	}

	/** The rewrites typing [range]'s part of [line] calls for, as if typed one character at a time. */
	private fun lineRewrites(
		state: TextEditorState,
		line: Int,
		range: TextEditorRange,
		codeStyles: Set<SpanStyle>,
	): LineResult {
		val none = LineResult(emptyList(), null)
		if (state.isCodeFence(line)) return none
		val lineText = state.textLines[line]
		val typedStart = if (line == range.start.line) range.start.char else 0
		val typedEnd = if (line == range.end.line) range.end.char else lineText.length
		if (typedStart >= typedEnd) return none

		val codeSpans = lineText.spanStyles.filter { it.item in codeStyles }
		fun isCode(index: Int) = codeSpans.any { index >= it.start && index < it.end }
		val made = lastMade?.takeIf { it.state === state && it.revision + 1 == state.revision }?.at

		val windowStart = maxOf(0, typedStart - LOOK_BEHIND)
		val original = lineText.text
		val typing = Typing(line, windowStart, original.substring(0, windowStart))
		for (index in windowStart until typedEnd) {
			typing.append(original[index], isCode(index), made = made == CharLineOffset(line, index))
			if (index >= typedStart) typing.substitute()
		}
		val madeAtEnd = typing.lastMadeIndex()?.let { CharLineOffset(line, windowStart + it) }
		return LineResult(typing.rewrites, madeAtEnd)
	}

	/**
	 * A line's characters from [windowStart] as they are typed and rewritten, with the
	 * rewrites made so far. [before] is the line ahead of the window, read only to
	 * find an open quotation.
	 */
	private inner class Typing(val line: Int, val windowStart: Int, val before: String) {
		private val chars = mutableListOf<Char>()
		private val code = mutableListOf<Boolean>()
		private val made = mutableListOf<Boolean>()
		val rewrites = mutableListOf<Rewrite>()

		fun append(char: Char, isCode: Boolean, made: Boolean) {
			chars += char
			code += isCode
			this.made += made
		}

		fun lastMadeIndex(): Int? = chars.lastIndex.takeIf { it >= 0 && made[it] }

		/** The character at [index], [CODE] for one in code, null before the line's start. */
		private fun at(index: Int): Char? = when {
			index < 0 -> null
			code[index] -> CODE
			else -> chars[index]
		}

		/** Replaces [from] until [until] with [text], made by this when [isMade]. */
		private fun rewrite(from: Int, until: Int, text: String, isMade: Boolean = false) {
			rewrites += Rewrite(line, windowStart + from, until - from, text)
			repeat(until - from) {
				chars.removeAt(from)
				code.removeAt(from)
				made.removeAt(from)
			}
			text.forEachIndexed { offset, char ->
				chars.add(from + offset, char)
				code.add(from + offset, false)
				made.add(from + offset, isMade)
			}
		}

		/** Rewrites the end of the line for the character just typed. */
		fun substitute() {
			val last = chars.lastIndex
			if (code[last]) return
			val previous = at(last - 1)
			when (val typed = chars[last]) {
				'"' -> if (doubleQuotes) {
					rewrite(last, last + 1, if (opensQuote(previous, typed)) "$LEFT_DOUBLE" else "$RIGHT_DOUBLE")
				}

				'\'' -> if (singleQuotes) {
					val opens = opensQuote(previous, typed)
					rewrite(last, last + 1, if (opens) "$LEFT_SINGLE" else "$RIGHT_SINGLE", isMade = opens)
				}

				'-' -> when {
					!emDashes -> Unit
					previous == '-' && at(last - 2) != '-' -> rewrite(last - 1, last + 1, "$EM_DASH", isMade = true)
					previous == EM_DASH && made[last - 1] -> rewrite(last - 1, last + 1, "---")
				}

				' ' -> if (enDashes && previous == '-' && at(last - 2) == ' ' && at(last - 3)?.isLetterOrDigit() == true) {
					rewrite(last - 1, last, "$EN_DASH")
				}

				'.' -> if (ellipses && previous == '.' && at(last - 2) == '.' && at(last - 3) != '.') {
					rewrite(last - 2, last + 1, "$ELLIPSIS")
				}

				else -> if (
					typed.isDigit() && singleQuotes && previous == LEFT_SINGLE && made[last - 1] &&
					opensQuote(at(last - 2), '\'')
				) {
					rewrite(last - 1, last, "$RIGHT_SINGLE")
				}
			}
		}

		/** Whether a [quote] typed after [previous] (null at a line start) opens a quotation. */
		private fun opensQuote(previous: Char?, quote: Char): Boolean = when {
			previous == null -> true
			previous == LEFT_DOUBLE -> quote == '\''
			previous == LEFT_SINGLE -> quote == '"'
			previous in DASHES -> !hasOpenQuotation(quote)
			else -> previous.isWhitespace() || previous in OPENERS
		}

		/** Whether the line before the character just typed holds more opening [quote]s than closing ones. */
		private fun hasOpenQuotation(quote: Char): Boolean {
			val (open, close) = if (quote == '"') LEFT_DOUBLE to RIGHT_DOUBLE else LEFT_SINGLE to RIGHT_SINGLE
			var depth = 0
			fun count(char: Char) {
				if (char == open) depth++ else if (char == close) depth--
			}
			before.forEach(::count)
			for (index in 0 until chars.lastIndex) count(chars[index])
			return depth > 0
		}
	}

	private companion object {
		// The rules look back three characters; more covers what a collapse before them shrinks.
		const val LOOK_BEHIND = 8
		const val LEFT_DOUBLE = '\u201C'
		const val RIGHT_DOUBLE = '\u201D'
		const val LEFT_SINGLE = '\u2018'
		const val RIGHT_SINGLE = '\u2019'
		const val EN_DASH = '\u2013'
		const val EM_DASH = '\u2014'
		const val ELLIPSIS = '\u2026'

		/** Stands in for a character in code, which matches no pattern. */
		const val CODE = '\uFFFC'
		const val TRIGGERS = "\"'-. "
		const val DASHES = "-$EN_DASH$EM_DASH"
		const val OPENERS = "([{<\"'"
	}
}
