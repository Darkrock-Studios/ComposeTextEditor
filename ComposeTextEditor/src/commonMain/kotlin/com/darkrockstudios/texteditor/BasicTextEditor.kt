package com.darkrockstudios.texteditor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.darkrockstudios.texteditor.clipboard.ClipboardEventsEffect
import com.darkrockstudios.texteditor.clipboard.LocalPrimarySelection
import com.darkrockstudios.texteditor.clipboard.PrimarySelectionEffect
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.contextmenu.ContextMenuOpener
import com.darkrockstudios.texteditor.contextmenu.ContextMenuPlacement
import com.darkrockstudios.texteditor.contextmenu.ContextMenuStrings
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuProvider
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.cursor.DrawCursor
import com.darkrockstudios.texteditor.dragdrop.DrawDropCaret
import com.darkrockstudios.texteditor.dragdrop.TextDragAndDrop
import com.darkrockstudios.texteditor.dragdrop.textDragAndDrop
import com.darkrockstudios.texteditor.input.CaptureViewForIme
import com.darkrockstudios.texteditor.input.DrawHandwritingPreview
import com.darkrockstudios.texteditor.input.KeyBindings
import com.darkrockstudios.texteditor.input.LocalKeyBindings
import com.darkrockstudios.texteditor.input.TextEditorInputModifierElement
import com.darkrockstudios.texteditor.input.TextInputRequester
import com.darkrockstudios.texteditor.input.pastePlainText
import com.darkrockstudios.texteditor.input.placeCaretForHandwriting
import com.darkrockstudios.texteditor.input.stylusHandwriting
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.state.LayoutUpdate
import com.darkrockstudios.texteditor.state.LocalImeInsets
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.scrollbar.EditorHorizontalScrollbar
import com.darkrockstudios.texteditor.scrollbar.TextEditorScrollbar
import com.darkrockstudios.texteditor.state.LendComposition
import com.darkrockstudios.texteditor.state.SpanClickType
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.measuresKeyboardCover
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import com.darkrockstudios.texteditor.state.updateKeyboardCover
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds

private const val CURSOR_BLINK_SPEED_MS = 500L

/**
 * The rich text editor with no surface or border chrome — bring your own
 * container. [TextEditor] wraps this in a Material [androidx.compose.material3.Surface];
 * reach for [BasicTextEditor] directly when you need that wrapping under your own
 * control, a custom context menu, or per-line decoration.
 *
 * @param state Holds the document, cursor, selection, and undo history.
 * @param modifier Applied to the editor's outer bounds. To focus the editor from code,
 *   add a [androidx.compose.ui.focus.FocusRequester] here with
 *   `Modifier.focusRequester` and call `requestFocus()`, as with any focusable.
 * @param contentPadding Padding between the editor bounds and the text.
 * @param enabled When `false`, the editor is disabled: it takes no input, shows no
 *   caret, and reports itself disabled, with no edit actions, to accessibility
 *   services. It still takes focus, so its text can be selected and copied.
 * @param readOnly When `true`, the editor shows its caret, which moves and selects from
 *   the keyboard, pointer and screen readers, but takes no edits and raises no soft
 *   keyboard; copy stays available. Accessibility services hear a read-only field
 *   rather than a disabled one. Ignored when not [enabled].
 * @param lineLimits How tall the editor is: [EditorLineLimits.Fill], the default, takes
 *   the height it is given; [EditorLineLimits.MultiLine] grows with the text between a
 *   minimum and maximum number of lines, then scrolls; [EditorLineLimits.SingleLine]
 *   keeps the text to one paragraph, one row that scrolls sideways, whatever [softWrap]
 *   says. For a maximum length or other rules on what may be entered, set
 *   [TextEditorState.inputFilter].
 * @param autoFocus Requests focus once when first composed, if [enabled]. For focus at
 *   any other time, see [modifier].
 * @param style Colors and text style for the editor and its gutter markers.
 * @param contextMenuStrings Localized labels for the built-in context menu.
 * @param contextMenuState Drives context-menu visibility; pass your own to add
 *   custom items (e.g. spell-check suggestions), or leave `null` for the default.
 * @param onRichSpanClick Invoked when a rich span is clicked or tapped; see
 *   [RichSpanClick] for when, and [RichSpanClickListener] for what the return
 *   value does (and does not do).
 * @param onRichSpanClickEvent The same clicks as [onRichSpanClick], with the
 *   modifier keys that were held.
 * @param onLinkClick Opens a [com.darkrockstudios.texteditor.richstyle.LinkSpanStyle]'s
 *   URL on Ctrl+click, or Cmd+click under the macOS [keyBindings]. A plain click
 *   only places the caret. `null` leaves links to [onRichSpanClick]; pass
 *   `LocalUriHandler.current::openUri` to open them in the browser.
 * @param decorateLine Optional per-line decorator drawn behind each line, keyed by
 *   line index, useful for gutters, current-line highlights, or diff markers. It
 *   draws in the text canvas's coordinates, unclipped, and its offset is where the
 *   line's text is drawn, which with wrapping off ([softWrap], or a single line) moves
 *   with the sideways scroll: a gutter places itself by its own x.
 * @param keyBindings Chord-to-command mapping, defaulting to [LocalKeyBindings].
 *   Bind chords to actions registered on [TextEditorState.actions] to add
 *   shortcuts of your own.
 * @param contentDescription The editor's label for accessibility services, read with
 *   its text ("Notes, edit box, ..."). Set it here rather than through [modifier]'s
 *   semantics, which land on a container around the editable node.
 * @param softWrap Whether lines wrap at the editor's width, as in `BasicTextField`. With
 *   `false` a line stays one row however long, and the editor scrolls sideways
 *   ([TextEditorState.horizontalScrollState]): to keep the caret in view, by a horizontal
 *   wheel (Shift and the wheel on desktop) or trackpad, by a drag, and on desktop and the
 *   web by a scrollbar over the bottom edge of the text. The layout is the state's, so
 *   wrapping is off while any editor showing the state has it off.
 */
