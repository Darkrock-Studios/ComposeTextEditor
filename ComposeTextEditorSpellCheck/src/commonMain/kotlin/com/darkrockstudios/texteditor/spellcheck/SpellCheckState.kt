package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.decoration.decorations
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker.Scope
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.spellcheck.utils.LineDiff
import com.darkrockstudios.texteditor.spellcheck.utils.applyCapitalizationStrategy
import com.darkrockstudios.texteditor.spellcheck.utils.decorationsTouching
import com.darkrockstudios.texteditor.spellcheck.utils.decorationsTouchingWithin
import com.darkrockstudios.texteditor.spellcheck.utils.replaceFlagged
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.WordSegment
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
 * Manages the spell-check decoration spans rendered in the document and runs full or partial
 * checks through an [EditorSpellChecker]. Each span's style records what it flagged, so the
 * finding moves with the text as the editor re-anchors the span through edits. The spans are
 * on spell check's decoration layer, [SpellCheckStyle]'s, which it reads and replaces by line;
 * a state made for the same editor later takes over the flags an earlier one left.
 * Span mutations are performed atomically after any asynchronous lookup completes so that a
 * cancelled check never leaves the document with its decorations wiped.
 *
 * Call from the dispatcher that drives the editor, normally Compose's main dispatcher. The
 * checks serialize among themselves, but the non-suspending entry points
 * ([invalidateSpellCheckSpans], [correctSpelling], [applySentenceCorrection]) mutate the
 * same spans unguarded, and the spans are Compose state.
 *
 * @property textState The underlying editor state whose content is spell checked.
 * @property spellChecker The [EditorSpellChecker] used to evaluate words and sentences; checks are
 *   no-ops while this is `null`. A checker serves one language: to change language, assign a
 *   checker for the new one and call [runFullSpellCheck] ([rememberSpellCheckState] does both
 *   when handed a new checker). The [ignoredWords] carry over.
 * @param enableSpellChecking Whether spell checking is active initially; exposed via
 *   [spellCheckingEnabled].
 * @property spellCheckMode Whether checking operates per-word or per-sentence; see [SpellCheckMode].
 * @param scanContext The context the dictionary lookups run in. Checks start on the UI
 *   dispatcher and an [EditorSpellChecker] typically hops to a worker per lookup, so gathering
 *   them here keeps the caller's dispatcher out of the loop. Span mutations still happen on the
 *   caller's dispatcher once the scan returns.
 */
/** A word as the dictionary spells it: with a straight apostrophe in place of a typographic one. */
internal fun String.forLookup(): String = replace('\u2019', '\'')

