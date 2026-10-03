package com.darkrockstudios.texteditor.input

import androidx.compose.ui.input.key.Key

/**
 * The key the editor last acted on, while it is held, for a platform that acts on it
 * again. UIKit handles a hardware key itself, through `UITextInput`, after the editor
 * has: it moves the caret for an arrow key, arriving as a selection change, and types a
 * tab for Tab, arriving as a commit of "\t". It also repeats a held key on its own
 * without sending the editor another key event. An input session on such a platform
 * hands those edits to [absorbSelection] and [absorbText], which drop the first as the
 * echo of the press and run the editor's own command again for each one after, so a
 * held key follows the editor's rows, word stops and indent rather than UIKit's
 * (roadmap 1.1, 4.6, 2.9).
 */
internal class HeldKey {
	/** What the platform sends for the held key. */
	sealed interface Echo {
		/** It moves the selection, as for a caret key. */
		data object Selection : Echo

		/** It types [text], as a tab for Tab. */
		data class Text(val text: String) : Echo
	}

	private var key: Key? = null
	private var echo: Echo? = null
	private var repeat: (() -> Unit)? = null
	private var echoPending = false

	/** The editor acted on [key], the platform will send [echo] for it, and [repeat] acts again. */
	fun pressed(key: Key, echo: Echo, repeat: () -> Unit) {
		this.key = key
		this.echo = echo
		this.repeat = repeat
		echoPending = true
	}

	fun released(key: Key) {
		if (key == this.key) clear()
	}

	fun clear() {
		key = null
		echo = null
		repeat = null
		echoPending = false
	}

	/** Whether a selection change the platform makes now belongs to the held key; see [absorb]. */
	fun absorbSelection(): Boolean = absorb(echo == Echo.Selection)

	/** Whether committing [text] now belongs to the held key; see [absorb]. */
	fun absorbText(text: CharSequence): Boolean = absorb((echo as? Echo.Text)?.text?.contentEquals(text) == true)

	/**
	 * Whether the platform's edit, when it [matches] what the held key sends, must not be
	 * applied as given. The first is the press's echo; each later one is a repeat, run
	 * here as the editor's command.
	 */
	private fun absorb(matches: Boolean): Boolean {
		val repeat = repeat?.takeIf { matches } ?: return false
		if (echoPending) echoPending = false else repeat()
		return true
	}
}