@Composable
fun BasicTextEditor(
	state: TextEditorState = rememberTextEditorState(),
	modifier: Modifier = Modifier,
	contentPadding: PaddingValues = PaddingValues(0.dp),
	enabled: Boolean = true,
	autoFocus: Boolean = false,
	style: TextEditorStyle = rememberTextEditorStyle(),
	contextMenuStrings: ContextMenuStrings = ContextMenuStrings.Default,
	contextMenuState: TextEditorContextMenuState? = null,
	onRichSpanClick: RichSpanClickListener? = null,
	decorateLine: LineDecorator? = null,
	keyBindings: KeyBindings = LocalKeyBindings.current,
	onRichSpanClickEvent: RichSpanClickEventListener? = null,
	onLinkClick: ((url: String) -> Unit)? = null,
	contentDescription: String? = null,
	readOnly: Boolean = false,
	lineLimits: EditorLineLimits = EditorLineLimits.Fill,
	softWrap: Boolean = true,
) {
	LendComposition(state)

	// Input, edits and the edit semantics follow this; the caret and navigation follow enabled.
	val editable = enabled && !readOnly

	// Capture platform view for IME cursor synchronization (Android only)
	CaptureViewForIme(state)
	PlatformAccessibilityBridge()
	ClipboardEventsEffect(state)
	PrimarySelectionEffect(state)

	val focusRequester = remember { FocusRequester() }
	val interactionSource = remember { MutableInteractionSource() }
	val clipboard = LocalClipboard.current
	val density = LocalDensity.current
	val layoutDirection = LocalLayoutDirection.current

	// The platform's own: a stretch on Android, Compose's bounce on iOS, none on desktop.
	val overscrollEffect = rememberOverscrollEffect()

	val inputRequester = remember { TextInputRequester() }
	val singleLine = lineLimits == EditorLineLimits.SingleLine
	val inputModifierElement = remember(state, clipboard, editable, keyBindings, singleLine) {
		TextEditorInputModifierElement(state, clipboard, editable, keyBindings, inputRequester, singleLine)
	}

	val horizontalPadding = remember(contentPadding, layoutDirection) {
		PaddingValues(
			start = contentPadding.calculateStartPadding(layoutDirection),
			end = contentPadding.calculateEndPadding(layoutDirection),
		)
	}
	val contentOrigin by rememberUpdatedState(
		with(density) { Offset(contentPadding.calculateLeftPadding(layoutDirection).roundToPx().toFloat(), 0f) }
	)

	// One row of the text style, the unit of the line limits; the measurer is not state,
	// so a new one is picked up when the style or density changes.
	val rowHeightPx = remember(style.textStyle, density, state.textMeasurer) {
		state.textMeasurer.measure(" ", style.textStyle.copy(textIndent = TextIndent.None)).multiParagraph.getLineHeight(0)
	}
	// Derived, so layout is invalidated only when the height of the rows changes, not on
	// every edit.
	val contentHeightPx = remember(state) {
		derivedStateOf { state.lineOffsets.lastOrNull()?.let { ceil(it.offset.y + it.effectiveHeight).toInt() } ?: 0 }
	}
	val verticalPaddingPx = with(density) {
		contentPadding.calculateTopPadding().roundToPx() + contentPadding.calculateBottomPadding().roundToPx()
	}
	val lineLimitsModifier = remember(lineLimits, verticalPaddingPx, rowHeightPx, contentHeightPx) {
		Modifier.editorLineLimits(lineLimits, verticalPaddingPx, rowHeightPx) { contentHeightPx.value }
	}

	DisposableEffect(state, singleLine) {
		if (singleLine) state.singleLineEditors++
		onDispose { if (singleLine) state.singleLineEditors-- }
	}

	// A single line is one row that scrolls sideways, as BasicTextField's is.
	val wraps = softWrap && !singleLine
	DisposableEffect(state, wraps) {
		if (!wraps) state.noWrapEditors++
		onDispose { if (!wraps) state.noWrapEditors-- }
	}

	LaunchedEffect(contentPadding, density) {
		with(density) {
			state.scrollManager.topContentPaddingPx = contentPadding.calculateTopPadding().roundToPx()
			state.scrollManager.bottomContentPaddingPx = contentPadding.calculateBottomPadding().roundToPx()
		}
	}

	LaunchedEffect(density) {
		state.density = density
	}

	// A soft keyboard drawn over the window covers the bottom of the viewport: measured
	// on the canvas's frame (measuresKeyboardCover), and again when the canvas moves
	// (onGloballyPositioned).
	// The local is static and unset outside tests, so the call order stays fixed.
	val imeInsets by rememberUpdatedState(LocalImeInsets.current ?: WindowInsets.ime)
	val imeInsetsProvider = remember { { imeInsets } }
	val caretFocusRect = remember(state) { CaretFocusRect(state) }
	// Use provided context menu state or create internal one
	val internalContextMenuState = remember { TextEditorContextMenuState() }
	val effectiveContextMenuState = contextMenuState ?: internalContextMenuState

	val contextMenuActions = remember(state, clipboard, editable) {
		ContextMenuActions(state, clipboard, state.scope, editable, inputRequester::editor)
	}
	val latestOnLinkClick by rememberUpdatedState(onLinkClick)
	val hasLinkClick = onLinkClick != null
	val canvasPlacement = remember { CanvasPlacement() }
	val semanticsDocument = remember(state, hasLinkClick) {
		SemanticsDocument(state, canvasPlacement, if (hasLinkClick) { url -> latestOnLinkClick?.invoke(url) } else null)
	}
	val semanticsModifier = remember(state, enabled, editable, focusRequester, contextMenuActions, contentDescription, semanticsDocument, singleLine) {
		Modifier.editorSemantics(
			state, enabled, editable, singleLine, inputRequester::editor, focusRequester, contextMenuActions, contentDescription, semanticsDocument,
		)
	}
	// A stylus stroke on an unfocused editor writes where it began, as in EditText.
	val handwritingStroke: (Offset, Boolean) -> Unit = remember(state, focusRequester) {
		{ start, focused ->
			if (!focused) {
				state.placeCaretForHandwriting(start - contentOrigin)
				focusRequester.requestFocus()
			}
		}
	}
	val menuPlacement = remember(state, effectiveContextMenuState) {
		ContextMenuPlacement(state, effectiveContextMenuState)
	}
	ContextMenuOpener(state, menuPlacement)

	val textToolbar = LocalTextToolbar.current
	val nativeTextToolbar = LocalNativeTextToolbar.current
	val takesInput by rememberUpdatedState(editable)
	val handles by rememberUpdatedState(style.handleShape.look)
	val touchToolbar = remember(state, textToolbar, nativeTextToolbar, contextMenuActions, menuPlacement) {
		TouchToolbar(
			state,
			textToolbar.takeIf { nativeTextToolbar },
			contextMenuActions,
			menuPlacement::showAtContent,
			takesInput = { takesInput },
			handles = { handles },
		)
	}
	// A right-click's menu: the platform's edit menu at the pointer where it answers one
	// (iOS), else the editor's. A span handler (spell check's suggestions) may already have
	// opened the editor's menu with items of its own; that menu stays.
	val pointerMenuIsTextToolbar = LocalPointerMenuIsTextToolbar.current
	val openPointerMenu: (Offset) -> Unit = remember(touchToolbar, menuPlacement, effectiveContextMenuState, pointerMenuIsTextToolbar) {
		{ at ->
			val platformMenu = pointerMenuIsTextToolbar && !effectiveContextMenuState.isVisible
			if (!(platformMenu && touchToolbar.showAtPointer(at))) menuPlacement.showAtContent(at)
		}
	}
	LaunchedEffect(touchToolbar) { touchToolbar.watch() }
	DisposableEffect(touchToolbar) { onDispose { touchToolbar.hide() } }

	LaunchedEffect(Unit) {
		if (enabled && autoFocus) {
			focusRequester.requestFocus()
		}
	}

	// The blink restarts, caret shown, on focus, on every caret move, when a selection
	// comes or goes, and on every edit, which may leave the caret in place (forward delete).
	LaunchedEffect(state, enabled) {
		if (!enabled) return@LaunchedEffect
		merge(
			snapshotFlow { Triple(state.hasFocus, state.cursorPosition, state.selector.hasSelection()) },
			state.editOperations,
		).collectLatest {
			if (!state.hasFocus) return@collectLatest
			state.cursor.setVisible()
			while (true) {
				delay(CURSOR_BLINK_SPEED_MS.milliseconds)
				state.cursor.toggleVisibility()
			}
		}
	}

	LaunchedEffect(style.textStyle) {
		state.textStyle = style.textStyle
	}

	LaunchedEffect(enabled) {
		if (!enabled) state.selector.hideCaretHandle()
	}

	LaunchedEffect(
		style.bulletColor,
		style.blockquoteBarColor,
		style.blockquoteBackgroundColor,
		style.orderedListMarkerColor,
		style.codeFenceBackgroundColor,
		style.codeFenceBorderColor,
	) {
		state.bulletColor = style.bulletColor
		state.blockquoteBarColor = style.blockquoteBarColor
		state.blockquoteBackgroundColor = style.blockquoteBackgroundColor
		state.orderedListMarkerColor = style.orderedListMarkerColor
		state.codeFenceBackgroundColor = style.codeFenceBackgroundColor
		state.codeFenceBorderColor = style.codeFenceBorderColor
	}

	LaunchedEffect(style.paragraphSpacing) {
		state.paragraphSpacing = style.paragraphSpacing
	}

	// Re-run layout when an asynchronous block-state change (e.g. an image
	// finishing its load) changes a [BlockSpanStyle]'s reported height.
	// `updateBookKeeping` reads block heights but isn't itself snapshot-tracked,
	// so without this effect a freshly loaded image leaves stale Y offsets —
	// subsequent paragraphs render at the placeholder height position and
	// overlap the image until the user scrolls or resizes.
	LaunchedEffect(state) {
		snapshotFlow {
			val d = state.density ?: return@snapshotFlow emptyList<Float>()
			val viewportWidth = state.viewportSize.width
			state.richSpanManager.getAllRichSpans().mapNotNull { span ->
				(span.style as? BlockSpanStyle)?.blockHeight(d, viewportWidth)
			}
		}
			.distinctUntilChanged()
			// A block height change moves Y offsets but no line content, so the
			// pass can skip shaping entirely.
			.collect { state.updateBookKeeping(LayoutUpdate.SpansOnly) }
	}

	TextEditorContextMenuProvider(
		menuState = effectiveContextMenuState,
		actions = contextMenuActions,
		strings = contextMenuStrings,
		enabled = editable,
	) {
		TextEditorScrollbar(
			modifier = menuPlacement.modifier.then(modifier).then(lineLimitsModifier),
			scrollState = state.scrollState,
		) { editorModifier ->
			// The sideways scrollbar lies over the text beside the editor's own box, so a press
			// on it neither focuses the editor nor places the caret.
			Box(editorModifier) {
				// The horizontal padding is applied inside the canvas, below its pointer input,
				// so presses in it reach the text; the vertical padding is scroll range.
				Box(
					modifier = Modifier
						.focusRequester(focusRequester)
						.stylusHandwriting(state, editable, handwritingStroke)
						.requestFocusOnPress(
							state,
							focusRequester,
							popupIsShowing = { effectiveContextMenuState.isVisible },
							onRequestInput = inputRequester::requestInput,
						)
						.then(inputModifierElement)
						.then(caretFocusRect.modifier)
						// Focusable even when disabled, so a selection can be copied by keyboard.
						.focusable(enabled = true, interactionSource = interactionSource)
						.then(semanticsModifier)
						.fillMaxSize()
						// Outside the overscroll, which can move the canvas.
						.measuresKeyboardCover(state, imeInsetsProvider)
						.overscroll(overscrollEffect)
						.scrollable(
							orientation = Orientation.Vertical,
							reverseDirection = false,
							state = state.scrollState,
							overscrollEffect = overscrollEffect,
						)
						// Content x is not mirrored in a right-to-left layout, and bare scrollable
						// does not flip for one.
						.scrollable(
							orientation = Orientation.Horizontal,
							state = state.horizontalScrollState,
							enabled = !state.softWrap,
						)
				) {
					// The pointer handler never restarts, so it must reach the listeners the
					// host passed most recently rather than the ones captured at first composition.
					val currentOnRichSpanClick by rememberUpdatedState(onRichSpanClick)
					val currentOnRichSpanClickEvent by rememberUpdatedState(onRichSpanClickEvent)
					val currentOnLinkClick by rememberUpdatedState(onLinkClick)
					val spanClickProxy: SpanClickSink = remember {
						{ click ->
							currentOnRichSpanClick?.invoke(click.span, click.type, click.offset)
							currentOnRichSpanClickEvent?.invoke(click)
						}
					}
					val linkClicks = remember(keyBindings) {
						LinkClicks.forEditor(keyBindings) { currentOnLinkClick }
					}
					val primarySelection = LocalPrimarySelection.current
					val primaryPasteScope = rememberCoroutineScope()
					val primaryPaste: (() -> Unit)? = remember(state, editable, primarySelection, primaryPasteScope) {
						if (editable && primarySelection != null) {
							{
								// Taken at the click: focus can move between editors sharing the state
								// while the read is suspended, and the paste keeps to this one's limit.
								val target = inputRequester.editor
								primaryPasteScope.launch {
									val text = primarySelection.readText()?.takeIf { it.isNotEmpty() } ?: return@launch
									state.asEditor(target) { state.pastePlainText(text) }
								}
							}
						} else {
							null
						}
					}
					val dragAndDrop = remember(state) { TextDragAndDrop(state, inputRequester::editor) }
					dragAndDrop.enabled = editable
					dragAndDrop.textColor = style.textColor
					// The canvas: a box, so the handles' popups are placed from its content.
					Box(
						modifier = Modifier
							.textDragAndDrop(dragAndDrop)
							.textEditorPointerIcon(state, linkClicks, contentOrigin = { contentOrigin })
							.textEditorPointerInputHandling(
								state = state,
								onSpanClick = spanClickProxy,
								onContextMenuRequest = openPointerMenu,
								links = linkClicks,
								caretHandle = enabled,
								contentOrigin = { contentOrigin },
								touchToolbar = touchToolbar,
								selectionDrag = dragAndDrop,
								primaryPaste = primaryPaste,
								handles = handles,
							)
							.padding(horizontalPadding)
							.textMagnifier(state, style)
							.background(style.backgroundColor)
							.onSizeChanged { size -> state.onViewportSizeChange(size.toSize()) }
							// The content canvas's position, below the padding: the desktop IME places
							// its candidate window by it, and the touch toolbar its menu.
							.onGloballyPositioned {
								canvasPlacement.coordinates = it
								state.canvasLayoutCoordinates = it
								state.canvasPositionInRoot = it.positionInRoot()
								state.updateKeyboardCover(imeInsets.getBottom(density))
							}
							.fillMaxSize()
							.graphicsLayer {
								clip = false
							}
							.drawBehind { drawEditorCanvas(state, style, decorateLine, enabled, dragAndDrop) }
					) {
						TouchHandlePopups(state, handles, style.effectiveHandleColor, touchToolbar)
					}
				}
				if (state.horizontalScrollState.maxValue > 0 && !singleLine) {
					EditorHorizontalScrollbar(
						state.horizontalScrollState,
						Modifier
							.align(Alignment.BottomStart)
							.padding(horizontalPadding)
							.fillMaxWidth()
							.onSizeChanged { state.scrollManager.scrollbarBottomPx = it.height },
					)
					DisposableEffect(state) { onDispose { state.scrollManager.scrollbarBottomPx = 0 } }
				}
			}
		}
	}
}

