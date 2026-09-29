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
) {
	companion object {
		/**
		 * Default English strings for the spell check menu.
		 */
		val Default = SpellCheckStrings(
			loading = "Loading...",
			noSuggestions = "No suggestions",
		)
	}
}