class SpellCheckState(
	val textState: TextEditorState,
	var spellChecker: EditorSpellChecker?,
	enableSpellChecking: Boolean = true,
	var spellCheckMode: SpellCheckMode = SpellCheckMode.Word,
	private val scanContext: CoroutineContext = Dispatchers.Default,
) {
	/** The decoration layer the flags are on; see `docs/design/decorations.md`. */
	internal val layer = SpellCheckStyle.layer

	/** Words the user chose to ignore this session, through [ignoreWord]. */
	var ignoredWords: Set<String> = emptySet()
		private set

	/**
	 * Words treated as correct this session, [ignoredWords] and words added to a dictionary,
	 * each in its [acceptedForm].
	 */
	private var acceptedWords: Set<String> = emptySet()

	/** The same, as flagged: a sentence issue is matched exactly. */
	private var acceptedTexts: Set<String> = emptySet()

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
			textState.updateRichSpans(remove = textState.decorations(layer), add = emptyList())
		}
	}

	/**
	 * The [TextEditorState.documentGeneration] the latest full check scanned, set when the
	 * scan starts so a replacement arriving mid-scan is not mistaken for already checked.
	 */
	internal var fullCheckGeneration = -1
		private set

	/**
	 * Serializes the checks. Two running at once interleave their suspending lookups
	 * against a single [EditorSpellChecker] session and race each other's span swaps,
	 * and two can start at init: one from [rememberSpellCheckState] and one from the
	 * editor's document-replacement listener.
	 */
	private val checkMutex = Mutex()

	/**
	 * Handle click on a spell check span.
	 * @return WordSegment for word-level misspellings, Correction for sentence-level issues, or null
	 */
	fun handleSpanClick(span: RichSpan): Any? {
		return handleWordSpanClick(span) ?: handleSentenceSpanClick(span)
	}

	/**
	 * Handle click for word-level misspellings only.
	 * Use this when you specifically need a WordSegment.
	 */
	fun handleWordSpanClick(span: RichSpan): WordSegment? {
		if (span.style !is MisspelledWordStyle) return null
		return WordSegment(textState.getStringInRange(span.range), span.range)
	}

	/**
	 * Handle click for sentence-level corrections only.
	 * Use this when you specifically need a Correction.
	 */
	fun handleSentenceSpanClick(span: RichSpan): Correction? {
		val style = span.style as? SentenceIssueStyle ?: return null
		return style.correction.copy(range = span.range)
	}

	/**
	 * Replace a misspelled word with the chosen correction.
	 *
	 * Applies the replacement to [textState] and removes the word's spell-check decoration once
	 * the correction has landed as given. If [TextEditorState.inputFilter] refuses it, the word
	 * stays flagged; a correction the filter changes is an edit like any other, which the edit's
	 * re-check settles.
	 *
	 * @param segment The misspelled [WordSegment] to correct.
	 * @param correction The replacement text.
	 */
	fun correctSpelling(segment: WordSegment, correction: String) {
		textState.replaceFlagged(segment.range, correction, layer)
	}

	/**
	 * Apply a sentence-level correction. Its flag is kept as [correctSpelling] keeps a word's.
	 */
	fun applySentenceCorrection(correction: Correction, selectedSuggestion: String) {
		textState.replaceFlagged(correction.range, selectedSuggestion, layer)
	}

	/**
	 * Stop flagging [word] for the rest of this session, whichever checker is in use, and clear
	 * its current flags. [word] is the flagged text: the word, or a sentence issue's
	 * [Correction.originalText], which is matched exactly. A word in lowercase also clears
	 * capitalised and in capitals, as a dictionary matches case; one capitalised only at its
	 * start is taken for a sentence's first word and matched as its lowercase. One with other
	 * capitals, such as "NASA" or "iPhone", clears only as written.
	 */
	fun ignoreWord(word: String) {
		ignoredWords = ignoredWords + word
		accept(word)
	}

	/** Treats [word] as correct for the rest of this session and clears its current flags. */
	internal fun accept(word: String) {
		acceptedWords = acceptedWords + word.acceptedForm()
		acceptedTexts = acceptedTexts + word
		val doomed = textState.decorations(layer).filter { span ->
			when (val style = span.style) {
				is MisspelledWordStyle -> isAcceptedWord(textState.getStringInRange(span.range))
				is SentenceIssueStyle -> style.correction.originalText in acceptedTexts
				else -> false
			}
		}
		if (doomed.isNotEmpty()) textState.updateRichSpans(remove = doomed, add = emptyList())
	}

	private fun isAcceptedWord(text: String): Boolean {
		if (acceptedWords.isEmpty()) return false
		val form = text.acceptedForm()
		return form in acceptedWords || (form.isAllCapitals() && form.lowercase() in acceptedWords)
	}

	private fun isAccepted(segment: WordSegment): Boolean = isAcceptedWord(segment.text)

	private fun isAccepted(correction: Correction): Boolean = correction.originalText in acceptedTexts

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

		// Compute the misspellings under suspension WITHOUT touching spans. A
		// cancellation here (e.g. a recomposition restarting the check) leaves the
		// existing spans intact rather than wiping them.
		fullCheckGeneration = textState.documentGeneration.value
		val scannedLines = textState.textLines
		val candidates = textState.wordSegments().filter(::shouldSpellCheck).filterNot(::isAccepted).toList()
		val misspelled = withContext(scanContext) {
			candidates.filterNot { sp.isCorrectWord(it.lookupText) }
		}

		// Re-check after the async lookups: a concurrent disable must not have its
		// clearing undone by this swap.
		if (spellCheckingEnabled.not()) return

		val diff = LineDiff(scannedLines, textState.textLines)
		installFullCheck(
			// An ignore can land while the lookups run.
			misspelled.filterNot(::isAccepted).mapNotNull { segment ->
				diff.move(segment.range)?.let { RichSpan(it, MisspelledWordStyle) }
			},
		)
		diff.changedLines()?.let { runPartialWordCheck(it, textState.textLines) }
	}

	/**
	 * Run full sentence-level spell check on the entire document.
	 */
	private suspend fun runFullSentenceCheck() {
		val sp = spellChecker ?: return
		if (spellCheckingEnabled.not()) return

		// Compute corrections under suspension first; only mutate spans once the
		// async work is done, so a cancellation can't leave the document wiped.
		fullCheckGeneration = textState.documentGeneration.value
		val scannedLines = textState.textLines
		val sentences = textState.sentenceSegments().toList()
		val corrections = withContext(scanContext) {
			sentences.flatMap { sentence -> sp.checkSentence(sentence.text, sentence.range) }
		}

		// Re-check after the async lookups: a concurrent disable must not have its
		// clearing undone by this swap.
		if (spellCheckingEnabled.not()) return

		val diff = LineDiff(scannedLines, textState.textLines)
		installFullCheck(
			corrections.filterNot(::isAccepted).mapNotNull { correction ->
				diff.move(correction.range)?.let { RichSpan(it, SentenceIssueStyle(correction)) }
			},
		)
		diff.changedLines()?.let { runPartialSentenceCheck(it, textState.textLines) }
	}

	/**
	 * Replaces every spell-check span with [add], the full scan's results carried onto
	 * the current document.
	 *
	 * The document stays editable while the lookups run. Results on lines an edit
	 * changed are left out of [add] and the caller re-checks just those lines, so a
	 * full check never waits for the whole document to hold still.
	 */
	private fun installFullCheck(add: List<RichSpan>) {
		// Swap atomically: no suspension points between removal and re-add, and the
		// batch lands as one measure-free relayout instead of one per span.
		textState.updateRichSpans(remove = textState.decorations(layer), add = add)
	}

	private suspend fun runPartialWordCheck(
		range: TextEditorRange,
		computedAgainst: List<AnnotatedString>,
	) {
		val sp = spellChecker ?: return
		settlePartialCheck(
			range = range.acrossDots(computedAgainst),
			computedAgainst = computedAgainst,
			scan = { region ->
				val candidates = textState.wordSegmentsInRange(region).filter(::shouldSpellCheck).filterNot(::isAccepted)
				withContext(scanContext) { candidates.filterNot { sp.isCorrectWord(it.lookupText) } }
			},
			move = { segment, diff -> diff.move(segment.range)?.let { segment.copy(range = it) } },
		) { region, misspelled ->
			// Swap atomically: no suspension points between removal and re-add, and the
			// batch lands as one measure-free relayout instead of one per span.
			// The scan reaches the nearest word beyond each end of the region.
			val scanned = misspelled.fold(region) { covered, segment -> covered.merge(segment.range) }
			textState.updateRichSpans(
				remove = textState.decorationsTouching(layer, scanned),
				add = misspelled.filterNot(::isAccepted).map { RichSpan(it.range, MisspelledWordStyle) },
			)
		}
	}

	/**
	 * Run sentence-level spell check on the lines the given range touches. A sentence
	 * ends with its line, so whole lines hold whole sentences, and replacing their flags
	 * leaves none of an edited sentence's behind.
	 */
	private suspend fun runPartialSentenceCheck(
		range: TextEditorRange,
		computedAgainst: List<AnnotatedString>,
	) {
		val sp = spellChecker ?: return
		settlePartialCheck(
			range = range.wholeLines(computedAgainst),
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
			textState.updateRichSpans(
				remove = textState.decorationsTouching(layer, region),
				add = corrections.filterNot(::isAccepted).map { RichSpan(it.range, SentenceIssueStyle(it)) },
			)
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
		// A range past the last line never moves through a LineDiff, so settling it would spin.
		var region = range.within(computedAgainst) ?: return
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
	 * [segment] addresses the document as it stands when this is called. If an edit changes
	 * the word's line before the span goes in, that line is re-checked instead.
	 *
	 * @param segment The word segment to check
	 * @return true if the word is misspelled, false otherwise
	 */
	suspend fun checkWordSegment(segment: WordSegment): Boolean {
		val computedAgainst = textState.textLines
		return checkMutex.withLock {
			val sp = spellChecker ?: return false

			// Resolve the async lookup first; only mutate spans afterward so a
			// cancellation can't leave the word's span removed-but-not-restored.
			val isSpelledCorrectly = !shouldSpellCheck(segment) || isAccepted(segment) ||
				sp.isCorrectWord(segment.lookupText)

			if (spellCheckingEnabled) {
				val diff = LineDiff(computedAgainst, textState.textLines)
				val range = diff.move(segment.range)
				if (range != null) {
					val add = if (isSpelledCorrectly || isAccepted(segment)) emptyList() else {
						listOf(RichSpan(range, MisspelledWordStyle))
					}
					textState.updateRichSpans(remove = textState.decorationsTouchingWithin(layer, range), add = add)
				} else {
					diff.cover(segment.range)?.let { runPartialWordCheck(it, textState.textLines) }
				}
			}

			!isSpelledCorrectly
		}
	}

	/**
	 * Numbers, single letters and two letters a period joins to another letter are not
	 * checked. Words break at a period, so "U.S.A." arrives as three letters and "Ph.D." as
	 * "Ph" and "D". Longer words stay checked, so a missing space in "mat.Teh" still flags
	 * the typo.
	 */
	private fun shouldSpellCheck(segment: WordSegment): Boolean {
		val text = segment.text
		if (text.all { it.isDigit() } || text.length == 1) return false
		if (text.length > 2) return true
		val line = textState.textLines.getOrNull(segment.range.start.line) ?: return true
		return !line.dotsOnToLetter(segment.range.start.char - 1, -1) &&
			!line.dotsOnToLetter(segment.range.end.char, 1)
	}

	/** The segment's text as the dictionary spells it: with a straight apostrophe. */
	private val WordSegment.lookupText: String get() = text.forLookup()

	/**
	 * Remove spell-check decorations affected by an edit operation.
	 *
	 * Called as edits stream in so stale decorations disappear immediately, ahead of the debounced
	 * re-check. No-op for span and line-block operations, which move no text.
	 *
	 * The flags have already moved with the edit, so they are read in the text after it: those
	 * over or touching the text it wrote, or the point a deletion closed up. A flag on a word
	 * beside it goes too, and comes back with the re-check. Edits that have landed since
	 * [operation] must be passed with it, through the overload taking a list.
	 *
	 * @param operation The [TextEditOperation] that mutated the document.
	 */
	fun invalidateSpellCheckSpans(operation: TextEditOperation) {
		invalidateSpellCheckSpans(listOf(operation))
	}

	/**
	 * Remove the spell-check decorations [operations] affected, edits that landed one after
	 * another with no other since: each is read in the text after it, as moved by those after
	 * it. See the overload for one edit.
	 */
	fun invalidateSpellCheckSpans(operations: List<TextEditOperation>) {
		val doomed = computeAffectedRanges(operations)
			.flatMapTo(LinkedHashSet()) { range -> textState.decorationsTouchingWithin(layer, range) }
		if (doomed.isNotEmpty()) textState.updateRichSpans(remove = doomed.toList(), add = emptyList())
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

		val lookup = word.forLookup()
		val wordLevel = sp.suggestions(lookup, scope = Scope.Word, closestOnly = true)
		val sentenceLevel = if (!sp.isCorrectWord(lookup)) {
			sp.suggestions(lookup, scope = Scope.Sentence, closestOnly = false)
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

/**
 * Reaches over a period at either end into the word beyond, whose check depends on this
 * range's text: typing the "D" of "Ph.D" clears the flag on "Ph".
 */
private fun TextEditorRange.acrossDots(lines: List<AnnotatedString>): TextEditorRange {
	val startLine = lines.getOrNull(start.line)?.text ?: return this
	val endLine = lines.getOrNull(end.line)?.text ?: return this
	val from = if (startLine.dotsOnToLetter(start.char - 1, -1)) start.copy(char = start.char - 2) else start
	val to = if (endLine.dotsOnToLetter(end.char, 1)) end.copy(char = end.char + 2) else end
	return TextEditorRange(from, to)
}

/** The range with either end past [lines] brought back to their end; null when there are none. */
private fun TextEditorRange.within(lines: List<AnnotatedString>): TextEditorRange? {
	if (lines.isEmpty()) return null
	val documentEnd = CharLineOffset(lines.lastIndex, lines.last().length)
	fun CharLineOffset.inside() = if (line > lines.lastIndex) documentEnd else this
	return TextEditorRange(start.inside(), end.inside())
}

/** The range widened to the start of its first line and the end of its last, within [lines]. */
private fun TextEditorRange.wholeLines(lines: List<AnnotatedString>): TextEditorRange {
	if (lines.isEmpty()) return this
	val lastLine = end.line.coerceAtMost(lines.lastIndex)
	val firstLine = start.line.coerceAtMost(lastLine)
	return TextEditorRange(CharLineOffset(firstLine, 0), CharLineOffset(lastLine, lines[lastLine].length))
}

/** Whether a period at [index] leads, one more step in [direction], to a letter. */
private fun CharSequence.dotsOnToLetter(index: Int, direction: Int): Boolean =
	getOrNull(index) == '.' && getOrNull(index + direction)?.isLetter() == true

/**
 * How an accepted word is kept: as the dictionary spells it, and lowercased when it is in
 * lowercase or capitalised only at its start, which may just be a sentence's first word.
 */
private fun String.acceptedForm(): String {
	val word = forLookup()
	val afterFirstLetter = word.substring(word.indexOfFirst(Char::isLetter) + 1)
	val lowercaseAfterFirstLetter = afterFirstLetter.any(Char::isLowerCase) && afterFirstLetter.none(Char::isUpperCase)
	return if (lowercaseAfterFirstLetter) word.lowercase() else word
}

private fun String.isAllCapitals(): Boolean = any(Char::isLetter) && none(Char::isLowerCase)

/** Marks a span as a misspelled word; the word is whatever text the span covers. */
internal object MisspelledWordStyle : SpellCheckStyle()

/** Marks a span as a sentence-level issue, carrying the [Correction] that flagged it. */
internal class SentenceIssueStyle(val correction: Correction) : SpellCheckStyle()
