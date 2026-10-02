package codeeditor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.decoration.Decoration
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.decorations
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.PhraseLocation
import dev.snipme.highlights.model.SyntaxLanguage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntaxHighlightingTest {

	private val layer = DecorationLayer("syntax")
	private val theme = syntaxTheme(darkMode = false)
	private val colors = SyntaxColors(layer, theme)

	private fun color(start: Int, end: Int, rgb: Int = theme.keyword) = ColorHighlight(PhraseLocation(start, end), rgb)

	/** Each line's spans as `start-end` columns. */
	private fun List<List<RichSpan>>.columns() = map { line -> line.map { "${it.range.start.char}-${it.range.end.char}" } }

	private fun TextEditorState.highlightNow(language: SyntaxLanguage = SyntaxLanguage.KOTLIN): Boolean {
		val snapshot = snapshot()
		return applyHighlights(layer, snapshot, runBlocking { SyntaxHighlighter(language, theme, layer).changes(snapshot) })
	}

	@Test
	fun `ranges map to columns of their lines`() {
		val code = "val a = 1\nfun b() {}\n"
		val spans = spansByLine(code, listOf(color(0, 3), color(10, 13), color(8, 9, theme.literal)), colors)
		assertEquals(listOf(listOf("0-3", "8-9"), listOf("0-3"), emptyList()), spans.columns())
		assertEquals(Color(theme.literal or 0xFF000000.toInt()), (spans[0][1].style as Decoration).textColor)
		assertTrue(spans.flatten().all { (it.style as Decoration).layer === layer })
	}

	@Test
	fun `a range across lines is split at each break, and one past the end clamped`() {
		val code = "/* one\n\ntwo */ x"
		val spans = spansByLine(code, listOf(color(0, 14, theme.multilineComment), color(15, 99)), colors)
		assertEquals(listOf(listOf("0-6"), emptyList(), listOf("0-6", "7-8")), spans.columns())
	}

	@Test
	fun `columns after an emoji count its two chars`() {
		val code = "val s = \"😀\" // c"
		// Highlights' offsets are UTF-16 chars, as the editor's columns are.
		val comment = code.indexOf("//")
		val spans = spansByLine(code, listOf(color(8, 12, theme.string), color(comment, code.length, theme.comment)), colors)
		assertEquals(listOf(listOf("8-12", "13-17")), spans.columns())
		assertEquals("\"😀\"", code.substring(8, 12))
	}

	@Test
	fun `an empty file has one empty line, and bold is ignored`() {
		assertEquals(listOf(emptyList<String>()), spansByLine("", emptyList(), colors).columns())
		assertEquals(listOf(emptyList<String>()), spansByLine("", listOf(color(0, 4)), colors).columns())
		assertEquals(listOf(emptyList<String>()), spansByLine("abc", listOf(BoldHighlight(PhraseLocation(0, 3))), colors).columns())
	}

	@Test
	fun `the lines from the first that differs to the last change`() {
		val a = RichSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3)), colors.styleFor(theme.keyword))
		fun on(line: Int) = RichSpan(TextEditorRange(CharLineOffset(line, 0), CharLineOffset(line, 3)), colors.styleFor(theme.keyword))
		val old = listOf(listOf(a), listOf(on(1)), emptyList(), listOf(on(3)), listOf(on(4)))
		val new = listOf(listOf(a), emptyList(), listOf(on(2)), listOf(on(3)), emptyList())
		assertEquals(LineChange(1..4, listOf(on(2), on(3))), changedLines(old, new))
		assertEquals(null, changedLines(new, new))
	}

	@Test
	fun `Kotlin keywords, strings and comments get the theme's colours`() {
		val state = TextEditorState(AnnotatedString("fun main() {\n    val s = \"hi\" // note\n}"))
		assertTrue(state.highlightNow())

		fun colourAt(line: Int, char: Int) = state.decorations(layer, line..line)
			.lastOrNull { it.range.start.char <= char && char < it.range.end.char }
			?.let { (it.style as Decoration).textColor }
		assertEquals(Color(theme.keyword or 0xFF000000.toInt()), colourAt(0, 0))
		assertEquals(Color(theme.string or 0xFF000000.toInt()), colourAt(1, 13))
		assertEquals(Color(theme.comment or 0xFF000000.toInt()), colourAt(1, 21))

		// Highlighting the same text again changes nothing.
		val snapshot = state.snapshot()
		assertEquals(null, runBlocking { SyntaxHighlighter(SyntaxLanguage.KOTLIN, theme, layer).changes(snapshot) })
	}

	@Test
	fun `a result for older text is not applied`() {
		val state = TextEditorState(AnnotatedString("val a = 1"))
		val before = state.snapshot()
		val changes = runBlocking { SyntaxHighlighter(SyntaxLanguage.KOTLIN, theme, layer).changes(before) }
		assertTrue(changes != null)

		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertStringAtCursor("// ")

		assertFalse(state.applyHighlights(layer, before, changes))
		assertTrue(state.decorations(layer).isEmpty())
	}

	@Test
	fun `a result for the same text typed again is not applied either`() {
		val state = TextEditorState(AnnotatedString("val a = 1"))
		val before = state.snapshot()
		val changes = runBlocking { SyntaxHighlighter(SyntaxLanguage.KOTLIN, theme, layer).changes(before) }
		state.cursor.updatePosition(CharLineOffset(0, 9))
		state.insertStringAtCursor("2")
		state.undo()
		assertEquals("val a = 1", state.getAllText().text)

		assertFalse(state.applyHighlights(layer, before, changes))
	}

	@Test
	fun `highlighting never enters undo, copies or the text`() {
		val state = TextEditorState(AnnotatedString("fun a() = 1"))
		assertTrue(state.highlightNow())
		assertTrue(state.decorations(layer).isNotEmpty())
		assertFalse(state.canUndo)

		state.cursor.updatePosition(CharLineOffset(0, 11))
		state.insertStringAtCursor("0")
		assertTrue(state.highlightNow())
		state.undo()
		assertEquals("fun a() = 1", state.getAllText().text)
		assertFalse(state.canUndo, "highlighting added an undo step")

		assertTrue(state.getAllText().spanStyles.isEmpty())
		state.selector.selectAll()
		assertTrue(state.selector.getSelectedText().spanStyles.isEmpty(), "a copy would carry the colours")
		val copied = state.copyRichSpans(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 11)))
		val other = TextEditorState(AnnotatedString(""))
		other.insertStringAtCursor(state.getAllText().text)
		other.pasteRichSpans(CharLineOffset(0, 0), state.getAllText(), copied)
		assertTrue(other.snapshot().richSpans.isEmpty(), "a paste brought the colours along")
	}

	@Test
	fun `pieces end at blank lines, never inside a block comment or raw string`() {
		fun lines(vararg lines: String) = lines.joinToString("\n")
		val code = lines("a", "b", "", "/* one", "", "two */", "c", "", "val s = \"\"\"", "", "\"\"\"", "d", "", "e")
		val starts = chunkStarts(code, SyntaxLanguage.KOTLIN, minLines = 2, maxLines = 100, pickOneIn = Int.MAX_VALUE)
		val startLines = starts.map { start -> code.substring(0, start).count { it == '\n' } }
		assertEquals(listOf(0, 3, 8, 13), startLines)
	}

	@Test
	fun `a quote or comment on one line opens nothing across lines`() {
		val code = "val c = '\"'\n// don't\n\nx\n\ny"
		assertEquals(listOf(0, code.indexOf("x"), code.indexOf("y")), chunkStarts(code, SyntaxLanguage.KOTLIN, minLines = 1, maxLines = 100, pickOneIn = Int.MAX_VALUE))
	}

	@Test
	fun `a long run of lines is cut at most every so many`() {
		val code = (1..10).joinToString("\n") { "x$it" }
		assertEquals(4, chunkStarts(code, minLines = 1, maxLines = 3, pickOneIn = Int.MAX_VALUE).size)
	}

	@Test
	fun `a line added to a long run without blank lines moves only the nearest cut`() {
		val lines = (0 until 3_000).map { "val v$it = compute($it)" }
		fun pieces(code: String): Set<String> {
			val starts = chunkStarts(code, SyntaxLanguage.KOTLIN)
			return starts.indices.map { code.substring(starts[it], starts.getOrElse(it + 1) { code.length }) }.toSet()
		}
		val before = pieces(lines.joinToString("\n"))
		val after = pieces((lines.take(100) + "val added = 1" + lines.drop(100)).joinToString("\n"))
		assertTrue(before.size > 10, "only ${before.size} pieces")
		assertTrue((after - before).size <= 2, "${(after - before).size} of ${after.size} pieces changed")
	}

	@Test
	fun `a language's own comments and strings decide the cuts`() {
		// "//" is Python's floor division, so the docstring after it still opens.
		val code = "x = a // b; s = \"\"\"\n\nnot code\n\"\"\"\n\ny = 1"
		val starts = chunkStarts(code, SyntaxLanguage.PYTHON, minLines = 1, maxLines = 100, pickOneIn = Int.MAX_VALUE)
		assertEquals(listOf(0, code.indexOf("y = 1")), starts)
	}

	@Test
	fun `a file in pieces highlights as it does whole`() {
		val block = "/** Doc\n\n */\nfun f%d(x: Int) = \"\"\"\n\nraw %d\n\"\"\".length + x * 2 // c\n\n"
		val code = (0 until 300).joinToString("") { block.replace("%d", it.toString()) }
		val state = TextEditorState(AnnotatedString(code))
		assertTrue(chunkStarts(code, SyntaxLanguage.KOTLIN).size > 5)
		assertTrue(state.highlightNow())

		val whole = dev.snipme.highlights.Highlights.Builder().code(code).language(SyntaxLanguage.KOTLIN).theme(theme).build().getHighlights()
		val expected = spansByLine(code, whole, colors).map { line -> line.map { it.range to (it.style as Decoration).textColor }.toSet() }
		val actual = layerSpansByLine(state.snapshot(), layer).map { line -> line.map { it.range to (it.style as Decoration).textColor }.toSet() }
		// Analysed whole, Highlights misses some literals it finds in a piece ("2" in `x * 2`),
		// so the pieces colour everything the whole does, and some more literals.
		val missing = expected.indices.filter { !actual[it].containsAll(expected[it]) }
		assertTrue(missing.isEmpty(), "lines $missing lost colours in pieces")
		val literal = Color(theme.literal or 0xFF000000.toInt())
		val more = expected.indices.flatMap { actual[it] - expected[it] }
		assertTrue(more.all { it.second == literal }, "pieces added ${more.filter { it.second != literal }}")
	}

	@Test
	fun `only a changed piece is analysed again`() {
		var analysed = 0
		val highlighter = SyntaxHighlighter(SyntaxLanguage.KOTLIN, theme, layer) { code ->
			analysed++
			dev.snipme.highlights.Highlights.Builder().code(code).language(SyntaxLanguage.KOTLIN).theme(theme).build().getHighlights()
		}
		val code = (0 until 2_000).joinToString("\n") { if (it % 10 == 9) "" else "val v$it = $it" }
		val state = TextEditorState(AnnotatedString(code))
		val first = state.snapshot()
		assertTrue(state.applyHighlights(layer, first, runBlocking { highlighter.changes(first) }))
		val pieces = analysed
		assertTrue(pieces > 10)

		state.cursor.updatePosition(CharLineOffset(1_000, 4))
		state.insertStringAtCursor("x")
		state.cursor.updatePosition(CharLineOffset(1_500, 0))
		state.insertNewlineAtCursor()
		val second = state.snapshot()
		val changes = runBlocking { highlighter.changes(second) }
		assertTrue(analysed - pieces <= 3, "a typed letter and a new line analysed ${analysed - pieces} pieces again")
		assertTrue(state.applyHighlights(layer, second, changes))
		// The colours moved with the edits, so little or nothing is left to replace.
		changes?.lines?.let { assertTrue(it.first >= 1_000 && it.last <= 1_501, "replaced $it") }
	}
}
