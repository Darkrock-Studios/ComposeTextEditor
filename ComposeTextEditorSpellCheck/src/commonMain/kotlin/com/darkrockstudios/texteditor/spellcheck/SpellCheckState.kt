package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker.Scope
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.spellcheck.utils.LineDiff
import com.darkrockstudios.texteditor.spellcheck.utils.applyCapitalizationStrategy
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.WordSegment
import com.darkrockstudios.texteditor.state.getRichSpansInRange
import com.darkrockstudios.texteditor.state.sentenceSegments
import com.darkrockstudios.texteditor.state.sentenceSegmentsInRange
import com.darkrockstudios.texteditor.state.wordSegments
import com.darkrockstudios.texteditor.state.wordSegmentsInRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

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
 * @param scanContext The context the dictionary lookups run in. Checks start on the UI
 *   dispatcher and an [EditorSpellChecker] typically hops to a worker per lookup, so gathering
 *   them here keeps the caller's dispatcher out of the loop. Span mutations still happen on the
 *   caller's dispatcher once the scan returns.
 */
class SpellCheckState(
	val textState: TextEditorState,
	var spellChecker: EditorSpellChecker?,
	enableSpellChecking: Boolean = true,
	var spellCheckMode: SpellCheckMode = SpellCheckMode.Word,
	private val scanContext: CoroutineContext = Dispatchers.Default,
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

	/**
	 * The [TextEditorState.documentGeneration] the latest full check scanned, set when the
	 * scan starts so a replacement arriving mid-scan is not mistaken for already checked.
	 */
	internal var fullCheckGeneration = -1
		private set
	private val misspelledWords = mutableListOf<WordSegment>()
	private val sentenceCorrections = mutableListOf<Correction>()

	/**
	 * Serializes the checks. Two running at once interleave their suspending lookups
	 * against a single [EditorSpellChecker] session and race each other's span swaps,
	 * and two can start at init: one from [rememberSpellCheckState] and one from the
	 * editor's document-replacement listener.
	 */
	private val checkMutex = Mutex()

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
		checkMutex.withLock {
			when (spellCheckMode) {
				SpellCheckMode.Word -> runFullWordCheck()
				SpellCheckMode.Sentence -> runFullSentenceCheck()
			}
		}
	}

	/**
	 * Run partial spell check based on the current mode.
	 *
	 * [range] addresses the document as it stands when this is called. Edits that land
	 * while the check waits its turn or runs its lookups carry the range along with them.
	 */
	suspend fun runPartialSpellCheck(range: TextEditorRange) {
		runPartialSpellCheck(range, textState.textLines)
	}

	/**
	 * Run partial spell check over [range], which addresses [computedAgainst], an earlier
	 * [TextEditorState.textLines].
	 */
	internal suspend fun runPartialSpellCheck(
		range: TextEditorRange,
		computedAgainst: List<AnnotatedString>,
	) {
		checkMutex.withLock {
			when (spellCheckMode) {
				SpellCheckMode.Word -> runPartialWordCheck(range, computedAgainst)
				SpellCheckMode.Sentence -> runPartialSentenceCheck(range, computedAgainst)
			}
		}
	}

	/**
	 * This is a very naive algorithm that just removes all spell check spans and
	 * reruns the entire word-level spell check again.
	 */
	private suspend fun runFullWordCheck() {
		val sp = spellChecker ?: return
		if (spellCheckingEnabled.not()) return

		repeat(MAX_SCAN_ATTEMPTS) {
			// Compute the misspellings under suspension WITHOUT touching spans. A
			// cancellation here (e.g. a recomposition restarting the check) leaves the
			// existing spans intact rather than wiping them.
			fullCheckGeneration = textState.documentGeneration.value
			val scannedText = textState.computeTextHash()
			val candidates = textState.wordSegments().filter(::shouldSpellCheck).toList()
			val misspelled = withContext(scanContext) {
				candidates.filterNot { sp.isCorrectWord(it.text) }
			}

			// Re-check after the async lookups: a concurrent disable must not have its
			// clearing undone by this swap.
			if (spellCheckingEnabled.not()) return
			// The document stays editable while the lookups run, so a scan that raced an
			// edit describes text that has moved; planting its ranges would underline the
			// wrong words. Re-scan the newer text instead.
			if (textState.computeTextHash() != scannedText) return@repeat

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
			return
		}
	}

	/**
	 * Run full sentence-level spell check on the entire document.
	 */
	private suspend fun runFullSentenceCheck() {
		val sp = spellChecker ?: return
		if (spellCheckingEnabled.not()) return

		repeat(MAX_SCAN_ATTEMPTS) {
			// Compute corrections under suspension first; only mutate spans once the
			// async work is done, so a cancellation can't leave the document wiped.
			fullCheckGeneration = textState.documentGeneration.value
			val scannedText = textState.computeTextHash()
			val sentences = textState.sentenceSegments().toList()
			val corrections = withContext(scanContext) {
				sentences.flatMap { sentence -> sp.checkSentence(sentence.text, sentence.range) }
			}

			// Re-check after the async lookups: a concurrent disable must not have its
			// clearing undone by this swap.
			if (spellCheckingEnabled.not()) return
			// The document stays editable while the lookups run, so a scan that raced an
			// edit describes text that has moved; planting its ranges would underline the
			// wrong words. Re-scan the newer text instead.
			if (textState.computeTextHash() != scannedText) return@repeat

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
			return
		}
	}

	private suspend fun runPartialWordCheck(
		range: TextEditorRange,
		computedAgainst: List<AnnotatedString>,
	) {
		val sp = spellChecker ?: return
		settlePartialCheck(
			range = range,
			computedAgainst = computedAgainst,
			scan = { region ->
				val candidates = textState.wordSegmentsInRange(region).filter(::shouldSpellCheck)
				withContext(scanContext) { candidates.filterNot { sp.isCorrectWord(it.text) } }
			},
			move = { segment, diff -> diff.move(segment.range)?.let { segment.copy(range = it) } },
		) { region, misspelled ->
			// Swap atomically: no suspension points between removal and re-add, and the
			// batch lands as one measure-free relayout instead of one per span.
			val doomed = textState.richSpanManager.getSpansInRange(region)
				.filter { it.style is SpellCheckStyle }
			removeMissSpellingsInRange(region)
			textState.updateRichSpans(
				remove = doomed,
				add = misspelled.map { RichSpan(it.range, SpellCheckStyle) },
			)
			misspelledWords.addAll(misspelled)
		}
	}

	/**
	 * Run sentence-level spell check on sentences that intersect the given range.
	 */
	private suspend fun runPartialSentenceCheck(
		range: TextEditorRange,
		computedAgainst: List<AnnotatedString>,
	) {
		val sp = spellChecker ?: return
		settlePartialCheck(
			range = range,
			computedAgainst = computedAgainst,
			scan = { region ->
				val sentences = textState.sentenceSegmentsInRange(region)
				withContext(scanContext) {
					sentences.flatMap { sentence -> sp.checkSentence(sentence.text, sentence.range) }
				}
			},
			move = { correction, diff -> diff.move(correction.range)?.let { correction.copy(range = it) } },
		) { region, corrections ->
			// Swap atomically: no suspension points between removal and re-add, and the
			// batch lands as one measure-free relayout instead of one per span.
			val doomed = textState.richSpanManager.getSpansInRange(region)
				.filter { it.style is SpellCheckStyle }
			removeSentenceCorrectionsInRange(region)
			textState.updateRichSpans(
				remove = doomed,
				add = corrections.map { RichSpan(it.range, SpellCheckStyle) },
			)
			sentenceCorrections.addAll(corrections)
		}
	}

	/**
	 * Runs [scan] over [range] until its results can be installed against the current
	 * document, then hands them to [install].
	 *
	 * The document stays editable while a check waits on [checkMutex] and while its
	 * lookups run. Edits clear of the range just shift it and its results by whole
	 * lines. Edits that land on it widen it over the changed lines and scan again,
	 * rather than dropping the result: the edit's own check covers only the text it
	 * touched, and [invalidateSpellCheckSpans] already stripped the rest of this range.
	 */
	private suspend fun <T> settlePartialCheck(
		range: TextEditorRange,
		computedAgainst: List<AnnotatedString>,
		scan: suspend (TextEditorRange) -> List<T>,
		move: (T, LineDiff) -> T?,
		install: (TextEditorRange, List<T>) -> Unit,
	) {
		var region = range
		var regionLines = computedAgainst
		while (true) {
			if (spellCheckingEnabled.not()) return
			val scannedLines = textState.textLines
			region = LineDiff(regionLines, scannedLines).cover(region) ?: return
			regionLines = scannedLines

			// Compute under suspension before touching spans, so a cancellation leaves
			// the region's existing spans intact.
			val results = scan(region)

			// A concurrent disable may have cleared the document while this check was
			// suspended on lookups; adding spans now would undo its cleanup.
			if (spellCheckingEnabled.not()) return

			val diff = LineDiff(scannedLines, textState.textLines)
			val movedRegion = diff.move(region) ?: continue
			val movedResults = results.map { move(it, diff) }
			if (null in movedResults) continue
			install(movedRegion, movedResults.filterNotNull())
			return
		}
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

	private companion object {
		/** Attempts a full scan gets to land against text that stopped moving. */
		const val MAX_SCAN_ATTEMPTS = 3
	}
}