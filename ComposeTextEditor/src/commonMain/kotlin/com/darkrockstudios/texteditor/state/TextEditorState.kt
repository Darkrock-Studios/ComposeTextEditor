package com.darkrockstudios.texteditor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.CodeFenceBoundary
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.splitAnnotatedString
import com.darkrockstudios.texteditor.annotatedstring.subSequence
import com.darkrockstudios.texteditor.annotatedstring.toAnnotatedString
import com.darkrockstudios.texteditor.coerceInto
import com.darkrockstudios.texteditor.cursor.CursorMetrics
import com.darkrockstudios.texteditor.cursor.getWrappedLineIndex
import com.darkrockstudios.texteditor.effectiveHeight
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.applyLineBlock
import com.darkrockstudios.texteditor.richstyle.demoteLineBlock
import com.darkrockstudios.texteditor.richstyle.detectLineBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.math.min

/**
 * The single source of truth for a [com.darkrockstudios.texteditor.TextEditor]:
 * the document text, cursor, selection, rich spans, scroll position, and undo
 * history. Create one with
 * [rememberTextEditorState] and hoist it so you can drive the editor from outside.
 *
 * Content lives in [textLines] (one [AnnotatedString] per line); replace it wholesale
 * with [setText] or read it back with [getAllText]. Edit it through the cursor-aware
 * operations ([insertStringAtCursor], [backspaceAtCursor], …) or by range
 * ([replace], [delete]). Character styling goes through [addStyleSpan]/[removeStyleSpan],
 * while block decorations (lists, blockquotes, code fences, highlights) go through the
 * `RichSpan` API ([addRichSpan]/[removeRichSpan]). [undo]/[redo] walk the edit history,
 * gated by [canUndo]/[canRedo].
 *
 * Related concerns are delegated to focused sub-objects exposed as properties:
 * [cursor] (caret position and movement), [selector] (selection), [scrollManager]
 * (scrolling and visible range), and [richSpanManager] (rich-span book-keeping).
 * Observe changes reactively via [cursorDataFlow] and [editOperations].
 *
 * Coordinates are [CharLineOffset]s and [TextEditorRange]s; convert to and from flat
 * character indices with [getCharacterIndex]/[getOffsetAtCharacter].
 */
