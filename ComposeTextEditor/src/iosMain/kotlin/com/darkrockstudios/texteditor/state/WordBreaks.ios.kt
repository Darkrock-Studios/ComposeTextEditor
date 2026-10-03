package com.darkrockstudios.texteditor.state

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFLocaleCopyCurrent
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFStringTokenizerAdvanceToNextToken
import platform.CoreFoundation.CFStringTokenizerCreate
import platform.CoreFoundation.CFStringTokenizerGetCurrentTokenRange
import platform.CoreFoundation.CFStringTokenizerRef
import platform.CoreFoundation.CFStringTokenizerSetString
import platform.CoreFoundation.kCFStringTokenizerTokenNone
import platform.CoreFoundation.kCFStringTokenizerUnitWordBoundary
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSString

/**
 * Skia's ICU word breaks, as on the other platforms, except for a line in a script ICU
 * breaks by dictionary: skia's ICU on iOS has no dictionaries and splits a run of kanji
 * into single characters, so such a line takes iOS's own breaks from
 * `CFStringTokenizer`. The tokenizer is many times slower, which a scan of
 * every line (word count, spell check) would feel, so other lines stay with skia.
 */
internal actual fun wordCursor(text: String): BreakCursor = DictionaryAwareBreakCursor(text)

private class DictionaryAwareBreakCursor(text: String) : BreakCursor {
	private val skia = skiaWordCursor("")
	private var tokenizer: TokenizerBreakCursor? = null
	private var active: BreakCursor = skia

	init {
		setText(text)
	}

	override fun setText(text: String) {
		active = if (text.needsWordDictionary()) {
			tokenizer?.also { it.setText(text) } ?: TokenizerBreakCursor(text).also { tokenizer = it }
		} else {
			skia.also { it.setText(text) }
		}
	}

	override fun first(): Int = active.first()
	override fun next(): Int = active.next()
	override fun preceding(index: Int): Int = active.preceding(index)
	override fun following(index: Int): Int = active.following(index)
	override fun isBoundary(index: Int): Boolean = active.isBoundary(index)

	private var closed = false

	override fun close() {
		if (closed) return
		closed = true
		skia.close()
		tokenizer?.close()
	}
}

/** Whether the text holds a script ICU breaks into words by dictionary: CJK, Thai, Lao, Khmer, Myanmar. */
private fun String.needsWordDictionary(): Boolean {
	var i = 0
	while (i < length) {
		val c = this[i]
		if (c.isHighSurrogate() && i + 1 < length) {
			val codePoint = 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
			// Kana Supplement and Extended, and CJK extensions B onward.
			if (codePoint in 0x1B000..0x1B16F || codePoint in 0x20000..0x3FFFF) return true
			i += 2
			continue
		}
		when (c.code) {
			in 0x0E00..0x0EFF, // Thai, Lao
			in 0x1000..0x109F, // Myanmar
			in 0x1780..0x17FF, in 0x19E0..0x19FF, // Khmer
			in 0x2E80..0x2FDF, // CJK and Kangxi radicals
			in 0x3040..0x30FF, in 0x31F0..0x31FF, // Hiragana, Katakana
			in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xF900..0xFAFF, // Han
			in 0xFF66..0xFF9F, // half-width Katakana
			-> return true
		}
		i++
	}
	return false
}

/**
 * iOS's own word breaks. The tokenizer's word boundary tokens cover the string, words,
 * spaces and punctuation alike, so their edges are the boundaries. They are collected when the text is set and
 * stepped through as ICU's iterator steps; one tokenizer serves every string set on it.
 */
@OptIn(ExperimentalForeignApi::class)
private class TokenizerBreakCursor(text: String) : BreakCursor {
	private var locale = CFLocaleCopyCurrent()
	private var tokenizer: CFStringTokenizerRef? = null
	private var string: CFStringRef? = null
	private var boundaries = IntArray(0)
	private var current = 0

	init {
		setText(text)
	}

	override fun setText(text: String) {
		val previous = string
		string = text.toCFString()
		val range = CFRangeMake(0, text.length.toLong())
		val tokenizer = tokenizer?.also { CFStringTokenizerSetString(it, string, range) }
			?: CFStringTokenizerCreate(null, string, range, kCFStringTokenizerUnitWordBoundary, locale)
				.also { tokenizer = it }
		previous?.let(::CFRelease)

		val edges = mutableSetOf(0, text.length)
		while (CFStringTokenizerAdvanceToNextToken(tokenizer) != kCFStringTokenizerTokenNone) {
			CFStringTokenizerGetCurrentTokenRange(tokenizer).useContents {
				edges.add(location.toInt())
				edges.add((location + length).toInt())
				Unit
			}
		}
		boundaries = edges.toIntArray().apply { sort() }
		current = 0
	}

	override fun first(): Int {
		current = 0
		return boundaries[0]
	}

	override fun next(): Int {
		if (current >= boundaries.lastIndex) {
			current = boundaries.size
			return BreakCursor.DONE
		}
		return boundaries[++current]
	}

	override fun preceding(index: Int): Int {
		val at = firstAtOrAfter(index) - 1
		if (at < 0) return BreakCursor.DONE
		current = at
		return boundaries[at]
	}

	override fun following(index: Int): Int {
		val at = firstAtOrAfter(index + 1)
		if (at > boundaries.lastIndex) return BreakCursor.DONE
		current = at
		return boundaries[at]
	}

	override fun isBoundary(index: Int): Boolean = boundaries.getOrNull(firstAtOrAfter(index)) == index

	/** The position of the first boundary at or after [index], or the size when none is. */
	private fun firstAtOrAfter(index: Int): Int {
		var low = 0
		var high = boundaries.size
		while (low < high) {
			val mid = (low + high) ushr 1
			if (boundaries[mid] < index) low = mid + 1 else high = mid
		}
		return low
	}

	override fun close() {
		tokenizer?.let(::CFRelease)
		string?.let(::CFRelease)
		locale?.let(::CFRelease)
		tokenizer = null
		string = null
		locale = null
	}
}

@OptIn(ExperimentalForeignApi::class)
@Suppress("CAST_NEVER_SUCCEEDS")
private fun String.toCFString(): CFStringRef? = CFBridgingRetain(this as NSString)?.reinterpret()
