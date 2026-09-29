package com.darkrockstudios.texteditor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.ViewConfiguration
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.KeyBindings
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.platformKeyBindings
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.SelectionGranularity
import com.darkrockstudios.texteditor.state.SpanClickType
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SelectionHandle(
	val position: CharLineOffset,
	val isStart: Boolean,
	val bounds: Offset
)

/**
 * Pointer input for the text canvas, split by device: [handleMouseInput] owns every
 * mouse gesture, [handleHandleDrag] and [handleTouchInteractions] every finger gesture.
 * Mouse versus finger is decided from the buttons, not the pointer type, because
 * Android reports an external mouse as [PointerType.Touch] with its buttons filled in.
 */
internal fun Modifier.textEditorPointerInputHandling(
	state: TextEditorState,
	onSpanClick: SpanClickSink? = null,
	onContextMenuRequest: ((Offset) -> Unit)? = null,
	readOnly: Boolean = false,
	links: LinkClicks? = null,
): Modifier {
	return this
		.handleHandleDrag(state)
		.handleTouchInteractions(state, onSpanClick, onContextMenuRequest, readOnly, links)
		.handleMouseInput(state, onSpanClick, onContextMenuRequest, readOnly, links)
}

internal typealias SpanClickSink = (RichSpanClick) -> Unit

/**
 * The built-in link convention: a click on a [LinkSpanStyle] opens it through the
 * host's `onLinkClick`, read through [handler] so the latest one is always used. In an
 * editor a plain click places the caret, so opening takes Ctrl, or Cmd when
 * [usesCommandKey]; a read-only view opens on a plain click or tap.
 */
internal class LinkClicks(
	private val requiresShortcutKey: Boolean,
	private val usesCommandKey: Boolean,
	private val handler: () -> ((String) -> Unit)?,
) {
	fun opensOnClick(modifiers: PointerKeyboardModifiers): Boolean = handler() != null && when {
		!requiresShortcutKey -> true
		usesCommandKey -> modifiers.isMetaPressed
		else -> modifiers.isCtrlPressed
	}

	val opensOnTap: Boolean get() = handler() != null && !requiresShortcutKey

	fun open(url: String) {
		handler()?.invoke(url)
	}

	companion object {
		/** Cmd opens links under the macOS bindings, Ctrl under any other. */
		fun forEditor(keyBindings: KeyBindings, handler: () -> ((String) -> Unit)?) = LinkClicks(
			requiresShortcutKey = true,
			usesCommandKey = when {
				keyBindings === MacKeyBindings -> true
				keyBindings === CtrlKeyBindings -> false
				else -> platformKeyBindings() === MacKeyBindings
			},
			handler = handler,
		)

		fun forReadOnly(handler: () -> ((String) -> Unit)?) =
			LinkClicks(requiresShortcutKey = false, usesCommandKey = false, handler = handler)
	}
}

private fun PointerEvent.isMouseLike(down: PointerInputChange): Boolean =
	down.type == PointerType.Mouse || buttons.areAnyPressed

/**
 * Counts successive primary presses into single, double, and triple clicks. A press
 * continues the sequence when it lands within the platform's double-tap timeout and
 * touch slop of the previous one.
 */
private class ClickCounter(private val viewConfiguration: ViewConfiguration) {
	private var lastTime = 0L
	private var lastPosition: Offset? = null
	private var clicks = 0

	fun register(down: PointerInputChange): Int {
		val previous = lastPosition
		val continues = previous != null &&
				down.uptimeMillis - lastTime < viewConfiguration.doubleTapTimeoutMillis &&
				(down.position - previous).getDistance() < viewConfiguration.touchSlop
		clicks = if (continues) (clicks + 1).coerceAtMost(3) else 1
		lastTime = down.uptimeMillis
		lastPosition = down.position
		return clicks
	}

	fun reset() {
		lastPosition = null
		clicks = 0
	}
}

/**
 * Every mouse gesture. The primary button places the caret on press (or extends with
 * shift), a second and third press select the word and the line, and a drag extends by
 * whatever unit the press selected. The secondary button opens the context menu; any
 * other button does nothing.
 */
