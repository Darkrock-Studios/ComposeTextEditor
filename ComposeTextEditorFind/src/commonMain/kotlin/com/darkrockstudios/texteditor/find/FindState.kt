package com.darkrockstudios.texteditor.find

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * State management for the Find feature.
 *
 * @param textState The TextEditorState to search within
 * @param scope CoroutineScope for handling text change reactions
 */
@OptIn(FlowPreview::class)
class FindState(
	val textState: TextEditorState,
	private val scope: CoroutineScope
) {
	// Search configuration
	var query: String by mutableStateOf("")
		private set

	var caseSensitive: Boolean by mutableStateOf(false)
		private set

	// Search results
	private val _matches = mutableStateListOf<TextEditorRange>()
	val matches: List<TextEditorRange> get() = _matches

	var currentMatchIndex: Int by mutableIntStateOf(-1)
		private set

	val matchCount: Int get() = _matches.size

	// Styles for highlighting
	private val matchStyle = FindMatchStyle()
	private val currentMatchStyle = FindCurrentMatchStyle()

	// Job for debounced search on text changes
	private var searchUpdateJob: Job? = null

	/** The [TextEditorState.documentGeneration] [matches] were found in. */
	private var matchesGeneration = textState.documentGeneration.value

	init {
		searchUpdateJob = scope.launch {
			// Listen for text changes and update search results
			launch {
				textState.editOperations
					.debounce(300.milliseconds)
					.collect {
						if (query.isNotEmpty()) {
							refreshSearch()
						}
					}
			}
			// A whole-document replacement (setText, setDocument) emits no edit, so
			// without this the matches would keep describing the old document.
			textState.documentGeneration.collect { generation ->
				if (generation != matchesGeneration && query.isNotEmpty()) {
					refreshSearch()
				}
			}
		}
	}

	/**
	 * Execute a search with the given query.
	 * Updates highlights and jumps to the nearest match.
	 */
	fun search(newQuery: String) {
		query = newQuery

		if (newQuery.isEmpty()) {
			clearSearch()
			return
		}

		// Find all matches
		val results = textState.findAll(newQuery, caseSensitive)
		matchesGeneration = textState.documentGeneration.value
		_matches.clear()
		_matches.addAll(results)

		if (results.isEmpty()) {
			currentMatchIndex = -1
			clearHighlights()
			return
		}

		// Find nearest match to cursor
		val cursorPos = textState.cursor.position
		currentMatchIndex = findNearestMatchIndex(results, cursorPos)

		// Add highlights
		updateHighlights()

		// Navigate to current match
		goToCurrentMatch()
	}

	/**
	 * Toggle case sensitivity and re-run search if there's an active query.
	 */
	fun toggleCaseSensitive(sensitive: Boolean) {
		if (caseSensitive != sensitive) {
			caseSensitive = sensitive
			if (query.isNotEmpty()) {
				search(query)
			}
		}
	}

	/**
	 * Navigate to the next match.
	 */
	fun findNext() {
		if (_matches.isEmpty()) return

		currentMatchIndex = if (currentMatchIndex < _matches.lastIndex) {
			currentMatchIndex + 1
		} else {
			0 // Wrap around
		}

		updateHighlights()
		goToCurrentMatch()
	}

	/**
	 * Navigate to the previous match.
	 */
	fun findPrevious() {
		if (_matches.isEmpty()) return

		currentMatchIndex = if (currentMatchIndex > 0) {
			currentMatchIndex - 1
		} else {
			_matches.lastIndex // Wrap around
		}

		updateHighlights()
		goToCurrentMatch()
	}

	/**
	 * Jump to a specific match by index.
	 */
	fun goToMatch(index: Int) {
		if (index < 0 || index >= _matches.size) return

		currentMatchIndex = index
		updateHighlights()
		goToCurrentMatch()
	}

	/**
	 * Clear the search - remove all highlights and reset state.
	 */
	fun clearSearch() {
		query = ""
		clearHighlights()
		_matches.clear()
		currentMatchIndex = -1
		textState.selector.clearSelection()
	}

	/**
	 * Replace the current match with the given text and move to the next match.
	 * @param replaceText The text to replace with
	 * @return true if a replacement was made, false if no current match
	 */
	fun replaceCurrent(replaceText: String): Boolean {
		if (currentMatchIndex < 0 || currentMatchIndex >= _matches.size) return false

		val match = _matches[currentMatchIndex]

		// Clear highlights before replacement
		clearHighlights()

		// Perform the replacement
		textState.replace(match, replaceText)

		// Re-run the search to update matches
		// The debounced search will also run, but we do it immediately for responsiveness
		val results = textState.findAll(query, caseSensitive)
		_matches.clear()
		_matches.addAll(results)

		// Adjust current index - stay at same index if possible, or wrap
		if (_matches.isEmpty()) {
			currentMatchIndex = -1
		} else {
			// Keep at same index (which is now the next match after replacement)
			// but clamp to valid range
			currentMatchIndex = currentMatchIndex.coerceIn(0, _matches.lastIndex)
		}

		// Update highlights and navigate
		updateHighlights()
		if (_matches.isNotEmpty()) {
			goToCurrentMatch()
		}

		return true
	}

	/**
	 * Replace all matches with the given text.
	 * @param replaceText The text to replace with
	 * @return The number of replacements made
	 */
	fun replaceAll(replaceText: String): Int {
		if (_matches.isEmpty()) return 0

		val count = _matches.size

		// Clear highlights before replacements
		clearHighlights()

		// Replace from end to start to preserve positions
		_matches.sortedByDescending { it.start }.forEach { match ->
			textState.replace(match, replaceText)
		}

		// Clear matches since they're all replaced
		_matches.clear()
		currentMatchIndex = -1

		return count
	}

	/**
	 * Cancel any ongoing operations. Call this when done with FindState.
	 */
	fun dispose() {
		searchUpdateJob?.cancel()
		clearSearch()
	}

	/**
	 * Refresh search results (e.g., after text changes).
	 */
	private fun refreshSearch() {
		if (query.isEmpty()) return

		// Save current match position for continuity
		val previousMatchStart = if (currentMatchIndex >= 0 && currentMatchIndex < _matches.size) {
			_matches[currentMatchIndex].start
		} else null

		// Re-search
		val results = textState.findAll(query, caseSensitive)
		matchesGeneration = textState.documentGeneration.value
		_matches.clear()
		_matches.addAll(results)

		if (results.isEmpty()) {
			currentMatchIndex = -1
			clearHighlights()
			return
		}

		// Try to stay at the same position, or find nearest
		currentMatchIndex = if (previousMatchStart != null) {
			findNearestMatchIndex(results, previousMatchStart)
		} else {
			0
		}

		updateHighlights()
	}

	/**
	 * Update RichSpan highlights for all matches in a single batched relayout.
	 */
	private fun updateHighlights() {
		val newSpans = _matches.mapIndexed { index, range ->
			val style = if (index == currentMatchIndex) currentMatchStyle else matchStyle
			RichSpan(range, style)
		}
		textState.updateRichSpans(remove = currentHighlightSpans(), add = newSpans)
	}

	/**
	 * Remove all find-related highlights.
	 */
	private fun clearHighlights() {
		val existing = currentHighlightSpans()
		if (existing.isNotEmpty()) {
			textState.updateRichSpans(remove = existing, add = emptyList())
		}
	}

	/**
	 * The highlight spans currently applied by this find session, identified by
	 * the two style instances this session owns. Read live from the manager so
	 * removal stays correct even after an edit transforms span positions.
	 */
	private fun currentHighlightSpans(): List<RichSpan> =
		textState.richSpanManager.getAllRichSpans().filter {
			it.style === matchStyle || it.style === currentMatchStyle
		}

	/**
	 * Navigate to the current match - scroll and select.
	 */
	private fun goToCurrentMatch() {
		if (currentMatchIndex < 0 || currentMatchIndex >= _matches.size) return

		val match = _matches[currentMatchIndex]

		// Select the match
		textState.selector.updateSelection(match.start, match.end)

		// Scroll to make it visible
		textState.scrollManager.scrollToPosition(match.start)
	}
}
