package com.darkrockstudios.texteditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.contextmenu.ContextMenuStrings
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuProvider
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.cursor.DrawCursor
import com.darkrockstudios.texteditor.input.CaptureViewForIme
import com.darkrockstudios.texteditor.input.KeyBindings
import com.darkrockstudios.texteditor.input.LocalKeyBindings
import com.darkrockstudios.texteditor.input.TextEditorInputModifierElement
import com.darkrockstudios.texteditor.input.selectionAsTextRange
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.state.LayoutUpdate
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.scrollbar.TextEditorScrollbar
import com.darkrockstudios.texteditor.state.SpanClickType
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.time.Duration.Companion.milliseconds

private const val CURSOR_BLINK_SPEED_MS = 500L

/**
 * The rich text editor with no surface or border chrome — bring your own
 * container. [TextEditor] wraps this in a Material [androidx.compose.material3.Surface];
 * reach for [BasicTextEditor] directly when you need that wrapping under your own
 * control, a custom context menu, or per-line decoration.
 *
 * @param state Holds the document, cursor, selection, and undo history.
 * @param contentPadding Padding between the editor bounds and the text.
 * @param enabled When `false`, the editor is read-only and cannot take focus.
 * @param autoFocus Requests focus once when first composed.
 * @param style Colors and text style for the editor and its gutter markers.
 * @param contextMenuStrings Localized labels for the built-in cut/copy/paste menu.
 * @param contextMenuState Drives context-menu visibility; pass your own to add
 *   custom items (e.g. spell-check suggestions), or leave `null` for the default.
 * @param onRichSpanClick Invoked when a rich span is tapped or right-clicked;
 *   see [RichSpanClickListener] for what the return value does (and does not do).
 * @param decorateLine Optional per-line decorator drawn behind each line, keyed by
 *   line index — useful for gutters, current-line highlights, or diff markers.
 * @param keyBindings Chord-to-command mapping, defaulting to [LocalKeyBindings].
 *   Bind chords to actions registered on [TextEditorState.actions] to add
 *   shortcuts of your own.
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
) {
	// Capture platform view for IME cursor synchronization (Android only)
	CaptureViewForIme(state)

	val focusRequester = remember { FocusRequester() }
	val interactionSource = remember { MutableInteractionSource() }
	val clipboard = LocalClipboard.current
	val density = LocalDensity.current
	val layoutDirection = LocalLayoutDirection.current

	val inputModifierElement = remember(state, clipboard, enabled, keyBindings) {
		TextEditorInputModifierElement(state, clipboard, enabled, keyBindings)
	}

	val horizontalPadding = remember(contentPadding, layoutDirection) {
		PaddingValues(
			start = contentPadding.calculateStartPadding(layoutDirection),
			end = contentPadding.calculateEndPadding(layoutDirection),
		)
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

	// Use provided context menu state or create internal one
	val internalContextMenuState = remember { TextEditorContextMenuState() }
	val effectiveContextMenuState = contextMenuState ?: internalContextMenuState

	val contextMenuActions = remember(state, clipboard) {
		ContextMenuActions(state, clipboard, state.scope)
	}

	LaunchedEffect(Unit) {
		if (enabled && autoFocus) {
			focusRequester.requestFocus()
		}
	}

	LaunchedEffect(state.isFocused, state.cursorPosition, enabled) {
		if (enabled && state.isFocused) {
			state.cursor.setVisible()
			while (state.isFocused) {
				delay(CURSOR_BLINK_SPEED_MS.milliseconds)
				state.cursor.toggleVisibility()
			}
		}
	}

	LaunchedEffect(style.textStyle) {
		state.textStyle = style.textStyle
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
		enabled = enabled,
	) {
		TextEditorScrollbar(
			modifier = modifier,
			scrollState = state.scrollState,
		) { editorModifier ->
			Box(
				modifier = editorModifier
					.padding(horizontalPadding)
					.focusRequester(focusRequester)
					.requestFocusOnPress(focusRequester) { effectiveContextMenuState.isVisible }
					.then(inputModifierElement)
					.focusable(enabled = true, interactionSource = interactionSource)
					// Publish text-editing semantics so the node is recognized as an editable
					// text field. This drives accessibility services (VoiceOver/TalkBack read and
					// edit the content) and, on iOS, lets the platform expose the focused editor as
					// a keyboard-focused text element — without which XCUITest can't type into it.
					.semantics {
						editableText = state.getAllText()
						textSelectionRange = state.selectionAsTextRange()
						setText { newText ->
							state.setText(newText)
							true
						}
						insertTextAtCursor { newText ->
							if (state.selector.hasSelection()) state.selector.deleteSelection()
							state.insertStringAtCursor(newText)
							true
						}
						setSelection { start, end, _ ->
							val length = state.getTextLength()
							val from = start.coerceIn(0, length)
							val to = end.coerceIn(0, length)
							if (from == to) {
								state.cursor.updatePosition(state.getOffsetAtCharacter(from))
								state.selector.clearSelection()
							} else {
								state.selector.updateSelection(
									state.getOffsetAtCharacter(from),
									state.getOffsetAtCharacter(to),
								)
								state.cursor.updatePosition(state.getOffsetAtCharacter(to))
							}
							true
						}
						onClick { focusRequester.requestFocus(); true }
					}
					.background(style.backgroundColor)
					.onSizeChanged { size ->
						state.onViewportSizeChange(
							size.toSize()
						)
					}
					.fillMaxSize()
					.scrollable(
						orientation = Orientation.Vertical,
						reverseDirection = false,
						state = state.scrollState,
					)
			) {
				Canvas(
					modifier = Modifier
						.textEditorPointerInputHandling(
							state = state,
							onSpanClick = onRichSpanClick,
							onContextMenuRequest = { offset -> effectiveContextMenuState.showMenu(offset) },
						)
						// Capture the canvas position so the desktop IME can place the
						// composition/candidate window relative to the cursor.
						.onGloballyPositioned { state.canvasLayoutCoordinates = it }
						.size(
							width = state.viewportSize.width.dp,
							height = state.viewportSize.height.dp
						)
						.graphicsLayer {
							clip = false
						}
				) {
					if (state.isEmpty() && style.placeholderText.isNotEmpty()) {
						DrawPlaceholderText(state, style)
					}

					try {
						DrawEditorText(state, style, decorateLine)
					} catch (e: IllegalArgumentException) {
						// Handle resize exception gracefully
					}

					DrawSelection(state, style.selectionColor)

					DrawSelectionHandles(state)

					if (enabled && state.isFocused && state.cursor.isVisible) {
						DrawCursor(state, style.cursorColor)
					}
				}
			}
		}
	}
}

/**
 * Focuses the editor when the user actually points at it.
 *
 * A mouse press is unambiguous, so it focuses immediately. A finger press is not:
 * it may still turn into a scroll, and on Android focusing raises the soft
 * keyboard, so focusing on the down event pops the keyboard over the text every
 * time the user tries to pan. A finger therefore has to lift roughly where it
 * landed before this counts as a tap, which matches how the editor already
 * decides caret placement: mouse on press, finger on release.
 *
 * A tap that opened a popup is skipped as well, reported by [popupIsShowing]. The
 * thing to avoid is a keyboard sliding up over the spell-check suggestions or the
 * context menu that same tap just opened. Note this asks what the tap *did*, not
 * whether a listener said it handled the click: a host is free to answer a rich
 * span click and still want the editor focused, and most do.
 */
