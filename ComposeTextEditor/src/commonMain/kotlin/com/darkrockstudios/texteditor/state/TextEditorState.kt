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
import androidx.compose.ui.text.TextLayoutResult
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
import com.darkrockstudios.texteditor.input.EditorActionRegistry
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.LineBlockEditBehavior
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.normalizeLineBlocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlin.concurrent.Volatile
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
 * Observe changes reactively via [cursorDataFlow], [editOperations] and
 * [documentReplacements].
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
			invalidateLayoutInputs()
			updateBookKeeping()
		}

	var textStyle: TextStyle = TextStyle.Default
		internal set(value) {
			if (field != value) {
				field = value
				invalidateLayoutInputs()
				updateBookKeeping()
			}
		}

	/**
	 * Styling used when converting styled text to and from an external
	 * representation, currently the clipboard's HTML flavor. Header levels are
	 * recognised by matching font sizes against this, so a mismatch silently
	 * downgrades headings to plain bold text.
	 *
	 * Kept in sync by
	 * [MarkdownExtension][com.darkrockstudios.texteditor.markdown.MarkdownExtension];
	 * editors that do not use markdown keep the default.
	 */
	var markdownConfiguration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
		internal set(value) {
			field = value
			hasMarkdownConfiguration = true
		}

	/**
	 * Whether an extension installed [markdownConfiguration]. A plain editor keeps
	 * the default value but never opts in, so its typed text stays on [textStyle].
	 */
	internal var hasMarkdownConfiguration: Boolean = false
		private set

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

	/**
	 * The last committed document content. Every mutation publishes a whole new
	 * [DocumentSnapshot] rather than editing the previous one in place, so a reader
	 * on any thread sees a complete, self-consistent snapshot and can never observe
	 * a collection mid-mutation. Writes must all come from the thread driving edits.
	 *
	 * Only ever holds a fully applied revision, never a half-finished one. [snapshot]
	 * exposes it to callers outside this module.
	 */
	@Volatile
	internal var content = DocumentSnapshot(emptyList(), emptySet())
		private set

	/**
	 * Content staged by an open [withAtomicEdit] transaction, or null when none is
	 * running. Written only by the thread inside the transaction; readers elsewhere
	 * keep seeing [content] until it commits. Volatile so the edit thread's own
	 * nested reads through [workingContent] can never see a stale draft.
	 */
	@Volatile
	private var draft: DocumentSnapshot? = null

	/** Actions deferred by [onCommit] until the outermost transaction commits. */
	private val pendingCommitActions = mutableListOf<() -> Unit>()

	/**
	 * Layout work requested while a transaction is open, merged across requests and
	 * flushed as one pass at commit. Laying out mid-transaction would both waste the
	 * work and read a half-applied revision.
	 */
	private var pendingLayoutUpdate: LayoutUpdate? = null

	/**
	 * Whether a cursor move inside the open transaction still needs its
	 * scroll-into-view. Deferred with the layout: scrolling mid-transaction computes
	 * the target from the stale pre-edit offsets and can fling the viewport to the
	 * document top.
	 */
	private var pendingCursorScroll = false

	/**
	 * Scrolls the cursor into view, or defers the scroll to the transaction commit
	 * so it reads the freshly flushed layout.
	 */
	internal fun requestCursorVisible() {
		if (draft != null) {
			pendingCursorScroll = true
		} else {
			scrollManager.ensureCursorVisible()
		}
	}

	/** Content as the current edit sees it: the open draft if there is one, else [content]. */
	internal val workingContent: DocumentSnapshot get() = draft ?: content

	/**
	 * Returns the document's text and rich spans as they stood after the same edit.
	 *
	 * Safe to call from any thread. [textLines] and [RichSpanManager.getAllRichSpans]
	 * are separate reads, so pairing them can straddle an edit and yield text from one
	 * revision with span line indices from another; anything that serializes the whole
	 * document (an exporter, an autosave) wants this instead.
	 */
	fun snapshot(): DocumentSnapshot = content

	/**
	 * Runs [block] as a single atomic revision: mutations inside it accumulate in a
	 * draft and reach [content] in one write when it returns.
	 *
	 * Every edit touches lines and rich spans separately (the text first, then
	 * `updateSpans` re-anchors the spans onto it). Without this, the intermediate
	 * state is publicly observable, and a reader that catches it gets new text
	 * paired with span line indices from the previous revision, which serializes
	 * block markers onto the wrong lines.
	 *
	 * Re-entrant: a nested call joins the outer transaction and commits with it. A
	 * throwing [block] discards the draft and leaves [content] on the previous
	 * revision, because a half-applied revision would keep serializing block markers
	 * onto the wrong lines long after the failure rather than only during it.
	 */
	internal fun <T> withAtomicEdit(block: () -> T): T {
		if (draft != null) return block()
		draft = content
		try {
			val result = block()
			// Every publish passes through line-block normalization, so no caller
			// can commit a revision violating the placeholder-line invariant. A
			// transaction that mutated nothing skips the scan and the republish.
			draft?.let {
				if (it !== content) {
					val normalized = normalizeLineBlocks(it, markdownConfiguration)
					// Normalization can rewrite lines no operation declared dirty,
					// so a rewrite invalidates any deferred partial relayout.
					if (normalized !== it) invalidateLayoutInputs()
					content = normalized
				}
			}
			draft = null
			// Flush the deferred relayout, then the cursor scroll that must read the
			// fresh offsets, then the commit actions that announce the edit. All of
			// this runs only on the committing path.
			pendingLayoutUpdate?.let {
				pendingLayoutUpdate = null
				updateBookKeeping(it)
			}
			if (pendingCursorScroll) {
				pendingCursorScroll = false
				scrollManager.ensureCursorVisible()
			}
			val actions = pendingCommitActions.toList()
			pendingCommitActions.clear()
			actions.forEach { it() }
			return result
		} finally {
			// The throwing path discards everything staged: the draft, the relayout,
			// the scroll, and the queued actions, which would announce an edit that
			// no longer exists.
			draft = null
			pendingLayoutUpdate = null
			pendingCursorScroll = false
			pendingCommitActions.clear()
		}
	}

	/**
	 * Runs [action] once the outermost transaction has committed, or immediately when
	 * there is none. For work that announces an edit to the outside world and must not
	 * run while the revision it describes is still staged.
	 */
	internal fun onCommit(action: () -> Unit) {
		if (draft != null) pendingCommitActions += action else action()
	}

	private fun mutateContent(transform: (DocumentSnapshot) -> DocumentSnapshot) {
		// The no-draft branch publishes directly, so it normalizes like a commit;
		// drafted mutations wait for the transaction's own commit to normalize once.
		if (draft != null) {
			draft = transform(workingContent)
		} else {
			val transformed = transform(content)
			val normalized = normalizeLineBlocks(transformed, markdownConfiguration)
			if (normalized !== transformed) invalidateLayoutInputs()
			content = normalized
		}
	}

	/**
	 * The document content as one [AnnotatedString] per line, in order. An immutable
	 * list; mutate through the edit operations or replace wholesale with [setText].
	 *
	 * Reflects the in-progress revision while an edit is running. To read the document
	 * from another thread, or to pair the text with its rich spans, use [snapshot].
	 */
	val textLines: List<AnnotatedString> get() = workingContent.lines

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
	 * Guards partial relayout. [layoutInputGeneration] advances whenever an input that
	 * shapes every line changes (style, measurer, density, viewport, normalization
	 * rewrites); a partial [updateBookKeeping] runs only when the last completed pass
	 * saw the same generation and a line count consistent with the update's delta.
	 */
	private var layoutInputGeneration = 0
	private var lastLayoutGeneration = -1
	private var lastLayoutLineCount = -1

	internal fun invalidateLayoutInputs() {
		layoutInputGeneration++
	}

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
				invalidateLayoutInputs()
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

	/**
	 * Behaviors consulted before [insertNewlineAtCursor], [backspaceAtCursor] and
	 * [deleteAtCursor], in order; the first to claim an edit wins. Every input
	 * path reaches these three, hardware keys and IME alike.
	 *
	 * Pre-loaded with [LineBlockEditBehavior] at index 0, which claims every
	 * newline and column-0 backspace on a block line, so a behavior appended
	 * after it never sees those edits. Use `add(0, behavior)` to run first, or
	 * remove it outright for plain line breaks.
	 */
	val editBehaviors: MutableList<EditBehavior> = mutableListOf(LineBlockEditBehavior)

	/** Depth of the behavior chain currently dispatching, to break recursion. */
	private var behaviorDepth = 0

	/**
	 * Offers the edit to each behavior until one claims it. Iterates a snapshot
	 * so a behavior may mutate the list mid-edit; nested calls skip the chain, so
	 * a behavior can finish with an ordinary edit without being offered its own
	 * edit again forever.
	 *
	 * A claim also asks the IME to resync: once a behavior answered the edit, the
	 * keyboard's assumption about what its request did is wrong in a way no diff
	 * of the text can express.
	 */
	private fun claimedByBehavior(hook: (EditBehavior) -> Boolean): Boolean {
		if (behaviorDepth > 0) return false

		behaviorDepth++
		val claimed = try {
			editBehaviors.toList().any(hook)
		} finally {
			behaviorDepth--
		}

		if (claimed) requestImeResync()
		return claimed
	}

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
	 * Everything this editor can be asked to do, keyed by action id, pre-loaded
	 * with the built-ins. Register here to add an action a custom
	 * [KeyBindings][com.darkrockstudios.texteditor.input.KeyBindings] can bind or
	 * a menu can invoke, or to replace a built-in with your own implementation.
	 */
	val actions: EditorActionRegistry = EditorActionRegistry()

	private val _documentReplacements = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

	/**
	 * Fires when [setText] swaps the whole document. A replacement is not an edit and
	 * emits nothing on [editOperations], so anything deriving state from the text
	 * (spell check, search results) has no other way to learn its document is gone.
	 */
	val documentReplacements: Flow<Unit> = _documentReplacements

	/**
	 * Replaces the entire document with [text], clearing rich spans and resetting
	 * book-keeping. To edit existing content instead, use [replace] or the cursor
	 * operations.
	 */
	fun setText(text: String) {
		replaceContent(text.split("\n").map { it.toAnnotatedString() })
		updateBookKeeping()
		cursor.refreshStyles()
	}

	/**
	 * Replaces the entire document with [text], preserving its character-level spans
	 * while clearing rich spans and resetting book-keeping. To edit existing content
	 * instead, use [replace] or the cursor operations.
	 */
	fun setText(text: AnnotatedString) {
		replaceContent(text.splitAnnotatedString())
		updateBookKeeping()
		cursor.refreshStyles()
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

	private val _imeResyncRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

	/**
	 * Fires when the editor answered an IME request in a way the IME cannot infer
	 * from the text or the caret, so its mirror of the buffer has to be pushed
	 * again rather than deduplicated away. Platforms without an IME ignore it.
	 */
	internal val imeResyncRequests: Flow<Unit> = _imeResyncRequests

	internal fun requestImeResync() {
		_imeResyncRequests.tryEmit(Unit)
	}

	/**
	 * Inserts a line break at the cursor, splitting the current line, unless an
	 * [EditBehavior] claims the edit first.
	 */
	fun insertNewlineAtCursor() {
		if (claimedByBehavior { it.onNewline(this) }) return
		insertNewlineRaw()
	}

	/**
	 * Splits the line at the cursor with no [EditBehavior] consulted, for a
	 * behavior that needs the plain split as part of the edit it is claiming.
	 */
	internal fun insertNewlineRaw() {
		val operation = TextEditOperation.Insert(
			position = cursorPosition,
			text = cursor.applyCursorStyle("\n"),
			cursorBefore = cursorPosition,
			cursorAfter = CharLineOffset(cursorPosition.line + 1, 0)
		)
		editManager.applyOperation(operation)
	}

	/**
	 * Deletes the character before the cursor, merging with the previous line when
	 * at column 0, unless an [EditBehavior] claims the edit first.
	 */
	fun backspaceAtCursor() {
		if (claimedByBehavior { it.onBackspace(this) }) return

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
	 * one when at end of line (forward delete), unless an [EditBehavior] claims the
	 * edit first.
	 */
	fun deleteAtCursor() {
		if (claimedByBehavior { it.onDeleteForward(this) }) return

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
	fun delete(range: TextEditorRange) = delete(range, cursorBefore = cursorPosition)

	/**
	 * Deletes the text covered by [range], recording [cursorBefore] as the position undo
	 * returns to. Callers that located [range] by running a cursor motion have already moved
	 * the caret off the position the user actually had, and pass it explicitly.
	 */
	internal fun delete(range: TextEditorRange, cursorBefore: CharLineOffset) {
		val operation = TextEditOperation.Delete(
			range = range,
			cursorBefore = cursorBefore,
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
		setLine(index, text)
		updateBookKeeping(LayoutUpdate.Partial(index, index, 0))
	}

	/**
	 * Rewrites the document by passing each line index and content through [processor],
	 * then relays out the result. [processor] sees the document as it stood on entry
	 * and the rewritten lines land as a single revision once every line is visited.
	 *
	 * [processor] must be a pure function of its arguments. Editing this state from
	 * inside it (adding a span, replacing a range) does not compose: those writes are
	 * computed against the entry snapshot and overwritten by the batch.
	 */
	fun processLines(processor: (index: Int, line: AnnotatedString) -> AnnotatedString) {
		withAtomicEdit { setLines(textLines.mapIndexed(processor)) }
		updateBookKeeping()
	}

	internal fun removeLines(startIndex: Int, count: Int) {
		val lines = textLines
		// If there are no lines, or we're trying to remove more lines than exist, abort
		if (lines.isEmpty() || startIndex >= lines.size) {
			return
		}

		// Ensure we don't remove more lines than available. The floor matters: an
		// inverted range reaches here with a negative count, which subList would
		// reject outright.
		val safeCount = minOf(count, lines.size - startIndex).coerceAtLeast(0)

		// Always keep at least one empty line
		if (lines.size <= safeCount) {
			setLines(listOf(AnnotatedString("")))
		} else {
			setLines(
				lines.toMutableList().also {
					it.subList(startIndex, startIndex + safeCount).clear()
				}
			)
		}
	}

	internal fun insertLine(index: Int, text: String) = insertLine(index, text.toAnnotatedString())
	internal fun insertLine(index: Int, text: AnnotatedString) {
		setLines(textLines.toMutableList().also { it.add(index, text) })
	}

	/**
	 * Publishes [lines] as the entire document and drops every rich span in a single
	 * write. A full content replacement leaves any prior spans pointing at stale line
	 * indices: on a markdown roundtrip, leftover bullet/blockquote spans would block
	 * `applyLineBlock` from re-attaching the paragraph indent and the gutter marker
	 * would draw over the first character of the line.
	 */
	private fun replaceContent(lines: List<AnnotatedString>) {
		mutateContent { DocumentSnapshot(lines, emptySet()) }
		_documentReplacements.tryEmit(Unit)
	}

	internal fun setLines(lines: List<AnnotatedString>) {
		mutateContent { it.withLines(lines) }
	}

	internal fun setLine(index: Int, text: AnnotatedString) {
		setLines(workingContent.lines.toMutableList().also { it[index] = text })
	}

	internal fun setRichSpans(richSpans: Set<RichSpan>) {
		mutateContent { it.withRichSpans(richSpans) }
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
		invalidateLayoutInputs()
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
		val starts = workingContent.lineStartOffsets
		val lineCount = textLines.size
		if (index < 0) return CharLineOffset(0, index)
		if (index >= starts[lineCount]) {
			return CharLineOffset(textLines.lastIndex, textLines.last().length)
		}

		val line = lineOfCharacter(starts, lineCount, index)
		return CharLineOffset(line, index - starts[line])
	}

	/**
	 * Index of the line containing flat character [index], by binary search over the
	 * snapshot's line starts. [index] must be within the document.
	 */
	private fun lineOfCharacter(starts: IntArray, lineCount: Int, index: Int): Int {
		var low = 0
		var high = lineCount - 1
		while (low < high) {
			val mid = (low + high + 1) ushr 1
			if (starts[mid] <= index) low = mid else high = mid - 1
		}
		return low
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

		return workingContent.lineStartOffsets[safe.line] + safe.char
	}

	fun CharLineOffset.toCharacterIndex(): Int =
		workingContent.lineStartOffsets[line] + char

	// Convert character index to CharLineOffset
	fun Int.toCharLineOffset(): CharLineOffset = getOffsetAtCharacter(this)

	fun wrapStartToCharacterIndex(lineWrap: LineWrap): Int {
		// First get the physical line start offset
		val physicalLineStartOffset = getLineStartOffset(lineWrap.line)
		// Add the local offset from the LineWrap
		return physicalLineStartOffset + lineWrap.wrapStartsAtIndex
	}

	fun getLineStartOffset(lineIndex: Int): Int {
		require(lineIndex >= 0) { "Line index must be non-negative" }
		require(lineIndex < textLines.size) { "Line index $lineIndex out of bounds for ${textLines.size} lines" }

		return workingContent.lineStartOffsets[lineIndex]
	}

	internal fun updateBookKeeping(update: LayoutUpdate = LayoutUpdate.Full) {
		// Inside a transaction the content is a half-applied draft; merge the request
		// and lay out once at commit.
		if (draft != null) {
			pendingLayoutUpdate = pendingLayoutUpdate?.mergedWith(update) ?: update
			return
		}

		// Defer until the viewport has a real size; the 1×1 sentinel forces character-wide wraps.
		if (viewportSize.width <= 1f || viewportSize.height <= 1f) return

		// A partial pass is only sound against the exact layout the last pass produced.
		// Degrade to full when the cache is missing, a full invalidator (style, measurer,
		// density, viewport, normalization) fired since, or the line count disagrees
		// with the update's own delta; reusing stale layouts corrupts every consumer.
		val partial = (update as? LayoutUpdate.Partial)?.takeIf {
			_lineOffsets.isNotEmpty() &&
					lastLayoutGeneration == layoutInputGeneration &&
					lastLayoutLineCount == textLines.size - it.lineDelta
		}

		val previousLayouts: Map<Int, TextLayoutResult>? = if (partial != null) {
			HashMap<Int, TextLayoutResult>(lastLayoutLineCount * 2).also { map ->
				for (wrap in _lineOffsets) {
					if (!map.containsKey(wrap.line)) map[wrap.line] = wrap.textLayoutResult
				}
			}
		} else null

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

		// Use a tight width constraint (minWidth == maxWidth) so the paragraph lays out
		// at the full viewport width rather than shrinking to its natural content width.
		// The shrinking behavior interacts badly with TextIndent: if the paragraph
		// shrinks to its natural width W and then TextIndent consumes X pixels of
		// first-line width, the first line has only W-X pixels available instead of
		// viewportWidth-X, causing wraps that shouldn't happen.
		val lineConstraints = Constraints(
			minWidth = maxOf(1, viewportSize.width.toInt()),
			maxWidth = maxOf(1, viewportSize.width.toInt()),
			minHeight = 0,
			maxHeight = Constraints.Infinity
		)

		textLines.forEachIndexed { lineIndex, line ->
			// Lines outside the dirty range kept their content; only their position
			// changed, so their previous shaping result is reused as-is.
			val cachedLayout: TextLayoutResult? = when {
				partial == null -> null
				lineIndex < partial.remeasureFirst -> previousLayouts?.get(lineIndex)
				lineIndex > partial.remeasureLast -> previousLayouts?.get(lineIndex - partial.lineDelta)
				else -> null
			}

			val textLayoutResult = cachedLayout ?: run {
				// Skip if the line already has a ParagraphStyle (block line):
				// Compose forbids overlapping ParagraphStyle ranges.
				val measureLine = if (bakedIndentStyle != null && line.paragraphStyles.isEmpty()) {
					buildAnnotatedString { withStyle(bakedIndentStyle) { append(line) } }
				} else {
					line
				}
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

				// Spans are re-resolved even for reused layouts: after a line-shifting
				// edit the span set holds re-anchored copies, so a cached list would
				// carry pre-edit ranges into drawing and hit testing.
				val richSpans = richSpanManager.getSpansForLineWrap(lineWrap)

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
		lastLayoutLineCount = textLines.size
		lastLayoutGeneration = layoutInputGeneration

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
	}

	/** Removes a previously applied character-level [SpanStyle] from [range]. */
	fun removeStyleSpan(range: TextEditorRange, style: SpanStyle) {
		editManager.removeStyleSpan(range, style)
	}

	/**
	 * Adds a [RichSpan] block decoration ([RichSpanStyle]: list, blockquote, code
	 * fence, highlight) over [range]. For inline text styling use [addStyleSpan].
	 */
	fun addRichSpan(range: TextEditorRange, style: RichSpanStyle) {
		editManager.addRichSpan(range, style)
	}

	/** Adds a [RichSpan] block decoration spanning [start] to [end]. */
	fun addRichSpan(start: CharLineOffset, end: CharLineOffset, style: RichSpanStyle) {
		editManager.addRichSpan(TextEditorRange(start, end), style)
	}

	/** Adds a [RichSpan] block decoration over the flat character range [start] until [end]. */
	fun addRichSpan(start: Int, end: Int, style: RichSpanStyle) {
		editManager.addRichSpan(
			TextEditorRange(start.toCharLineOffset(), end.toCharLineOffset()),
			style
		)
	}

	/** Removes the [RichSpan] block decoration of [style] spanning [start] to [end]. */
	fun removeRichSpan(start: CharLineOffset, end: CharLineOffset, style: RichSpanStyle) {
		editManager.removeRichSpan(TextEditorRange(start, end), style)
	}

	/** Removes the given [RichSpan], e.g. one returned by [findSpanAtPosition]. */
	fun removeRichSpan(span: RichSpan) {
		editManager.removeRichSpan(span.range, span.style)
	}

	/**
	 * Applies a batch of transient highlight spans (find results, etc.) directly to
	 * the span manager with a single relayout, bypassing the edit/undo pipeline.
	 * These are view overlays, not user edits: they must not enter undo history and
	 * must not emit on [editOperations], and per-span [addRichSpan]/[removeRichSpan]
	 * would relayout the whole document once per span.
	 */
	fun updateRichSpans(remove: Collection<RichSpan>, add: Collection<RichSpan>) {
		if (remove.isEmpty() && add.isEmpty()) return
		// One revision as well as one relayout: published per span, a reader between
		// the removals and the additions sees the batch half-applied.
		withAtomicEdit {
			richSpanManager.removeRichSpans(remove)
			richSpanManager.addRichSpansClamped(add)
			// Span overlays don't move text, so the flushed pass re-resolves spans
			// and offsets without shaping a single line.
			updateBookKeeping(LayoutUpdate.SpansOnly)
		}
	}

	/**
	 * Returns the [RichSpan] covering [position], or null if none does. Useful for
	 * hit-testing taps on a list item or code fence.
	 *
	 * Spans nest, so several can cover one position and only one can answer. Content
	 * spans (link, highlight) go first, then the editor's own decorations (spell
	 * check squiggle, find match), then the line-anchored marker of the heading, list
	 * item, blockquote or code fence the line belongs to. Within a tier the span
	 * covering the least of the clicked line wins, so the marker answers only where
	 * nothing more specific does.
	 */
	fun findSpanAtPosition(position: CharLineOffset): RichSpan? {
		// Find the line wrap that contains our position
		val lineWrap = _lineOffsets.lastOrNull { wrap ->
			wrap.line == position.line && position.char >= wrap.wrapStartsAtIndex
		} ?: return null

		return lineWrap.richSpans
			.filter { it.containsPosition(position) }
			.minWithOrNull(hitTestOrder(position.line))
	}

	/**
	 * Ranks the spans covering a click on [line]. Total, down to the style name: a
	 * partial order would leave the winner to the span set's iteration order, which
	 * re-folds on every edit and would answer the same click differently before and
	 * after an unrelated keystroke.
	 */
	private fun hitTestOrder(line: Int): Comparator<RichSpan> = compareBy(
		{ it.style.hitTestTier() },
		{ it.charsOnLine(line) },
		{ it.range.start.char },
		{ it.style::class.simpleName.orEmpty() },
	)

	private fun RichSpanStyle.hitTestTier(): Int = when {
		stickyAtStart || this is BlockSpanStyle -> 2
		isDecoration -> 1
		else -> 0
	}

	/**
	 * How much of [line] the span covers. Measured within the line rather than across
	 * the document so a span running on from an earlier line is ranked by what it
	 * claims here, not by its full length.
	 */
	private fun RichSpan.charsOnLine(line: Int): Int {
		val lineLength = textLines.getOrNull(line)?.length ?: return Int.MAX_VALUE
		val start = (if (range.start.line < line) 0 else range.start.char).coerceIn(0, lineLength)
		val end = (if (range.end.line > line) lineLength else range.end.char).coerceIn(0, lineLength)
		return end - start
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
	 * copy/cut handlers alongside writing the text to the system clipboard, and
	 * attach the returned copy id to the clipboard content so paste can prove
	 * the clipboard still holds this copy.
	 */
	fun copyRichSpans(range: TextEditorRange): Long {
		// getSpansInRange returns spans that merely OVERLAP the copy range. A span
		// starting before range.start (partial selection of a list item, or a
		// multi-line span only partly covered) would yield a negative relative
		// offset and a corrupt span on paste, so clamp each span to the copy range
		// and drop any that collapse to empty/inverted.
		val preserved = richSpanManager.getSpansInRange(range).mapNotNull { span ->
			// A line marker or placeholder block belongs to its line, not to the
			// characters copied out of it: a fragment of an item's text pastes as
			// plain text, only a copy covering the whole span carries the marker.
			val lineAnchored = span.style.stickyAtStart || span.style is BlockSpanStyle
			if (lineAnchored &&
				(span.range.start < range.start || span.range.end > range.end)
			) {
				return@mapNotNull null
			}
			val clampedStart = maxOf(span.range.start, range.start)
			val clampedEnd = minOf(span.range.end, range.end)
			if (clampedStart >= clampedEnd) return@mapNotNull null
			PreservedRichSpan(
				relativeStart = getRelativePosition(clampedStart, range.start),
				relativeEnd = getRelativePosition(clampedEnd, range.start),
				style = span.style
			)
		}
		val copyId = nextCopyId++
		copiedRichSpans = if (preserved.isEmpty()) {
			null
		} else {
			CopiedRichSpans(text = getStringInRange(range), spans = preserved, copyId = copyId)
		}
		return copyId
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
	 * No-op unless [pastedText] matches the text that was copied, and, when
	 * [requireCopyIdMatch] is set, unless [clipboardCopyId] proves the clipboard
	 * still holds the copy that filled the buffer. Platforms whose clipboard
	 * carries a copy id pass both, so identical text written by another
	 * application can never resurrect stale spans; plain-text-only clipboards
	 * fall back to the text match, guarded by [invalidateCopiedRichSpans]
	 * clearing the buffer on any intervening edit.
	 */
	fun pasteRichSpans(
		insertPosition: CharLineOffset,
		pastedText: AnnotatedString,
		clipboardCopyId: Long? = null,
		requireCopyIdMatch: Boolean = false,
	) = withAtomicEdit {
		val copied = copiedRichSpans ?: return@withAtomicEdit
		if (copied.text != pastedText.text) return@withAtomicEdit
		if (requireCopyIdMatch && clipboardCopyId != copied.copyId) return@withAtomicEdit
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
	fun getAllText(): AnnotatedString = workingContent.getAllText()

	/** Returns the total character count of the document, counting newlines between lines. */
	fun getTextLength(): Int {
		val starts = workingContent.lineStartOffsets
		return starts[starts.lastIndex] - 1
	}

	/**
	 * Returns a hash of the document text and inline character spans, suitable for
	 * cheaply detecting whether the document has changed. Rich spans (headings,
	 * links, line blocks) live outside [textLines] and do not affect the hash.
	 */
	fun computeTextHash(): Int {
		var hash = 3
		val multiplier = 31
		textLines.forEach { line ->
			hash = multiplier * hash + line.hashCode()
		}
		return hash
	}

	/**
	 * Returns true when [other] holds the same document text and inline character
	 * spans. Rich spans (headings, links, line blocks) live outside [textLines]
	 * and are not compared.
	 *
	 * Equality on the state itself is reference identity: a mutable controller
	 * object is not a value, and content-based equals/hashCode would make
	 * instances unusable as keys in hash-keyed collections or `remember` keys.
	 */
	fun contentEquals(other: TextEditorState): Boolean {
		val mine = textLines
		val theirs = other.textLines
		if (mine === theirs) return true
		if (mine.size != theirs.size) return false
		for (i in mine.indices) {
			if (mine[i] != theirs[i]) {
				return false
			}
		}
		return true
	}

	init {
		setText(initialText ?: AnnotatedString(""))
	}
}

// Process-wide so two editors in one window can never mint the same id; copies
// only happen on the UI thread, so a plain increment is race-free in practice.
private var nextCopyId: Long = 1L
