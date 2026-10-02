package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.decoration.Decoration
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.DecorationStyle
import com.darkrockstudios.texteditor.decoration.clearDecorations
import com.darkrockstudios.texteditor.decoration.decorations
import com.darkrockstudios.texteditor.decoration.setDecorations
import com.darkrockstudios.texteditor.find.FindCurrentMatchStyle
import com.darkrockstudios.texteditor.find.FindMatchStyle
import com.darkrockstudios.texteditor.find.FindState
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.spellcheck.diagnostics.LineDiagnostic
import com.darkrockstudios.texteditor.spellcheck.diagnostics.TextDiagnosticsChecker
import com.darkrockstudios.texteditor.spellcheck.diagnostics.TextDiagnosticsState
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spell check, diagnostics, find and a host each keep their decorations on a layer of
 * their own: none reads another's spans, and none clears another's.
 */
class DecorationLayerCoexistenceTest {

	private fun editor(text: String) = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	)

	private val checker = object : EditorSpellChecker {
		val words = setOf("cat", "the", "dog", "over", "plain", "words")

		override suspend fun isCorrectWord(word: String): Boolean = word in words

		override suspend fun suggestions(input: String, scope: EditorSpellChecker.Scope, closestOnly: Boolean): List<Suggestion> =
			emptyList()
	}

	private fun flagging(pattern: String) = TextDiagnosticsChecker { lines ->
		lines.map { line -> Regex(pattern).findAll(line).map { LineDiagnostic(it.range.first, it.range.last + 1, pattern, listOf("x")) }.toList() }
	}

	private fun span(line: Int, start: Int, end: Int, style: DecorationStyle) =
		RichSpan(TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, end)), style)

	/** Counts how often a span of it is hashed, which building the whole span set does to every span. */
	private class CountingStyle(override val layer: DecorationLayer) : DecorationStyle {
		var hashes = 0

		override fun hashCode(): Int {
			hashes++
			return 7
		}

		override fun equals(other: Any?): Boolean = other === this
	}

	@Test
	fun `spell check and diagnostics read no other owner's spans`() = runTest {
		val text = (0 until 300).joinToString("\n") { if (it < 100) "teh cat the the dog over" else "plain words" }
		val textState = editor(text)
		val host = DecorationLayer("host")
		val counting = CountingStyle(host)
		textState.setDecorations(host, (100 until 300).map { span(it, 0, 5, counting) })
		val spellCheck = SpellCheckState(textState, checker, scanContext = EmptyCoroutineContext)
		val diagnostics = TextDiagnosticsState(textState, flagging("the the"), scanContext = EmptyCoroutineContext)
		spellCheck.runFullSpellCheck()
		diagnostics.refresh()
		assertEquals(100, textState.decorations(spellCheck.layer).size)
		assertEquals(100, textState.decorations(diagnostics.layer).size)
		counting.hashes = 0

		spellCheck.runFullSpellCheck()
		diagnostics.refresh()
		diagnostics.setColor(Color.Red)
		assertEquals(0, counting.hashes, "a full pass built the whole span set")

		val at = CharLineOffset(5, 3)
		textState.replace(TextEditorRange(at, at), "x")
		val edit = TextEditOperation.Insert(at, AnnotatedString("x"), at, at.copy(char = 4))
		counting.hashes = 0
		spellCheck.invalidateSpellCheckSpans(edit)
		diagnostics.invalidate(edit)
		spellCheck.runPartialSpellCheck(TextEditorRange(CharLineOffset(5, 0), CharLineOffset(5, 8)))
		diagnostics.refresh()
		diagnostics.applyFix(textState.decorations(diagnostics.layer).first(), "the")
		spellCheck.ignoreWord("teh")
		spellCheck.setSpellCheckingEnabled(false)

		assertEquals(0, counting.hashes, "a pass after an edit built the whole span set")
		assertEquals(200, textState.decorations(host).size)
	}

	@Test
	fun `spell check, diagnostics, find and a host layer leave each other alone`() = runTest {
		val textState = editor("teh cat the the dog\ncat over teh dog")
		val syntax = DecorationLayer("syntax")
		val keyword = Decoration(syntax, textColor = Color.Blue)
		val spellCheck = SpellCheckState(textState, checker, scanContext = EmptyCoroutineContext)
		val diagnostics = TextDiagnosticsState(textState, flagging("the the|over"), scanContext = EmptyCoroutineContext)
		val find = FindState(textState, backgroundScope)
		runCurrent()

		fun owners(): Map<String, List<String>> = mapOf(
			"syntax" to textState.decorations(syntax),
			"spell check" to textState.decorations(spellCheck.layer),
			"diagnostics" to textState.decorations(diagnostics.layer),
			"find" to textState.richSpanManager.getAllRichSpans().filter { it.style is FindMatchStyle || it.style is FindCurrentMatchStyle },
		).mapValues { (_, spans) -> spans.map { "${it.range.start.line}:${it.range.start.char}-${it.range.end.char}" }.sorted() }

		suspend fun step(owner: String, action: suspend () -> Unit) {
			val before = owners()
			action()
			assertEquals(before - owner, owners() - owner, "$owner touched another owner's decorations")
		}

		step("syntax") { textState.setDecorations(syntax, listOf(span(0, 4, 7, keyword), span(1, 0, 3, keyword))) }
		step("spell check") { spellCheck.runFullSpellCheck() }
		step("diagnostics") { diagnostics.refresh() }
		step("find") { find.search("dog") }
		val all = owners()
		assertTrue(all.values.all { it.isNotEmpty() }, "every owner has decorations: $all")

		step("find") { find.findNext() }
		step("spell check") { spellCheck.ignoreWord("teh") }
		step("spell check") { spellCheck.setSpellCheckingEnabled(false) }
		step("spell check") { spellCheck.setSpellCheckingEnabled(true) }
		step("diagnostics") { diagnostics.setColor(Color.Green) }
		step("diagnostics") { diagnostics.setChecker(null) }
		step("syntax") { textState.clearDecorations(syntax) }
		step("find") { find.close() }
	}
}
