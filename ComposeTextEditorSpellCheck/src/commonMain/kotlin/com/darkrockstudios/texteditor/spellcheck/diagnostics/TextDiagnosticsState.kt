package com.darkrockstudios.texteditor.spellcheck.diagnostics

import androidx.compose.ui.graphics.Color
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** The default underline color: blue, apart from spell check's red. */
val DefaultDiagnosticColor = Color(0xFF2F6FDB)

/**
 * Underlines what a [TextDiagnosticsChecker] finds in [textState], line by line. Results are kept by
 * line text, so after an edit only the lines that changed go to the checker. Pass it to
 * [com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor], which refreshes it as the text
 * changes and opens a menu of the message and fixes on an underline.
 *
 * @param scanContext Where [TextDiagnosticsChecker.check] runs. Spans change on the caller's dispatcher.
 * @param cacheLines How many lines' results to keep.
 */
class TextDiagnosticsState(
	val textState: TextEditorState,
	checker: TextDiagnosticsChecker?,
	color: Color = DefaultDiagnosticColor,
	private val scanContext: CoroutineContext = Dispatchers.Default,
	private val cacheLines: Int = DEFAULT_CACHE_LINES,
) {
	var checker: TextDiagnosticsChecker? = checker
		private set

	var color: Color = color
		private set

	/** The [TextEditorState.documentGeneration] the latest refresh started on. */
	internal var refreshGeneration = -1
		private set

	// By line text, oldest first, so the oldest go when there are too many.
	private val checked = LinkedHashMap<String, List<LineDiagnostic>>()
	private val refreshing = Mutex()

	/** Replaces the checker, forgetting what the old one found, and checks the whole text again. */
	suspend fun setChecker(value: TextDiagnosticsChecker?) {
		if (value === checker) return
		checker = value
		refreshing.withLock { checked.clear() }
		refresh()
	}

	/** Redraws every underline in [value]. */
	fun setColor(value: Color) {
		if (value == color) return
		color = value
		val spans = diagnosticSpans()
		textState.updateRichSpans(
			remove = spans,
			add = spans.map { it.copy(style = (it.style as DiagnosticStyle).copy(color = value)) },
		)
	}

	/**
	 * Checks the lines not checked yet, then makes every line's underlines match its results. A line
	 * edited while the checker ran keeps its underlines until the next refresh.
	 */
	suspend fun refresh() = refreshing.withLock {
		refreshGeneration = textState.documentGeneration.value
		val current = checker
		if (current == null) {
			textState.updateRichSpans(remove = diagnosticSpans(), add = emptyList())
			return@withLock
		}
		val unchecked = textState.textLines.map { it.text }.filter { it.isNotBlank() && it !in checked }.distinct()
		if (unchecked.isNotEmpty()) {
			val results = withContext(scanContext) { current.check(unchecked) }
			// Replaced while this ran: the new checker's own refresh follows.
			if (checker !== current) return@withLock
			unchecked.forEachIndexed { index, line -> remember(line, results.getOrNull(index).orEmpty()) }
		}
		reconcile()
	}

	/**
	 * Removes the underlines an edit touched, ahead of the refresh that checks the edited lines again,
	 * so none sits on text it no longer describes.
	 */
	fun invalidate(operation: TextEditOperation) {
		val touched = when (operation) {
			is TextEditOperation.Insert -> TextEditorRange(operation.position, operation.position.after(operation.text.text))
			is TextEditOperation.Delete -> TextEditorRange(operation.range.start, operation.range.start)
			is TextEditOperation.Replace -> TextEditorRange(operation.range.start, operation.range.start.after(operation.newText.text))
			else -> return
		}
		val doomed = diagnosticSpans().filter { it.range.start <= touched.end && touched.start <= it.range.end }
		if (doomed.isNotEmpty()) textState.updateRichSpans(remove = doomed, add = emptyList())
	}

	/** Replaces the text under [span], a diagnostic's underline, with [fix], unless a refresh has since replaced it. */
	fun applyFix(span: RichSpan, fix: String) {
		if (span !in textState.richSpanManager.getAllRichSpans()) return
		textState.updateRichSpans(remove = listOf(span), add = emptyList())
		textState.replace(span.range, fix, true)
	}

	private fun reconcile() {
		val existing = diagnosticSpans().groupBy { it.range.start.line }
		val remove = mutableListOf<RichSpan>()
		val add = mutableListOf<RichSpan>()
		textState.textLines.forEachIndexed { index, line ->
			val found = checked[line.text] ?: return@forEachIndexed
			val wanted = found.mapNotNull { it.toSpan(index, line.length) }.toSet()
			val have = existing[index].orEmpty().toSet()
			if (wanted != have) {
				remove += have - wanted
				add += wanted - have
			}
		}
		textState.updateRichSpans(remove = remove, add = add)
	}

	private fun remember(line: String, diagnostics: List<LineDiagnostic>) {
		checked[line] = diagnostics
		if (checked.size > cacheLines) {
			val oldest = checked.keys.iterator()
			repeat(checked.size - cacheLines) {
				oldest.next()
				oldest.remove()
			}
		}
	}

	private fun LineDiagnostic.toSpan(line: Int, length: Int): RichSpan? {
		val from = start.coerceIn(0, length)
		val to = end.coerceIn(0, length)
		if (from >= to) return null
		return RichSpan(
			TextEditorRange(CharLineOffset(line, from), CharLineOffset(line, to)),
			DiagnosticStyle(message, fixes, color),
		)
	}

	private fun diagnosticSpans(): List<RichSpan> =
		textState.richSpanManager.getAllRichSpans().filter { it.style is DiagnosticStyle }

	private companion object {
		const val DEFAULT_CACHE_LINES = 4096
	}
}

private fun CharLineOffset.after(text: String): CharLineOffset {
	val breaks = text.count { it == '\n' }
	return if (breaks == 0) copy(char = char + text.length) else CharLineOffset(line + breaks, text.length - text.lastIndexOf('\n') - 1)
}
