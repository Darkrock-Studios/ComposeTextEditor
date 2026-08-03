package com.darkrockstudios.texteditor.spellcheck

import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker.Scope
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.spellcheck.utils.applyCapitalizationStrategy
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.WordSegment
import com.darkrockstudios.texteditor.state.getRichSpansInRange
import com.darkrockstudios.texteditor.state.sentenceSegments
import com.darkrockstudios.texteditor.state.sentenceSegmentsInRange
import com.darkrockstudios.texteditor.state.wordSegments
import com.darkrockstudios.texteditor.state.wordSegmentsInRange

/**
 * Determines which spell checking mode is active.
 */
enum class SpellCheckMode {
	/** Check individual words - current/default behavior */
	Word,

	/** Check full sentences for context-aware corrections */
	Sentence
}

/**
 * State holder that coordinates spell checking over a [TextEditorState].
 *
 * Tracks misspelled words and sentence-level [Correction]s, manages the spell-check decoration
 * spans rendered in the document, and runs full or partial checks through an [EditorSpellChecker].
 * Span mutations are performed atomically after any asynchronous lookup completes so that a
 * cancelled check never leaves the document with its decorations wiped.
 *
 * @property textState The underlying editor state whose content is spell checked.
 * @property spellChecker The [EditorSpellChecker] used to evaluate words and sentences; checks are
 *   no-ops while this is `null`.
 * @param enableSpellChecking Whether spell checking is active initially; exposed via
 *   [spellCheckingEnabled].
 * @property spellCheckMode Whether checking operates per-word or per-sentence; see [SpellCheckMode].
 */