private fun Modifier.handleMouseInput(
	state: TextEditorState,
	onSpanClick: SpanClickSink?,
	onContextMenuRequest: ((Offset) -> Unit)?,
	readOnly: Boolean,
	links: LinkClicks?,
): Modifier = pointerInput(state, links) {
	val clickCounter = ClickCounter(viewConfiguration)
	val touchSlop = viewConfiguration.touchSlop
	awaitEachGesture {
		// Not awaitFirstDown: on skiko it ignores every mouse button but the primary one.
		val press = awaitPointerEvent()
		val down = press.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
			?: return@awaitEachGesture
		if (!press.isMouseLike(down)) return@awaitEachGesture

		val buttons = press.buttons
		when {
			(buttons.isPrimaryPressed && !buttons.isSecondaryPressed) || !buttons.areAnyPressed -> {
				val isShiftPressed = press.keyboardModifiers.isShiftPressed
				val clicks = clickCounter.register(down)
				// The second and third press of a multi-click select; only a plain first
				// press can become a click on what is under it.
				val pressed = if (clicks == 1 && !isShiftPressed) ClickTarget.at(state, down.position) else null
				val selection = MouseSelection.press(
					state,
					position = down.position,
					granularity = SelectionGranularity.forClickCount(clicks),
					isShiftPressed = isShiftPressed,
				)
				val release = followDrag(selection, down, touchSlop) ?: return@awaitEachGesture
				// A drag inside the slop that still selected something is a drag too.
				if (pressed == null || state.selector.hasSelection()) return@awaitEachGesture
				val released = ClickTarget.at(state, release.position)
				val modifiers = currentEvent.keyboardModifiers
				if (pressed.span != null && pressed.span == released.span) {
					onSpanClick?.invoke(
						RichSpanClick(pressed.span, SpanClickType.PRIMARY_CLICK, release.position, modifiers)
					)
				}
				if (pressed.link != null && pressed.link == released.link && links?.opensOnClick(modifiers) == true) {
					links.open(pressed.link)
				}
			}

			buttons.isSecondaryPressed -> {
				clickCounter.reset()
				handleSpanInteraction(
					state,
					down.position,
					SpanClickType.SECONDARY_CLICK,
					press.keyboardModifiers,
					onSpanClick,
					readOnly,
				)
				onContextMenuRequest?.invoke(down.position)
			}

			else -> clickCounter.reset()
		}
	}
}

/**
 * A primary-button selection in progress: the unit the press selected stays selected,
 * and the drag extends from it by the same [granularity]. The caret follows the moving
 * end even in a read-only view, where it is not drawn, because shift+click extends from
 * wherever the caret is.
 */
private class MouseSelection(
	private val state: TextEditorState,
	private val anchor: TextEditorRange,
	private val granularity: SelectionGranularity,
) {
	fun selectTo(position: Offset) {
		state.selector.selectFromAnchor(anchor, state.getOffsetAtPosition(position), granularity)
	}

	companion object {
		/** Selects for a press at [position]: a caret, word, or line, or a shift extension. */
		fun press(
			state: TextEditorState,
			position: Offset,
			granularity: SelectionGranularity,
			isShiftPressed: Boolean,
		): MouseSelection {
			val anchor = if (isShiftPressed) {
				val fixed = state.selector.extensionAnchor(state.cursorPosition)
				TextEditorRange(fixed, fixed)
			} else {
				state.selector.rangeAt(state.getOffsetAtPosition(position), granularity)
			}
			return MouseSelection(state, anchor, granularity).also {
				it.selectTo(position)
				state.endCompositionIfPointerLeft()
			}
		}
	}
}

/**
 * Extends [selection] as the pointer drags, until it is released. Returns the release
 * when the pointer came up without having moved past [touchSlop], null otherwise.
 */
private suspend fun AwaitPointerEventScope.followDrag(
	selection: MouseSelection,
	down: PointerInputChange,
	touchSlop: Float,
): PointerInputChange? {
	var dragged = false
	while (true) {
		val event = awaitPointerEvent()
		val change = event.changes.firstOrNull { it.id == down.id } ?: return null
		if (!change.pressed) return if (dragged) null else change
		if (change.positionChanged()) {
			dragged = dragged || (change.position - down.position).getDistance() > touchSlop
			selection.selectTo(change.position)
			change.consume()
		}
	}
}

