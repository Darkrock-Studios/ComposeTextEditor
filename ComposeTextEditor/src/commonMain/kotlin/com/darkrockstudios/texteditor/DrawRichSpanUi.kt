package com.darkrockstudios.texteditor

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.util.fastForEach
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Which pass of rich-span drawing to perform. [Background] runs before the
 * line's `drawText` so opaque fills don't obscure characters; [Foreground]
 * runs after so glyphs/borders/underlines overlay the text. Most styles only
 * implement one phase — the other is a no-op default.
 */
internal enum class RichSpanDrawPhase { Background, Foreground }

internal fun DrawScope.drawRichSpans(
	lineWrap: LineWrap,
	state: TextEditorState,
	phase: RichSpanDrawPhase = RichSpanDrawPhase.Foreground,
) {
	val spans = lineWrap.richSpans
	if (spans.isEmpty()) return

	val textLayoutResult = lineWrap.textLayoutResult

	// Every value below describes the wrap, not the span, so it is computed once
	// per line instead of once per span. Draw runs these on each visible line each
	// frame, and the character-index conversions are the expensive part.
	val wrapVisibleStart = textLayoutResult.getLineStart(lineWrap.virtualLineIndex)
	val wrapVisibleEnd = textLayoutResult.getLineEnd(lineWrap.virtualLineIndex, visibleEnd = true)

	val lineStart = CharLineOffset(line = lineWrap.line, char = lineWrap.wrapStartsAtIndex)
	val lineEnd = CharLineOffset(
		line = lineWrap.line,
		char = lineWrap.wrapStartsAtIndex + (wrapVisibleEnd - wrapVisibleStart)
	)
	val lineStartAbsChar = lineStart.toCharacterIndex(state)
	val lineEndAbsChar = lineEnd.toCharacterIndex(state)
	val translateY = lineWrap.offset.y - state.scrollState.value

	spans.fastForEach { richSpan ->
		val spanStartAbsChar = richSpan.range.start.toCharacterIndex(state)
		val spanEndAbsChar = richSpan.range.end.toCharacterIndex(state)

		// If this wrapped segment intersects with our span
		if (spanStartAbsChar <= lineEndAbsChar && spanEndAbsChar >= lineStartAbsChar) {
			// Calculate position adjustment based on whether this is a wrapped line
			// Calculate the local range within this wrapped segment
			val localStart = if (spanStartAbsChar <= lineStartAbsChar) {
				wrapVisibleStart
			} else {
				(spanStartAbsChar - lineStartAbsChar) + wrapVisibleStart
			}

			val localEnd = if (spanEndAbsChar >= lineEndAbsChar) {
				wrapVisibleEnd
			} else {
				((spanEndAbsChar - lineStartAbsChar) + wrapVisibleStart)
					.coerceAtMost(wrapVisibleEnd)
			}

			val localRange = androidx.compose.ui.text.TextRange(
				start = localStart,
				end = localEnd
			)

			with(richSpan.style) {
				translate(top = translateY) {
					when (phase) {
						RichSpanDrawPhase.Background -> drawBackground(
							layoutResult = textLayoutResult,
							lineWrap = lineWrap,
							textRange = localRange,
							state = state,
						)

						RichSpanDrawPhase.Foreground -> drawCustomStyle(
							layoutResult = textLayoutResult,
							lineWrap = lineWrap,
							textRange = localRange,
							state = state,
						)
					}
				}
			}
		}
	}
}