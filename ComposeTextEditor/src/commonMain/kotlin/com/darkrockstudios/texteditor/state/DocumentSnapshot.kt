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
class DocumentSnapshot internal constructor(
	/** The document as one [AnnotatedString] per line, in order. */
	val lines: List<AnnotatedString>,
	/** Every rich span in the document, its ranges addressing [lines]. */
	val richSpans: Set<RichSpan>,
) {
	/** The whole document as a single [AnnotatedString], lines joined with newlines. */
	fun getAllText(): AnnotatedString = buildAnnotatedString {
		lines.forEachIndexed { index, line ->
			append(line)
			if (index < lines.lastIndex) append('\n')
		}
	}

	internal fun withLines(lines: List<AnnotatedString>) = DocumentSnapshot(lines, richSpans)

	internal fun withRichSpans(richSpans: Set<RichSpan>) = DocumentSnapshot(lines, richSpans)
}
