package com.darkrockstudios.texteditor.state

/**
 * Boundaries in one string from the platform's ICU break iterators, so the editor
 * segments text the way the platform's own widgets do. Grapheme clusters are the
 * unit of caret movement, forward deletion, and hit testing.
 *
 * Indices are UTF-16 offsets. A cursor wraps a native ICU object on skiko
 * platforms, so callers [use] it and let it go.
 */
internal interface BreakCursor : AutoCloseable {
	/** The last boundary before [index], or [DONE] when there is none. */
	fun preceding(index: Int): Int

	/** The first boundary after [index], or [DONE] when there is none. */
	fun following(index: Int): Int

	fun isBoundary(index: Int): Boolean

	companion object {
		const val DONE = -1
	}
}

/** A cursor over the grapheme cluster boundaries of [text]. */
internal expect fun graphemeCursor(text: String): BreakCursor

/**
 * Whether [codePoint] has emoji presentation or is an extended pictograph, which
 * makes a cluster containing it an emoji sequence for [backspaceStart].
 */
internal expect fun isEmojiCodePoint(codePoint: Int): Boolean

/**
 * The last grapheme boundary before [index], or 0; the length for an [index] past
 * the end. ICU reads an [index] inside a surrogate pair as the pair's start, so
 * pass a boundary or a code point start.
 */
internal fun String.precedingGraphemeBoundary(index: Int): Int {
	if (index <= 0) return 0
	if (index > length) return length
	return graphemeCursor(this).use { it.preceding(index) }.coerceAtLeast(0)
}

/** The first grapheme boundary after [index], or the length. */
internal fun String.followingGraphemeBoundary(index: Int): Int {
	if (index >= length) return length
	val next = graphemeCursor(this).use { it.following(index.coerceAtLeast(0)) }
	return if (next == BreakCursor.DONE) length else next
}

internal fun String.isGraphemeBoundary(index: Int): Boolean {
	if (index <= 0 || index >= length) return true
	return graphemeCursor(this).use { it.isBoundary(index) }
}

/**
 * [index] when it is a grapheme boundary, else the next boundary when [forward] or
 * the previous one.
 */
internal fun String.snapToGraphemeBoundary(index: Int, forward: Boolean): Int {
	if (index <= 0) return 0
	if (index >= length) return length
	return graphemeCursor(this).use {
		if (it.isBoundary(index)) return index
		// Through the cluster's end: ICU rounds a mid-surrogate index down before looking back.
		val end = it.following(index).let { next -> if (next == BreakCursor.DONE) length else next }
		if (forward) end else it.preceding(end).coerceAtLeast(0)
	}
}

/**
 * Where Backspace at [index] deletes from: the start of the previous code point,
 * or of the whole cluster when that cluster is an emoji sequence. This is the rule
 * `BasicTextField` and Android's `EditText` share: a flag, a keycap, a skin tone or
 * a ZWJ family goes at once, while a combining mark comes off its base on its own,
 * as Indic and Vietnamese input expect.
 */
internal fun String.backspaceStart(index: Int): Int {
	if (index <= 0) return 0
	val codePointStart = if (index >= 2 && this[index - 1].isLowSurrogate() && this[index - 2].isHighSurrogate()) {
		index - 2
	} else {
		index - 1
	}
	val clusterStart = precedingGraphemeBoundary(index)
	if (clusterStart >= codePointStart) return codePointStart
	return if (clusterIsEmoji(clusterStart, index)) clusterStart else codePointStart
}

private fun String.clusterIsEmoji(start: Int, end: Int): Boolean {
	var i = start
	while (i < end) {
		val high = this[i]
		val codePoint = if (high.isHighSurrogate() && i + 1 < end && this[i + 1].isLowSurrogate()) {
			(((high - Char.MIN_HIGH_SURROGATE) shl 10) or (this[i + 1] - Char.MIN_LOW_SURROGATE)) + 0x10000
		} else {
			high.code
		}
		if (codePoint == EMOJI_PRESENTATION_SELECTOR || isEmojiCodePoint(codePoint)) return true
		i += if (codePoint >= 0x10000) 2 else 1
	}
	return false
}

/** U+FE0F, which turns a text symbol into an emoji and marks keycap sequences. */
private const val EMOJI_PRESENTATION_SELECTOR = 0xFE0F
