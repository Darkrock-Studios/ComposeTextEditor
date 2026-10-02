package codeeditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.decoration.Decoration
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.DecorationStyle
import com.darkrockstudios.texteditor.decoration.clearDecorations
import com.darkrockstudios.texteditor.decoration.replaceDecorations
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import com.darkrockstudios.texteditor.state.TextEditorState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.CodeHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.PhraseLocation
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.concurrent.Volatile

/** How long typing pauses before the text is highlighted again. */
private const val TYPING_PAUSE_MS = 150L

/** The Highlights theme for the app's light or dark look. */
fun syntaxTheme(darkMode: Boolean): SyntaxTheme =
	if (darkMode) SyntaxThemes.darcula(darkMode = true) else SyntaxThemes.atom(darkMode = false)

fun SyntaxLanguage.displayName(): String = when (this) {
	SyntaxLanguage.DEFAULT -> "Any"
	SyntaxLanguage.CPP -> "C++"
	SyntaxLanguage.CSHARP -> "C#"
	SyntaxLanguage.COFFEESCRIPT -> "CoffeeScript"
	SyntaxLanguage.JAVASCRIPT -> "JavaScript"
	SyntaxLanguage.TYPESCRIPT -> "TypeScript"
	SyntaxLanguage.PHP -> "PHP"
	else -> name.lowercase().replaceFirstChar { it.uppercase() }
}

/** The languages Highlights knows, by name, any language last. */
val syntaxLanguages: List<SyntaxLanguage> =
	SyntaxLanguage.entries.filter { it != SyntaxLanguage.DEFAULT }.sortedBy { it.displayName() } + SyntaxLanguage.DEFAULT

/** One look per colour of [theme], as decorations of [layer]; read-only, so safe from any thread. */
class SyntaxColors(val layer: DecorationLayer, theme: SyntaxTheme) {
	private val styles: Map<Int, Decoration> = with(theme) {
		listOf(keyword, string, literal, comment, metadata, multilineComment, punctuation, mark, code)
	}.distinct().associateWith(::look)

	fun styleFor(rgb: Int): Decoration = styles[rgb] ?: look(rgb)

	private fun look(rgb: Int) = Decoration(layer, textColor = Color(rgb or 0xFF000000.toInt()))
}

/**
 * Highlights' colour ranges over [code] as decoration spans, a list for each line of [code]
 * in Highlights' order, so a comment's colour is drawn after a keyword's inside it, and
 * each span once. A range crossing a line break is split at it, and one past the end
 * clamped. Highlights counts UTF-16 chars over the whole text, as the editor counts them
 * within a line.
 */
fun spansByLine(code: String, highlights: List<CodeHighlight>, colors: SyntaxColors): List<List<RichSpan>> {
	val starts = lineStarts(code)
	val byLine = List(starts.size) { LinkedHashSet<RichSpan>() }
	for (highlight in highlights) {
		if (highlight !is ColorHighlight) continue
		val start = highlight.location.start.coerceIn(0, code.length)
		val end = highlight.location.end.coerceIn(start, code.length)
		if (end == start) continue
		val style = colors.styleFor(highlight.rgb)
		var line = lineOf(starts, start)
		while (line < starts.size && starts[line] < end) {
			val lineEnd = if (line + 1 < starts.size) starts[line + 1] - 1 else code.length
			val from = maxOf(start, starts[line]) - starts[line]
			val to = minOf(end, lineEnd) - starts[line]
			if (to > from) byLine[line] += RichSpan(TextEditorRange(CharLineOffset(line, from), CharLineOffset(line, to)), style)
			line++
		}
	}
	return byLine.map { it.toList() }
}

/** Where each line of [code] starts. */
private fun lineStarts(code: String): IntArray {
	val starts = ArrayList<Int>()
	starts += 0
	for (i in code.indices) if (code[i] == '\n') starts += i + 1
	return starts.toIntArray()
}

/** The line holding [index]. */
private fun lineOf(starts: IntArray, index: Int): Int {
	var low = 0
	var high = starts.size - 1
	while (low < high) {
		val mid = (low + high + 1) ushr 1
		if (starts[mid] <= index) low = mid else high = mid - 1
	}
	return low
}

/** [layer]'s spans in [snapshot], a list for each line, each under the line it starts on. */
fun layerSpansByLine(snapshot: DocumentSnapshot, layer: DecorationLayer): List<List<RichSpan>> {
	val byLine = List(snapshot.lines.size) { ArrayList<RichSpan>() }
	for (span in snapshot.richSpans) {
		if ((span.style as? DecorationStyle)?.layer === layer) byLine.getOrNull(span.range.start.line)?.add(span)
	}
	return byLine
}

