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
	/** The lines, chunked: an edit shares the chunks it leaves alone. */
	internal val lineList: LineList,
	/**
	 * The rich spans keyed by line, chunked like the lines: a text edit splices it in
	 * step with them, and a span edit rewrites the chunks holding its lines.
	 */
	internal val spanIndex: SpanIndex,
	/**
	 * Backs [getAllText] and [plainText]. Depends only on the text, so it is shared
	 * with the revision [withSpanIndex] produces.
	 */
	internal val text: DocumentText,
) {
	private constructor(lineList: LineList, spanIndex: SpanIndex) : this(lineList, spanIndex, DocumentText(lineList))

	/**
	 * Builds a snapshot by hand. [richSpans] need not fit [lines]:
	 * [TextEditorState.setDocument] clamps them onto the document on load.
	 */
	constructor(lines: List<AnnotatedString>, richSpans: Set<RichSpan> = emptySet()) : this(
		lineList = LineList.of(lines),
		spanIndex = SpanIndex.of(lines.size, richSpans),
	)

	/** The document as one [AnnotatedString] per line, in order. */
	val lines: List<AnnotatedString> get() = lineList

	/**
	 * Every rich span in the document, its ranges addressing [lines]. Built from the
	 * per-line index on first read; a reader that wants one line's spans reads
	 * [spansOn] instead.
	 */
	val richSpans: Set<RichSpan> get() = spanIndex.all

	/** Every span covering [line]: those on it, and those crossing it from another line. */
	internal fun spansOn(line: Int): List<RichSpan> = spanIndex.spansOn(line)

	/**
	 * Flat character index at which [line] starts, counting one newline between
	 * lines; `line` may be the line count, for the position just past the document's
	 * final newline slot, so `lineStart(n + 1) - 1` is the end of line `n`.
	 */
	internal fun lineStart(line: Int): Int = lineList.charStart(line)

	/** The line holding flat character [index], which must be within the document. */
	internal fun lineOfCharacter(index: Int): Int = lineList.lineOf(index)

	/** The flat length of the document, newlines between lines counted. */
	internal val textLength: Int get() = lineList.textLength

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
	internal val chars: CharSequence by lazy(LazyThreadSafetyMode.PUBLICATION) { DocumentChars(lineList) }

	/**
	 * Keeps the span index as it is: the edit that replaced the lines re-anchors the
	 * spans on the lines it touched and splices the index to match, in the same
	 * transaction. [splice], when the caller knows it, says which lines changed;
	 * otherwise the lines are compared by identity.
	 */
	internal fun withLines(lines: List<AnnotatedString>, splice: LineSplice? = null): DocumentSnapshot {
		val list = LineList.of(lines)
		return DocumentSnapshot(
			lineList = list,
			spanIndex = spanIndex,
			text = text.next(lineList, list, splice),
		)
	}

	internal fun withSpanIndex(spanIndex: SpanIndex): DocumentSnapshot =
		if (spanIndex === this.spanIndex) this else DocumentSnapshot(lineList, spanIndex, text)

	/** This revision with its spans replaced wholesale by [richSpans]. */
	internal fun withRichSpans(richSpans: Set<RichSpan>): DocumentSnapshot =
		withSpanIndex(SpanIndex.of(lineList.size, richSpans))
}

/**
 * How a revision's lines differ from the one before: its first [unchangedBefore] lines
 * and last [unchangedAfter] lines are the same objects as the previous revision's.
 */
internal class LineSplice(val unchangedBefore: Int, val unchangedAfter: Int)

/**
 * A revision whose [text] was built, with its line starts, and how many lines at each
 * end the revision holding this shares with it. Holds no lines, so an unread revision
 * keeps one old text of each kind alive at most, never a chain of them or their lines.
 */
internal class TextBase<T : CharSequence>(
	val text: T,
	/** Each line's start in [text], then the index past the final line's break slot. */
	val starts: IntArray,
	val unchangedBefore: Int,
	val unchangedAfter: Int,
) {
	constructor(text: T, lines: LineList, unchangedBefore: Int, unchangedAfter: Int) : this(
		text = text,
		starts = IntArray(lines.size + 1) { lines.charStart(it) },
		unchangedBefore = unchangedBefore,
		unchangedAfter = unchangedAfter,
	)

	val lineCount: Int get() = starts.size - 1

	/** This base for a revision that further kept [step]'s ends, or null when it shares no line with it. */
	fun narrowed(step: LineSplice): TextBase<T>? {
		val before = minOf(unchangedBefore, step.unchangedBefore)
		val after = minOf(unchangedAfter, step.unchangedAfter)
		return if (before == 0 && after == 0) null else TextBase(text, starts, before, after)
	}
}

/**
 * The whole text of one revision, built on demand and spliced from a base when it can
 * be. A base is dropped once its text is built, so a read revision holds no old text.
 */
internal class DocumentText(
	private val lines: LineList,
	private var plainBase: TextBase<String>? = null,
	private var annotatedBase: TextBase<AnnotatedString>? = null,
) {
	val annotated: Lazy<AnnotatedString> = lazy(LazyThreadSafetyMode.PUBLICATION) {
		val base = annotatedBase
		val capacity = base?.text?.length ?: (lines.textLength + 1)
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
			base == null -> buildString(lines.textLength + 1) {
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
	fun next(oldLines: LineList, newLines: LineList, splice: LineSplice?): DocumentText {
		val plainBuilt = plain.isInitialized()
		val annotatedBuilt = annotated.isInitialized()
		val oldPlainBase = plainBase
		val oldAnnotatedBase = annotatedBase
		if (!plainBuilt && !annotatedBuilt && oldPlainBase == null && oldAnnotatedBase == null) {
			return DocumentText(newLines)
		}
		val step = splice ?: diff(oldLines, newLines)
		val shares = step.unchangedBefore > 0 || step.unchangedAfter > 0
		fun <T : CharSequence> baseOf(text: T) = TextBase(text, oldLines, step.unchangedBefore, step.unchangedAfter)
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

	/** The lines two revisions share at each end, compared by identity. */
	private fun diff(old: List<AnnotatedString>, new: List<AnnotatedString>): LineSplice {
		val shorter = minOf(old.size, new.size)
		var before = 0
		while (before < shorter && old[before] === new[before]) before++
		var after = 0
		while (after < shorter - before && old[old.size - 1 - after] === new[new.size - 1 - after]) after++
		return LineSplice(before, after)
	}
}

/** [DocumentSnapshot.chars]: the lines read in place, with a line break between each two. */
internal class DocumentChars(private val lines: LineList) : CharSequence {
	override val length: Int get() = lines.textLength

	override fun get(index: Int): Char {
		if (index < 0 || index >= length) throw IndexOutOfBoundsException("index: $index, length: $length")
		val line = lines.lineOf(index)
		val char = index - lines.charStart(line)
		val text = lines[line].text
		return if (char < text.length) text[char] else '\n'
	}

	override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
		if (startIndex < 0 || endIndex > length || startIndex > endIndex) {
			throw IndexOutOfBoundsException("start: $startIndex, end: $endIndex, length: $length")
		}
		return buildString(endIndex - startIndex) {
			var index = startIndex
			var line = if (startIndex < endIndex) lines.lineOf(startIndex) else 0
			while (index < endIndex) {
				val text = lines[line].text
				val char = index - lines.charStart(line)
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
}
