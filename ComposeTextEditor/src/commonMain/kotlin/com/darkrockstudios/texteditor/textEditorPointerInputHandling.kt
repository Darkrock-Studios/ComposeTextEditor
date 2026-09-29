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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
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
	caretHandle: Boolean = !readOnly,
	contentOrigin: () -> Offset,
): Modifier {
	return this
		.handleHandleDrag(state, contentOrigin)
		.handleTouchInteractions(state, onSpanClick, onContextMenuRequest, readOnly, links, caretHandle, contentOrigin)
		.handleMouseInput(state, onSpanClick, onContextMenuRequest, readOnly, links, contentOrigin)
}

/**
 * [PointerInputChange.position] in text-canvas coordinates. The pointer node can span the
 * content padding as well, so its origin sits [origin] above and left of the canvas's.
 */
private fun PointerInputChange.inContent(origin: Offset): Offset = position - origin

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
	contentOrigin: () -> Offset,
): Modifier = pointerInput(state, links) {
	val clickCounter = ClickCounter(viewConfiguration)
	val touchSlop = viewConfiguration.touchSlop
	coroutineScope {
	val autoScrollScope = this
	awaitEachGesture {
		// Not awaitFirstDown: on skiko it ignores every mouse button but the primary one.
		val press = awaitPointerEvent()
		val down = press.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
			?: return@awaitEachGesture
		if (!press.isMouseLike(down)) return@awaitEachGesture
		val origin = contentOrigin()
		val downAt = down.inContent(origin)

		val buttons = press.buttons
		when {
			(buttons.isPrimaryPressed && !buttons.isSecondaryPressed) || !buttons.areAnyPressed -> {
				val isShiftPressed = press.keyboardModifiers.isShiftPressed
				val clicks = clickCounter.register(down)
				// The second and third press of a multi-click select; only a plain first
				// press can become a click on what is under it.
				val pressed = if (clicks == 1 && !isShiftPressed) ClickTarget.at(state, downAt) else null
				val selection = MouseSelection.press(
					state,
					position = downAt,
					granularity = SelectionGranularity.forClickCount(clicks),
					isShiftPressed = isShiftPressed,
				)
				val autoScroll = DragAutoScroll(state, autoScrollScope, onDrag = selection::selectTo)
				val release = followDrag(autoScroll, down, origin, touchSlop) ?: return@awaitEachGesture
				// A drag inside the slop that still selected something is a drag too.
				if (pressed == null || state.selector.hasSelection()) return@awaitEachGesture
				val releasedAt = release.inContent(origin)
				val released = ClickTarget.at(state, releasedAt)
				val modifiers = currentEvent.keyboardModifiers
				if (pressed.span != null && pressed.span == released.span) {
					onSpanClick?.invoke(
						RichSpanClick(pressed.span, SpanClickType.PRIMARY_CLICK, releasedAt, modifiers)
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
					downAt,
					SpanClickType.SECONDARY_CLICK,
					press.keyboardModifiers,
					onSpanClick,
					readOnly,
				)
				onContextMenuRequest?.invoke(downAt)
			}

			else -> clickCounter.reset()
		}
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
			state.selector.hideCaretHandle()
			return MouseSelection(state, anchor, granularity).also {
				it.selectTo(position)
				state.endCompositionIfPointerLeft()
			}
		}
	}
}

/**
 * Feeds the drag to [autoScroll] until the pointer is released. Returns the release
 * when the pointer came up without having moved past [touchSlop], null otherwise.
 * [consumeAll] consumes every change rather than only the moves, as a handle drag does.
 */
private suspend fun AwaitPointerEventScope.followDrag(
	autoScroll: DragAutoScroll,
	down: PointerInputChange,
	origin: Offset,
	touchSlop: Float = 0f,
	consumeAll: Boolean = false,
): PointerInputChange? {
	var dragged = false
	try {
		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull { it.id == down.id } ?: return null
			if (!change.pressed) return if (dragged) null else change
			if (change.positionChanged()) {
				dragged = dragged || (change.position - down.position).getDistance() > touchSlop
				autoScroll.update(change.inContent(origin))
				change.consume()
			} else if (consumeAll) {
				change.consume()
			}
		}
	} finally {
		autoScroll.stop()
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
 * nearest caret position. Above the first row or below the last there is none.
 */
private fun TextEditorState.spanAt(offset: Offset): RichSpan? {
	val character = characterAt(offset)
	return if (character != null) findSpanAtPosition(character) else findSpanAtPoint(offset)
}

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
	contentOrigin: () -> Offset,
): Modifier = composed {
	var icon by remember(state, default) { mutableStateOf(default) }
	val tracking = pointerInput(state, links, default) {
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Initial)
				val change = event.changes.firstOrNull() ?: continue
				if (change.type != PointerType.Mouse) continue
				icon = pointerIconAt(state, change.inContent(contentOrigin()), event.keyboardModifiers, links, default)
			}
		}
	}
	icon?.let { tracking.pointerHoverIcon(it) } ?: tracking
}