class SpellCheckState(
	val textState: TextEditorState,
	var spellChecker: EditorSpellChecker?,
	enableSpellChecking: Boolean = true,
	var spellCheckMode: SpellCheckMode = SpellCheckMode.Word,
) {
	/** Whether spell checking is currently active. Toggle via [setSpellCheckingEnabled]. */
	var spellCheckingEnabled: Boolean = enableSpellChecking
		private set

	/**
	 * Enable or disable spell checking.
	 *
	 * Enabling when previously disabled triggers a full re-check; disabling clears all
	 * existing spell-check decorations.
	 *
	 * @param value The new enabled state.
	 */
	suspend fun setSpellCheckingEnabled(value: Boolean) {
		val wasEnabled = spellCheckingEnabled
		spellCheckingEnabled = value
		if (value && !wasEnabled) {
			runFullSpellCheck()
		} else if (!value) {
			clearSpellCheck()
		}
	}

	private var lastTextHash = -1
	private val misspelledWords = mutableListOf<WordSegment>()
	private val sentenceCorrections = mutableListOf<Correction>()

	private fun removeMissSpellingsInRange(range: TextEditorRange) {
		misspelledWords.removeAll { it.range.intersects(range) }
	}

	private fun removeSentenceCorrectionsInRange(range: TextEditorRange) {
		sentenceCorrections.removeAll { it.range.intersects(range) }
	}

	/**
	 * Handle click on a spell check span.
	 * @return WordSegment for word-level misspellings, Correction for sentence-level issues, or null
	 */
	fun handleSpanClick(span: RichSpan): Any? {
		if (span.style !is SpellCheckStyle) return null

		// First check word-level misspellings
		val wordSegment = findWordSegmentContainingRange(misspelledWords, span.range)
		if (wordSegment != null) return wordSegment

		// Then check sentence-level corrections
		return sentenceCorrections.find { it.range.intersects(span.range) }
	}

	/**
	 * Handle click for word-level misspellings only.
	 * Use this when you specifically need a WordSegment.
	 */
	fun handleWordSpanClick(span: RichSpan): WordSegment? {
		return if (span.style is SpellCheckStyle) {
			findWordSegmentContainingRange(misspelledWords, span.range)
		} else {
			null
		}
	}

	/**
	 * Handle click for sentence-level corrections only.
	 * Use this when you specifically need a Correction.
	 */
	fun handleSentenceSpanClick(span: RichSpan): Correction? {
		return if (span.style is SpellCheckStyle) {
			sentenceCorrections.find { it.range.intersects(span.range) }
		} else {
			null
		}
	}

	/**
	 * Replace a misspelled word with the chosen correction.
	 *
	 * Removes the word's spell-check decoration and applies the replacement to [textState].
	 *
	 * @param segment The misspelled [WordSegment] to correct.
	 * @param correction The replacement text.
	 */
	fun correctSpelling(segment: WordSegment, correction: String) {
		val doomed = textState.getRichSpansInRange(segment.range)
			.filter { it.style == SpellCheckStyle }
		textState.updateRichSpans(remove = doomed, add = emptyList())
		misspelledWords.remove(segment)
		println("Correcting spelling for $segment, correcting to: $correction")
		textState.replace(segment.range, correction, true)
	}

	/**
	 * Apply a sentence-level correction.
	 */
	fun applySentenceCorrection(correction: Correction, selectedSuggestion: String) {
		val doomed = textState.getRichSpansInRange(correction.range)
			.filter { it.style == SpellCheckStyle }
		textState.updateRichSpans(remove = doomed, add = emptyList())
		sentenceCorrections.remove(correction)
		println("Applying sentence correction: ${correction.originalText} -> $selectedSuggestion")
		textState.replace(correction.range, selectedSuggestion, true)
	}

	private fun clearSpellCheck() {
		val doomed = textState.richSpanManager.getAllRichSpans()
			.filter { it.style is SpellCheckStyle }
		textState.updateRichSpans(remove = doomed, add = emptyList())

		misspelledWords.clear()
		sentenceCorrections.clear()
	}

	/**
	 * Run full spell check based on the current mode.
	 *
	 * No-op while checking is disabled via [setSpellCheckingEnabled].
	 */
	suspend fun runFullSpellCheck() {
		when (spellCheckMode) {
			SpellCheckMode.Word -> runFullWordCheck()
			SpellCheckMode.Sentence -> runFullSentenceCheck()
		}
	}

	/**
	 * Run partial spell check based on the current mode.
	 */
	suspend fun runPartialSpellCheck(range: TextEditorRange) {
		when (spellCheckMode) {
			SpellCheckMode.Word -> runPartialWordCheck(range)
			SpellCheckMode.Sentence -> runPartialSentenceCheck(range)
		}
	}

	/**
	 * This is a very naive algorithm that just removes all spell check spans and
	 * reruns the entire word-level spell check again.
	 */
	private suspend fun runFullWordCheck() {
		val sp = spellChecker ?: return
		if (spellCheckingEnabled.not()) return

		println("Running full Word Spell Check")

		// Compute the misspellings under suspension WITHOUT touching spans. A
		// cancellation here (e.g. a recomposition restarting the check) leaves the
		// existing spans intact rather than wiping them.
		val candidates = textState.wordSegments().filter(::shouldSpellCheck).toList()
		val misspelled = candidates.filterNot { sp.isCorrectWord(it.text) }

		// Re-check after the async lookups: a concurrent disable must not have its
		// clearing undone by this swap.
		if (spellCheckingEnabled.not()) return

		// Swap atomically: no suspension points between removal and re-add, and the
		// batch lands as one measure-free relayout instead of one per span.
		val doomed = textState.richSpanManager.getAllRichSpans()
			.filter { it.style is SpellCheckStyle }
		misspelledWords.clear()
		sentenceCorrections.clear()
		textState.updateRichSpans(
			remove = doomed,
			add = misspelled.map { RichSpan(it.range, SpellCheckStyle) },
		)
		misspelledWords.addAll(misspelled)
	}

	/**
	 * Run full sentence-level spell check on the entire document.
	 */
	private suspend fun runFullSentenceCheck() {
		val sp = spellChecker ?: return
		if (spellCheckingEnabled.not()) return

		println("Running full Sentence Spell Check")

		// Compute corrections under suspension first; only mutate spans once the
		// async work is done, so a cancellation can't leave the document wiped.
		val corrections = textState.sentenceSegments().toList().flatMap { sentence ->
			sp.checkSentence(sentence.text, sentence.range)
		}

		// Re-check after the async lookups: a concurrent disable must not have its
		// clearing undone by this swap.
		if (spellCheckingEnabled.not()) return

		// One measure-free relayout for the whole swap instead of one per span.
		val doomed = textState.richSpanManager.getAllRichSpans()
			.filter { it.style is SpellCheckStyle }
		misspelledWords.clear()
		sentenceCorrections.clear()
		textState.updateRichSpans(
			remove = doomed,
			add = corrections.map { RichSpan(it.range, SpellCheckStyle) },
		)
		sentenceCorrections.addAll(corrections)
	}

	private suspend fun runPartialWordCheck(range: TextEditorRange) {
		val sp = spellChecker ?: return
		if (spellCheckingEnabled.not()) return

		// Compute misspellings under suspension before touching spans, so a
		// cancellation leaves the range's existing spans intact.
		val candidates = textState.wordSegmentsInRange(range).filter(::shouldSpellCheck)
		val misspelled = candidates.filterNot { sp.isCorrectWord(it.text) }

		// A concurrent disable may have cleared the document while this check was
		// suspended on lookups; adding spans now would undo its cleanup.
		if (spellCheckingEnabled.not()) return

		// Swap atomically: no suspension points between removal and re-add, and the
		// batch lands as one measure-free relayout instead of one per span.
		val doomed = textState.richSpanManager.getSpansInRange(range)
			.filter { it.style is SpellCheckStyle }
		removeMissSpellingsInRange(range)
		textState.updateRichSpans(
			remove = doomed,
			add = misspelled.map { RichSpan(it.range, SpellCheckStyle) },
		)
		misspelledWords.addAll(misspelled)
	}

	/**
	 * Run sentence-level spell check on sentences that intersect the given range.
	 */
	private suspend fun runPartialSentenceCheck(range: TextEditorRange) {
		val sp = spellChecker ?: return
		if (spellCheckingEnabled.not()) return

		// Compute corrections under suspension before touching spans, so a
		// cancellation leaves the range's existing spans intact.
		val corrections = textState.sentenceSegmentsInRange(range).flatMap { sentence ->
			sp.checkSentence(sentence.text, sentence.range)
		}

		// A concurrent disable may have cleared the document while this check was
		// suspended on lookups; adding spans now would undo its cleanup.
		if (spellCheckingEnabled.not()) return

		// Swap atomically: no suspension points between removal and re-add, and the
		// batch lands as one measure-free relayout instead of one per span.
		val doomed = textState.richSpanManager.getSpansInRange(range)
			.filter { it.style is SpellCheckStyle }
		removeSentenceCorrectionsInRange(range)
		textState.updateRichSpans(
			remove = doomed,
			add = corrections.map { RichSpan(it.range, SpellCheckStyle) },
		)
		sentenceCorrections.addAll(corrections)
	}

	/**
	 * Run spell check on a specific word segment.
	 * This will remove any existing spell check spans for the word and add a new one if misspelled.
	 *
	 * The lookup always runs, but the document is only decorated while checking is enabled.
	 *
	 * @param segment The word segment to check
	 * @return true if the word is misspelled, false otherwise
	 */
	suspend fun checkWordSegment(segment: WordSegment): Boolean {
		val sp = spellChecker ?: return false

		// Resolve the async lookup first; only mutate spans afterward so a
		// cancellation can't leave the word's span removed-but-not-restored.
		val isSpelledCorrectly = sp.isCorrectWord(segment.text)

		if (spellCheckingEnabled) {
			removeMissSpellingsInRange(segment.range)
			val doomed = textState.getRichSpansInRange(segment.range)
				.filter { it.style is SpellCheckStyle }
			val add = if (isSpelledCorrectly) emptyList() else {
				listOf(RichSpan(segment.range, SpellCheckStyle))
			}
			textState.updateRichSpans(remove = doomed, add = add)
			if (!isSpelledCorrectly) {
				misspelledWords.removeAll { it.range == segment.range }
				misspelledWords.add(segment)
			}
		}

		return !isSpelledCorrectly
	}

	private fun shouldSpellCheck(segment: WordSegment): Boolean {
		// Skip segments that are purely numeric
		return !segment.text.all { it.isDigit() }
	}

	/**
	 * Remove spell-check decorations affected by an edit operation.
	 *
	 * Called as edits stream in so stale decorations disappear immediately, ahead of the debounced
	 * re-check. No-op when the operation did not change the document text.
	 *
	 * @param operation The [TextEditOperation] that mutated the document.
	 */
	fun invalidateSpellCheckSpans(operation: TextEditOperation) {
		val newTextHash = textState.computeTextHash()
		if (lastTextHash != newTextHash) {
			val range: TextEditorRange? = when (operation) {
				is TextEditOperation.Delete -> operation.range
				is TextEditOperation.Insert -> TextEditorRange(
					operation.position,
					operation.position
				)

				is TextEditOperation.Replace -> operation.range
				is TextEditOperation.StyleSpan -> null
				is TextEditOperation.RichSpan -> null
				is TextEditOperation.LineBlock -> null
			}

			range?.let {
				removeMissSpellingsInRange(range)
				removeSentenceCorrectionsInRange(range)
			}

			range?.let { r ->
				val doomed = r.affectedLineWraps(textState).flatMap { vLine ->
					textState.getWrappedLine(vLine).richSpans
						.filter { it.style is SpellCheckStyle && r.intersects(it.range) }
				}
				textState.updateRichSpans(remove = doomed, add = emptyList())
			}

			lastTextHash = newTextHash
		}
	}

	private fun findWordSegmentContainingRange(
		segments: List<WordSegment>,
		range: TextEditorRange,
	): WordSegment? {
		return segments.find { wordSegment ->
			val segmentRange = wordSegment.range
			range.start >= segmentRange.start && range.end <= segmentRange.end
		}
	}

	/**
	 * Gather correction suggestions for a word.
	 *
	 * Combines word-level and (for misspelled input) sentence-level [Suggestion]s, de-duplicates
	 * them case-insensitively, and matches each suggestion's capitalization to the source word.
	 *
	 * @param word The word to look up suggestions for.
	 * @return The combined, de-duplicated suggestions; empty when no [spellChecker] is configured.
	 */
	suspend fun getSuggestions(word: String): List<Suggestion> {
		val sp = spellChecker ?: return emptyList()

		val wordLevel = sp.suggestions(word, scope = Scope.Word, closestOnly = true)
		val sentenceLevel = if (!sp.isCorrectWord(word)) {
			sp.suggestions(word, scope = Scope.Sentence, closestOnly = false)
		} else emptyList()

		val combined = (wordLevel + sentenceLevel)
			.distinctBy { it.term.lowercase() }
			.map { suggestion ->
				suggestion.copy(
					term = applyCapitalizationStrategy(
						source = word,
						target = suggestion.term
					)
				)
			}

		return combined
	}
}