/** Lines whose spans change, and the spans they get. */
data class LineChange(val lines: IntRange, val spans: List<RichSpan>)

/**
 * The lines from the first whose spans in [new] differ from [old]'s to the last, with
 * their spans in [new], or null when none differs: one replace, one revision, however
 * many runs of lines changed.
 */
fun changedLines(old: List<List<RichSpan>>, new: List<List<RichSpan>>): LineChange? {
	val first = new.indices.firstOrNull { new[it] != old.getOrNull(it).orEmpty() } ?: return null
	val last = new.indices.last { new[it] != old.getOrNull(it).orEmpty() }
	return LineChange(first..last, (first..last).flatMap { new[it] })
}

/** How a language comments and writes text that can run over lines, as far as cutting it needs. */
private class CutSyntax(val lineComments: List<String>, val multiLine: List<Pair<String, String>>)

private val C_LIKE = CutSyntax(listOf("//"), listOf("/*" to "*/"))

private fun cutSyntax(language: SyntaxLanguage): CutSyntax = when (language) {
	SyntaxLanguage.C, SyntaxLanguage.CPP, SyntaxLanguage.JAVA, SyntaxLanguage.CSHARP, SyntaxLanguage.RUST -> C_LIKE
	SyntaxLanguage.KOTLIN, SyntaxLanguage.SWIFT -> CutSyntax(listOf("//"), listOf("/*" to "*/", "\"\"\"" to "\"\"\""))
	SyntaxLanguage.DART -> CutSyntax(listOf("//"), listOf("/*" to "*/", "\"\"\"" to "\"\"\"", "'''" to "'''"))
	SyntaxLanguage.GO, SyntaxLanguage.JAVASCRIPT, SyntaxLanguage.TYPESCRIPT -> CutSyntax(listOf("//"), listOf("/*" to "*/", "`" to "`"))
	SyntaxLanguage.PHP -> CutSyntax(listOf("//", "#"), listOf("/*" to "*/"))
	SyntaxLanguage.PYTHON -> CutSyntax(listOf("#"), listOf("\"\"\"" to "\"\"\"", "'''" to "'''"))
	SyntaxLanguage.COFFEESCRIPT -> CutSyntax(listOf("#"), listOf("###" to "###", "\"\"\"" to "\"\"\"", "'''" to "'''"))
	SyntaxLanguage.SHELL, SyntaxLanguage.PERL, SyntaxLanguage.RUBY -> CutSyntax(listOf("#"), emptyList())
	SyntaxLanguage.DEFAULT -> CutSyntax(listOf("//"), listOf("/*" to "*/", "\"\"\"" to "\"\"\"", "'''" to "'''", "`" to "`"))
}

/**
 * Where [code] can be cut into pieces Highlights analyses alone: the offset each piece
 * starts at. Highlights' analysis grows with the square of the text (200 lines take
 * about 3 ms, 5,000 lines about a second), so a long file is analysed a piece at a time,
 * and a piece whose text is unchanged is not analysed again.
 *
 * Once a piece has [minLines], it ends at a blank line or at a line its text picks (one
 * in about [pickOneIn]), so a line typed or removed moves no cut but the nearest and the
 * pieces after it keep their text; [maxLines] bounds a piece whatever its lines. No cut
 * falls inside a block comment or a string [language] lets run over lines, as far as a
 * scan that takes quotes to end at the line can tell; a cut it misjudges only colours the
 * lines around it wrongly.
 */
fun chunkStarts(
	code: String,
	language: SyntaxLanguage = SyntaxLanguage.DEFAULT,
	minLines: Int = 60,
	maxLines: Int = 400,
	pickOneIn: Int = 64,
): List<Int> {
	val syntax = cutSyntax(language)
	val starts = arrayListOf(0)
	var lines = 0
	var blank = true
	var lineHash = 0
	var closer: String? = null
	var i = 0
	while (i < code.length) {
		val c = code[i]
		if (c == '\n') {
			lines++
			// The hash's high bits, mixed by a multiply, pick about one line in [pickOneIn].
			val picked = blank || ((lineHash * -0x61C88647) ushr 8) % pickOneIn == 0
			if (closer == null && i + 1 < code.length && ((picked && lines >= minLines) || lines >= maxLines)) {
				starts += i + 1
				lines = 0
			}
			blank = true
			lineHash = 0
			i++
			continue
		}
		if (!c.isWhitespace()) blank = false
		lineHash = lineHash * 31 + c.code
		val open = closer
		if (open != null) {
			if (code.startsWith(open, i)) {
				closer = null
				i += open.length
			} else {
				i++
			}
			continue
		}
		val opener = syntax.multiLine.firstOrNull { code.startsWith(it.first, i) }
		when {
			opener != null -> {
				closer = opener.second
				i += opener.first.length
			}
			syntax.lineComments.any { code.startsWith(it, i) } -> i = code.indexOf('\n', i).takeIf { it >= 0 } ?: code.length
			c == '"' || c == '\'' -> {
				var j = i + 1
				while (j < code.length && code[j] != c && code[j] != '\n') {
					j += if (code[j] == '\\' && code.getOrNull(j + 1) != '\n') 2 else 1
				}
				i = if (j < code.length && code[j] == c) j + 1 else j
			}
			else -> i++
		}
	}
	return starts
}