/**
 * Link clicks for a read-only view that is not selectable, so has no other pointer
 * handling: a click or tap that lands and lifts on the same link opens it.
 */
internal fun Modifier.linkClickHandling(
	state: TextEditorState,
	links: LinkClicks,
	contentOrigin: () -> Offset,
): Modifier =
	pointerInput(state, links) {
		val touchSlop = viewConfiguration.touchSlop
		awaitEachGesture {
			val down = awaitFirstDown(requireUnconsumed = false)
			// Android's awaitFirstDown answers every mouse button; only the primary one clicks.
			val buttons = currentEvent.buttons
			if (buttons.areAnyPressed && !buttons.isPrimaryPressed) return@awaitEachGesture
			val origin = contentOrigin()
			val link = ClickTarget.at(state, down.inContent(origin)).link ?: return@awaitEachGesture
			while (true) {
				val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
				if ((change.position - down.position).getDistance() > touchSlop) return@awaitEachGesture
				if (!change.pressed) {
					val opens = if (currentEvent.isMouseLike(down)) {
						links.opensOnClick(currentEvent.keyboardModifiers)
					} else {
						links.opensOnTap
					}
					if (opens && ClickTarget.at(state, change.inContent(origin)).link == link) links.open(link)
					return@awaitEachGesture
				}
			}
		}
	}

/**
 * Drags a touch selection handle, or the caret handle. For a selection handle the other
 * end of the selection is fixed for the whole drag, so the dragged end can cross it. The
 * dragged end moves exactly as far as the finger does from where it grabbed the handle,
 * so grabbing moves nothing. Held above or below the viewport, the drag auto-scrolls like
 * a mouse drag.
 */
private fun Modifier.handleHandleDrag(state: TextEditorState, contentOrigin: () -> Offset): Modifier {
	return pointerInput(state) {
		coroutineScope {
			val autoScrollScope = this
			awaitEachGesture {
				val down = awaitFirstDown(requireUnconsumed = false)
				if (currentEvent.isMouseLike(down)) return@awaitEachGesture
				val origin = contentOrigin()
				val downAt = down.inContent(origin)
				if (isOnCaretHandle(downAt, state)) {
					dragCaretHandle(state, down, origin, autoScrollScope)
				} else {
					val handle = findHandleAtPosition(downAt, state) ?: return@awaitEachGesture
					dragSelectionHandle(state, handle, down, origin, autoScrollScope)
				}
			}
		}
	}
}

