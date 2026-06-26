package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.util.fastForEach
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker.Scope
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.spellcheck.utils.applyCapitalizationStrategy
import com.darkrockstudios.texteditor.state.*

/**
 * Determines which spell checking mode is active.
 */
enum class SpellCheckMode {
	/** Check individual words - current/default behavior */
	Word,

	/** Check full sentences for context-aware corrections */
	Sentence
}

class SpellCheckState(
	val textState: TextEditorState,
	var spellChecker: EditorSpellChecker?,
	enableSpellChecking: Boolean = true,
	var spellCheckMode: SpellCheckMode = SpellCheckMode.Word,
) {
	var spellCheckingEnabled: Boolean = enableSpellChecking
		private set

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

	fun correctSpelling(segment: WordSegment, correction: String) {
		textState.getRichSpansInRange(segment.range)
			.filter { it.style == SpellCheckStyle }
			.forEach { span ->
				textState.removeRichSpan(span)
			}
		misspelledWords.remove(segment)
		println("Correcting spelling for $segment, correcting to: $correction")
		textState.replace(segment.range, correction, true)
	}

	/**
	 * Apply a sentence-level correction.
	 */
	fun applySentenceCorrection(correction: Correction, selectedSuggestion: String) {
		textState.getRichSpansInRange(correction.range)
			.filter { it.style == SpellCheckStyle }
			.forEach { span ->
				textState.removeRichSpan(span)
			}
		sentenceCorrections.remove(correction)
		println("Applying sentence correction: ${correction.originalText} -> $selectedSuggestion")
		textState.replace(correction.range, selectedSuggestion, true)
	}

	private fun clearSpellCheck() {
		textState.apply {
			richSpanManager.getAllRichSpans()
				.filter { it.style is SpellCheckStyle }
				.forEach { span ->
					removeRichSpan(span)
				}
		}

		misspelledWords.clear()
		sentenceCorrections.clear()
	}

	/**
	 * Run full spell check based on the current mode.
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

		// Swap atomically: no suspension points between removal and re-add.
		textState.apply {
			richSpanManager.getAllRichSpans()
				.filter { it.style is SpellCheckStyle }
				.forEach { removeRichSpan(it) }
			misspelledWords.clear()
			sentenceCorrections.clear()
			misspelled.forEach { addRichSpan(it.range, SpellCheckStyle) }
		}
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

		textState.apply {
			richSpanManager.getAllRichSpans()
				.filter { it.style is SpellCheckStyle }
				.forEach { removeRichSpan(it) }
			misspelledWords.clear()
			sentenceCorrections.clear()
			corrections.forEach { addRichSpan(it.range, SpellCheckStyle) }
		}
		sentenceCorrections.addAll(corrections)
	}

	private suspend fun runPartialWordCheck(range: TextEditorRange) {
		val sp = spellChecker ?: return
		if (spellCheckingEnabled.not()) return

		// Compute misspellings under suspension before touching spans, so a
		// cancellation leaves the range's existing spans intact.
		val candidates = textState.wordSegmentsInRange(range).filter(::shouldSpellCheck)
		val misspelled = candidates.filterNot { sp.isCorrectWord(it.text) }

		// Swap atomically: no suspension points between removal and re-add.
		textState.richSpanManager.getSpansInRange(range)
			.filter { it.style is SpellCheckStyle }
			.forEach { textState.removeRichSpan(it) }
		removeMissSpellingsInRange(range)
		misspelled.forEach { textState.addRichSpan(it.range, SpellCheckStyle) }
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

		// Swap atomically: no suspension points between removal and re-add.
		textState.richSpanManager.getSpansInRange(range)
			.filter { it.style is SpellCheckStyle }
			.forEach { textState.removeRichSpan(it) }
		removeSentenceCorrectionsInRange(range)
		corrections.forEach { textState.addRichSpan(it.range, SpellCheckStyle) }
		sentenceCorrections.addAll(corrections)
	}

	/**
	 * Run spell check on a specific word segment.
	 * This will remove any existing spell check spans for the word and add a new one if misspelled.
	 *
	 * @param segment The word segment to check
	 * @return true if the word is misspelled and a new span was added, false otherwise
	 */
	suspend fun checkWordSegment(segment: WordSegment): Boolean {
		val sp = spellChecker ?: return false

		// Resolve the async lookup first; only mutate spans afterward so a
		// cancellation can't leave the word's span removed-but-not-restored.
		val isSpelledCorrectly = sp.isCorrectWord(segment.text)

		removeMissSpellingsInRange(segment.range)
		textState.apply {
			getRichSpansInRange(segment.range)
				.filter { it.style is SpellCheckStyle }
				.forEach { removeRichSpan(it) }

			if (!isSpelledCorrectly) {
				addRichSpan(segment.range, SpellCheckStyle)
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

			range?.affectedLineWraps(textState)?.forEach { vLine ->
				val lineWrap = textState.getWrappedLine(vLine)
				lineWrap.richSpans
					.filter { it.style is SpellCheckStyle }
					.fastForEach { span ->
						if (range.intersects(span.range)) {
							textState.removeRichSpan(span)
						}
					}
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