private fun DrawScope.drawEditorCanvas(
	state: TextEditorState,
	style: TextEditorStyle,
	decorateLine: LineDecorator?,
	enabled: Boolean,
	dragAndDrop: TextDragAndDrop,
) {
	if (state.isEmpty() && style.placeholderText.isNotEmpty()) {
		DrawPlaceholderText(state, style)
	}

	try {
		DrawEditorText(state, style, decorateLine)
	} catch (e: IllegalArgumentException) {
		// Handle resize exception gracefully
	}

	DrawSelection(state, style.selectionColorFor(state.hasFocus))
	DrawHandwritingPreview(state, style.selectionColorFor(focused = true), style.textColor)

	// A read-only editor holds focus without taking input, and still shows its caret.
	if (enabled && state.hasFocus) {
		DrawCursor(state, style.cursorColor, style.cursorWidth)
	}

	DrawDropCaret(dragAndDrop, state, style.cursorColor, style.cursorWidth)
}

/**
 * Focuses the editor when the user actually points at it.
 *
 * A mouse press is unambiguous, so it focuses immediately. A finger press is not:
 * it may still turn into a scroll, and on Android focusing raises the soft
 * keyboard, so focusing on the down event pops the keyboard over the text every
 * time the user tries to pan. A finger therefore has to lift roughly where it
 * landed before this counts as a tap, which matches how the editor already
 * decides caret placement: mouse on press, finger on release. A finger that
 * travelled further still focuses when it selected on the way (a long press or a
 * double tap dragged on, a handle drag), read from the selection manager's
 * touch selection generation: a selection has to be typeable over.
 *
 * A tap that opened a popup is skipped as well, reported by [popupIsShowing]. The
 * thing to avoid is a keyboard sliding up over the spell-check suggestions or the
 * context menu that same tap just opened. Note this asks what the tap *did*, not
 * whether a listener said it handled the click: a host is free to answer a rich
 * span click and still want the editor focused, and most do.
 *
 * Every press or tap that focuses also calls [onRequestInput], which brings back a soft
 * keyboard the user dismissed. Focusing cannot do that alone: the editor may already
 * have focus. A right-click only focuses, since it opens the context menu.
 */
