package com.darkrockstudios.texteditor.state

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * iOS word breaks, on the simulator: the words of a line as iOS itself finds them, CJK
 * by dictionary word included, and otherwise the segments the editor gets from ICU on
 * the other platforms.
 */
class WordBreaksIosTest {
	private fun words(text: String): List<String> =
		text.wordRuns().filter { it.isWord }.map { text.substring(it.start, it.end) }

	private fun boundaries(text: String): List<Int> =
		text.wordRuns().flatMap { listOf(it.start, it.end) }.distinct()

	/**
	 * By dictionary word, as iOS segments it: its tokenizer and `NSString`'s word
	 * enumeration both split 日本語 as 日本 and 語, in any locale, where desktop ICU keeps
	 * it whole. Not one word per kanji, as skia's ICU on iOS had it.
	 */
	@Test
	fun japaneseBreaksByDictionaryWord() {
		assertEquals(listOf("日本", "語", "を", "勉強", "し", "ます"), words("日本語を勉強します"))
	}

	@Test
	fun aLineMixingJapaneseAndLatinBreaksBoth() {
		assertEquals(listOf("Read", "日本", "語", "don’t", "stop"), words("Read 日本語, don’t stop"))
	}

	@Test
	fun oneCursorSwitchesBetweenDictionaryAndPlainLines() {
		wordCursor("").use { breaks ->
			assertEquals(listOf("勉強"), "勉強".wordRuns(breaks).map { "勉強".substring(it.start, it.end) })
			assertEquals(listOf(0, 3, 3, 4, 4, 9), "one three".wordRuns(breaks).flatMap { listOf(it.start, it.end) })
			assertEquals(listOf("勉強", "し", "ます"), "勉強します".wordRuns(breaks).map { "勉強します".substring(it.start, it.end) })
		}
	}

	@Test
	fun contractionsAndCombiningMarksStayWhole() {
		assertEquals(listOf("don’t", "won't"), words("don’t won't"))
		assertEquals(listOf("ño", "piña"), words("ño piña"))
	}

	@Test
	fun emojiAreTheirOwnWords() {
		val family = "👨‍👩‍👧"
		assertEquals(listOf("a", "😀", "b"), words("a 😀 b"))
		assertEquals(listOf("x", family, "y"), words("x $family y"))
	}

	@Test
	fun punctuationAndSpacesAreSegmentsBetweenWords() {
		assertEquals(listOf("self", "aware"), words("self-aware"))
		assertEquals(listOf(0, 4, 5, 10), boundaries("self-aware"))
		assertEquals(listOf(0, 3, 4, 9), boundaries("one three"))
	}

	@Test
	fun theRunsCoverTheWholeLine() {
		for (text in listOf("日本語を勉強します", "Hello, world!  ", " 😀 ", "a")) {
			val runs = text.wordRuns()
			assertEquals(0, runs.first().start, text)
			assertEquals(text.length, runs.last().end, text)
			runs.zipWithNext { a, b -> assertEquals(a.end, b.start, text) }
		}
		assertEquals(emptyList(), "".wordRuns())
	}

	@Test
	fun oneCursorServesManyLines() {
		wordCursor("").use { breaks ->
			assertEquals(listOf(0, 5), "hello".wordRuns(breaks).flatMap { listOf(it.start, it.end) })
			assertEquals(listOf(0, 3, 3, 4, 4, 9), "one three".wordRuns(breaks).flatMap { listOf(it.start, it.end) })
			assertEquals(listOf("勉強"), "勉強".wordRuns(breaks).map { "勉強".substring(it.start, it.end) })
		}
	}

	@Test
	fun theCursorAnswersAroundAnIndex() {
		wordCursor("one three").use { breaks ->
			assertEquals(3, breaks.following(0))
			assertEquals(4, breaks.following(3))
			assertEquals(BreakCursor.DONE, breaks.following(9))
			assertEquals(4, breaks.preceding(6))
			assertEquals(BreakCursor.DONE, breaks.preceding(0))
			assertEquals(true, breaks.isBoundary(4))
			assertEquals(false, breaks.isBoundary(5))
		}
	}

	@Test
	fun theDictionaryCursorAnswersAroundAnIndex() {
		// 日本|語|を|勉強|し|ます: boundaries 0, 2, 3, 4, 6, 7, 9.
		wordCursor("日本語を勉強します").use { breaks ->
			assertEquals(listOf(0, 2, 3, 4, 6, 7, 9), generateSequence(breaks.first()) { breaks.next().takeIf { it != BreakCursor.DONE } }.toList())
			assertEquals(BreakCursor.DONE, breaks.next())
			assertEquals(4, breaks.following(3))
			assertEquals(6, breaks.following(4))
			assertEquals(BreakCursor.DONE, breaks.following(9))
			assertEquals(4, breaks.preceding(5))
			assertEquals(3, breaks.preceding(4))
			assertEquals(BreakCursor.DONE, breaks.preceding(0))
			assertEquals(true, breaks.isBoundary(4))
			assertEquals(false, breaks.isBoundary(5))
			assertEquals(true, breaks.isBoundary(9))
		}
	}

	@Test
	fun closingTwiceIsSafe() {
		val breaks = wordCursor("日本語")
		breaks.close()
		breaks.close()
	}
}
