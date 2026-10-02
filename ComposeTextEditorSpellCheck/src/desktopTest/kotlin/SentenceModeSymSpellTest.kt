package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.symspellkt.common.SpellCheckSettings
import com.darkrockstudios.symspellkt.impl.SymSpell
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.adapters.SymSpellEditorSpellChecker
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sentence mode against a real checker: SymSpell's word-break
 * segmentation flags run-together words, and its offsets, relative to the sentence's
 * text, land on the words in the document.
 */
class SentenceModeSymSpellTest {

	private val checker = SymSpellEditorSpellChecker(
		SymSpell(spellCheckSettings = SpellCheckSettings(topK = 5)).apply {
			for (word in listOf("the", "cat", "sat", "on", "mat", "a", "dog", "ran", "home", "it", "was", "late")) {
				createDictionaryEntry(word, 1000)
			}
		}
	)

	private fun TestScope.editor(text: String) = TextEditorState(
		scope = this,
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString(text),
	)

	private fun TextEditorState.flagged(): List<Pair<TextEditorRange, String>> =
		richSpanManager.getAllRichSpans()
			.filter { it.style is SpellCheckStyle }
			.map { it.range to getTextInRange(it.range).text }
			.sortedBy { it.first.start }

	private fun range(line: Int, start: Int, end: Int) =
		TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, end))

	@Test
	fun `a run-together word is flagged where it is, on an indented line too`() = runTest {
		val state = editor("the cat saton the mat.\n    a dog ranhome")
		val spellCheck = SpellCheckState(state, checker).apply { spellCheckMode = SpellCheckMode.Sentence }

		spellCheck.runFullSpellCheck()

		assertEquals(listOf(range(0, 8, 13) to "saton", range(1, 10, 17) to "ranhome"), state.flagged())
	}

	@Test
	fun `a sentence does not run on into the next line`() = runTest {
		val state = editor("the cat sat\non the mat")
		val spellCheck = SpellCheckState(state, checker).apply { spellCheckMode = SpellCheckMode.Sentence }

		spellCheck.runFullSpellCheck()

		assertEquals(emptyList(), state.flagged())
	}

	@Test
	fun `a partial check replaces the flags of the lines it covers`() = runTest {
		val state = editor("the cat saton the mat\nit was late")
		val spellCheck = SpellCheckState(state, checker).apply { spellCheckMode = SpellCheckMode.Sentence }
		spellCheck.runFullSpellCheck()

		state.replace(range(1, 7, 11), "latehome")
		spellCheck.runPartialSpellCheck(range(1, 7, 15))

		assertEquals(listOf(range(0, 8, 13) to "saton", range(1, 7, 15) to "latehome"), state.flagged())
	}

	@Test
	fun `a partial check of part of a sentence leaves one flag on the rest of it`() = runTest {
		val state = editor("ranhome it was late")
		val spellCheck = SpellCheckState(state, checker).apply { spellCheckMode = SpellCheckMode.Sentence }
		spellCheck.runFullSpellCheck()

		state.replace(range(0, 15, 19), "home")
		spellCheck.runPartialSpellCheck(range(0, 15, 19))

		assertEquals(listOf(range(0, 0, 7) to "ranhome"), state.flagged())
	}
}
