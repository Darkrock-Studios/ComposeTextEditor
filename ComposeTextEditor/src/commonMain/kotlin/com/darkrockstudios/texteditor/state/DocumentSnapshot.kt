package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.richstyle.RichSpan

/**
 * A document's text and rich spans as they stood after one edit.
 *
 * Both halves are immutable and come from the same revision, so a serializer can walk
 * them without an edit changing either underneath it and without pairing text from one
 * revision with span line indices from another. Take one with
 * [TextEditorState.snapshot]; it is safe to hold and to read from any thread, and it
 * does not reflect later edits. Load one into an editor with
 * [TextEditorState.setDocument].
 */
class DocumentSnapshot private constructor(
	/** The document as one [AnnotatedString] per line, in order. */
	val lines: List<AnnotatedString>,
	/** Every rich span in the document, its ranges addressing [lines]. */
	val richSpans: Set<RichSpan>,
	/**
	 * Backs [richSpansByLine]. Held as the [Lazy] rather than the map so
	 * [withLines] can hand the same one to the revision it produces, which keeps the
	 * memoized index alive across a text-only edit. Sharing it also carries the
	 * `PUBLICATION` safety over, where a plain field would publish the map unsafely.
	 */
	private val spansByLine: Lazy<Map<Int, List<RichSpan>>>,
	/**
	 * Backs [lineStartOffsets]. Held as the [Lazy] for the same reasons as
	 * [spansByLine], and shared with the revision [withRichSpans] produces
	 * because a span-only edit leaves every line length untouched.
	 */
	private val lineStarts: Lazy<IntArray>,
	/**
	 * Backs [getAllText] and [plainText]. Depends only on the text, so like
	 * [lineStarts] it is shared with the revision [withRichSpans] produces.
	 */
	private val text: DocumentText,
) {
	/**
	 * Builds a snapshot by hand. [richSpans] need not fit [lines]:
	 * [TextEditorState.setDocument] clamps them onto the document on load.
	 */
	constructor(lines: List<AnnotatedString>, richSpans: Set<RichSpan> = emptySet()) : this(
		lines = lines,
		richSpans = richSpans,
		spansByLine = spansByLineOf(richSpans),
		lineStarts = lineStartsOf(lines),
		text = DocumentText(lines),
	)

	/**
	 * [richSpans] grouped by every line each one covers; a multi-line span appears
	 * under each of its lines. Built on first read and reused until a revision
	 * changes the spans, so the per-line queries the layout pass runs once per
	 * visual line cost a map lookup instead of a scan of every span in the document.
	 */
	internal val richSpansByLine: Map<Int, List<RichSpan>> get() = spansByLine.value

	/**
	 * Flat character index at which each line starts, counting one newline between
	 * lines. Has one entry per line plus a trailing entry for the position just past
	 * the document's final newline slot, so `lineStartOffsets[n + 1] - 1` is the end
	 * of line `n`.
	 *
	 * Built on first read and reused until a revision changes the text, which turns
	 * offset/index conversion from a walk over every preceding line into an array
	 * read. Draw does that conversion several times per rich span per frame, so the
	 * walk showed up directly in frame time on long documents.
	 */
	internal val lineStartOffsets: IntArray get() = lineStarts.value

	/**
	 * The whole document as a single [AnnotatedString], lines joined with newlines.
	 * Built on first read and reused until a revision changes the text; after an edit
	 * it is spliced from the last revision whose text was built, copying that one's
	 * unchanged ends rather than every line. A reader that needs a few characters
	 * wants [chars] instead.
	 */
	fun getAllText(): AnnotatedString = text.annotated.value

	/** [getAllText] without its styles, memoized and spliced the same way. */
	internal val plainText: String get() = text.plain.value

	/**
	 * The document's characters, lines joined with newlines, read in place: a
	 * character costs a binary search over the line starts and a range costs its own
	 * length, so an input method's reads around the caret never build the whole text.
	 */
	internal val chars: CharSequence by lazy(LazyThreadSafetyMode.PUBLICATION) { DocumentChars(lines, lineStartOffsets) }

	/**
	 * Keeps the span index: the ranges are untouched by a text edit, so the lines they
	 * start on are the same ones they started on before. [splice], when the caller knows
	 * it, says which lines changed; otherwise the lines are compared by identity.
	 */
	internal fun withLines(lines: List<AnnotatedString>, splice: LineSplice? = null) = DocumentSnapshot(
		lines = lines,
		richSpans = richSpans,
		spansByLine = spansByLine,
		lineStarts = lineStartsOf(lines),
		text = text.next(this.lines, lineStarts, lines, splice),
	)

	internal fun withRichSpans(richSpans: Set<RichSpan>) = DocumentSnapshot(
		lines = lines,
		richSpans = richSpans,
		spansByLine = spansByLineOf(richSpans),
		lineStarts = lineStarts,
		text = text,
	)
}