/** What a click at a point would act on: the span that answers it, and any link there. */
private class ClickTarget(val span: RichSpan?, val link: String?) {
	companion object {
		fun at(state: TextEditorState, offset: Offset): ClickTarget = ClickTarget(
			state.spanAt(offset),
			state.characterAt(offset)?.let { state.linkAt(it) },
		)
	}
}

/**
 * The span a click at [offset] answers to. Hit on the character under the pointer; off
 * the text, where there is none (a block image's line, past a row's end), on the
 * nearest caret position.
 */
private fun TextEditorState.spanAt(offset: Offset): RichSpan? =
	findSpanAtPosition(characterAt(offset) ?: getOffsetAtPosition(offset))

/**
 * The character under [offset], or null when the pointer is beside a row rather than
 * over it. [TextEditorState.getOffsetAtPosition] answers the nearest caret position
 * instead, which over the right half of a character is the one after it, and past the
 * end of a wrapped row is the first character of the next.
 */
private fun TextEditorState.characterAt(offset: Offset): CharLineOffset? {
	val y = offset.y + scrollState.value
	var found: LineWrap? = null
	var previousLine = -1
	for (wrap in lineOffsets) {
		// A paragraph's first row carries its top and its whole layout.
		if (wrap.line == previousLine) continue
		previousLine = wrap.line
		val height = wrap.blockHeight ?: wrap.textLayoutResult.size.height.toFloat()
		if (y >= wrap.offset.y && y <= wrap.offset.y + height) {
			found = wrap
			break
		}
	}
	if (found == null) return null
	val layout = found.textLayoutResult.multiParagraph
	val relative = Offset(offset.x, y) - found.offset
	val row = layout.getLineForVerticalPosition(relative.y)
	if (relative.x < layout.getLineLeft(row) || relative.x >= layout.getLineRight(row)) return null
	val caret = layout.getOffsetForPosition(relative)
	val char = if (caret > layout.getLineStart(row) && relative.x < layout.getHorizontalPosition(caret, true)) {
		caret - 1
	} else {
		caret
	}
	val length = textLines[found.line].length
	return if (char < length) CharLineOffset(found.line, char) else null
}

/** The URL of the [LinkSpanStyle] covering [position], if any. */
private fun TextEditorState.linkAt(position: CharLineOffset): String? =
	lineOffsets.lastOrNull { it.line == position.line && position.char >= it.wrapStartsAtIndex }
		?.richSpans
		?.firstOrNull { it.style is LinkSpanStyle && it.containsPosition(position) }
		?.let { (it.style as LinkSpanStyle).url }

/**
 * The pointer icon for a mouse hovering at [offset] with [modifiers] held: a hand over a
 * link that a click would open, [default] everywhere else.
 */
internal fun pointerIconAt(
	state: TextEditorState,
	offset: Offset,
	modifiers: PointerKeyboardModifiers,
	links: LinkClicks?,
	default: PointerIcon?,
): PointerIcon? {
	if (links == null || !links.opensOnClick(modifiers)) return default
	val link = state.characterAt(offset)?.let { state.linkAt(it) }
	return if (link != null) PointerIcon.Hand else default
}

/**
 * Shows [default] over the text and a hand over links a click would open; a null
 * [default] leaves the parent's icon everywhere but over a link. Only a pointer event
 * updates it, so pressing Ctrl or Cmd over a link changes the icon on the next mouse
 * move.
 */
internal fun Modifier.textEditorPointerIcon(
	state: TextEditorState,
	links: LinkClicks?,
	default: PointerIcon? = PointerIcon.Text,
): Modifier = composed {
	var icon by remember(state, default) { mutableStateOf(default) }
	val tracking = pointerInput(state, links, default) {
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Initial)
				val change = event.changes.firstOrNull() ?: continue
				if (change.type != PointerType.Mouse) continue
				icon = pointerIconAt(state, change.position, event.keyboardModifiers, links, default)
			}
		}
	}
	icon?.let { tracking.pointerHoverIcon(it) } ?: tracking
}