internal fun Modifier.requestFocusOnPress(
	state: TextEditorState,
	focusRequester: FocusRequester,
	popupIsShowing: () -> Boolean,
	onRequestInput: () -> Unit = {},
) = pointerInput(state) {
	val touchSlop = viewConfiguration.touchSlop
	awaitEachGesture {
		// Any button, so a right-click focuses the editor its menu acts on. The Initial
		// pass, so the generation below is read before the Canvas handler has acted on
		// the press: a second tap selects its word on the down.
		val down = awaitAnyPress(PointerEventPass.Initial)
		if (currentEvent.isMouseLike(down)) {
			focusRequester.requestFocus()
			if (!currentEvent.buttons.isSecondaryPressed) onRequestInput()
			return@awaitEachGesture
		}

		val generationAtPress = state.selector.touchSelectionGeneration
		var panned = false
		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
			// Travel, or a second finger, means a pan or a pinch rather than pointing.
			panned = panned || event.leavesTap(down, change, touchSlop)
			if (!change.pressed) {
				// Safe to read synchronously: the Main pass dispatches child-first, so
				// the Canvas gesture handler has already run this gesture's dispatch
				// (which opens any menu, or selects) before this container-level
				// handler sees the release.
				val selected = state.selector.touchSelectionGeneration != generationAtPress
				val popup = popupIsShowing()
				if (selected || (!panned && !popup)) {
					focusRequester.requestFocus()
					// A selection made under an open popup still needs focus to be typed
					// over, but the keyboard would cover the popup.
					if (!popup) onRequestInput()
				}
				return@awaitEachGesture
			}
		}
	}
}

/**
 * Handles clicks on a [RichSpan]. Receives the clicked span, the [SpanClickType]
 * that distinguishes a tap from a left- or right-click, and the click [Offset] in
 * editor coordinates. [RichSpanClick] says when a click is reported; use
 * [RichSpanClickEventListener] to also receive the modifier keys.
 *
 * The return value is a chaining protocol between listeners: `true` means "this
 * click was answered here", which lets a wrapping listener (e.g. the spell-check
 * editor's) decide whether to delegate a click onward to the host's listener.
 * It does not affect the editor itself: caret placement, selection, scrolling,
 * focus, and the soft keyboard all behave the same whatever is returned.
 */
typealias RichSpanClickListener = ((RichSpan, SpanClickType, Offset) -> Boolean)