/** Highlights [language] in [theme] into [layer]. */
class SyntaxHighlighter(
	val language: SyntaxLanguage,
	val theme: SyntaxTheme,
	val layer: DecorationLayer,
	private val analyze: (String) -> List<CodeHighlight> = { code ->
		Highlights.Builder().code(code).language(language).theme(theme).build().getHighlights()
	},
) {
	private val colors = SyntaxColors(layer, theme)

	/**
	 * The analysis of each piece, by its text, as the last run left it. Replaced whole, so a
	 * run racing another only loses entries.
	 */
	@Volatile
	private var analysed: Map<String, List<CodeHighlight>> = emptyMap()

	/**
	 * What brings [snapshot]'s layer to its text highlighted, or null when nothing changes.
	 * Reads only the snapshot, so it runs off the main thread, and yields after each piece it
	 * analyses. Highlights' own incremental mode is not used: it only follows text added at
	 * the end.
	 */
	suspend fun changes(snapshot: DocumentSnapshot): LineChange? {
		val code = snapshot.getAllText().text
		val starts = chunkStarts(code, language)
		val previous = analysed
		val current = HashMap<String, List<CodeHighlight>>(starts.size * 2)
		var finished = false
		val highlights = ArrayList<CodeHighlight>()
		try {
			for ((index, from) in starts.withIndex()) {
				val chunk = code.substring(from, starts.getOrElse(index + 1) { code.length })
				val found = previous[chunk] ?: current[chunk] ?: analyze(chunk).also {
					current[chunk] = it
					yield()
				}
				current[chunk] = found
				for (highlight in found) {
					if (highlight is ColorHighlight) {
						highlights += ColorHighlight(PhraseLocation(highlight.location.start + from, highlight.location.end + from), highlight.rgb)
					}
				}
			}
			finished = true
		} finally {
			// A run cancelled for newer text keeps what it analysed for the run after it.
			analysed = if (finished) current else previous + current
		}
		return changedLines(layerSpansByLine(snapshot, layer), spansByLine(code, highlights, colors))
	}
}

/**
 * Applies [change], worked out from [snapshot], to [layer] unless the text has changed
 * since, which would land it on the wrong characters; returns whether it did. Every
 * revision until the text changes shares the snapshot's line list, so its identity says
 * whether the text has.
 */
fun TextEditorState.applyHighlights(layer: DecorationLayer, snapshot: DocumentSnapshot, change: LineChange?): Boolean {
	if (snapshot().lines !== snapshot.lines) return false
	if (change != null) replaceDecorations(layer, change.lines, change.spans)
	return true
}

/**
 * Keeps [state]'s text highlighted as [language] in [theme] in [layer] while [enabled]:
 * at once, then after each pause in typing, worked out off the main thread, with only the
 * lines that change applied.
 */
@OptIn(FlowPreview::class)
@Composable
fun SyntaxHighlighting(
	state: TextEditorState,
	layer: DecorationLayer,
	language: SyntaxLanguage,
	theme: SyntaxTheme,
	enabled: Boolean = true,
) {
	LaunchedEffect(state, layer, language, theme, enabled) {
		if (!enabled) {
			state.clearDecorations(layer)
			return@LaunchedEffect
		}
		val highlighter = SyntaxHighlighter(language, theme, layer)
		var settled = false
		snapshotFlow {
			// The rows are published after every edit, as soon as the editor is laid out;
			// colours on text not laid out yet are not drawn either.
			state.lineOffsets
			state.snapshot()
		}
			// A revision that kept the line list, such as one applying highlights, is no edit.
			.distinctUntilChanged { a, b -> a.lines === b.lines }
			.debounce { if (settled) TYPING_PAUSE_MS else 0L }
			.collectLatest { snapshot ->
				settled = true
				val change = withContext(Dispatchers.Default) { highlighter.changes(snapshot) }
				state.applyHighlights(layer, snapshot, change)
			}
	}
}