/**
 * Link clicks for a read-only view that is not selectable, so has no other pointer
 * handling: a click or tap that lands and lifts on the same link opens it.
 */
internal fun Modifier.linkClickHandling(state: TextEditorState, links: LinkClicks): Modifier =
	pointerInput(state, links) {
		val touchSlop = viewConfiguration.touchSlop
		awaitEachGesture {
			val down = awaitFirstDown(requireUnconsumed = false)
			// Android's awaitFirstDown answers every mouse button; only the primary one clicks.
			val buttons = currentEvent.buttons
			if (buttons.areAnyPressed && !buttons.isPrimaryPressed) return@awaitEachGesture
			val link = ClickTarget.at(state, down.position).link ?: return@awaitEachGesture
			while (true) {
				val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
				if ((change.position - down.position).getDistance() > touchSlop) return@awaitEachGesture
				if (!change.pressed) {
					val opens = if (currentEvent.isMouseLike(down)) {
						links.opensOnClick(currentEvent.keyboardModifiers)
					} else {
						links.opensOnTap
					}
					if (opens && ClickTarget.at(state, change.position).link == link) links.open(link)
					return@awaitEachGesture
				}
			}
		}
	}

/** Drags a touch selection handle. */
private fun Modifier.handleHandleDrag(state: TextEditorState): Modifier {
	return pointerInput(state) {
		awaitEachGesture {
			val down = awaitFirstDown(requireUnconsumed = false)
			if (currentEvent.isMouseLike(down)) return@awaitEachGesture
			val handle = findHandleAtPosition(down.position, state) ?: return@awaitEachGesture

			state.selector.setDraggingHandle(handle.isStart)
			state.endCompositionIfPointerLeft()

			try {
				while (true) {
					val event = awaitPointerEvent()
					val dragEvent = event.changes.firstOrNull { it.id == down.id } ?: break
					if (!dragEvent.pressed) break

					// Offset the finger position well above where the finger is touching
					// so the user can clearly see the text being selected above their finger.
					// This includes: handle visual offset + handle size + extra clearance for finger
					val dragOffset = SELECTION_HANDLE_OFFSET + SELECTION_HANDLE_DIAMETER + 60f
					val adjustedPosition = dragEvent.position.copy(y = dragEvent.position.y - dragOffset)
					val newPosition = state.getOffsetAtPosition(adjustedPosition)
					val selection = state.selector.selection
					if (selection != null) {
						if (state.selector.isDraggingStartHandle()) {
							state.selector.updateSelection(newPosition, selection.end)
						} else {
							state.selector.updateSelection(selection.start, newPosition)
						}
					}
					dragEvent.consume()
				}
			} finally {
				state.selector.clearDraggingHandle()
			}
		}
	}
}

/**
 * Ends the IME composition once a pointer has put the caret or a selection outside it,
 * which is what keyboards do themselves when told of the move. One that does not would
 * replace the old composing word, wherever it is, with its next keystroke. A caret
 * placed inside the composition keeps it: some keyboards edit mid-composition.
 */
private fun TextEditorState.endCompositionIfPointerLeft() {
	val composing = composingRange ?: return
	val caretInside = selector.selection == null &&
			(cursorPosition isAfterOrEqual composing.start) &&
			(cursorPosition isBeforeOrEqual composing.end)
	if (!caretInside) clearComposingRange()
}

private fun findHandleAtPosition(
	position: Offset,
	state: TextEditorState,
): SelectionHandle? {
	val selection = state.selector.selection ?: return null

	val startMetrics = state.getPositionForOffset(selection.start)
	val startHandleY = startMetrics.position.y + startMetrics.height + SELECTION_HANDLE_OFFSET + SELECTION_HANDLE_RADIUS
	val startHandlePos = startMetrics.position.copy(y = startHandleY)

	val endMetrics = state.getPositionForOffset(selection.end)
	val endHandleY = endMetrics.position.y + endMetrics.height + SELECTION_HANDLE_OFFSET + SELECTION_HANDLE_RADIUS
	val endHandlePos = endMetrics.position.copy(y = endHandleY)

	// Larger hit area for easier touch targeting
	val handleHitArea = 80f

	return if ((position - startHandlePos).getDistance() < handleHitArea) {
		SelectionHandle(selection.start, true, startHandlePos)
	} else if ((position - endHandlePos).getDistance() < handleHitArea) {
		SelectionHandle(selection.end, false, endHandlePos)
	} else {
		null
	}
}

