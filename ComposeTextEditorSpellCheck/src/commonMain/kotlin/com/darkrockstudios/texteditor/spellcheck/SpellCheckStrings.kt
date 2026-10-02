package com.darkrockstudios.texteditor.spellcheck

/**
 * Localizable strings for the spell check context menu.
 * Provide a custom implementation to localize the menu.
 */
data class SpellCheckStrings(
	/** Shown in place of the suggestions while they are looked up. */
	val loading: String,
	/** Shown when the checker has no suggestion for a flagged word or sentence. */
	val noSuggestions: String,
	/** Stops flagging the word for the rest of the session. */
	val ignore: String,
	/** Adds the word to the host's dictionary; offered when the editor has an `onAddToDictionary`. */
	val addToDictionary: String,
) {
	companion object {
		/**
		 * Default English strings for the spell check menu.
		 */
		val Default = SpellCheckStrings(
			loading = "Loading...",
			noSuggestions = "No suggestions",
			ignore = "Ignore",
			addToDictionary = "Add to dictionary",
		)
	}
}
