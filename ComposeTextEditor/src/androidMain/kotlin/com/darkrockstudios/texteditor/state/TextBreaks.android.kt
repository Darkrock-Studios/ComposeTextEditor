package com.darkrockstudios.texteditor.state

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.os.Build
import java.text.BreakIterator

/**
 * `java.text.BreakIterator`, which Android backs with the platform's ICU (the
 * same data `EditText` and the keyboard segment by), and which the host JVM
 * provides for real in `androidHostTest`, where `android.icu` is a stub.
 */
private class IcuBreakCursor(private val iterator: BreakIterator) : BreakCursor {
	override fun preceding(index: Int): Int = iterator.preceding(index)
	override fun following(index: Int): Int = iterator.following(index)
	override fun isBoundary(index: Int): Boolean = iterator.isBoundary(index)
	override fun close() = Unit
}

internal actual fun graphemeCursor(text: String): BreakCursor =
	IcuBreakCursor(BreakIterator.getCharacterInstance().also { it.setText(text) })

// SDK_INT is 0 on the host JVM, which keeps the stubbed android.icu out of host tests.
internal actual fun isEmojiCodePoint(codePoint: Int): Boolean = when {
	Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
		UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_PRESENTATION) ||
			UCharacter.hasBinaryProperty(codePoint, UProperty.EXTENDED_PICTOGRAPHIC)

	Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
		UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_PRESENTATION) ||
			UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI)

	// API 26 and 27 predate the emoji properties; the pictographic blocks stand in.
	else -> codePoint in 0x1F000..0x1FAFF || codePoint in 0x2600..0x27BF ||
		codePoint in 0x2300..0x23FF || codePoint in 0x2B00..0x2BFF
}
