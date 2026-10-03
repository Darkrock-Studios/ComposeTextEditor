package com.darkrockstudios.texteditor.input

import androidx.compose.ui.input.key.Key

/**
 * The caret key the editor last moved for, while it is held. UIKit moves the caret for a
 * hardware arrow key itself, through `UITextInput`, after the editor has moved it, and
 * repeats a held key on its own without sending the editor another key event; each of
 * its moves arrives as a selection change. An input session on such a platform hands
 * those changes to [absorbSelection], which drops the first as the echo of the press
 * and runs the editor's own move again for each one after, so the caret follows the
 * editor's rows and word stops rather than UIKit's (roadmap 1.1, 4.6).
 */
internal class HeldCaretKey {
	private var key: Key? = null
	private var repeat: (() -> Unit)? = null
	private var echoPending = false

	/** [key] moved the caret, and [repeat] moves it the same way again. */
	fun pressed(key: Key, repeat: () -> Unit) {
		this.key = key
		this.repeat = repeat
		echoPending = true
	}

	fun released(key: Key) {
		if (key == this.key) clear()
	}

	fun clear() {
		key = null
		repeat = null
		echoPending = false
	}

	/**
	 * Whether a selection change the platform makes now belongs to the held key, and so
	 * must not be applied as given. The first is the press's echo; each later one is a
	 * repeat, run here as the editor's move.
	 */
	fun absorbSelection(): Boolean {
		val repeat = repeat ?: return false
		if (echoPending) echoPending = false else repeat()
		return true
	}
}