private suspend fun AwaitPointerEventScope.dragSelectionHandle(
	state: TextEditorState,
	handle: SelectionHandle,
	down: PointerInputChange,
	origin: Offset,
	autoScrollScope: CoroutineScope,
) {
	val selection = state.selector.selection ?: return
	val anchor = if (handle.isStart) selection.end else selection.start
	val downAt = down.inContent(origin)
	val grabOffset = grabOffset(state, handle.position, downAt)
	state.selector.setDraggingHandle(handle.isStart)
	state.selector.magnifierCenter = magnifierCenter(state, handle.position, downAt + grabOffset)
	state.endCompositionIfPointerLeft()

	val autoScroll = DragAutoScroll(state, autoScrollScope, grabOffset) { target ->
		// Anything else that changes the selection mid-drag (an edit, an undo) ends it.
		val current = state.selector.selection
		if (current == null || (current.start != anchor && current.end != anchor)) {
			state.selector.magnifierCenter = null
			return@DragAutoScroll
		}
		val position = state.getOffsetAtPosition(target)
		// Meeting the fixed end would empty the selection and drop the handles
		// mid-drag, so the last selection holds until the finger moves past.
		if (position != anchor) {
			state.selector.selectFromAnchor(
				TextEditorRange(anchor, anchor),
				position,
				SelectionGranularity.Character,
				isTouch = true,
			)
			state.selector.setDraggingHandle(isStart = position isBefore anchor)
		}
		state.selector.magnifierCenter = magnifierCenter(state, position, target)
	}
	try {
		followDrag(autoScroll, down, origin, consumeAll = true)
	} finally {
		state.selector.clearDraggingHandle()
		state.selector.magnifierCenter = null
	}
}

/**
 * Drags the touch caret handle, moving the caret. The handle stays up through the drag,
 * and its idle timeout restarts on release. Anything else that moves the caret, edits,
 * selects, or takes focus meanwhile takes the handle away and ends the drag.
 */
private suspend fun AwaitPointerEventScope.dragCaretHandle(
	state: TextEditorState,
	down: PointerInputChange,
	origin: Offset,
	autoScrollScope: CoroutineScope,
) {
	val downAt = down.inContent(origin)
	val grabOffset = grabOffset(state, state.cursorPosition, downAt)
	state.selector.magnifierCenter = magnifierCenter(state, state.cursorPosition, downAt + grabOffset)
	val autoScroll = DragAutoScroll(state, autoScrollScope, grabOffset) { target ->
		if (!state.selector.isCaretHandleVisible) {
			state.selector.magnifierCenter = null
			return@DragAutoScroll
		}
		val position = state.getOffsetAtPosition(target)
		state.selector.dragCaretHandleTo(position)
		state.selector.magnifierCenter = magnifierCenter(state, position, target)
		state.endCompositionIfPointerLeft()
	}
	try {
		followDrag(autoScroll, down, origin, consumeAll = true)
	} finally {
		state.selector.releaseCaretHandle()
		state.selector.magnifierCenter = null
	}
}

/**
 * The magnifier's point for a handle dragged to [position]: on the middle of its row,
 * and level with the dragged point [target] so it glides rather than jumping a character
 * at a time, but never beyond the row's text, as Android's own text magnifier.
 */
private fun magnifierCenter(state: TextEditorState, position: CharLineOffset, target: Offset): Offset {
	val row = state.getPositionForOffset(position)
	val wrap = state.lineOffsets.lastOrNull { it.line == position.line && position.char >= it.wrapStartsAtIndex }
	val x = if (wrap == null) {
		row.position.x
	} else {
		val layout = wrap.textLayoutResult.multiParagraph
		val left = wrap.offset.x + layout.getLineLeft(wrap.virtualLineIndex)
		val right = wrap.offset.x + layout.getLineRight(wrap.virtualLineIndex)
		target.x.coerceIn(minOf(left, right), maxOf(left, right))
	}
	return Offset(x, row.position.y + row.height / 2f)
}

/** From the finger at [down] to the middle of [position]'s row: what a handle drag moves. */
private fun grabOffset(state: TextEditorState, position: CharLineOffset, down: Offset): Offset {
	val edge = state.getPositionForOffset(position)
	return Offset(edge.position.x, edge.position.y + edge.height / 2f) - down
}

/**
 * Whether a finger at [position] lands on the touch caret handle. The hit area is the
 * drawn handle and a margin, no wider: the handle hangs over the lines below the caret,
 * and a tap or long press there must still reach them.
 */
private fun isOnCaretHandle(position: Offset, state: TextEditorState): Boolean {
	if (!state.selector.isCaretHandleVisible) return false
	val center = handleCenter(state.getPositionForOffset(state.cursorPosition))
	return (position - center).getDistance() < SELECTION_HANDLE_RADIUS * 1.5f
}