class TextEditorState(
	val scope: CoroutineScope,
	measurer: TextMeasurer,
	initialText: AnnotatedString? = null
) {
	var textMeasurer: TextMeasurer = measurer
		internal set(value) {
			field = value
			updateBookKeeping()
		}

	var textStyle: TextStyle = TextStyle.Default
		internal set(value) {
			if (field != value) {
				field = value
				updateBookKeeping()
			}
		}

	/**
	 * Theming colors for line-block gutter markers, mirrored from
	 * [TextEditorStyle][com.darkrockstudios.texteditor.TextEditorStyle] by
	 * `BasicTextEditor`. `Color.Unspecified` means "use the
	 * span's hardcoded fallback" so a state created without a host editor (e.g.
	 * tests) still renders sensibly.
	 */
	var bulletColor: Color by mutableStateOf(Color.Unspecified)
		internal set
	var blockquoteBarColor: Color by mutableStateOf(Color.Unspecified)
		internal set
	var blockquoteBackgroundColor: Color by mutableStateOf(Color.Unspecified)
		internal set
	var orderedListMarkerColor: Color by mutableStateOf(Color.Unspecified)
		internal set
	var codeFenceBackgroundColor: Color by mutableStateOf(Color.Unspecified)
		internal set
	var codeFenceBorderColor: Color by mutableStateOf(Color.Unspecified)
		internal set

	internal val _textLines = mutableListOf<AnnotatedString>()

	/**
	 * The document content as one [AnnotatedString] per line, in order. Read-only;
	 * mutate through the edit operations or replace wholesale with [setText].
	 */
	val textLines: List<AnnotatedString> get() = _textLines

	/** The caret: its [CharLineOffset] position, movement, and active typing style. */
	val cursor = TextEditorCursorState(this)

	/** The caret's current [CharLineOffset]; shorthand for [cursor]'s position. */
	val cursorPosition: CharLineOffset
		get() = cursor.position

	/** Whether the editor currently holds keyboard focus. */
	var isFocused by mutableStateOf(false)

	/**
	 * The current IME composing region (for autocomplete preview).
	 * When non-null, this text should be rendered with an underline.
	 * This is set by the Android InputConnection during text composition.
	 */
	var composingRange: TextEditorRange? by mutableStateOf(null)
		internal set

	/**
	 * Last calculated cursor pixel metrics.
	 * Updated during rendering and used by IME for cursor anchor info.
	 */
	var lastCursorMetrics: CursorMetrics? = null
		internal set

	/**
	 * Layout coordinates of the editor's drawing canvas, captured via
	 * `onGloballyPositioned`. Used by the desktop IME to translate the cursor's
	 * canvas-local [lastCursorMetrics] into root coordinates for placing the
	 * input-method candidate window.
	 */
	var canvasLayoutCoordinates: LayoutCoordinates? = null
		internal set

	private var _lineOffsets by mutableStateOf(emptyList<LineWrap>())

	/**
	 * The laid-out [LineWrap]s for the document: each visual (wrapped) line with its
	 * pixel offset, text-layout result, and resolved rich spans. Recomputed on every
	 * edit, style, or viewport change.
	 */
	val lineOffsets: List<LineWrap> get() = _lineOffsets

	/**
	 * Emits a [CursorData] snapshot (active styles, position, selection) whenever the
	 * caret moves, the typing style changes, or the selection changes. Collect this to
	 * keep a toolbar or status display in sync with the editor.
	 */
	val cursorDataFlow: Flow<CursorData>
		get() {
			return cursor.stylesFlow
				.combine(cursor.positionFlow) { styles, position ->
					Pair(styles, position)
				}
				.combine(selector.selectionRangeFlow) { (styles, position), selectionRange ->
					CursorData(
						styles = styles,
						position = position,
						selection = selectionRange,
					)
				}
		}

	private var _canUndo by mutableStateOf(false)
	private var _canRedo by mutableStateOf(false)

	/** Whether [undo] currently has an edit to revert. */
	val canUndo: Boolean get() = _canUndo

	/** Whether [redo] currently has a reverted edit to re-apply. */
	val canRedo: Boolean get() = _canRedo

	internal var viewportSize by mutableStateOf(Size(1f, 1f))

	/**
	 * Density used by [BlockSpanStyle] spans to convert their intrinsic size to
	 * pixels. Set by the host composable from [androidx.compose.ui.platform.LocalDensity].
	 */
	internal var density: Density? = null
		set(value) {
			if (field != value) {
				field = value
				updateBookKeeping()
			}
		}

	/** Scrolling, content height, and the currently visible line range. */
	val scrollManager = TextEditorScrollManager(
		scope = scope,
		scrollState = TextEditorScrollState(0),
		getLines = { textLines },
		getViewportSize = { viewportSize },
		getCursorPosition = { cursorPosition },
		getLineOffsets = { _lineOffsets },
	)

	/** The text selection: its [TextEditorRange], gestures, and selected-content queries. */
	val selector = TextEditorSelectionManager(this)
	internal val editManager = TextEditManager(this)

	/** Book-keeping for the document's [RichSpan] block decorations (lists, quotes, code fences, highlights). */
	val richSpanManager = RichSpanManager(this)

	// In-editor rich-span clipboard. The system clipboard only carries the
	// AnnotatedString (text + character-level spans), so line-anchored rich spans
	// like ordered/bullet lists would be lost on a copy→paste round-trip. We
	// remember them here keyed by the copied text and re-apply on paste of the
	// same text. Null until the first copy/cut.
	private var copiedRichSpans: CopiedRichSpans? = null

	// Exempts the next single edit from clearing [copiedRichSpans], so a cut's
	// delete or a paste's insert/replace doesn't wipe the buffer it depends on.
	private var richSpanBufferSurvivesNextEdit = false

	/**
	 * Platform-specific extensions for TextEditorState.
	 * On Android: Contains IME-related functionality (cursor anchor monitoring, etc.)
	 * On Desktop/WASM: Empty class (no-op)
	 */
	val platformExtensions = PlatformTextEditorExtensions(this)

	/** The underlying scroll position, surfaced from [scrollManager]. */
	val scrollState get() = scrollManager.scrollState

	/**
	 * The [CharLineOffset] currently at the top of the viewport. Compose-observable:
	 * composables reading this recompose when the user scrolls. Useful for driving
	 * synchronized scrolling between two editors.
	 */
	val firstVisibleOffset: CharLineOffset get() = scrollManager.firstVisibleOffset

	/**
	 * Emits each [TextEditOperation] as it is applied (insert, delete, replace).
	 * Collect this to observe the edit stream; decoration-only changes are excluded.
	 */
	val editOperations = editManager.editOperations

	/**
	 * Replaces the entire document with [text], clearing rich spans and resetting
	 * book-keeping. To edit existing content instead, use [replace] or the cursor
	 * operations.
	 */
	fun setText(text: String) {
		_textLines.clear()
		richSpanManager.clear()
		_textLines.addAll(text.split("\n").map { it.toAnnotatedString() })
		updateBookKeeping()
	}

	/**
	 * Replaces the entire document with [text], preserving its character-level spans
	 * while clearing rich spans and resetting book-keeping. To edit existing content
	 * instead, use [replace] or the cursor operations.
	 */
	fun setText(text: AnnotatedString) {
		_textLines.clear()
		richSpanManager.clear()
		_textLines.addAll(text.splitAnnotatedString())
		updateBookKeeping()
	}

	/** Sets [isFocused]; losing focus also clears any pending IME composing region. */
	fun updateFocus(focused: Boolean) {
		isFocused = focused
		// Clear composing state when focus is lost
		if (!focused) {
			composingRange = null
		}
	}

	/**
	 * Updates the IME composing region.
	 * Called by the Android InputConnection when composing text changes.
	 * @param startIndex Character index of composing start, or -1 to clear
	 * @param endIndex Character index of composing end, or -1 to clear
	 */
	internal fun updateComposingRange(startIndex: Int, endIndex: Int) {
		composingRange = if (startIndex >= 0 && endIndex > startIndex) {
			val startOffset = getOffsetAtCharacter(startIndex)
			val endOffset = getOffsetAtCharacter(endIndex)
			TextEditorRange(startOffset, endOffset)
		} else {
			null
		}
	}

	/**
	 * Clears the IME composing region.
	 */
	internal fun clearComposingRange() {
		composingRange = null
	}

	/**
	 * Inserts a line break at the cursor, splitting the current line. On an empty
	 * list or blockquote item this instead exits the block (dropping its gutter
	 * marker) and consumes the keystroke.
	 */
	fun insertNewlineAtCursor() {
		val originalLine = cursorPosition.line
		val activeBlock = detectLineBlock(originalLine)
		val lineText = textLines.getOrNull(originalLine)?.text ?: ""

		// Enter on an empty bullet/quote item exits the block — drop the gutter
		// marker and indent, eat the keystroke. Matches Notion / Google Docs and
		// gives a discoverable way to leave a list or quote without backspacing.
		if (lineText.isEmpty() && activeBlock != null) {
			demoteLineBlock(originalLine, activeBlock)
			return
		}

		val operation = TextEditOperation.Insert(
			position = cursorPosition,
			text = cursor.applyCursorStyle("\n"),
			cursorBefore = cursorPosition,
			cursorAfter = CharLineOffset(originalLine + 1, 0)
		)
		editManager.applyOperation(operation)

		// RichSpanManager's newline handling only keeps the span on one side when
		// the cursor was at a span boundary, so apply to both lines so both halves
		// of the split keep the gutter marker. applyLineBlock is idempotent.
		activeBlock?.let {
			applyLineBlock(originalLine, it)
			applyLineBlock(originalLine + 1, it)
		}
	}

	/**
	 * Deletes the character before the cursor, merging with the previous line when at
	 * column 0. At the start of a list or blockquote item it first demotes the block
	 * (removing its gutter marker) unless the previous line shares the same block.
	 */
	fun backspaceAtCursor() {
		// Backspace at column 0 of a line-block (blockquote, bullet) first demotes
		// (removes the gutter marker and indent); a follow-up backspace then merges
		// with the previous line. Matches Notion / Google Docs — discoverable way
		// to exit a block prefix without nuking the line content.
		//
		// Exception: if the previous line is the SAME line-block, fall through to
		// merge directly. Otherwise demote-first turns "join two adjacent items"
		// into a two-keystroke operation, which feels worse than Docs/Notion.
		if (cursorPosition.char == 0) {
			val activeBlock = detectLineBlock(cursorPosition.line)
			if (activeBlock != null) {
				val prevBlock = detectLineBlock(cursorPosition.line - 1)
				if (prevBlock != activeBlock) {
					demoteLineBlock(cursorPosition.line, activeBlock)
					return
				}
			}
		}
		if (cursorPosition.char > 0) {
			val deleteRange = TextEditorRange(
				CharLineOffset(cursorPosition.line, cursorPosition.char - 1),
				cursorPosition
			)

			val operation = TextEditOperation.Delete(
				range = deleteRange,
				cursorBefore = cursorPosition,
				cursorAfter = CharLineOffset(cursorPosition.line, cursorPosition.char - 1)
			)
			editManager.applyOperation(operation)
		} else if (cursorPosition.line > 0) {
			val previousLineLength = textLines[cursorPosition.line - 1].length
			val deleteRange = TextEditorRange(
				CharLineOffset(cursorPosition.line - 1, previousLineLength),
				cursorPosition
			)

			val operation = TextEditOperation.Delete(
				range = deleteRange,
				cursorBefore = cursorPosition,
				cursorAfter = CharLineOffset(cursorPosition.line - 1, previousLineLength)
			)
			editManager.applyOperation(operation)
		}
	}

	/**
	 * Deletes the character after the cursor, merging the next line into the current
	 * one when at end of line (forward delete).
	 */
	fun deleteAtCursor() {
		if (cursorPosition.char < textLines[cursorPosition.line].length) {
			val deleteRange = TextEditorRange(
				cursorPosition,
				CharLineOffset(cursorPosition.line, cursorPosition.char + 1)
			)

			val operation = TextEditOperation.Delete(
				range = deleteRange,
				cursorBefore = cursorPosition,
				cursorAfter = cursorPosition
			)
			editManager.applyOperation(operation)
		} else if (cursorPosition.line < textLines.size - 1) {
			val deleteRange = TextEditorRange(
				cursorPosition,
				CharLineOffset(cursorPosition.line + 1, 0)
			)

			val operation = TextEditOperation.Delete(
				range = deleteRange,
				cursorBefore = cursorPosition,
				cursorAfter = cursorPosition
			)
			editManager.applyOperation(operation)
		}
	}

	/** Inserts a single [char] at the cursor, applying the active typing style. */
	fun insertCharacterAtCursor(char: Char) {
		val text = cursor.applyCursorStyle(char.toString())
		val operation = TextEditOperation.Insert(
			position = cursorPosition,
			text = text,
			cursorBefore = cursorPosition,
			cursorAfter = CharLineOffset(cursorPosition.line, cursorPosition.char + 1)
		)
		editManager.applyOperation(operation)
	}

	/** Inserts plain [string] at the cursor, applying the active typing style. */
	fun insertStringAtCursor(string: String) = insertStringAtCursor(string.toAnnotatedString())

	/**
	 * Inserts [text] at the cursor, preserving its character-level spans and applying
	 * the active typing style. Advances the cursor past the inserted text, accounting
	 * for any embedded line breaks.
	 */
	fun insertStringAtCursor(text: AnnotatedString) {
		val styledText = cursor.applyCursorStyle(text)

		// Calculate cursor position after insertion, accounting for newlines
		val textString = text.text
		val lastNewlineIndex = textString.lastIndexOf('\n')
		val cursorAfter = if (lastNewlineIndex >= 0) {
			val newlineCount = textString.count { it == '\n' }
			val charsAfterLastNewline = textString.length - lastNewlineIndex - 1
			CharLineOffset(cursorPosition.line + newlineCount, charsAfterLastNewline)
		} else {
			CharLineOffset(cursorPosition.line, cursorPosition.char + text.length)
		}

		val operation = TextEditOperation.Insert(
			position = cursorPosition,
			text = styledText,
			cursorBefore = cursorPosition,
			cursorAfter = cursorAfter
		)
		editManager.applyOperation(operation)
	}

	/** Deletes the text covered by [range], leaving the cursor at the range start. */
	fun delete(range: TextEditorRange) {
		val operation = TextEditOperation.Delete(
			range = range,
			cursorBefore = cursorPosition,
			cursorAfter = range.start
		)
		editManager.applyOperation(operation)
	}

	/**
	 * Replaces the text in [range] with plain [newText].
	 * @param inheritStyle when true, the inserted text adopts the style of the
	 * replaced text rather than carrying none.
	 */
	fun replace(range: TextEditorRange, newText: String, inheritStyle: Boolean = false) =
		replace(range, newText.toAnnotatedString(), inheritStyle)

	/**
	 * Replaces the text in [range] with [newText], preserving the latter's
	 * character-level spans and moving the cursor to the end of the inserted text.
	 * @param inheritStyle when true, the inserted text adopts the style of the
	 * replaced text rather than only its own spans.
	 */
	fun replace(range: TextEditorRange, newText: AnnotatedString, inheritStyle: Boolean = false) {
		val operation = TextEditOperation.Replace(
			range = range,
			newText = newText,
			oldText = buildAnnotatedString {
				if (range.isSingleLine()) {
					// Single line - get text and spans from the range
					val line = textLines[range.start.line]
					append(line.subSequence(range.start.char, range.end.char))
				} else {
					// Multi-line - preserve text and spans across lines
					append(textLines[range.start.line].subSequence(range.start.char))
					append("\n")

					for (line in (range.start.line + 1) until range.end.line) {
						append(textLines[line])
						append("\n")
					}

					append(textLines[range.end.line].subSequence(0, range.end.char))
				}
			},
			cursorBefore = cursorPosition,
			cursorAfter = when {
				newText.contains('\n') -> {
					val lines = newText.split('\n')
					CharLineOffset(
						range.start.line + lines.size - 1,
						if (lines.size > 1) lines.last().length else range.start.char + newText.length
					)
				}

				else -> CharLineOffset(
					range.start.line,
					range.start.char + newText.length
				)
			},
			inheritStyle = inheritStyle,
		)

		editManager.applyOperation(operation)
	}

	internal fun updateLine(index: Int, text: String) =
		updateLine(index, text.toAnnotatedString())

	internal fun updateLine(index: Int, text: AnnotatedString) {
		_textLines[index] = text
		updateBookKeeping(index..index)
	}

	/**
	 * Rewrites every line in place by passing each line index and content through
	 * [processor] and storing its result, then relays out the document.
	 */
	fun processLines(processor: (index: Int, line: AnnotatedString) -> AnnotatedString) {
		for (i in textLines.indices) {
			val line = textLines[i]
			_textLines[i] = processor(i, line)
		}
		updateBookKeeping()
	}

	internal fun removeLines(startIndex: Int, count: Int) {
		// If there are no lines, or we're trying to remove more lines than exist, abort
		if (_textLines.isEmpty() || startIndex >= _textLines.size) {
			return
		}

		// Ensure we don't remove more lines than available
		val safeCount = minOf(count, _textLines.size - startIndex)

		// Always keep at least one empty line
		if (_textLines.size <= safeCount) {
			_textLines.clear()
			_textLines.add(AnnotatedString(""))
		} else {
			repeat(safeCount) {
				_textLines.removeAt(startIndex)
			}
		}

		updateBookKeeping()
	}

	internal fun insertLine(index: Int, text: String) = insertLine(index, text.toAnnotatedString())
	internal fun insertLine(index: Int, text: AnnotatedString) {
		_textLines.add(index, text)
		updateBookKeeping()
	}

	/** True when the document holds a single empty line. */
	fun isEmpty(): Boolean = textLines.size == 1 && textLines[0].isEmpty()

	/** Reverts the most recent edit; no-op when [canUndo] is false. */
	fun undo() {
		editManager.undo()
	}

	/** Re-applies the most recently undone edit; no-op when [canRedo] is false. */
	fun redo() {
		editManager.redo()
	}

	/**
	 * Returns the index into [lineOffsets] of the wrapped (visual) line containing
	 * [position], or -1 if none matches.
	 */
	fun getWrappedLineIndex(position: CharLineOffset): Int {
		return _lineOffsets.indexOfLast { lineOffset ->
			lineOffset.line == position.line && lineOffset.wrapStartsAtIndex <= position.char
		}
	}

	/** Returns the [LineWrap] (visual line) that contains [position]. */
	fun getWrappedLine(position: CharLineOffset): LineWrap {
		return _lineOffsets.last { lineOffset ->
			lineOffset.line == position.line && lineOffset.wrapStartsAtIndex <= position.char
		}
	}

	/** Returns the [LineWrap] at visual-line index [vLineIndex] in [lineOffsets]. */
	fun getWrappedLine(vLineIndex: Int): LineWrap {
		return _lineOffsets[vLineIndex]
	}

	/** Records the editor's new viewport [size] and re-wraps the document to fit. */
	fun onViewportSizeChange(size: Size) {
		viewportSize = size
		updateBookKeeping()
	}

	/**
	 * Returns the [CursorMetrics] (pixel position and line height) for the caret at
	 * [CharLineOffset] [position], accounting for the current scroll offset.
	 */
	fun getPositionForOffset(position: CharLineOffset): CursorMetrics {
		val (_, charIndex) = position

		val currentWrappedLineIndex = lineOffsets.getWrappedLineIndex(position)
		val currentWrappedLine = lineOffsets[currentWrappedLineIndex]

		val layout = currentWrappedLine.textLayoutResult

		val cursorX = layout.getHorizontalPosition(charIndex, usePrimaryDirection = true)
		val cursorY = currentWrappedLine.offset.y - scrollState.value

		val lineHeight = currentWrappedLine.effectiveHeight

		return CursorMetrics(
			position = Offset(cursorX, cursorY),
			height = lineHeight
		)
	}

	/**
	 * Maps a pixel [Offset] within the editor (e.g. a tap location) to the nearest
	 * [CharLineOffset], accounting for scroll. Clamps to the end of the last line when
	 * the point falls below all content.
	 */
	fun getOffsetAtPosition(offset: Offset): CharLineOffset {
		if (_lineOffsets.isEmpty()) return CharLineOffset(0, 0)

		// Add scroll offset to the input y coordinate
		val adjustedOffset = offset.copy(y = offset.y + scrollState.value)

		var curRealLine: LineWrap = _lineOffsets[0]

		// Find the line that contains the offset
		for (lineWrap in _lineOffsets) {
			if (lineWrap.line != curRealLine.line) {
				curRealLine = lineWrap
			}
			val textLayoutResult = lineWrap.textLayoutResult

			// Full paragraph height — using a single sub-line's height misses clicks past the first wrap.
			val paragraphHeight = lineWrap.blockHeight ?: textLayoutResult.size.height.toFloat()

			val relativeOffset = adjustedOffset - lineWrap.offset
			if (adjustedOffset.y in curRealLine.offset.y..(curRealLine.offset.y + paragraphHeight)) {
				val charPos = textLayoutResult.multiParagraph.getOffsetForPosition(relativeOffset)
				return CharLineOffset(lineWrap.line, min(charPos, textLines[lineWrap.line].length))
			}
		}

		// If we're below all lines, return position at end of last line
		val lastLine = textLines.lastIndex
		return CharLineOffset(lastLine, textLines[lastLine].length)
	}

	/**
	 * Converts a flat character [index] into the document to its [CharLineOffset].
	 * The inverse of [getCharacterIndex]; clamps to the document end when out of range.
	 */
	fun getOffsetAtCharacter(index: Int): CharLineOffset {
		var remainingChars = index

		for (lineIndex in textLines.indices) {
			val lineLength = textLines[lineIndex].length + 1  // +1 for newline
			if (remainingChars < lineLength) {
				return CharLineOffset(lineIndex, remainingChars)
			}
			remainingChars -= lineLength
		}

		return CharLineOffset(
			textLines.lastIndex,
			textLines.last().length
		)
	}

	/**
	 * Converts a [CharLineOffset] to its flat character index into the document.
	 * The inverse of [getOffsetAtCharacter]; an out-of-bounds [offset] is clamped
	 * into the document first.
	 */
	fun getCharacterIndex(offset: CharLineOffset): Int {
		if (textLines.isEmpty()) return 0
		// Belt-and-braces: applyOperation already clears stale selections, but
		// any future flow-emit-before-coerce path would crash here without this.
		val safe = offset.coerceInto(textLines)
		if (safe != offset) {
			println("TextEditor warning: getCharacterIndex clamped $offset to $safe (textLines.size=${textLines.size})")
		}

		var totalChars = 0
		for (lineIndex in 0 until safe.line) {
			totalChars += textLines[lineIndex].length + 1
		}
		return totalChars + safe.char
	}

	fun CharLineOffset.toCharacterIndex(): Int {
		var totalChars = 0
		for (lineIndex in 0 until line) {
			totalChars += textLines[lineIndex].length + 1  // +1 for newline
		}
		return totalChars + char
	}

	// Convert character index to CharLineOffset
	fun Int.toCharLineOffset(): CharLineOffset {
		var remainingChars = this
		for (lineIndex in textLines.indices) {
			val lineLength = textLines[lineIndex].length + 1  // +1 for newline
			if (remainingChars < lineLength) {
				return CharLineOffset(lineIndex, remainingChars)
			}
			remainingChars -= lineLength
		}
		return CharLineOffset(
			textLines.lastIndex,
			textLines.last().length
		)
	}

	fun wrapStartToCharacterIndex(lineWrap: LineWrap): Int {
		// First get the physical line start offset
		val physicalLineStartOffset = getLineStartOffset(lineWrap.line)
		// Add the local offset from the LineWrap
		return physicalLineStartOffset + lineWrap.wrapStartsAtIndex
	}

	fun getLineStartOffset(lineIndex: Int): Int {
		require(lineIndex >= 0) { "Line index must be non-negative" }
		require(lineIndex < textLines.size) { "Line index $lineIndex out of bounds for ${textLines.size} lines" }

		var offset = 0
		// Sum up lengths of all previous lines
		for (i in 0 until lineIndex) {
			offset += textLines[i].length
			// Add 1 for the newline character at the end of each line
			// except for the last line if it doesn't end with a newline
			offset += 1
		}
		return offset
	}

	internal fun updateBookKeeping(affectedLines: IntRange? = null) {
		// Defer until the viewport has a real size; the 1×1 sentinel forces character-wide wraps.
		if (viewportSize.width <= 1f || viewportSize.height <= 1f) return

		val offsets = mutableListOf<LineWrap>()
		var yOffset = 0f

		// Pre-collect ordered-list line indices so we can number each item by its
		// position within a contiguous run without re-scanning the span set per line.
		val orderedListLines = richSpanManager.getAllRichSpans()
			.asSequence()
			.filter { it.style === OrderedListSpanStyle }
			.map { it.range.start.line }
			.toHashSet()
		var orderedListRunPosition = 0

		// Pre-collect code-fence line indices so each line can compute its boundary
		// (top/middle/bottom/only) by checking neighbors — driving which edges of
		// the card border `CodeFenceSpanStyle` paints.
		val codeFenceLines = richSpanManager.getAllRichSpans()
			.asSequence()
			.filter { it.style === CodeFenceSpanStyle }
			.map { it.range.start.line }
			.toHashSet()

		// Compose Android doesn't reliably honor per-paragraph ParagraphStyle
		// .textIndent overriding an editor-wide TextStyle.textIndent, so we
		// sidestep the merge: strip the indent from the outer style and bake it
		// into plain lines as their own ParagraphStyle below. Block lines
		// already carry a ParagraphStyle from `applyLineBlock`.
		val outerIndent = textStyle.textIndent
		val needsIndentBaking = outerIndent != null && outerIndent != TextIndent.None
		val measureStyle = if (needsIndentBaking) textStyle.copy(textIndent = TextIndent.None) else textStyle
		val bakedIndentStyle = if (needsIndentBaking) ParagraphStyle(textIndent = outerIndent) else null

		textLines.forEachIndexed { lineIndex, line ->
			val shouldRemeasure = affectedLines == null ||
					lineIndex in affectedLines ||
					lineIndex > (affectedLines.lastOrNull() ?: -1)

			// Use a tight width constraint (minWidth == maxWidth) so the paragraph lays out
			// at the full viewport width rather than shrinking to its natural content width.
			// The shrinking behavior interacts badly with TextIndent: if the paragraph
			// shrinks to its natural width W and then TextIndent consumes X pixels of
			// first-line width, the first line has only W-X pixels available instead of
			// viewportWidth-X — causing wraps that shouldn't happen.
			val lineConstraints = Constraints(
				minWidth = maxOf(1, viewportSize.width.toInt()),
				maxWidth = maxOf(1, viewportSize.width.toInt()),
				minHeight = 0,
				maxHeight = Constraints.Infinity
			)

			// Skip if the line already has a ParagraphStyle (block line) —
			// Compose forbids overlapping ParagraphStyle ranges.
			val measureLine = if (bakedIndentStyle != null && line.paragraphStyles.isEmpty()) {
				buildAnnotatedString { withStyle(bakedIndentStyle) { append(line) } }
			} else {
				line
			}

			val textLayoutResult = if (shouldRemeasure) {
				try {
					textMeasurer.measure(
						text = measureLine,
						style = measureStyle,
						constraints = lineConstraints
					)
				} catch (e: IllegalArgumentException) {
					println(e)
					// If measurement fails, create an empty layout result
					textMeasurer.measure(
						text = AnnotatedString(""),
						style = measureStyle,
						constraints = lineConstraints
					)
				}
			} else {
				val existing = _lineOffsets.find { it.line == lineIndex }?.textLayoutResult
				if (existing != null) {
					existing
				} else {
					textMeasurer.measure(
						text = measureLine,
						style = measureStyle,
						constraints = lineConstraints
					)
				}
			}

			val virtualLineCount = textLayoutResult.multiParagraph.lineCount
			val paragraphTop = yOffset

			val orderedListNumber: Int? = if (lineIndex in orderedListLines) {
				orderedListRunPosition += 1
				orderedListRunPosition
			} else {
				orderedListRunPosition = 0
				null
			}

			val codeFenceBoundary: CodeFenceBoundary? = if (lineIndex in codeFenceLines) {
				val prevIn = (lineIndex - 1) in codeFenceLines
				val nextIn = (lineIndex + 1) in codeFenceLines
				when {
					!prevIn && !nextIn -> CodeFenceBoundary.Only
					!prevIn -> CodeFenceBoundary.First
					!nextIn -> CodeFenceBoundary.Last
					else -> CodeFenceBoundary.Middle
				}
			} else null

			for (virtualLineIndex in 0 until virtualLineCount) {
				val lineWrapsAt = textLayoutResult.getLineStart(virtualLineIndex)

				val lineLength =
					textLayoutResult.getLineEnd(virtualLineIndex) - textLayoutResult.getLineStart(
						virtualLineIndex
					)

				val lineWrap = LineWrap(
					line = lineIndex,
					wrapStartsAtIndex = lineWrapsAt,
					virtualLength = lineLength,
					virtualLineIndex = virtualLineIndex,
					offset = Offset(0f, yOffset),
					textLayoutResult = textLayoutResult,
					paragraphTop = paragraphTop,
				)

				val richSpans = if (shouldRemeasure) {
					richSpanManager.getSpansForLineWrap(lineWrap)
				} else {
					_lineOffsets.find {
						it.line == lineIndex && it.wrapStartsAtIndex == lineWrapsAt
					}?.richSpans ?: emptyList()
				}

				val blockHeight = density?.let { d ->
					richSpans.firstNotNullOfOrNull { span ->
						(span.style as? BlockSpanStyle)?.blockHeight(d, viewportSize.width)
					}
				}

				val resolved = lineWrap.copy(
					richSpans = richSpans,
					blockHeight = blockHeight,
					orderedListNumber = orderedListNumber,
					codeFenceBoundary = codeFenceBoundary,
				)
				offsets.add(resolved)
				yOffset += resolved.effectiveHeight
			}
		}

		_lineOffsets = offsets
		scrollManager.updateContentHeight(yOffset.toInt())

		_canUndo = editManager.history.hasUndoLevels()
		_canRedo = editManager.history.hasRedoLevels()
	}

	/**
	 * Applies a character-level [SpanStyle] (bold, color, etc.) to [range]. This is an
	 * undoable text style; for block decorations like lists or code fences use
	 * [addRichSpan].
	 */
	fun addStyleSpan(range: TextEditorRange, style: SpanStyle) {
		editManager.addSpanStyle(range, style)
		updateBookKeeping()
	}

	/** Removes a previously applied character-level [SpanStyle] from [range]. */
	fun removeStyleSpan(range: TextEditorRange, style: SpanStyle) {
		editManager.removeStyleSpan(range, style)
		updateBookKeeping()
	}

	/**
	 * Adds a [RichSpan] block decoration ([RichSpanStyle]: list, blockquote, code
	 * fence, highlight) over [range]. For inline text styling use [addStyleSpan].
	 */
	fun addRichSpan(range: TextEditorRange, style: RichSpanStyle) {
		editManager.addRichSpan(range, style)
		updateBookKeeping()
	}

	/** Adds a [RichSpan] block decoration spanning [start] to [end]. */
	fun addRichSpan(start: CharLineOffset, end: CharLineOffset, style: RichSpanStyle) {
		editManager.addRichSpan(TextEditorRange(start, end), style)
		updateBookKeeping()
	}

	/** Adds a [RichSpan] block decoration over the flat character range [start] until [end]. */
	fun addRichSpan(start: Int, end: Int, style: RichSpanStyle) {
		editManager.addRichSpan(
			TextEditorRange(start.toCharLineOffset(), end.toCharLineOffset()),
			style
		)
		updateBookKeeping()
	}

	/** Removes the [RichSpan] block decoration of [style] spanning [start] to [end]. */
	fun removeRichSpan(start: CharLineOffset, end: CharLineOffset, style: RichSpanStyle) {
		editManager.removeRichSpan(TextEditorRange(start, end), style)
		updateBookKeeping()
	}

	/** Removes the given [RichSpan], e.g. one returned by [findSpanAtPosition]. */
	fun removeRichSpan(span: RichSpan) {
		editManager.removeRichSpan(span.range, span.style)
		updateBookKeeping()
	}

	/**
	 * Applies a batch of transient highlight spans (find results, etc.) directly to
	 * the span manager with a single relayout, bypassing the edit/undo pipeline.
	 * These are view overlays, not user edits: they must not enter undo history and
	 * must not emit on [editOperations], and per-span [addRichSpan]/[removeRichSpan]
	 * would relayout the whole document once per span.
	 */
	fun updateRichSpans(remove: Collection<RichSpan>, add: Collection<RichSpan>) {
		remove.forEach { richSpanManager.removeRichSpan(it) }
		add.forEach { richSpanManager.addRichSpan(it.range, it.style) }
		updateBookKeeping()
	}

	/**
	 * Returns the [RichSpan] covering [position], or null if none does. Useful for
	 * hit-testing taps on a list item or code fence.
	 */
	fun findSpanAtPosition(position: CharLineOffset): RichSpan? {
		// Find the line wrap that contains our position
		val lineWrap = _lineOffsets.lastOrNull { wrap ->
			wrap.line == position.line && position.char >= wrap.wrapStartsAtIndex
		} ?: return null

		// Check each span in the line wrap
		return lineWrap.richSpans.firstOrNull { span ->
			span.containsPosition(position)
		}
	}

	fun captureMetadata(range: TextEditorRange): OperationMetadata {
		val deletedContent = when {
			range.isSingleLine() -> {
				textLines[range.start.line].subSequence(range.start.char, range.end.char)
			}

			else -> {
				buildAnnotatedString {
					// First line - from start to end
					append(textLines[range.start.line].subSequence(range.start.char))
					append("\n")

					// Middle lines
					for (line in (range.start.line + 1) until range.end.line) {
						append(textLines[line])
						append("\n")
					}

					// Last line - up to end char
					if (range.end.line < textLines.size) {
						append(textLines[range.end.line].subSequence(0, range.end.char))
					}
				}
			}
		}

		return OperationMetadata(
			deletedText = deletedContent,
			deletedSpans = richSpanManager.getSpansInRange(range),
			preservedRichSpans = richSpanManager.getSpansInRange(range).map { span ->
				PreservedRichSpan(
					relativeStart = getRelativePosition(span.range.start, range.start),
					relativeEnd = getRelativePosition(span.range.end, range.start),
					style = span.style
				)
			}
		)
	}

	/**
	 * Remembers the rich spans (ordered/bullet list, blockquote, etc.) within
	 * [range] so a subsequent [pasteRichSpans] can restore them. Call from the
	 * copy/cut handlers alongside writing the text to the system clipboard.
	 */
	fun copyRichSpans(range: TextEditorRange) {
		// getSpansInRange returns spans that merely OVERLAP the copy range. A span
		// starting before range.start (partial selection of a list item, or a
		// multi-line span only partly covered) would yield a negative relative
		// offset and a corrupt span on paste, so clamp each span to the copy range
		// and drop any that collapse to empty/inverted.
		val preserved = richSpanManager.getSpansInRange(range).mapNotNull { span ->
			val clampedStart = maxOf(span.range.start, range.start)
			val clampedEnd = minOf(span.range.end, range.end)
			if (clampedStart >= clampedEnd) return@mapNotNull null
			PreservedRichSpan(
				relativeStart = getRelativePosition(clampedStart, range.start),
				relativeEnd = getRelativePosition(clampedEnd, range.start),
				style = span.style
			)
		}
		copiedRichSpans = if (preserved.isEmpty()) {
			null
		} else {
			CopiedRichSpans(text = getStringInRange(range), spans = preserved)
		}
	}

	/**
	 * Exempts the next single edit from invalidating the rich-span buffer. Call
	 * immediately before an edit that must not clear the buffer: the delete in a
	 * cut, or the insert/replace in a paste.
	 */
	internal fun preserveCopiedRichSpansThroughNextEdit() {
		richSpanBufferSurvivesNextEdit = copiedRichSpans != null
	}

	/**
	 * Drops the remembered rich spans. Any document mutation that is not the
	 * paste's own edit invalidates the buffer, so a buffer captured before an
	 * intervening edit — or text that merely happens to match content copied from
	 * another source after the document changed — cannot apply stale spans.
	 */
	internal fun invalidateCopiedRichSpans() {
		if (richSpanBufferSurvivesNextEdit) {
			richSpanBufferSurvivesNextEdit = false
			return
		}
		copiedRichSpans = null
	}

	/**
	 * Re-applies the rich spans captured by [copyRichSpans] at [insertPosition].
	 * No-op unless [pastedText] matches the text that was copied. The text match
	 * is a secondary guard; the primary protection against stale spans is
	 * [invalidateCopiedRichSpans], which clears the buffer on any intervening edit.
	 *
	 * Residual limitation: if the user copies identical text from another app with
	 * no editor edit in between, the text match still succeeds and the in-editor
	 * spans apply. Fully closing this needs platform-clipboard ownership tracking,
	 * which is out of scope here.
	 */
	fun pasteRichSpans(insertPosition: CharLineOffset, pastedText: AnnotatedString) {
		val copied = copiedRichSpans ?: return
		if (copied.text != pastedText.text) return
		copied.spans.forEach { preserved ->
			val startPos = CharLineOffset(
				line = insertPosition.line + preserved.relativeStart.lineDiff,
				char = if (preserved.relativeStart.lineDiff == 0)
					insertPosition.char + preserved.relativeStart.char
				else
					preserved.relativeStart.char
			)
			val endPos = CharLineOffset(
				line = insertPosition.line + preserved.relativeEnd.lineDiff,
				char = if (preserved.relativeEnd.lineDiff == 0)
					insertPosition.char + preserved.relativeEnd.char
				else
					preserved.relativeEnd.char
			)
			addRichSpan(startPos, endPos, preserved.style)
		}
	}

	private fun getRelativePosition(
		pos: CharLineOffset,
		basePos: CharLineOffset
	): RelativePosition {
		val lineDiff = pos.line - basePos.line
		val char = when {
			lineDiff == 0 -> pos.char - basePos.char
			lineDiff > 0 -> pos.char  // On later line, keep char position
			else -> pos.char          // Should not happen in properly bounded spans
		}
		return RelativePosition(lineDiff, char)
	}

	internal fun getLine(lineIndex: Int): AnnotatedString = textLines[lineIndex]

	/**
	 * Returns the plain text within [range], with newlines between spanned lines.
	 * Use [getTextInRange] to keep character-level spans.
	 */
	fun getStringInRange(range: TextEditorRange): String {
		return if (range.isSingleLine()) {
			textLines[range.start.line].text.substring(range.start.char, range.end.char)
		} else {
			buildString {
				// First line
				append(textLines[range.start.line].text.substring(range.start.char))
				append('\n')

				// Middle lines
				for (line in (range.start.line + 1) until range.end.line) {
					append(textLines[line].text)
					append('\n')
				}

				// Last line
				append(textLines[range.end.line].text.substring(0, range.end.char))
			}
		}
	}

	/**
	 * Returns the text within [range] as an [AnnotatedString], preserving its
	 * character-level spans. Use [getStringInRange] for plain text only.
	 */
	fun getTextInRange(range: TextEditorRange): AnnotatedString {
		return if (range.isSingleLine()) {
			// For single line, we can use subSequence which preserves spans
			textLines[range.start.line].subSequence(range.start.char, range.end.char)
		} else {
			buildAnnotatedString {
				// First line - from start to end, preserving spans
				append(textLines[range.start.line].subSequence(range.start.char))
				append('\n')

				// Middle lines - complete lines with their spans
				for (line in (range.start.line + 1) until range.end.line) {
					append(textLines[line])
					append('\n')
				}

				// Last line - up to end char, preserving spans
				if (range.end.line < textLines.size) {
					append(textLines[range.end.line].subSequence(0, range.end.char))
				}
			}
		}
	}

	/**
	 * Returns the entire document as a single [AnnotatedString], joining [textLines]
	 * with newlines and preserving character-level spans.
	 */
	fun getAllText(): AnnotatedString {
		return buildAnnotatedString {
			textLines.forEachIndexed { index, line ->
				append(line)
				if (index < textLines.lastIndex) {
					append('\n')
				}
			}
		}
	}

	/** Returns the total character count of the document, counting newlines between lines. */
	fun getTextLength(): Int {
		val length = textLines.sumOf { line ->
			line.length + 1
		}
		return length - 1
	}

	/**
	 * Returns a hash of the document content (text and spans), suitable for cheaply
	 * detecting whether the document has changed.
	 */
	fun computeTextHash(): Int {
		var hash = 3
		val multiplier = 31
		textLines.forEach { line ->
			hash = multiplier * hash + line.hashCode()
		}
		return hash
	}

	init {
		setText(initialText ?: AnnotatedString(""))
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other == null || this::class != other::class) return false

		other as TextEditorState

		if (compareLines(textLines, other.textLines).not()) return false

		return true
	}

	private fun compareLines(list1: List<AnnotatedString>, list2: List<AnnotatedString>): Boolean {
		if (list1 === list2) return true
		if (list1.size != list2.size) return false
		for (i in list1.indices) {
			if (list1[i] != list2[i]) {
				return false
			}
		}
		return true
	}

	override fun hashCode(): Int {
		return 31 + textLines.hashCode()
	}
}