/**
 * How a revision's lines differ from the one before: its first [unchangedBefore] lines
 * and last [unchangedAfter] lines are the same objects as the previous revision's.
 */
internal class LineSplice(val unchangedBefore: Int, val unchangedAfter: Int)

/**
 * A revision whose [text] was built, with its line starts, and how many lines at each
 * end the revision holding this shares with it. Holds no lines, so an unread revision
 * keeps one old text of each kind alive at most, never a chain of them.
 */
internal class TextBase<T : CharSequence>(
	val text: T,
	val lineCount: Int,
	val starts: IntArray,
	val unchangedBefore: Int,
	val unchangedAfter: Int,
) {
	/** This base for a revision that further kept [step]'s ends, or null when it shares no line with it. */
	fun narrowed(step: LineSplice): TextBase<T>? {
		val before = minOf(unchangedBefore, step.unchangedBefore)
		val after = minOf(unchangedAfter, step.unchangedAfter)
		return if (before == 0 && after == 0) null else TextBase(text, lineCount, starts, before, after)
	}
}

/**
 * The whole text of one revision, built on demand and spliced from a base when it can
 * be. A base is dropped once its text is built, so a read revision holds no old text.
 */
internal class DocumentText(
	private val lines: List<AnnotatedString>,
	private var plainBase: TextBase<String>? = null,
	private var annotatedBase: TextBase<AnnotatedString>? = null,
) {
	val annotated: Lazy<AnnotatedString> = lazy(LazyThreadSafetyMode.PUBLICATION) {
		val base = annotatedBase
		val capacity = base?.text?.length ?: (lines.sumOf { it.length } + lines.size)
		val text = with(AnnotatedString.Builder(capacity + 16)) {
			if (base == null) {
				lines.forEachIndexed { index, line ->
					if (index > 0) append('\n')
					append(line)
				}
			} else {
				splice(base, { start, end -> append(base.text, start, end) }) { append(it) }
			}
			toAnnotatedString()
		}
		annotatedBase = null
		text
	}

	val plain: Lazy<String> = lazy(LazyThreadSafetyMode.PUBLICATION) {
		val base = plainBase
		val text = when {
			// One string for both when the styled text is already there.
			annotated.isInitialized() -> annotated.value.text
			base == null -> buildString(lines.sumOf { it.length } + lines.size) {
				lines.forEachIndexed { index, line ->
					if (index > 0) append('\n')
					append(line.text)
				}
			}
			else -> buildString(base.text.length + 16) {
				splice(base, { start, end -> append(base.text, start, end) }) { append(it.text) }
			}
		}
		plainBase = null
		text
	}

	/**
	 * Writes the base's unchanged first lines, this revision's changed lines, and the
	 * base's unchanged last lines, through [appendBase] for a range of the base's text
	 * and [appendLine] for a line of this revision's.
	 *
	 * A copied range drops an empty annotation at its end (an empty block line's styles
	 * are), and one covering the whole base keeps every annotation, those at its end
	 * included. So the line at each end of a copied range is written from the lines, and
	 * no range covers the whole base.
	 */
	private inline fun Appendable.splice(
		base: TextBase<*>,
		appendBase: (start: Int, end: Int) -> Unit,
		appendLine: (AnnotatedString) -> Unit,
	) {
		val baseLength = base.starts[base.lineCount] - 1
		val lastBaseLine = base.lineCount - 1
		// The head copies lines [0, headLines), each with the line break after it.
		var headLines = (base.unchangedBefore - 1).coerceAtLeast(0)
		if (headLines > 0 && base.starts[headLines] >= baseLength) headLines--
		if (headLines > 0) appendBase(0, base.starts[headLines])
		val after = base.unchangedAfter
		for (index in headLines until lines.size - after) {
			if (index > headLines) append('\n')
			appendLine(lines[index])
		}
		if (after > 0) {
			var tailLine = base.lineCount - after
			var newLine = lines.size - after
			if (newLine > 0) append('\n')
			if (tailLine < lastBaseLine && base.starts[tailLine] == 0 && base.starts[lastBaseLine] >= baseLength) {
				appendLine(lines[newLine++])
				append('\n')
				tailLine++
			}
			// The tail copies lines [tailLine, last), then writes the last.
			if (tailLine < lastBaseLine) appendBase(base.starts[tailLine], base.starts[lastBaseLine])
			appendLine(lines[lines.size - 1])
		}
	}

	/**
	 * The text of the revision whose lines are [newLines], replacing [oldLines] (this
	 * one's). Each kind splices from this revision when this one built it (the plain text
	 * from the styled text's string when only that was built), else from this one's own
	 * base, with the unchanged ends narrowed to what both edits kept.
	 */
	fun next(
		oldLines: List<AnnotatedString>,
		oldStarts: Lazy<IntArray>,
		newLines: List<AnnotatedString>,
		splice: LineSplice?,
	): DocumentText {
		val plainBuilt = plain.isInitialized()
		val annotatedBuilt = annotated.isInitialized()
		val oldPlainBase = plainBase
		val oldAnnotatedBase = annotatedBase
		if (!plainBuilt && !annotatedBuilt && oldPlainBase == null && oldAnnotatedBase == null) {
			return DocumentText(newLines)
		}
		val step = splice ?: diff(oldLines, newLines)
		val shares = step.unchangedBefore > 0 || step.unchangedAfter > 0
		fun <T : CharSequence> baseOf(text: T) =
			TextBase(text, oldLines.size, oldStarts.value, step.unchangedBefore, step.unchangedAfter)
		return DocumentText(
			lines = newLines,
			plainBase = when {
				!shares -> null
				plainBuilt -> baseOf(plain.value)
				annotatedBuilt -> baseOf(annotated.value.text)
				else -> oldPlainBase?.narrowed(step)
			},
			annotatedBase = when {
				!shares -> null
				annotatedBuilt -> baseOf(annotated.value)
				else -> oldAnnotatedBase?.narrowed(step)
			},
		)
	}
}


