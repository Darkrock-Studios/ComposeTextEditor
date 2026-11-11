package com.darkrockstudios.texteditor.spellcheck.diagnostics

/** Finds issues other than spelling, such as grammar, in lines of text. */
fun interface TextDiagnosticsChecker {
	/**
	 * A result for each of [lines], in the same order. A line is one of the editor's lines, a
	 * paragraph, without its line break. Called only for lines whose text it has not seen, or whose
	 * last result was not [LineCheck.complete], so it should judge each line on its own. It must not
	 * throw: it runs inside the editor's effects. A line left without a result is incomplete.
	 */
	suspend fun check(lines: List<String>): List<LineCheck>

	/**
	 * The issues that depend on more than their own line, such as a word used again a line later: one
	 * list per line of [lines], the whole text. Called with every line, blank ones included, each time
	 * the text is checked, so it should be quick. Null, as by default, when the checker has no such
	 * issues to find. It must not throw either.
	 */
	suspend fun checkText(lines: List<String>): List<List<LineDiagnostic>>? = null
}

/**
 * An issue in one line, from [start] to [end], as character offsets into the line.
 *
 * @param message Says what is wrong, shown on the menu the underline opens.
 * @param fixes Offered on that menu.
 * @param severity How the issue is underlined.
 */
data class LineDiagnostic(
	val start: Int,
	val end: Int,
	val message: String,
	val fixes: List<DiagnosticFix> = emptyList(),
	val severity: DiagnosticSeverity = DiagnosticSeverity.Error,
)

enum class DiagnosticSeverity {
	/** A mistake: a wavy underline. */
	Error,

	/** Something the writer may want to change, such as a repeated word: a dotted underline. */
	Suggestion,
}

/**
 * One line's result from [TextDiagnosticsChecker.check]: the [diagnostics] to underline, and whether
 * the line was [complete]ly checked. A complete result is kept until the line's text changes. An
 * incomplete one, such as when a service the checker relies on could not be reached, is underlined
 * and the line goes to [TextDiagnosticsChecker.check] again on each refresh until a complete result
 * comes back. Each refresh, after any edit, sends every incomplete line again, so a checker that
 * retries something slow should hold off on its own and answer incomplete meanwhile. A failure that
 * will not pass, such as a line the service refuses, is complete, or the line is sent again for as
 * long as it stays.
 */
data class LineCheck(
	val diagnostics: List<LineDiagnostic>,
	val complete: Boolean = true,
)

/** A [LineDiagnostic] whose fixes are replacements, each shown as itself. */
fun LineDiagnostic(start: Int, end: Int, message: String, fixes: List<String>): LineDiagnostic =
	LineDiagnostic(start, end, message, fixes.map(::DiagnosticFix))

/**
 * A fix for a diagnostic: [replacement] for its range, shown on the menu as [label]. A label says what
 * a fix does where the replacement alone would not, such as "Remove “the”" for an empty replacement.
 */
data class DiagnosticFix(
	val replacement: String,
	val label: String = replacement,
)
