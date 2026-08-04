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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How many times a full check will recompute when the document changes under it
 * before leaving the remaining edits to the debounced partial checks.
 */
private const val MAX_FULL_CHECK_ATTEMPTS = 3

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
 * Call from the dispatcher that drives the editor, normally Compose's main dispatcher. The
 * checks serialize among themselves, but the non-suspending entry points
 * ([invalidateSpellCheckSpans], [correctSpelling], [handleSpanClick]) mutate the same
 * book-keeping unguarded, and the spans they touch are Compose state.
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

	/**
	 * Serializes the checks. Two running at once interleave their suspending lookups
	 * against a single [EditorSpellChecker] session and race each other's span swaps,
	 * and two are the norm at init: a host that runs its own re-check effect gets one
	 * alongside the effect inside [rememberSpellCheckState].
	 */
	private val checkMutex = Mutex()

	/** What the last full check covered, and how many have finished. */
	private var completedFullChecks = 0
	private var lastFullCheckHash = -1
	private var lastFullCheckChecker: EditorSpellChecker? = null

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
		val requestedAfter = completedFullChecks
		val requestedHash = textState.computeTextHash()
		val requestedChecker = spellChecker

		checkMutex.withLock {
			// A caller that queued behind an equivalent check already has what it asked
			// for. Only a check that finished after this request counts: a later call
			// over identical text is a deliberate refresh and still runs.
			val satisfiedWhileWaiting = completedFullChecks > requestedAfter &&
				lastFullCheckHash == requestedHash &&
				lastFullCheckChecker === requestedChecker
			if (satisfiedWhileWaiting) return

			val covered = when (spellCheckMode) {
				SpellCheckMode.Word -> runFullWordCheck()
				SpellCheckMode.Sentence -> runFullSentenceCheck()
			}
			if (covered != null) {
				lastFullCheckHash = covered
				lastFullCheckChecker = spellChecker
				completedFullChecks++
			}
		}
	}

	/**
	 * Run partial spell check based on the current mode.
	 */
	suspend fun runPartialSpellCheck(range: TextEditorRange) {
		checkMutex.withLock {
			when (spellCheckMode) {
				SpellCheckMode.Word -> runPartialWordCheck(range)
				SpellCheckMode.Sentence -> runPartialSentenceCheck(range)
			}
		}
	}

	/**
	 * This is a very naive algorithm that just removes all spell check spans and
	 * reruns the entire word-level spell check again.
	 *
	 * @return the document hash the check covered, or null if it did not complete.
	 */
	private suspend fun runFullWordCheck(): Int? {
		val sp = spellChecker ?: return null
		if (spellCheckingEnabled.not()) return null

		println("Running full Word Spell Check")

		repeat(MAX_FULL_CHECK_ATTEMPTS) {
			val revision = textState.computeTextHash()

			// Compute the misspellings under suspension WITHOUT touching spans. A
			// cancellation here (e.g. a recomposition restarting the check) leaves the
			// existing spans intact rather than wiping them.
			val candidates = textState.wordSegments().filter(::shouldSpellCheck).toList()
			val misspelled = candidates.filterNot { sp.isCorrectWord(it.text) }

			// Re-check after the async lookups: a concurrent disable must not have its
			// clearing undone by this swap.
			if (spellCheckingEnabled.not()) return null

			// An edit during the lookups leaves every range addressing a document that
			// is gone. Installing them would squiggle the wrong words and leave
			// `misspelledWords` out of step with the spans, so a right-click on a
			// squiggle finds no segment and offers no suggestions.
			if (textState.computeTextHash() != revision) return@repeat

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
			return revision
		}

		// Edits outpaced the checker. The edited ranges are covered by the debounced
		// partial checks, and the rest of the document keeps the spans it had.
		return null
	}

	/**
	 * Run full sentence-level spell check on the entire document.
	 *
	 * @return the document hash the check covered, or null if it did not complete.
	 */
	private suspend fun runFullSentenceCheck(): Int? {
		val sp = spellChecker ?: return null
		if (spellCheckingEnabled.not()) return null

		println("Running full Sentence Spell Check")

		repeat(MAX_FULL_CHECK_ATTEMPTS) {
			val revision = textState.computeTextHash()

			// Compute corrections under suspension first; only mutate spans once the
			// async work is done, so a cancellation can't leave the document wiped.
			val corrections = textState.sentenceSegments().toList().flatMap { sentence ->
				sp.checkSentence(sentence.text, sentence.range)
			}

			// Re-check after the async lookups: a concurrent disable must not have its
			// clearing undone by this swap.
			if (spellCheckingEnabled.not()) return null

			// The corrections address the pre-edit document; see [runFullWordCheck].
			if (textState.computeTextHash() != revision) return@repeat

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
			return revision
		}

		return null
	}

	private suspend fun runPartialWordCheck(range: TextEditorRange) {
		val sp = spellChecker ?: return
		if (spellCheckingEnabled.not()) return

		val revision = textState.computeTextHash()

		// Compute misspellings under suspension before touching spans, so a
		// cancellation leaves the range's existing spans intact.
		val candidates = textState.wordSegmentsInRange(range).filter(::shouldSpellCheck)
		val misspelled = candidates.filterNot { sp.isCorrectWord(it.text) }

		// A concurrent disable may have cleared the document while this check was
		// suspended on lookups; adding spans now would undo its cleanup.
		if (spellCheckingEnabled.not()) return

		// `range` and every computed range address the pre-edit document. Dropping the
		// result costs nothing: the edit that invalidated it schedules its own check.
		if (textState.computeTextHash() != revision) return

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

		val revision = textState.computeTextHash()

		// Compute corrections under suspension before touching spans, so a
		// cancellation leaves the range's existing spans intact.
		val corrections = textState.sentenceSegmentsInRange(range).flatMap { sentence ->
			sp.checkSentence(sentence.text, sentence.range)
		}

		// A concurrent disable may have cleared the document while this check was
		// suspended on lookups; adding spans now would undo its cleanup.
		if (spellCheckingEnabled.not()) return

		// `range` and every computed range address the pre-edit document. Dropping the
		// result costs nothing: the edit that invalidated it schedules its own check.
		if (textState.computeTextHash() != revision) return

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
	suspend fun checkWordSegment(segment: WordSegment): Boolean = checkMutex.withLock {
		val sp = spellChecker ?: return false

		val revision = textState.computeTextHash()

		// Resolve the async lookup first; only mutate spans afterward so a
		// cancellation can't leave the word's span removed-but-not-restored.
		val isSpelledCorrectly = sp.isCorrectWord(segment.text)

		// An edit during the lookup leaves `segment.range` pointing at text that has
		// moved; the caller still learns whether the word was spelled correctly.
		if (spellCheckingEnabled && textState.computeTextHash() == revision) {
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

		!isSpelledCorrectly
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