/** The lines two revisions share at each end, compared by identity. */
private fun diff(old: List<AnnotatedString>, new: List<AnnotatedString>): LineSplice {
	val shorter = minOf(old.size, new.size)
	var before = 0
	while (before < shorter && old[before] === new[before]) before++
	var after = 0
	while (after < shorter - before && old[old.size - 1 - after] === new[new.size - 1 - after]) after++
	return LineSplice(before, after)
}

/** [DocumentSnapshot.chars]: the lines read in place, with a line break between each two. */
private class DocumentChars(private val lines: List<AnnotatedString>, private val starts: IntArray) : CharSequence {
	override val length: Int get() = maxOf(0, starts[lines.size] - 1)

	override fun get(index: Int): Char {
		if (index < 0 || index >= length) throw IndexOutOfBoundsException("index: $index, length: $length")
		val line = lineOf(index)
		val char = index - starts[line]
		val text = lines[line].text
		return if (char < text.length) text[char] else '\n'
	}

	override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
		if (startIndex < 0 || endIndex > length || startIndex > endIndex) {
			throw IndexOutOfBoundsException("start: $startIndex, end: $endIndex, length: $length")
		}
		return buildString(endIndex - startIndex) {
			var index = startIndex
			var line = if (startIndex < endIndex) lineOf(startIndex) else 0
			while (index < endIndex) {
				val text = lines[line].text
				val char = index - starts[line]
				val take = minOf(text.length, char + endIndex - index)
				if (char < take) append(text, char, take)
				index += take - char
				if (index < endIndex) {
					append('\n')
					index++
					line++
				}
			}
		}
	}

	override fun toString(): String = subSequence(0, length).toString()

	private fun lineOf(index: Int): Int = lineOfCharacter(starts, lines.size, index)
}

/**
 * Index of the line containing flat character [index], by binary search over a
 * snapshot's line [starts] for [lineCount] lines. [index] must be within the document.
 */
internal fun lineOfCharacter(starts: IntArray, lineCount: Int, index: Int): Int {
	var low = 0
	var high = lineCount - 1
	while (low < high) {
		val mid = (low + high + 1) ushr 1
		if (starts[mid] <= index) low = mid else high = mid - 1
	}
	return low
}

private fun spansByLineOf(richSpans: Set<RichSpan>): Lazy<Map<Int, List<RichSpan>>> =
	lazy(LazyThreadSafetyMode.PUBLICATION) {
		val byLine = mutableMapOf<Int, MutableList<RichSpan>>()
		for (span in richSpans) {
			for (line in span.range.start.line..span.range.end.line) {
				byLine.getOrPut(line) { mutableListOf() }.add(span)
			}
		}
		byLine
	}

private fun lineStartsOf(lines: List<AnnotatedString>): Lazy<IntArray> =
	lazy(LazyThreadSafetyMode.PUBLICATION) {
		val starts = IntArray(lines.size + 1)
		var offset = 0
		for (index in lines.indices) {
			starts[index] = offset
			offset += lines[index].length + 1
		}
		starts[lines.size] = offset
		starts
	}