internal fun Modifier.requestFocusOnPress(
	focusRequester: FocusRequester,
	popupIsShowing: () -> Boolean,
) = pointerInput(Unit) {
	val touchSlop = viewConfiguration.touchSlop
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = false)

		// Android reports an external mouse as PointerType.Touch but still fills in
		// the buttons, so the button state is what actually separates the two.
		val hasButton = currentEvent.buttons.isPrimaryPressed ||
				currentEvent.buttons.isSecondaryPressed
		if (down.type == PointerType.Mouse || hasButton) {
			focusRequester.requestFocus()
			return@awaitEachGesture
		}

		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
			if ((change.position - down.position).getDistance() > touchSlop) {
				// Panning, not pointing.
				return@awaitEachGesture
			}
			if (!change.pressed) {
				// Safe to read synchronously: the Main pass dispatches child-first, so
				// the Canvas gesture handler has already run this tap's dispatch (which
				// opens any menu) before this container-level handler sees the release.
				if (!popupIsShowing()) focusRequester.requestFocus()
				return@awaitEachGesture
			}
		}
	}
}

/**
 * Handles clicks on a [RichSpan]. Receives the clicked span, the [SpanClickType]
 * that distinguishes a tap from a left- or right-click, and the click [Offset] in
 * editor coordinates.
 *
 * The return value is a chaining protocol between listeners: `true` means "this
 * click was answered here", which lets a wrapping listener (e.g. the spell-check
 * editor's) decide whether to delegate a click onward to the host's listener.
 * It does not affect the editor itself: caret placement, selection, scrolling,
 * focus, and the soft keyboard all behave the same whatever is returned.
 */
typealias RichSpanClickListener = ((RichSpan, SpanClickType, Offset) -> Boolean)
