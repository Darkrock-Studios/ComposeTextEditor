package com.darkrockstudios.texteditor.input

import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.insertTypedString

/**
 * Set on a key's character when the key is a dead key: Android's
 * `KeyCharacterMap.COMBINING_ACCENT`, which `KeyEvent.getUnicodeChar` (Compose's
 * `utf16CodePoint`) reports for a hardware keyboard layout's accent keys. The other
 * platforms compose dead keys in their input method and never set it.
 */
internal const val COMBINING_ACCENT: Int = 0x80000000.toInt()
internal const val COMBINING_ACCENT_MASK: Int = 0x7FFFFFFF

/**
 * The character [accent] (a dead key's spacing accent) and [codePoint] compose to, or
 * 0 when they do not compose. The same accent twice, or the accent then a space, gives
 * the accent itself. Android's `KeyCharacterMap.getDeadChar`.
 */
internal expect fun deadChar(accent: Int, codePoint: Int): Int

/**
 * Hardware keyboard dead keys on the key path, for Android, where a layout's accent key
 * arrives as a key event rather than through the input method.
 *
 * A dead key shows its accent as a composition, as the desktop input methods do. The
 * next character replaces it with the two composed, or, when they do not compose,
 * commits the accent and types after it, as `EditText` does. Any other key commits it
 * first. An input method's own text command lands over the composition, as it would
 * over the accent `EditText` selects.
 */
internal class DeadKeyComposer(
	private val combine: (accent: Int, codePoint: Int) -> Int = ::deadChar,
) {
	/** The accent this composer is showing, and where, while nothing else has touched it. */
	private var pending: Pending? = null

	private class Pending(val state: TextEditorState, val accent: Int, val range: TextEditorRange)

	/**
	 * Types [codePoint] if it is a dead key, or the character after one. Returns false
	 * for an ordinary character with no accent pending, which the caller types, and for
	 * a flagged value that is no accent.
	 */
	fun type(codePoint: Int, state: TextEditorState): Boolean {
		if (codePoint and COMBINING_ACCENT != 0) {
			val dead = codePoint and COMBINING_ACCENT_MASK
			if (dead !in 0x20..0xFFFF) return false
			val accent = takePending(state)
			if (accent != 0) {
				val combined = combine(accent, dead)
				if (combined != 0) {
					state.imeCommitText(codePointToString(combined), 1)
					return true
				}
			}
			// The pending accent, or an input method's composition, is committed first.
			if (state.composingRange != null) state.imeFinishComposing()
			state.imeSetComposingText(codePointToString(dead), 1)
			pending = state.composingRange?.let { Pending(state, dead, it) }
			return true
		}
		val accent = takePending(state)
		if (accent == 0) return false
		val combined = combine(accent, codePoint)
		if (combined != 0) {
			state.imeCommitText(codePointToString(combined), 1)
		} else {
			state.imeFinishComposing()
			state.insertTypedString(codePointToString(codePoint))
		}
		return true
	}

	/** Commits a pending accent as typed, before another key acts. */
	fun commitPending(state: TextEditorState) {
		if (takePending(state) != 0) state.imeFinishComposing()
	}

	/**
	 * Takes the pending accent, or 0 if its composition has gone or changed. An accent
	 * the caret has left is committed where it stands.
	 */
	private fun takePending(state: TextEditorState): Int {
		val pending = pending ?: return 0
		this.pending = null
		if (pending.state !== state || state.composingRange != pending.range) return 0
		if (!state.isWithinDocument(pending.range)) return 0
		if (state.getStringInRange(pending.range) != codePointToString(pending.accent)) return 0
		if (state.cursorPosition == pending.range.end && !state.selector.hasSelection()) return pending.accent
		state.imeFinishComposing()
		return 0
	}
}

/** [codePoint] as a string, a surrogate pair above the Basic Multilingual Plane. */
internal fun codePointToString(codePoint: Int): String {
	return if (codePoint <= 0xFFFF) {
		codePoint.toChar().toString()
	} else {
		val adjusted = codePoint - 0x10000
		val highSurrogate = ((adjusted shr 10) + 0xD800).toChar()
		val lowSurrogate = ((adjusted and 0x3FF) + 0xDC00).toChar()
		"$highSurrogate$lowSurrogate"
	}
}