/** Whether a finger at [position] lands on any touch handle. */
private fun isOnAnyHandle(position: Offset, state: TextEditorState): Boolean =
	findHandleAtPosition(position, state) != null || isOnCaretHandle(position, state)

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
	// Handles are drawn only for a focused touch selection, so only then can a finger grab one.
	if (!state.selector.isTouchSelection || !state.hasFocus) return null
	val selection = state.selector.selection ?: return null

	val startHandlePos = handleCenter(state.getPositionForOffset(selection.start))
	val endHandlePos = handleCenter(state.getPositionForOffset(selection.end))

	// The hit areas overlap on a short selection, so the nearer handle wins.
	val toStart = (position - startHandlePos).getDistance()
	val toEnd = (position - endHandlePos).getDistance()
	return when {
		toStart < HANDLE_HIT_RADIUS && toStart <= toEnd -> SelectionHandle(selection.start, true, startHandlePos)
		toEnd < HANDLE_HIT_RADIUS -> SelectionHandle(selection.end, false, endHandlePos)
		else -> null
	}
}

/** Larger than the drawn handle, for easier touch targeting. */
private const val HANDLE_HIT_RADIUS = 80f

/**
 * Places the caret for a tap or a right-click, then offers the event to the [RichSpan]
 * under it. A tap reports only when it lifts on the span it landed on, [pressedSpan].
 * Returns whether the caret was placed.
 */
private fun handleSpanInteraction(
	state: TextEditorState,
	offset: Offset,
	clickType: SpanClickType,
	modifiers: PointerKeyboardModifiers,
	onSpanClick: SpanClickSink?,
	readOnly: Boolean,
	pressedSpan: RichSpan? = null,
): Boolean {
	if (clickType == SpanClickType.TAP && isOnAnyHandle(offset, state)) return false

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

	val span = state.spanAt(offset)
	if (span != null && (clickType != SpanClickType.TAP || span == pressedSpan)) {
		onSpanClick?.invoke(RichSpanClick(span, clickType, offset, modifiers))
	}
	return placesCaret
}

/** Finger taps and long presses. */
private fun Modifier.handleTouchInteractions(
	state: TextEditorState,
	onSpanClick: SpanClickSink?,
	onContextMenuRequest: ((Offset) -> Unit)?,
	readOnly: Boolean,
	links: LinkClicks?,
	caretHandle: Boolean,
	contentOrigin: () -> Offset,
): Modifier {
	return pointerInput(state, links, caretHandle) {
		val touchSlop = viewConfiguration.touchSlop
		awaitEachGesture {
			val down = awaitFirstDown(requireUnconsumed = false)
			if (currentEvent.isMouseLike(down)) return@awaitEachGesture
			val origin = contentOrigin()
			val downAt = down.inContent(origin)
			if (isOnAnyHandle(downAt, state)) return@awaitEachGesture

			var didLongPress = false
			var wasDrag = false
			val pressed = ClickTarget.at(state, downAt)
			val existingSelection = state.selector.selection
			val longPressJob = state.scope.launch {
				delay(500)
				val wordPosition = state.getOffsetAtPosition(downAt)

				val isOnSelection = existingSelection != null &&
						(wordPosition isAfterOrEqual existingSelection.start) &&
						(wordPosition isBeforeOrEqual existingSelection.end)

				if (isOnSelection) {
					onContextMenuRequest?.invoke(downAt)
				} else {
					// Off any word this selects nothing and leaves the caret at the press.
					val word = state.selector.rangeAt(wordPosition, SelectionGranularity.Word)
					state.selector.selectFromAnchor(word, wordPosition, SelectionGranularity.Word, isTouch = true)
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
							val placed = handleSpanInteraction(
								state,
								change.inContent(origin),
								SpanClickType.TAP,
								event.keyboardModifiers,
								onSpanClick,
								readOnly,
								pressedSpan = pressed.span,
							)
							if (placed && caretHandle) state.selector.showCaretHandle()
							if (pressed.link != null && links?.opensOnTap == true &&
								ClickTarget.at(state, change.inContent(origin)).link == pressed.link
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
