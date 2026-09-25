package com.darkrockstudios.texteditor.spellcheck.diagnostics

/** Finds issues other than spelling, such as grammar, in lines of text. */
fun interface TextDiagnosticsChecker {
	/**
	 * The issues in each of [lines], in the same order. A line is one of the editor's lines, a
	 * paragraph, without its line break. Called only for lines whose text it has not seen, so it
	 * should judge each line on its own. It must not throw: it runs inside the editor's effects.
	 */
	suspend fun check(lines: List<String>): List<List<LineDiagnostic>>
}

/**
 * An issue in one line, from [start] to [end], as character offsets into the line.
 *
 * @param message Says what is wrong, shown on the menu the underline opens.
 * @param fixes Replacements for the range, offered on that menu.
 */
data class LineDiagnostic(
	val start: Int,
	val end: Int,
	val message: String,
	val fixes: List<String> = emptyList(),
)
