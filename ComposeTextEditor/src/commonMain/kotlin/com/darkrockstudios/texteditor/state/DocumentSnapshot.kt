package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.darkrockstudios.texteditor.richstyle.RichSpan

/**
 * A document's text and rich spans as they stood after one edit.
 *
 * Both halves are immutable and come from the same revision, so a serializer can walk
 * them without an edit changing either underneath it and without pairing text from one
 * revision with span line indices from another. Take one with
 * [TextEditorState.snapshot]; it is safe to hold and to read from any thread, and it
 * does not reflect later edits.
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
	 * [spansByStartLine], and shared with the revision [withRichSpans] produces
	 * because a span-only edit leaves every line length untouched.
	 */
	private val lineStarts: Lazy<IntArray>,
	/**
	 * Backs [getAllText]. Depends only on the text, so like [lineStarts] it is
	 * shared with the revision [withRichSpans] produces.
	 */
	private val allText: Lazy<AnnotatedString>,
) {
	internal constructor(lines: List<AnnotatedString>, richSpans: Set<RichSpan>) : this(
		lines = lines,
		richSpans = richSpans,
		spansByLine = spansByLineOf(richSpans),
		lineStarts = lineStartsOf(lines),
		allText = allTextOf(lines),
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
	 * Built on first read and reused until a revision changes the text; semantics
	 * rebuilds read this per frame, so an unmemoized concatenation is O(document)
	 * each time.
	 */
	fun getAllText(): AnnotatedString = allText.value

	/**
	 * Keeps the span index: the ranges are untouched by a text edit, so the lines they
	 * start on are the same ones they started on before.
	 */
	internal fun withLines(lines: List<AnnotatedString>) =
		DocumentSnapshot(lines, richSpans, spansByLine, lineStartsOf(lines), allTextOf(lines))

	internal fun withRichSpans(richSpans: Set<RichSpan>) = DocumentSnapshot(
		lines = lines,
		richSpans = richSpans,
		spansByLine = spansByLineOf(richSpans),
		lineStarts = lineStarts,
		allText = allText,
	)
}

private fun allTextOf(lines: List<AnnotatedString>): Lazy<AnnotatedString> =
	lazy(LazyThreadSafetyMode.PUBLICATION) {
		buildAnnotatedString {
			lines.forEachIndexed { index, line ->
				append(line)
				if (index < lines.lastIndex) append('\n')
			}
		}
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
