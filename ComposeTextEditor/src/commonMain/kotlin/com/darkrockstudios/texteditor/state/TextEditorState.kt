package com.darkrockstudios.texteditor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.annotatedstring.normalizeLineEndings
import com.darkrockstudios.texteditor.annotatedstring.splitAnnotatedString
import com.darkrockstudios.texteditor.annotatedstring.subSequence
import com.darkrockstudios.texteditor.annotatedstring.toAnnotatedString
import com.darkrockstudios.texteditor.coerceInto
import com.darkrockstudios.texteditor.cursor.CursorMetrics
import com.darkrockstudios.texteditor.cursor.getWrapForDrawing
import com.darkrockstudios.texteditor.cursor.getWrappedLineIndex
import com.darkrockstudios.texteditor.effectiveHeight
import com.darkrockstudios.texteditor.lastRowAtOrAbove
import com.darkrockstudios.texteditor.rowAt
import com.darkrockstudios.texteditor.rowIndexOf
import com.darkrockstudios.texteditor.input.EditorActionRegistry
import com.darkrockstudios.texteditor.input.KeyboardSettings
import com.darkrockstudios.texteditor.input.KillRing
import com.darkrockstudios.texteditor.input.TabSettings
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.LineBlockEditBehavior
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.normalizeLineBlocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onSubscription
import kotlin.concurrent.Volatile
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

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
 * [documentGeneration].
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
			updateBookKeeping(LayoutUpdate.Reshape)
		}

	var textStyle: TextStyle = TextStyle.Default
		internal set(value) {
			if (field != value) {
				field = value
				invalidateLayoutInputs()
				updateBookKeeping(LayoutUpdate.Reshape)
			}
		}

	/**
	 * The space below every paragraph, unless the paragraph's own format says
	 * otherwise; mirrored from [com.darkrockstudios.texteditor.TextEditorStyle.paragraphSpacing].
	 */
	var paragraphSpacing: Dp = 0.dp
		internal set(value) {
			if (field != value) {
				field = value
				invalidateLayoutInputs()
				updateBookKeeping(LayoutUpdate.Reshape)
			}
		}

	private var lineBreakWidthKey: Pair<TextMeasurer, TextStyle>? = null
	private var lineBreakWidthPx = 0f

	/**
	 * Width of the sliver a selected line break draws: one space in [textStyle], with
	 * no indent, which layout strips from the style as well.
	 */
	internal val lineBreakWidth: Float
		get() {
			val key = textMeasurer to textStyle
			if (key != lineBreakWidthKey) {
				lineBreakWidthPx = textMeasurer.measure(" ", textStyle.copy(textIndent = TextIndent.None))
					.size.width.toFloat()
				lineBreakWidthKey = key
			}
			return lineBreakWidthPx
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
			// The typing style is derived from this as well as from the text, so a
			// config swap invalidates it even though the document did not change.
			cursor.refreshStyles()
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
		private set(value) {
			// The edit that replaced the lines re-anchors the spans in the same transaction.
			check(value.spanIndex.lineCount == value.lines.size) { "spans indexed for ${value.spanIndex.lineCount} of ${value.lines.size} lines" }
			val textChanged = value.lines !== field.lines
			field = value
			_revision.intValue++
			if (textChanged) _textRevision.intValue++
		}

	/**
	 * The lines mutated since the last publish, as how many at each end are untouched,
	 * narrowed by every mutation (the same composition as the whole-text base's), and
	 * whether a span was added, removed or lost, or a line came or went. Normalization
	 * examines only those lines.
	 */
	private var untouchedBefore = Int.MAX_VALUE
	private var untouchedAfter = Int.MAX_VALUE
	private var spansChanged = false

	private fun markChanged(unchangedBefore: Int, unchangedAfter: Int) {
		untouchedBefore = minOf(untouchedBefore, unchangedBefore)
		untouchedAfter = minOf(untouchedAfter, unchangedAfter)
	}

	/** [snapshot] with the line-block invariants repaired over the lines changed since the last publish. */
	private fun normalized(snapshot: DocumentSnapshot): DocumentSnapshot {
		val lines = snapshot.lines.size
		val first = minOf(untouchedBefore, lines)
		val end = lines - minOf(untouchedAfter, lines)
		val result = normalizeLineBlocks(snapshot, markdownConfiguration, first until end, spansChanged)
		untouchedBefore = Int.MAX_VALUE
		untouchedAfter = Int.MAX_VALUE
		spansChanged = false
		return result
	}

	private val _revision = mutableIntStateOf(0)
	private val _textRevision = mutableIntStateOf(0)

	/**
	 * Advances with every published [content], as snapshot state: the document itself
	 * is not, so a derived value (semantics, the word count) reads this to be recomputed
	 * after an edit.
	 */
	internal val revision: Int get() = _revision.intValue

	/** [revision], advancing only when the text changes: a rich-span change leaves it alone. */
	internal val textRevision: Int get() = _textRevision.intValue

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

	/** Undo actions registered by [onRollback], run if the transaction throws. */
	private val pendingRollbackActions = mutableListOf<() -> Unit>()

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
	 * The revision is also one undo step: edits recorded inside are staged by the
	 * history and land together when the transaction commits (see [editGroup]).
	 *
	 * Re-entrant: a nested call joins the outer transaction and commits with it. A
	 * throwing [block] discards the draft and leaves [content] on the previous
	 * revision, because a half-applied revision would keep serializing block markers
	 * onto the wrong lines long after the failure rather than only during it. The
	 * caret and selection return to where they were, other state outside the
	 * document is put back by the block's [onRollback] actions, and the staged
	 * history entries are dropped with the draft.
	 */
	internal fun <T> withAtomicEdit(block: () -> T): T {
		if (draft != null) return block()
		draft = content
		editManager.history.beginGroup()
		// The caret and selection live outside the draft; a rollback puts them back
		// too, or they would address the revision that was discarded.
		val cursorBefore = cursor.position
		val affinityBefore = cursor.affinity
		val selectionBefore = selector.selection
		val touchSelectionBefore = selector.isTouchSelection
		var committed = false
		try {
			val result = block()
			// Every publish passes through line-block normalization, so no caller
			// can commit a revision violating the placeholder-line invariant. A
			// transaction that mutated nothing skips the scan and the republish.
			draft?.let {
				if (it !== content) {
					val normalized = normalized(it)
					// Normalization can rewrite lines no operation declared dirty,
					// so a rewrite invalidates any deferred partial relayout.
					if (normalized !== it) invalidateLayoutInputs()
					content = normalized
				}
			}
			draft = null
			committed = true
			pendingRollbackActions.clear()
			editManager.history.endGroup(commit = true)
			refreshHistoryFlags()
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
			// the scroll, the history entries, and the queued actions, which would
			// announce an edit that no longer exists.
			draft = null
			pendingLayoutUpdate = null
			pendingCursorScroll = false
			pendingCommitActions.clear()
			if (!committed) {
				untouchedBefore = Int.MAX_VALUE
				untouchedAfter = Int.MAX_VALUE
				spansChanged = false
				val rollbacks = pendingRollbackActions.asReversed().toList()
				pendingRollbackActions.clear()
				rollbacks.forEach { it() }
				// After the rollbacks: a document load's rollback restores the entries
				// it cleared, staged ones included, and those go with the draft too.
				editManager.history.endGroup(commit = false)
				// Cleared first, which also drops any touch mode the block left pending.
				selector.clearSelection()
				if (selectionBefore != null) {
					selector.updateSelection(selectionBefore.start, selectionBefore.end)
					if (touchSelectionBefore) selector.markTouchSelection()
				}
				cursor.updatePosition(cursorBefore, affinityBefore)
			}
		}
	}

	/**
	 * Runs [block] as one undo step and one published revision: every edit made
	 * inside, through any of the editing functions, is reverted by a single [undo]
	 * and re-applied by a single [redo], which restore the text, the spans, and the
	 * caret from before and after the group.
	 *
	 * Nested groups join the outermost one. A group of one typed character
	 * coalesces with surrounding typing as the character alone would, and typing
	 * right after a group that ended in a typed character (a character typed over a
	 * selection) continues that group's step. If [block] throws, the document is
	 * left as it was, nothing is recorded, and the redo stack is untouched. [canUndo]
	 * and [canRedo] reflect the step once the group has committed, not before, and
	 * calling [undo] or [redo] inside the block is an error.
	 *
	 * The built-in compound edits (a rich paste, a link) already run in a group; a
	 * host uses this for its own, such as a find-and-replace-all or a template
	 * insertion.
	 */
	fun <T> editGroup(block: () -> T): T = withAtomicEdit(block)

	private fun refreshHistoryFlags() {
		_canUndo = editManager.history.hasUndoLevels()
		_canRedo = editManager.history.hasRedoLevels()
	}

	/**
	 * Registers [action] to undo a side effect of the open transaction if it throws,
	 * or drops it when there is none, since the side effect then stands.
	 */
	private fun onRollback(action: () -> Unit) {
		if (draft != null) pendingRollbackActions += action
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
			val normalized = normalized(transformed)
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

	/**
	 * Whether the editor holds focus and takes input. False while it is disabled or
	 * read-only even when focused; [hasFocus] says whether it holds focus at all.
	 */
	var isFocused by mutableStateOf(false)

	/**
	 * Whether the editor holds focus, whether or not it takes input: a disabled or
	 * read-only editor, or a selectable `RichTextView`, still takes focus to select and
	 * copy. Use this for focus chrome such as a border.
	 */
	var hasFocus by mutableStateOf(false)
		internal set

	/**
	 * The current IME composing region (for autocomplete preview).
	 * When non-null, this text should be rendered with an underline.
	 * This is set by the Android InputConnection during text composition.
	 */
	var composingRange: TextEditorRange? by mutableStateOf(null)
		internal set

	/**
	 * Whether [composingRange] holds text the IME typed itself (`setComposingText`),
	 * as opposed to existing text it marked (`setComposingRegion`, the shape of an
	 * autocorrect). Only the former's commit is the user's own typing for undo.
	 * Written only with the range, so the two cannot disagree.
	 */
	internal var composingIsTyped = false
		private set

	/**
	 * The caret's pixel metrics as the last frame drew them, whether or not the blink
	 * showed it. A caret move is reflected from the next frame; the input methods
	 * measure the caret when they ask instead.
	 */
	var lastCursorMetrics: CursorMetrics? = null
		internal set

	/**
	 * Layout coordinates of the editor's drawing canvas, captured via
	 * `onGloballyPositioned`. The skiko input request uses them to translate the
	 * canvas-local caret into root coordinates for placing the input-method
	 * candidate window and the web backing input.
	 */
	var canvasLayoutCoordinates: LayoutCoordinates? = null
		internal set

	/**
	 * Where the canvas sits in the root, as snapshot state. [canvasLayoutCoordinates] is
	 * a plain field and stays the same object when the canvas moves, so observers of the
	 * input method's rectangles read this to follow moves as well as resizes.
	 */
	internal var canvasPositionInRoot by mutableStateOf(Offset.Unspecified)

	// Referential: every pass publishes a new list, and comparing two by content would
	// build every row of both.
	private var _lineOffsets by mutableStateOf<List<LineWrap>>(emptyList(), referentialEqualityPolicy())

	/** The laid-out rows, the same object [_lineOffsets] holds once a pass has run. */
	private var rows: RowList? = null

	/**
	 * Guards partial relayout. [layoutInputGeneration] advances whenever an input that
	 * shapes every line changes (style, measurer, density, viewport, normalization
	 * rewrites); a partial [updateBookKeeping] runs only when the last completed pass
	 * saw the same generation and a line count consistent with the update's delta.
	 */
	private var layoutInputGeneration = 0
	private var lastLayoutGeneration = -1

	/** The viewport width the last completed pass shaped to; rows depend on no other viewport dimension. */
	private var lastLayoutWidth = -1f

	/** The lines the last completed pass laid out. */
	private var lastLayoutLines: List<AnnotatedString>? = null

	internal fun invalidateLayoutInputs() {
		layoutInputGeneration++
	}

	/**
	 * The laid-out [LineWrap]s for the document: each visual (wrapped) line with its
	 * pixel offset, text-layout result, and resolved rich spans. Recomputed on every
	 * edit, style, or viewport change.
	 */
	val lineOffsets: List<LineWrap> get() = _lineOffsets

	/** The caret's position, its typing styles, and the selection, as they are now. */
	val cursorData: CursorData
		get() = CursorData(position = cursor.position, styles = cursor.styles, selection = selector.selection)

	/**
	 * Emits a [CursorData] snapshot (active styles, position, selection) at once on
	 * collection, then whenever the caret moves, the typing style changes, or the
	 * selection changes. Collect this to keep a toolbar or status display in sync with
	 * the editor.
	 */
	val cursorDataFlow: Flow<CursorData>
		get() = combine(
			// On subscription, not on start: a change between the two would be lost.
			cursor.stylesFlow.onSubscription { emit(cursor.styles) },
			cursor.positionFlow.onSubscription { emit(cursor.position) },
			selector.selectionRangeFlow.onSubscription { emit(selector.selection) },
		) { styles, position, selectionRange ->
			CursorData(position = position, styles = styles, selection = selectionRange)
		}

	/** The line a restored state scrolls to the top once it is laid out; see [rememberSaveableTextEditorState]. */
	internal var restoredFirstVisible: CharLineOffset? = null

	internal val wordCounter = WordCounter(this)

	/**
	 * The number of words in the document, as word motion and spell check segment them
	 * (the platform's ICU word breaks; a word holds a letter or digit). Observable in
	 * composition, and cheap to read after an edit: only the lines that changed are
	 * segmented again. [wordCount] with a range counts part of the document.
	 */
	val wordCount: Int get() = wordCounter.count

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
				updateBookKeeping(LayoutUpdate.Reshape)
			}
		}

	/** Scrolling, content height, and the currently visible line range. */
	val scrollManager = TextEditorScrollManager(
		scope = scope,
		scrollState = TextEditorScrollState(0),
		getLines = { textLines },
		getViewportSize = { viewportSize },
		getCursorPosition = { cursorPosition },
		getCursorAffinity = { cursor.affinity },
		getLineOffsets = { _lineOffsets },
		ensureLineShaped = ::ensureLineShaped,
	)

	/** The text selection: its [TextEditorRange], gestures, and selected-content queries. */
	val selector = TextEditorSelectionManager(this)
	internal val editManager = TextEditManager(this)

	/** Book-keeping for the document's [RichSpan] block decorations (lists, quotes, code fences, highlights). */
	val richSpanManager = RichSpanManager(this)

	/**
	 * Behaviors consulted before [insertNewlineAtCursor], [backspaceAtCursor] and
	 * [deleteAtCursor], and told after typed text has landed ([insertTypedString]
	 * and the IME's commits), in order; the first to claim an edit wins. Every
	 * input path reaches these, hardware keys and IME alike.
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
		val claimed = runBehaviors(hook)
		if (claimed) requestImeResync()
		return claimed
	}

	private fun runBehaviors(hook: (EditBehavior) -> Boolean): Boolean {
		if (behaviorDepth > 0) return false
		behaviorDepth++
		return try {
			editBehaviors.toList().any(hook)
		} finally {
			behaviorDepth--
		}
	}

	/**
	 * Tells the behaviors that typed [text] has landed at [range], after the
	 * default edit and any IME caret placement, so a behavior edits on top of the
	 * finished insert and owns the caret from there. The IME is asked to resync
	 * only when a behavior changed the document or moved the caret: the edit it
	 * expected has already happened, so a claim alone leaves its mirror right.
	 */
	internal fun textInputLanded(text: String, range: TextEditorRange) {
		// A lone line break is the Enter key, which has its own hook; the one that
		// lands here raw (an IME committing "\n" over its composition) is a
		// replacement of the composition, not typed text.
		if (text.isEmpty() || text == "\n") return
		// The working content, so an edit inside a host's open transaction counts.
		val contentBefore = workingContent
		val caretBefore = cursorPosition
		// An edit ends the chain, claimed or not: the range no longer holds.
		runBehaviors { it.onTextInput(this, text, range) || workingContent !== contentBefore }
		if (workingContent !== contentBefore || cursorPosition != caretBefore) requestImeResync()
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

	/** What the kill actions deleted, for a yank. */
	internal val killRing = KillRing()

	/** What Tab and Shift+Tab do: the indent size and character, or moving focus. */
	var tabSettings: TabSettings by mutableStateOf(TabSettings())

	/** What the soft keyboard is asked for: capitalisation, autocorrect, layout, and the action key. */
	var keyboardSettings: KeyboardSettings by mutableStateOf(KeyboardSettings())

	/**
	 * Called with the action when the soft keyboard's action key
	 * ([KeyboardSettings.imeAction]) is pressed. Null leaves the key to the default that
	 * [KeyboardSettings.imeAction] describes.
	 */
	var onImeAction: ((ImeAction) -> Unit)? = null

	/** The action key's default, supplied by the composed editor, which can move focus. */
	internal var defaultImeAction: ((ImeAction) -> Unit)? = null

	internal fun performImeAction(action: ImeAction) {
		(onImeAction ?: defaultImeAction)?.invoke(action)
	}

	/**
	 * Screens every edit that adds text, from the user or the editing functions, but not
	 * undo, redo or a document load; see [EditorInputFilter]. Null lets everything in.
	 */
	var inputFilter: EditorInputFilter? by mutableStateOf(null)

	/**
	 * How many composed editors show this state with a single-line limit; while any does,
	 * [EditorInputFilter.SingleLine] screens ahead of [inputFilter].
	 */
	internal var singleLineEditors by mutableIntStateOf(0)

	internal val effectiveInputFilter: EditorInputFilter?
		get() {
			if (singleLineEditors == 0) return inputFilter
			return inputFilter?.let { EditorInputFilter.SingleLine then it } ?: EditorInputFilter.SingleLine
		}

	/**
	 * How to open the context menu of each composable showing this state, which adds its
	 * own while composed. The last opens.
	 */
	internal val contextMenuOpeners = mutableListOf<() -> Unit>()

	private val _documentGeneration = MutableStateFlow(0)

	/**
	 * Increments each time [setText] or [setDocument] swaps the whole document. A
	 * replacement is not an edit and emits nothing on [editOperations], so anything
	 * deriving state from the text (spell check, search results) has no other way to
	 * learn its document is gone. Being a [StateFlow], a collector that subscribes
	 * after a replacement still sees it. Inside a transaction it increments only once
	 * the transaction commits, so a listener sees the finished document and a failed
	 * load is never announced.
	 */
	val documentGeneration: StateFlow<Int> = _documentGeneration

	private fun announceReplacement() {
		onCommit { _documentGeneration.value++ }
	}

	/**
	 * Replaces the entire document with [text], clearing rich spans and undo history
	 * and resetting book-keeping. To edit existing content instead, use [replace] or
	 * the cursor operations.
	 */
	fun setText(text: String) {
		replaceContent(text.normalizeLineEndings().split("\n").map { it.toAnnotatedString() })
		clearHistory()
		updateBookKeeping()
		cursor.refreshStyles()
	}

	/**
	 * Replaces the entire document with [text], preserving its character-level spans
	 * while clearing rich spans and undo history and resetting book-keeping. To edit
	 * existing content instead, use [replace] or the cursor operations; to load a
	 * document along with its rich spans, use [setDocument].
	 */
	fun setText(text: AnnotatedString) {
		replaceContent(text.normalizeLineEndings().splitAnnotatedString())
		clearHistory()
		updateBookKeeping()
		cursor.refreshStyles()
	}

	/**
	 * Replaces the entire document with [document]'s lines and rich spans in a single
	 * revision, typically one taken from another editor with [snapshot]. Unlike
	 * [setText], rich spans (rules, images, code fences, list and quote markers) come
	 * along.
	 *
	 * Decoration spans are dropped, since they belong to whatever produced them in the
	 * source editor, and spans that do not fit the incoming lines are clamped onto them.
	 * Like any document load this is not undoable: history is cleared, the selection
	 * and composing region are dropped, the cursor is coerced into the new document,
	 * and it increments [documentGeneration] rather than emitting on [editOperations].
	 */
	fun setDocument(document: DocumentSnapshot) {
		val lines = document.lines.ifEmpty { listOf(AnnotatedString("")) }
		val spans = document.richSpans.mapNotNullTo(mutableSetOf()) { span ->
			if (span.style.isDecoration) null else clampSpanToLines(span, lines)
		}
		// An already-clean snapshot is published as is; it is immutable, and sharing
		// it keeps its memoized indexes.
		val clean = lines === document.lines && spans == document.richSpans
		// A load is a change to every line.
		markChanged(0, 0)
		spansChanged = true
		mutateContent { if (clean) document else DocumentSnapshot(lines, spans) }
		announceReplacement()

		clearHistory()
		val previousComposing = composingRange
		val previousComposingTyped = composingIsTyped
		val previousSelection = selector.selection
		clearComposingRange()
		selector.clearSelection()
		onRollback {
			composingRange = previousComposing
			composingIsTyped = previousComposingTyped
			previousSelection?.let { selector.updateSelection(it.start, it.end) }
		}
		updateBookKeeping()
		cursor.updatePosition(cursor.position)
	}

	/**
	 * Drops every undo and redo entry. Their offsets address the document a wholesale
	 * replacement just discarded, so replaying one would corrupt the new content.
	 * Cleared immediately rather than at commit, so an edit later in the same
	 * transaction cannot coalesce into an entry from the old document.
	 */
	/** A replaced document starts afresh: no history, and nothing killed from the old one to yank. */
	private fun clearHistory() {
		killRing.clear()
		val restore = editManager.history.clearRestorably()
		refreshHistoryFlags()
		onRollback {
			restore()
			refreshHistoryFlags()
		}
	}

	/** Sets [isFocused]; losing focus also clears any pending IME composing region. */
	fun updateFocus(focused: Boolean) {
		isFocused = focused
		// Clear composing state when focus is lost
		if (!focused) {
			clearComposingRange()
		}
	}

	/**
	 * Updates the IME composing region.
	 * Called by the Android InputConnection when composing text changes.
	 * @param startIndex Character index of composing start, or -1 to clear
	 * @param endIndex Character index of composing end, or -1 to clear
	 */
	internal fun updateComposingRange(startIndex: Int, endIndex: Int, typed: Boolean = false) {
		val range = if (startIndex >= 0 && endIndex > startIndex) {
			val startOffset = getOffsetAtCharacter(startIndex)
			val endOffset = getOffsetAtCharacter(endIndex)
			TextEditorRange(startOffset, endOffset)
		} else {
			null
		}
		composingRange = range
		composingIsTyped = range != null && typed
	}

	/**
	 * Clears the IME composing region.
	 */
	internal fun clearComposingRange() {
		composingRange = null
		composingIsTyped = false
	}

	/**
	 * Advances whenever the editor answered an IME request its own way, so the IME's
	 * mirror of the buffer may no longer match. A platform with an IME remembers the
	 * generation it last acted on and resyncs the keyboard as it can: Android restarts
	 * input only when a selection report cannot tell the keyboard, so a substitution that
	 * keeps the caret where the keyboard expects it leaves the keyboard's copy of the
	 * text as `EditText` would. Snapshot state, so the skiko session can observe it.
	 */
	internal var imeResyncGeneration by mutableIntStateOf(0)
		private set

	internal fun requestImeResync() {
		imeResyncGeneration++
	}

	/**
	 * Inserts a line break at the cursor, splitting the current line, unless an
	 * [EditBehavior] claims the edit first.
	 */
	fun insertNewlineAtCursor() {
		// Asked before the behaviors, which would otherwise mark a line the split never made.
		if (screenInput(TextEditorRange(cursorPosition, cursorPosition), AnnotatedString("\n")) == null) {
			return requestImeResync()
		}
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
	 * Deletes the code point before the cursor, or a whole emoji sequence (see
	 * [backspaceStart]), merging with the previous line when at column 0, unless an
	 * [EditBehavior] claims the edit first.
	 */
	fun backspaceAtCursor() = backspaceAtCursor(from = null)

	/**
	 * [backspaceAtCursor], deleting back to [from] when an [EditBehavior] leaves the
	 * edit alone, rather than to the editor's own backspace unit. A platform whose
	 * keyboard picks the unit itself (iOS's, which takes a whole cluster) passes it.
	 */
	internal fun backspaceAtCursor(from: CharLineOffset?) {
		if (claimedByBehavior { it.onBackspace(this) }) return

		val caret = cursorPosition
		val start = from?.takeIf { it isBefore caret } ?: when {
			caret.char > 0 -> CharLineOffset(caret.line, textLines[caret.line].text.backspaceStart(caret.char))
			caret.line > 0 -> CharLineOffset(caret.line - 1, textLines[caret.line - 1].length)
			else -> return
		}
		val operation = TextEditOperation.Delete(
			range = TextEditorRange(start, caret),
			cursorBefore = caret,
			cursorAfter = start,
		)
		if (start.line == caret.line) {
			// Typing whatever the cluster's length, so an emoji joins the backspace run.
			editManager.recordingAsTyping(true) { editManager.applyOperation(operation) }
		} else {
			editManager.applyOperation(operation)
		}
	}

	/**
	 * Deletes the grapheme cluster after the cursor, merging the next line into the
	 * current one when at end of line (forward delete), unless an [EditBehavior]
	 * claims the edit first.
	 */
	fun deleteAtCursor() {
		if (claimedByBehavior { it.onDeleteForward(this) }) return

		val lineText = textLines[cursorPosition.line].text
		if (cursorPosition.char < lineText.length) {
			val deleteRange = TextEditorRange(
				cursorPosition,
				CharLineOffset(cursorPosition.line, lineText.followingGraphemeBoundary(cursorPosition.char))
			)

			val operation = TextEditOperation.Delete(
				range = deleteRange,
				cursorBefore = cursorPosition,
				cursorAfter = cursorPosition
			)
			editManager.recordingAsTyping(true) { editManager.applyOperation(operation) }
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
		if (char == '\n' || char == '\r') return insertStringAtCursor("\n")
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
		@Suppress("NAME_SHADOWING")
		val text = text.normalizeLineEndings()
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

	/**
	 * Deletes the text covered by [range], leaving the cursor at the range start.
	 * A collapsed [range] covers nothing and is no edit at all.
	 */
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
		@Suppress("NAME_SHADOWING")
		val newText = newText.normalizeLineEndings()
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

	/**
	 * Replaces lines [first] through [last] with [replacement], splicing the line list so
	 * only the chunks holding those lines are copied. [last] of `first - 1` inserts
	 * before [first]. A document left with no lines gets one empty line.
	 */
	internal fun replaceLines(first: Int, last: Int, replacement: List<AnnotatedString>) {
		val lines = workingContent.lineList
		val from = first.coerceIn(0, lines.size)
		val to = last.coerceIn(from - 1, lines.lastIndex)
		val updated = lines.splice(from, to + 1, replacement)
		if (updated.isEmpty()) return setLines(listOf(AnnotatedString("")))
		linesWritten += replacement.size
		setLines(updated, LineSplice(unchangedBefore = from, unchangedAfter = lines.size - (to + 1)))
	}

	/**
	 * Publishes [lines] as the entire document and drops every rich span in a single
	 * write. A full content replacement leaves any prior spans pointing at stale line
	 * indices: on a markdown roundtrip, leftover bullet/blockquote spans would block
	 * `applyLineBlock` from re-attaching the paragraph indent and the gutter marker
	 * would draw over the first character of the line.
	 */
	private fun replaceContent(lines: List<AnnotatedString>) {
		markChanged(0, 0)
		spansChanged = true
		mutateContent { DocumentSnapshot(lines, emptySet()) }
		announceReplacement()
	}

	/**
	 * How many lines have been written: [replaceLines]' replacements and the whole of any
	 * list [setLines] is handed that is not the spliced line list itself. The cost tests
	 * read it to catch a list rebuilt per line.
	 */
	internal var linesWritten = 0L
		private set

	/** Publishes [lines]; [splice] says which lines changed, when the caller knows. */
	internal fun setLines(lines: List<AnnotatedString>, splice: LineSplice? = null) {
		if (lines !is LineList) linesWritten += lines.size
		if (splice != null) markChanged(splice.unchangedBefore, splice.unchangedAfter) else markChanged(0, 0)
		mutateContent { it.withLines(lines, splice) }
	}

	internal fun setLine(index: Int, text: AnnotatedString) {
		val lineCount = workingContent.lines.size
		if (index !in 0 until lineCount) throw IndexOutOfBoundsException("line $index of $lineCount")
		replaceLines(index, index, listOf(text))
	}

	/** Replaces every span, as a document load does. */
	internal fun setRichSpans(richSpans: Set<RichSpan>) {
		markChanged(0, 0)
		spansChanged = true
		mutateContent { it.withRichSpans(richSpans) }
	}

	/** Publishes [index], whose spans on [first] through [last] differ from the current one's. */
	internal fun setSpanIndex(index: SpanIndex, first: Int, last: Int, spansChanged: Boolean = true) {
		val lines = workingContent.lines.size
		markChanged(first.coerceAtLeast(0), (lines - 1 - last).coerceAtLeast(0))
		if (spansChanged) this.spansChanged = true
		mutateContent { it.withSpanIndex(index) }
	}

	/** Publishes [index], which differs from the current one by [spans]. */
	internal fun setSpanIndex(index: SpanIndex, spans: Collection<RichSpan>) {
		var first = Int.MAX_VALUE
		var last = -1
		for (span in spans) {
			first = minOf(first, span.range.start.line)
			last = maxOf(last, span.range.end.line)
		}
		setSpanIndex(index, first, last)
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
	fun getWrappedLineIndex(position: CharLineOffset): Int = _lineOffsets.rowIndexOf(position)

	/**
	 * The index into [lineOffsets] of the row the caret is drawn on, or -1 if none
	 * matches. At a wrap offset the caret's affinity picks the row; every other read
	 * of the caret's row goes through here.
	 */
	internal fun cursorRowIndex(): Int = _lineOffsets.getWrappedLineIndex(cursorPosition, cursor.affinity)

	/** Returns the [LineWrap] (visual line) that contains [position]. */
	fun getWrappedLine(position: CharLineOffset): LineWrap =
		_lineOffsets.rowAt(position) ?: throw NoSuchElementException("No row holds $position")

	/** Returns the [LineWrap] at visual-line index [vLineIndex] in [lineOffsets]. */
	fun getWrappedLine(vLineIndex: Int): LineWrap {
		return _lineOffsets[vLineIndex]
	}

	/**
	 * Records the editor's new viewport [size] and re-wraps the document to fit a new
	 * width. A change of height alone, as when a soft keyboard opens or closes, shapes
	 * nothing: it only moves the scroll range. When a focused editor gets shorter, a caret
	 * in view (or on its way there) stays in view.
	 */
	fun onViewportSizeChange(size: Size) {
		val collapsed = size.width <= 1f || size.height <= 1f
		val keepCaret = isFocused && lineOffsets.isNotEmpty() && !collapsed &&
				size.width == viewportSize.width && size.height < viewportSize.height &&
				scrollManager.isCursorInViewOrScrolling()
		viewportSize = size
		when {
			!rowsAreCurrent(size.width) -> {
				invalidateLayoutInputs()
				updateBookKeeping(LayoutUpdate.Reshape)
			}
			// The rows wait, unchanged, for the viewport to open again.
			collapsed -> Unit
			else -> scrollManager.onViewportHeightChange()
		}
		// After the relayout, which an open transaction holds until it commits.
		if (keepCaret) onCommit { scrollManager.snapCursorVisible() }
	}

	/**
	 * Whether the last completed pass laid out the current lines at [width] against the
	 * current layout inputs, outside any transaction. A pass the collapsed viewport
	 * skipped leaves the rows behind the text, which the generation records.
	 */
	private fun rowsAreCurrent(width: Float): Boolean =
		draft == null && _lineOffsets.isNotEmpty() && width == lastLayoutWidth &&
				lastLayoutGeneration == layoutInputGeneration && lastLayoutLines === textLines

	/**
	 * Returns the [CursorMetrics] (pixel position and line height) for the caret at
	 * [CharLineOffset] [position], accounting for the current scroll offset.
	 */
	fun getPositionForOffset(position: CharLineOffset): CursorMetrics =
		getPositionForOffset(position, CaretAffinity.Downstream)

	/** [getPositionForOffset] on the row [affinity] picks at a wrap offset. */
	internal fun getPositionForOffset(position: CharLineOffset, affinity: CaretAffinity): CursorMetrics {
		val currentWrappedLine = lineOffsets.getWrapForDrawing(position, affinity)
			?: return CursorMetrics(position = Offset.Zero, height = 0f)

		val cursorX = currentWrappedLine.caretX(position.char)
		val cursorY = currentWrappedLine.offset.y - scrollState.value

		val lineHeight = currentWrappedLine.effectiveHeight

		return CursorMetrics(
			position = Offset(cursorX, cursorY),
			height = lineHeight
		)
	}

	/**
	 * Maps a pixel [Offset] within the editor (e.g. a tap location) to the nearest
	 * [CharLineOffset], accounting for scroll. A point above the first row hits the
	 * first row and a point below the last row hits the last row; x is hit-tested on
	 * that row either way, as native text fields do.
	 */
	fun getOffsetAtPosition(offset: Offset): CharLineOffset {
		val rows = _lineOffsets
		if (rows.isEmpty()) return CharLineOffset(0, 0)

		val contentY = offset.y + scrollState.value
		val row = rows[rows.lastRowAtOrAbove(contentY).coerceAtLeast(0)]
		val lineLength = textLines.getOrNull(row.line)?.length
			?: return CharLineOffset(textLines.lastIndex, textLines.last().length)

		// Hit-test inside the row itself, so a point above, below, or in a block
		// line's extra height lands on that row's text line.
		val paragraph = row.textLayoutResult.multiParagraph
		val lineHeight = paragraph.getLineHeight(row.virtualLineIndex)
		val yInLine = (contentY - row.offset.y).coerceIn(0f, (lineHeight - 1f).coerceAtLeast(0f))
		val charPos = paragraph.getOffsetForPosition(
			Offset(offset.x - row.offset.x, paragraph.getLineTop(row.virtualLineIndex) + yInLine)
		)
		val lineText = textLines[row.line].text
		// Skia already answers on a cluster boundary; the snap guards the caret invariant.
		return CharLineOffset(row.line, lineText.snapToGraphemeBoundary(min(charPos, lineLength), forward = false))
	}

	/**
	 * The [RichSpan] under a pointer at [offset], in the same coordinates as
	 * [getOffsetAtPosition]. Unlike that mapping, a point above the first row, below
	 * the last row, or in the side padding is over no span.
	 */
	internal fun findSpanAtPoint(offset: Offset): RichSpan? {
		val first = _lineOffsets.firstOrNull() ?: return null
		val last = _lineOffsets.last()
		val contentY = offset.y + scrollState.value
		if (contentY < first.offset.y || contentY >= last.offset.y + last.effectiveHeight) return null
		if (offset.x < 0f || offset.x > viewportSize.width) return null
		return findSpanAtPosition(getOffsetAtPosition(offset))
	}

	/**
	 * Converts a flat character [index] into the document to its [CharLineOffset].
	 * The inverse of [getCharacterIndex]; clamps to the document start or end when out of range.
	 */
	fun getOffsetAtCharacter(index: Int): CharLineOffset {
		val content = workingContent
		val lineCount = content.lines.size
		if (index < 0 || lineCount == 0) return CharLineOffset(0, 0)
		if (index > content.textLength) {
			return CharLineOffset(lineCount - 1, content.lines[lineCount - 1].length)
		}

		val line = content.lineOfCharacter(index)
		return CharLineOffset(line, index - content.lineStart(line))
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
		return workingContent.lineStart(safe.line) + safe.char
	}

	fun CharLineOffset.toCharacterIndex(): Int = getCharacterIndex(this)

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

		return workingContent.lineStart(lineIndex)
	}

	internal fun updateBookKeeping(update: LayoutUpdate = LayoutUpdate.Full) {
		// Inside a transaction the content is a half-applied draft; merge the request
		// and lay out once at commit.
		if (draft != null) {
			pendingLayoutUpdate = pendingLayoutUpdate?.mergedWith(update) ?: update
			return
		}

		// Defer until the viewport has a real size; the 1×1 sentinel forces character-wide wraps.
		// The skipped pass leaves the rows behind the text, so the next one must be full.
		if (viewportSize.width <= 1f || viewportSize.height <= 1f) {
			invalidateLayoutInputs()
			return
		}

		val content = content
		val lines = content.lineList
		val spans = content.spanIndex
		val previous = rows

		// A partial pass is only sound against the exact layout the last pass produced.
		// Degrade to full when the cache is missing, a full invalidator (style, measurer,
		// density, viewport, normalization) fired since, or the line count disagrees
		// with the update's own delta; reusing stale layouts corrupts every consumer.
		val partial = (update as? LayoutUpdate.Partial)?.takeIf {
			previous != null &&
					lastLayoutGeneration == layoutInputGeneration &&
					previous.lineCount == lines.size - it.lineDelta &&
					(it.lineDelta == 0 || it.remeasureFirst <= it.remeasureLast)
		}

		// A reshape stands on the rows as they are while the lines settle; rows laid
		// out for other lines or spans (a pass skipped while the viewport was
		// collapsed) cannot stand in, so everything shapes now.
		if (update is LayoutUpdate.Reshape && previous != null && lastLayoutLines === lines && previous.spans === spans) {
			reshapeLazily(previous)
			return
		}

		val laidOut = if (partial == null) layoutAll(lines, spans) else layoutPartial(previous!!, partial, lines, spans)
		if (laidOut === previous) return
		if (partial == null) {
			settleJob?.cancel()
		} else if (partial.lineDelta != 0) {
			// The settling walks continue past the edit, whose lines moved.
			if (settleAbove >= partial.remeasureFirst) settleAbove += partial.lineDelta
			if (settleBelow > partial.remeasureLast) settleBelow += partial.lineDelta
		}
		publishRows(laidOut)
		lastLayoutGeneration = layoutInputGeneration
		lastLayoutWidth = viewportSize.width
		lastLayoutLines = lines
	}

	private fun publishRows(laidOut: RowList) {
		rows = laidOut
		_lineOffsets = laidOut
		// Rounded up so the last row's fraction of a pixel is still in reach.
		scrollManager.updateContentHeight(ceil(laidOut.lastRowBottom()).toInt())
	}

	/** The settling reshape under way, shaping the lines out of view a slice at a time. */
	private var settleJob: Job? = null

	/** How many lines a settling slice shapes before yielding to the frame. */
	private val settleSlice = 32

	/** Where the settling walks continue from: the next lines to try above and below the viewport. */
	private var settleAbove = -1
	private var settleBelow = Int.MAX_VALUE

	/**
	 * Reshapes lazily (7.48): the lines with a row in the viewport, and a viewport's
	 * worth beyond each edge, are shaped now; every other line keeps its layout at the
	 * old shape until the settling job reaches it. The scroll stays anchored to the
	 * line at the top of the viewport, at its offset within it.
	 */
	private fun reshapeLazily(previous: RowList) {
		settleJob?.cancel()
		// An animated scroll's target was measured against rows about to change.
		scrollManager.stopScrolling()
		val keepCaret = isFocused && scrollManager.isCursorInViewOrScrolling()
		val scroll = scrollState.value.toFloat()
		val viewportHeight = viewportSize.height
		val anchorLine = previous.lineOfRow(previous.searchLastRowAtOrAbove(scroll).coerceAtLeast(0))
		val anchorOffset = scroll - previous.lineTop(anchorLine)
		val first = previous.lineOfRow(previous.searchFirstRowEndingAtOrBelow(scroll - viewportHeight).coerceAtMost(previous.size - 1))
		val last = previous.lineOfRow(previous.searchLastRowAtOrAbove(scroll + 2 * viewportHeight).coerceAtLeast(0))
		lastLayoutGeneration = layoutInputGeneration
		lastLayoutWidth = viewportSize.width
		lastLayoutLines = content.lineList
		reshapeLines(first, last)
		val settled = rows ?: return
		// Kept in the top padding when it was there, else within the anchor line.
		val within = anchorOffset.toDouble().coerceIn(minOf(anchorOffset.toDouble(), 0.0), (settled.layoutOf(anchorLine).height - 1).coerceAtLeast(0f).toDouble())
		scrollState.scrollTo((settled.lineTop(anchorLine) + within).roundToInt())
		settleAbove = first - 1
		settleBelow = last + 1
		if (first > 0 || last < previous.lineCount - 1) {
			// Between slices the frame gets to run: yield alone would not reach it on
			// Compose's dispatchers, which drain what a task enqueues in the same pass.
			settleJob = scope.launch {
				while (settleStep()) if (coroutineContext[MonotonicFrameClock] != null) withFrameNanos {} else yield()
				// The caret's row was measured against provisional rows; it may have left the view.
				if (keepCaret && isFocused) scrollManager.ensureCursorVisible()
			}
		}
	}

	/** Whether [line]'s layout was shaped under older inputs than the current ones. */
	private fun isProvisional(rows: RowList, line: Int): Boolean = rows.layoutOf(line).generation != layoutInputGeneration

	/**
	 * Shapes lines [first] through [last] at the current inputs, keeping their facts,
	 * and splices them in. The scroll moves by whatever that moved the top of the line
	 * at the top of the viewport, so what is on screen stays where it is; an animated
	 * scroll under way would write over that, so it is stopped.
	 */
	private fun reshapeLines(first: Int, last: Int) {
		val current = rows ?: return
		val content = content
		val lines = content.lineList
		val spans = content.spanIndex
		val shaper = LineShaper()
		val inputs = lineInputs()
		val scrollBefore = scrollState.value
		val topLine = if (current.size == 0) 0 else current.lineOfRow(current.searchLastRowAtOrAbove(scrollBefore.toFloat()).coerceIn(0, current.size - 1))
		val topBefore = current.lineTop(topLine)
		val layouts = ArrayList<LineLayout>(last - first + 1)
		for (line in first..last) {
			val onLine = spans.spansOn(line)
			val format = onLine.paragraphFormat(line)
			layouts += current.layoutOf(line).reshaped(shaper.shape(lines[line], format), line, onLine, format, inputs, layoutInputGeneration)
		}
		val settled = current.splice(first, last + 1, layouts, spans)
		publishRows(settled)
		val shift = (settled.lineTop(topLine) - topBefore).roundToInt()
		if (shift != 0) {
			scrollManager.stopScrolling()
			scrollState.scrollTo(scrollBefore + shift)
		}
	}

	/**
	 * Shapes the next slice of provisional lines: those with a row in the viewport
	 * first, else the nearer of the two walks continuing above and below it. Returns
	 * whether provisional lines remain.
	 */
	private fun settleStep(): Boolean {
		val current = rows ?: return false
		if (viewportSize.width <= 1f || viewportSize.height <= 1f || current.size == 0) return false
		val lineCount = current.lineCount
		val scroll = scrollState.value.toFloat()
		val topLine = current.lineOfRow(current.searchLastRowAtOrAbove(scroll).coerceIn(0, current.size - 1))
		val bottomLine = current.lineOfRow(current.searchLastRowAtOrAbove(scroll + viewportSize.height).coerceIn(0, current.size - 1))

		val inView = (topLine..bottomLine).firstOrNull { isProvisional(current, it) }
		if (inView != null) {
			var last = inView
			while (last < bottomLine && last - inView < settleSlice - 1 && isProvisional(current, last + 1)) last++
			reshapeLines(inView, last)
			return true
		}
		var above = settleAbove.coerceAtMost(lineCount - 1)
		while (above >= 0 && !isProvisional(current, above)) above--
		var below = settleBelow.coerceAtLeast(0)
		while (below < lineCount && !isProvisional(current, below)) below++
		if (above < 0 && below >= lineCount) {
			// A viewport that jumped leaves a band behind the walks: one sweep finds it.
			below = (0 until lineCount).firstOrNull { isProvisional(current, it) } ?: return false
		}
		val takeAbove = below >= lineCount || (above >= 0 && topLine - above <= below - bottomLine)
		if (takeAbove) {
			var first = above
			while (first > 0 && above - first < settleSlice - 1 && isProvisional(current, first - 1)) first--
			reshapeLines(first, above)
			settleAbove = first - 1
		} else {
			var last = below
			while (last < lineCount - 1 && last - below < settleSlice - 1 && isProvisional(current, last + 1)) last++
			reshapeLines(below, last)
			settleBelow = last + 1
		}
		return true
	}

	/** Finishes a settling reshape now, for tests and benchmarks. */
	internal fun settleLayout() {
		settleJob?.cancel()
		settleJob = null
		while (settleStep()) Unit
	}

	/**
	 * Shapes the provisional lines with a row between content-space [minY] and [maxY],
	 * before they are drawn, until none is left there: shaping moves the rows after it.
	 */
	internal fun shapeRowsInView(minY: Float, maxY: Float) {
		if (lastLayoutGeneration != layoutInputGeneration) return
		while (true) {
			val current = rows ?: return
			if (current.size == 0) return
			val first = current.lineOfRow(current.searchFirstRowEndingAtOrBelow(minY).coerceAtMost(current.size - 1))
			val last = current.lineOfRow(current.searchLastRowAtOrAbove(maxY).coerceAtLeast(0))
			val line = (first..last).firstOrNull { isProvisional(current, it) } ?: return
			var end = line
			while (end < last && isProvisional(current, end + 1)) end++
			reshapeLines(line, end)
		}
	}

	/** Shapes [line] now when it is provisional, so a scroll to it measures the real rows. */
	private fun ensureLineShaped(line: Int) {
		val current = rows ?: return
		if (line !in 0 until current.lineCount || lastLayoutGeneration != layoutInputGeneration) return
		if (isProvisional(current, line)) reshapeLines(line, line)
	}

	/** The inputs of one pass besides the shaping: density, viewport width and the paragraph spacing in pixels. */
	private fun lineInputs() = LineInputs(density, viewportSize.width, density?.run { paragraphSpacing.toPx() } ?: 0f)

	/** A full pass: every line shaped, every fact derived in line order. */
	private fun layoutAll(lines: LineList, spans: SpanIndex): RowList {
		val shaper = LineShaper()
		val facts = LineFacts(spans)
		val inputs = lineInputs()
		val layouts = ArrayList<LineLayout>(lines.size)
		for (line in 0 until lines.size) {
			facts.next(line)
			val onLine = spans.spansOn(line)
			val format = onLine.paragraphFormat(line)
			layouts += LineLayout.of(shaper.shape(lines[line], format), line, onLine, format, inputs, facts, layoutInputGeneration)
		}
		return RowList.of(layouts, spans)
	}

	/**
	 * A partial pass: [update]'s lines shaped or re-resolved, with a line each side for
	 * the fence edges, then the lines after them walked until one keeps its layout and
	 * its list counters, past which nothing can change; the result is spliced over
	 * [previous]. Every other line keeps its layout and moves with its chunk.
	 */
	private fun layoutPartial(
		previous: RowList,
		update: LayoutUpdate.Partial,
		lines: LineList,
		spans: SpanIndex,
	): RowList {
		val lastLine = lines.size - 1
		val shapeFirst = update.remeasureFirst
		val shapeLast = minOf(update.remeasureLast, lastLine)
		val spansFirst = update.spansFirst.coerceAtLeast(0)
		val spansLast = minOf(update.spansLast, lastLine)
		val shapes = shapeFirst <= shapeLast
		val respans = spansFirst <= spansLast
		if (!shapes && !respans) return previous.withSpans(spans)

		val first = (minOf(if (shapes) shapeFirst else Int.MAX_VALUE, if (respans) spansFirst else Int.MAX_VALUE) - 1).coerceAtLeast(0)
		val end = (maxOf(if (shapes) shapeLast else -1, if (respans) spansLast else -1) + 1).coerceAtMost(lastLine)
		// A line after the shaped range had its layout at its pre-edit index.
		fun oldIndex(line: Int) = if (shapes && line > shapeLast) line - update.lineDelta else line

		val facts = LineFacts(spans)
		if (first > 0) facts.resume(previous.layoutOf(oldIndex(first - 1)).counters)
		val shaper = LineShaper()
		val inputs = lineInputs()
		val layouts = ArrayList<LineLayout>(end - first + 2)
		var line = first
		while (line <= lastLine) {
			facts.next(line)
			val old = if (line in shapeFirst..shapeLast) null else previous.layoutOf(oldIndex(line))
			val layout = when {
				old == null -> {
					val onLine = spans.spansOn(line)
					val format = onLine.paragraphFormat(line)
					LineLayout.of(shaper.shape(lines[line], format), line, onLine, format, inputs, facts, layoutInputGeneration)
				}
				line in spansFirst..spansLast -> old.withSpans(line, spans.spansOn(line), inputs, facts)
				else -> old.withFacts(facts)
			}
			layouts += layout
			if (line >= end && layout === old) break
			line++
		}
		// The walk never stops inside the shaped range, whose old lines end at its last line's pre-edit index.
		val stop = minOf(line, lastLine)
		val oldEnd = if (shapes && stop >= shapeLast) stop - update.lineDelta + 1 else stop + 1
		return previous.splice(first, oldEnd, layouts, spans)
	}

	/** Shapes lines with the style, indent baking and width of one layout pass. */
	private inner class LineShaper {
		// Compose Android doesn't reliably honor per-paragraph ParagraphStyle
		// .textIndent overriding an editor-wide TextStyle.textIndent, so we
		// sidestep the merge: strip the indent from the outer style and bake it
		// into plain lines as their own ParagraphStyle. Block lines
		// already carry a ParagraphStyle from `applyLineBlock`.
		private val outerIndent = textStyle.textIndent
		private val needsIndentBaking = outerIndent != null && outerIndent != TextIndent.None
		private val measureStyle = if (needsIndentBaking) textStyle.copy(textIndent = TextIndent.None) else textStyle
		private val bakedIndentStyle = if (needsIndentBaking) ParagraphStyle(textIndent = outerIndent) else null

		// Use a tight width constraint (minWidth == maxWidth) so the paragraph lays out
		// at the full viewport width rather than shrinking to its natural content width.
		// The shrinking behavior interacts badly with TextIndent: if the paragraph
		// shrinks to its natural width W and then TextIndent consumes X pixels of
		// first-line width, the first line has only W-X pixels available instead of
		// viewportWidth-X, causing wraps that shouldn't happen.
		private val constraints = Constraints(
			minWidth = maxOf(1, viewportSize.width.toInt()),
			maxWidth = maxOf(1, viewportSize.width.toInt()),
			minHeight = 0,
			maxHeight = Constraints.Infinity
		)

		/**
		 * Shapes [line], with [format]'s alignment, indents and line height over the
		 * paragraph style the line carries (a block's indent) or the baked one. A line
		 * holds one paragraph style, so the merged one replaces it for measuring only.
		 */
		fun shape(line: AnnotatedString, format: ParagraphFormatSpanStyle? = null): TextLayoutResult {
			val measureLine = when {
				format != null && format.shapesText -> {
					val base = line.paragraphStyles.firstOrNull()?.item ?: bakedIndentStyle
					AnnotatedString(line.text, line.spanStyles, listOf(AnnotatedString.Range(format.paragraphStyleOver(base), 0, line.length)))
				}
				// Skip if the line already has a ParagraphStyle (block line):
				// Compose forbids overlapping ParagraphStyle ranges.
				bakedIndentStyle != null && line.paragraphStyles.isEmpty() ->
					buildAnnotatedString { withStyle(bakedIndentStyle) { append(line) } }
				else -> line
			}
			return try {
				textMeasurer.measure(text = measureLine, style = measureStyle, constraints = constraints)
			} catch (_: IllegalArgumentException) {
				// If measurement fails, create an empty layout result
				textMeasurer.measure(text = AnnotatedString(""), style = measureStyle, constraints = constraints)
			}
		}
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
			val added = richSpanManager.addRichSpansClamped(add)
			// Span overlays don't move text, so the flushed pass re-resolves the
			// lines they touch (the added ones where they landed) without shaping a
			// single line.
			var first = Int.MAX_VALUE
			var last = -1
			var reshapes = false
			for (span in remove) {
				first = minOf(first, span.range.start.line)
				last = maxOf(last, span.range.end.line)
				reshapes = reshapes || span.style.reshapesLine
			}
			for (span in added) {
				first = minOf(first, span.range.start.line)
				last = maxOf(last, span.range.end.line)
				reshapes = reshapes || span.style.reshapesLine
			}
			updateBookKeeping(if (reshapes) LayoutUpdate.Partial(first, last, 0) else LayoutUpdate.Spans(first, last))
		}
	}

	/**
	 * Returns the hit-testable [RichSpan] covering [position], or null if none does.
	 * Useful for hit-testing taps on a list item or code fence. A style whose
	 * [RichSpanStyle.isHitTestable] is false is never returned.
	 *
	 * Spans nest, so several can cover one position and only one can answer. Content
	 * spans (link, highlight) go first, then the editor's own decorations (spell
	 * check squiggle), then the line-anchored marker of the heading, list
	 * item, blockquote or code fence the line belongs to. Within a tier the span
	 * covering the least of the clicked line wins, so the marker answers only where
	 * nothing more specific does.
	 */
	fun findSpanAtPosition(position: CharLineOffset): RichSpan? {
		// Find the line wrap that contains our position
		val lineWrap = _lineOffsets.rowAt(position) ?: return null

		return lineWrap.richSpans
			.filter { it.style.isHitTestable && it.containsPosition(position) }
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

	/** [getAllText] without its styles, for a reader that needs the whole text as a string. */
	internal fun getAllPlainText(): String = workingContent.plainText

	/**
	 * The document's characters read in place, for a reader that needs a few of them (an
	 * input method looking around the caret) and must not build the whole text.
	 */
	internal val documentChars: CharSequence get() = workingContent.chars

	/** Returns the total character count of the document, counting newlines between lines. */
	fun getTextLength(): Int = workingContent.textLength

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
// Starts at random because an id leaves the process on the clipboard, and another
// app embedding the editor must not mint the one this copy carries.
private var nextCopyId: Long = kotlin.random.Random.nextLong()
