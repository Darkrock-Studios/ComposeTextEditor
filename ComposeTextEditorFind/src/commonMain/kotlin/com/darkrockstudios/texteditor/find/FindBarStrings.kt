package com.darkrockstudios.texteditor.find

/**
 * Localizable strings for the FindBar component.
 * Provide a custom implementation to localize the Find UI.
 */
data class FindBarStrings(
	/** Placeholder text shown in the search input field. */
	val placeholder: String,
	/** Accessibility label and tooltip for the clear-search button. */
	val clearSearch: String,
	/** Message shown when the search yields no matches. */
	val noMatches: String,
	/** Accessibility label and tooltip for the previous-match button. */
	val previousMatch: String,
	/** Accessibility label and tooltip for the next-match button. */
	val nextMatch: String,
	/** Accessibility label and tooltip for the close-find-bar button. */
	val close: String,
	/**
	 * Format string for match count display.
	 * Will be called with (currentIndex, totalCount) parameters.
	 * Example: "3 of 15"
	 */
	val matchCount: (current: Int, total: Int) -> String,
	/** Placeholder text shown in the replacement input field. */
	val replacePlaceholder: String,
	/** Label for the button that replaces the current match. */
	val replace: String,
	/** Label for the button that replaces all matches. */
	val replaceAll: String,
	/** Label for the control that reveals the replace UI. */
	val showReplace: String,
	/** Label for the control that hides the replace UI. */
	val hideReplace: String,
	/** Accessibility label for the case-sensitive toggle. */
	val matchCase: String = "Match case",
	/** Accessibility label for the whole-word toggle. */
	val wholeWord: String = "Whole word",
	/** Accessibility label for the regular expression toggle. */
	val regex: String = "Regular expression",
	/** Accessibility label for the find-in-selection toggle. */
	val inSelection: String = "Find in selection",
	/** Message shown when the query is not a valid regular expression. */
	val invalidPattern: String = "Invalid pattern",
) {
	companion object {
		/**
		 * Default English strings for FindBar.
		 */
		val Default = FindBarStrings(
			placeholder = "Find...",
			clearSearch = "Clear search",
			noMatches = "No matches",
			previousMatch = "Prev",
			nextMatch = "Next",
			close = "Close",
			matchCount = { current, total -> "$current of $total" },
			replacePlaceholder = "Replace with...",
			replace = "Replace",
			replaceAll = "All",
			showReplace = "Replace",
			hideReplace = "Hide",
		)
	}
}
