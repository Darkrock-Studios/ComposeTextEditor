package com.darkrockstudios.texteditor.state

import org.jetbrains.skia.BreakIterator
import org.jetbrains.skia.icu.CharProperties

/**
 * Skia's ICU, bundled with skiko on desktop, iOS, and wasm, and the iterator
 * `BasicTextField` steps by on these platforms. The JDK's own break iterator is
 * not used on desktop: its clusters depend on the JDK version, and JDK 17 predates
 * emoji ZWJ sequences. Word breaks are each platform's: skia's ICU on iOS has no CJK
 * dictionary, so iOS takes its own.
 */
private class SkiaBreakCursor(private val iterator: BreakIterator) : BreakCursor {
	override fun setText(text: String) = iterator.setText(text)
	override fun first(): Int = iterator.first()
	override fun next(): Int = iterator.next()
	override fun preceding(index: Int): Int = iterator.preceding(index)
	override fun following(index: Int): Int = iterator.following(index)
	override fun isBoundary(index: Int): Boolean = iterator.isBoundary(index)
	override fun close() = iterator.close()
}

internal actual fun graphemeCursor(text: String): BreakCursor =
	SkiaBreakCursor(BreakIterator.makeCharacterInstance().also { it.setText(text) })

/** Skia's ICU word breaks, with its CJK dictionary where the platform's ICU data has one. */
internal fun skiaWordCursor(text: String): BreakCursor =
	SkiaBreakCursor(BreakIterator.makeWordInstance().also { it.setText(text) })

internal actual fun isEmojiCodePoint(codePoint: Int): Boolean =
	CharProperties.codePointHasBinaryProperty(codePoint, CharProperties.EMOJI_PRESENTATION) ||
		CharProperties.codePointHasBinaryProperty(codePoint, CharProperties.EXTENDED_PICTOGRAPHIC)