/**
 * Places the caret for a tap or a right-click, then offers the event to the [RichSpan]
 * under it. A tap reports only when it lifts on the span it landed on, [pressedSpan].
 */
private fun handleSpanInteraction(
	state: TextEditorState,
	offset: Offset,
	clickType: SpanClickType,
	modifiers: PointerKeyboardModifiers,
	onSpanClick: SpanClickSink?,
	readOnly: Boolean,
	pressedSpan: RichSpan? = null,
) {
	if (findHandleAtPosition(offset, state) != null) return

	val position = state.getOffsetAtPosition(offset)
	val placesCaret = when (clickType) {
		SpanClickType.PRIMARY_CLICK, SpanClickType.TAP -> true
		// Like native editors, a right-click inside the selection keeps it for the context
		// menu, and one outside it moves the caret there first. Read-only views have no
		// caret to move, so they keep the selection either way.
		SpanClickType.SECONDARY_CLICK -> !readOnly && !state.selector.selectionContains(position)
	}
	if (placesCaret) {
		if (!readOnly) {
			state.cursor.updatePosition(position)
		}
		state.selector.clearSelection()
		state.endCompositionIfPointerLeft()
	}

	val span = state.spanAt(offset) ?: return
	if (clickType == SpanClickType.TAP && span != pressedSpan) return
	onSpanClick?.invoke(RichSpanClick(span, clickType, offset, modifiers))
}

/** Finger taps and long presses. */
private fun Modifier.handleTouchInteractions(
	state: TextEditorState,
	onSpanClick: SpanClickSink?,
	onContextMenuRequest: ((Offset) -> Unit)?,
	readOnly: Boolean,
	links: LinkClicks?,
): Modifier {
	return pointerInput(state, links) {
		val touchSlop = viewConfiguration.touchSlop
		awaitEachGesture {
			val down = awaitFirstDown(requireUnconsumed = false)
			if (currentEvent.isMouseLike(down)) return@awaitEachGesture
			if (findHandleAtPosition(down.position, state) != null) return@awaitEachGesture

			var didLongPress = false
			var wasDrag = false
			val pressed = ClickTarget.at(state, down.position)
			val existingSelection = state.selector.selection
			val longPressJob = state.scope.launch {
				delay(500)
				val wordPosition = state.getOffsetAtPosition(down.position)

				val isOnSelection = existingSelection != null &&
						(wordPosition isAfterOrEqual existingSelection.start) &&
						(wordPosition isBeforeOrEqual existingSelection.end)

				if (isOnSelection) {
					onContextMenuRequest?.invoke(down.position)
				} else {
					state.selector.startSelection(wordPosition, isTouch = true)
					state.selector.selectWordAt(wordPosition)
					state.endCompositionIfPointerLeft()
				}

				didLongPress = true
			}

			try {
				while (true) {
					val event = awaitPointerEvent()
					val change = event.changes.firstOrNull { it.id == down.id } ?: break
					if (!change.pressed) {
						if (!didLongPress && !wasDrag) {
							handleSpanInteraction(
								state,
								change.position,
								SpanClickType.TAP,
								event.keyboardModifiers,
								onSpanClick,
								readOnly,
								pressedSpan = pressed.span,
							)
							if (pressed.link != null && links?.opensOnTap == true &&
								ClickTarget.at(state, change.position).link == pressed.link
							) {
								links.open(pressed.link)
							}
						}
						break
					}
					// Only a move past touch slop is a drag, so a high-precision touch screen's
					// micro-movements still tap.
					if (!wasDrag && (change.position - down.position).getDistance() > touchSlop) {
						wasDrag = true
						longPressJob.cancel()
					}
				}
			} finally {
				longPressJob.cancel()
			}
		}
	}
}
