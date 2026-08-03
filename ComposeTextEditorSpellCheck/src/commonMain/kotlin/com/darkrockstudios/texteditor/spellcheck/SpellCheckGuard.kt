package com.darkrockstudios.texteditor.spellcheck

/**
 * Sanity limits that stop spell checking when its results stop looking plausible.
 *
 * The case this primarily guards against is a checker loaded with the wrong language: every
 * word comes back misspelled, the document turns into a wall of red squiggles, and every
 * lookup is wasted work. When a limit is exceeded, [SpellCheckState] drops all spell-check
 * decorations and suspends checking (see [SpellCheckSuspension]) instead of flagging the
 * whole document.
 *
 * @property maxMisspellings The most flagged items that may accumulate before checking is
 *   suspended. Counts misspelled words in [SpellCheckMode.Word] and corrections in
 *   [SpellCheckMode.Sentence].
 * @property maxFlaggedRatio The fraction of the document that may be flagged before the
 *   checker is presumed to be checking the wrong language. Judged against the whole
 *   document's word (or sentence) count, and only once the document is large enough to be
 *   meaningful; see [minWordSample] and [minSentenceSample].
 * @property minWordSample How many checkable words the document must contain before
 *   [maxFlaggedRatio] is applied in [SpellCheckMode.Word]. Documents shorter than this are
 *   never suspended by ratio; a handful of squiggles is not worth a false positive.
 * @property minSentenceSample How many sentences the document must contain before
 *   [maxFlaggedRatio] is applied in [SpellCheckMode.Sentence].
 */
data class SpellCheckGuard(
	val maxMisspellings: Int = DEFAULT_MAX_MISSPELLINGS,
	val maxFlaggedRatio: Float = DEFAULT_MAX_FLAGGED_RATIO,
	val minWordSample: Int = DEFAULT_MIN_WORD_SAMPLE,
	val minSentenceSample: Int = DEFAULT_MIN_SENTENCE_SAMPLE,
) {
	init {
		require(maxMisspellings > 0) { "maxMisspellings must be positive, was $maxMisspellings" }
		require(minWordSample > 0) { "minWordSample must be positive, was $minWordSample" }
		require(minSentenceSample > 0) { "minSentenceSample must be positive, was $minSentenceSample" }
		require(maxFlaggedRatio in 0f..1f) { "maxFlaggedRatio must be in 0..1, was $maxFlaggedRatio" }
	}

	companion object {
		const val DEFAULT_MAX_MISSPELLINGS = 500
		const val DEFAULT_MAX_FLAGGED_RATIO = 0.7f
		const val DEFAULT_MIN_WORD_SAMPLE = 40
		const val DEFAULT_MIN_SENTENCE_SAMPLE = 8

		/** The limits applied unless a caller supplies its own. */
		val Default = SpellCheckGuard()

		/** Limits that can never be reached: spell checking is never suspended on its own. */
		val Disabled = SpellCheckGuard(
			maxMisspellings = Int.MAX_VALUE,
			minWordSample = Int.MAX_VALUE,
			minSentenceSample = Int.MAX_VALUE,
		)
	}
}

/**
 * Why spell checking suspended itself. See [SpellCheckGuard].
 *
 * A suspension is sticky: automatic checking stays off so a wrong-language checker isn't
 * asked to re-scan the document on every keystroke. A full re-check (a replaced checker,
 * [SpellCheckState.resumeSpellChecking], [SpellCheckState.runFullSpellCheck], or re-enabling
 * via [SpellCheckState.setSpellCheckingEnabled]) re-tries the checker and lifts the
 * suspension once a scan completes with plausible results.
 */
sealed interface SpellCheckSuspension {
	/**
	 * Nearly everything checked came back wrong, which usually means the checker's dictionary
	 * doesn't match the document's language.
	 *
	 * @property checked The sample the ratio was judged against: the document's checkable
	 *   words or sentences, per [SpellCheckMode].
	 * @property flagged How many flagged items the guard had seen when it tripped.
	 */
	data class LikelyWrongLanguage(val checked: Int, val flagged: Int) : SpellCheckSuspension

	/**
	 * More flagged items accumulated than [SpellCheckGuard.maxMisspellings] allows.
	 *
	 * @property flagged How many flagged items had accumulated.
	 * @property limit The limit that was exceeded.
	 */
	data class TooManyMisspellings(val flagged: Int, val limit: Int) : SpellCheckSuspension
}

/** Trips when more than [SpellCheckGuard.maxMisspellings] items have been flagged. */
internal fun SpellCheckGuard.evaluateCount(flagged: Int): SpellCheckSuspension? =
	if (flagged > maxMisspellings) {
		SpellCheckSuspension.TooManyMisspellings(flagged, maxMisspellings)
	} else {
		null
	}

/** Trips when too large a share of a large enough word sample is flagged. */
internal fun SpellCheckGuard.evaluateWordRatio(checked: Int, flagged: Int): SpellCheckSuspension? =
	evaluateRatio(checked, flagged, minWordSample)

/** Trips when too large a share of a large enough sentence sample is flagged. */
internal fun SpellCheckGuard.evaluateSentenceRatio(checked: Int, flagged: Int): SpellCheckSuspension? =
	evaluateRatio(checked, flagged, minSentenceSample)

/**
 * [evaluateWordRatio], but [checked] is only asked for the document's word count once
 * [flagged] is large enough for the ratio to be reachable at all.
 *
 * Counting the document's words is O(document) and a partial check runs on every typing
 * pause, so an ordinary document with a handful of typos must never pay for it.
 */
internal inline fun SpellCheckGuard.evaluateWordRatioIfReachable(
	flagged: Int,
	checked: () -> Int,
): SpellCheckSuspension? =
	if (ratioReachable(flagged, minWordSample)) evaluateWordRatio(checked(), flagged) else null

/**
 * Whether [flagged] is large enough for the ratio to trip on any document. Tripping needs
 * `flagged > checked * maxFlaggedRatio` with `checked >= minSample`, so a flagged count at
 * or below `minSample * maxFlaggedRatio` cannot trip however the document is shaped. Float
 * arithmetic keeps [SpellCheckGuard.Disabled]'s `Int.MAX_VALUE` sample from overflowing.
 */
internal fun SpellCheckGuard.ratioReachable(flagged: Int, minSample: Int): Boolean =
	flagged > minSample.toFloat() * maxFlaggedRatio

private fun SpellCheckGuard.evaluateRatio(
	checked: Int,
	flagged: Int,
	minSample: Int,
): SpellCheckSuspension? =
	if (checked >= minSample && flagged > checked * maxFlaggedRatio) {
		SpellCheckSuspension.LikelyWrongLanguage(checked, flagged)
	} else {
		null
	}
