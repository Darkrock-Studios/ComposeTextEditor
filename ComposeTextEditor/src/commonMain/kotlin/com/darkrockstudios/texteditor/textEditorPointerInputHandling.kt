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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.cursor.getWrappedLineIndex
import com.darkrockstudios.texteditor.dragdrop.SelectionDrag
import com.darkrockstudios.texteditor.html.sanitizeLinkUrl
import com.darkrockstudios.texteditor.input.CtrlKeyBindings
import com.darkrockstudios.texteditor.input.KeyBindings
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.platformKeyBindings
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.CaretAffinity
import com.darkrockstudios.texteditor.state.PointerHit
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
	touchToolbar: TouchToolbar? = null,
	selectionDrag: SelectionDrag? = null,
	primaryPaste: (() -> Unit)? = null,
	handles: HandleLook,
): Modifier {
	return this
		.handleHandleDrag(state, contentOrigin, touchToolbar, handles)
		.handleTouchInteractions(
			state, onSpanClick, onContextMenuRequest, readOnly, links, caretHandle, contentOrigin, touchToolbar, selectionDrag,
			handles,
		)
		.handleMouseInput(
			state, onSpanClick, onContextMenuRequest, readOnly, links, contentOrigin, touchToolbar, selectionDrag, primaryPaste,
		)
}

/**
 * [PointerInputChange.position] in text-canvas coordinates. The pointer node can span the
 * content padding as well, so its origin sits [origin] above and left of the canvas's.
 */
private fun PointerInputChange.inContent(origin: Offset): Offset = position - origin

/**
 * Where a pointer is in text-canvas coordinates. A drag asks it once for each move, in
 * order: a handle's popup follows its finger by the moves alone ([FollowedFinger]).
 */
internal typealias ContentPosition = (PointerInputChange) -> Offset

private fun inContent(origin: Offset): ContentPosition = { it.inContent(origin) }

/**
 * A finger that went down at [downAt] in the canvas on a handle's popup, followed by its
 * moves: the popup moves with its handle, so the finger's place in it says nothing of
 * where it is over the text.
 */
internal class FollowedFinger(downAt: Offset) : ContentPosition {
	private var at = downAt

	override fun invoke(change: PointerInputChange): Offset {
		at += change.positionChangeIgnoreConsumed()
		return at
	}
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

/**
 * Whether a second finger is down beside [down], which makes the gesture a pinch or a
 * two-finger scroll rather than a tap or long press. Only pointers that hit the editor's
 * node appear in an event, so a second finger landing outside the editor is not seen.
 */
internal fun PointerEvent.hasOtherFingerDown(down: PointerInputChange): Boolean =
	changes.any { it.id != down.id && it.pressed }

/** Whether [change], of the pointer that went down at [down], has left the tap: travelled or joined. */
internal fun PointerEvent.leavesTap(down: PointerInputChange, change: PointerInputChange, touchSlop: Float): Boolean =
	(change.position - down.position).getDistance() > touchSlop || hasOtherFingerDown(down)

internal fun PointerEvent.isMouseLike(down: PointerInputChange): Boolean =
	down.type == PointerType.Mouse || buttons.areAnyPressed

/**
 * Waits on [pass] for a press by any pointer or mouse button and returns its change; the
 * event it came in is [AwaitPointerEventScope.currentEvent]. Not `awaitFirstDown`: on
 * skiko that ignores every mouse button but the primary one.
 */
internal suspend fun AwaitPointerEventScope.awaitAnyPress(
	pass: PointerEventPass = PointerEventPass.Main,
): PointerInputChange {
	while (true) {
		awaitPointerEvent(pass).changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.let { return it }
	}
}

/**
 * Counts successive presses into single, double, and triple clicks. A press continues
 * the sequence when it lands within the platform's double-tap timeout and [slop] of the
 * previous one. A mouse measures the timeout from the previous press; a finger
 * ([fromRelease]) from the previous lift, as Android's tap detection does.
 */
private class ClickCounter(
	private val viewConfiguration: ViewConfiguration,
	private val slop: Float = viewConfiguration.touchSlop,
	private val fromRelease: Boolean = false,
) {
	private var lastTime = 0L
	private var lastPosition: Offset? = null
	private var clicks = 0

	fun register(down: PointerInputChange): Int {
		val previous = lastPosition
		val continues = previous != null &&
				down.uptimeMillis - lastTime < viewConfiguration.doubleTapTimeoutMillis &&
				(down.position - previous).getDistance() < slop
		clicks = if (continues) (clicks + 1).coerceAtMost(3) else 1
		lastTime = down.uptimeMillis
		lastPosition = down.position
		return clicks
	}

	fun released(up: PointerInputChange) {
		if (fromRelease) lastTime = up.uptimeMillis
	}

	fun reset() {
		lastPosition = null
		clicks = 0
	}
}

/** How far apart two taps may land and still be a double tap: Android's double-tap slop. */
internal val DOUBLE_TAP_SLOP = 100.dp

/**
 * How much further than the touch slop a press held inside the selection waits where the
 * platform starts the drag itself, so that a browser whose drag threshold is the slop
 * (Firefox on GTK) still starts it.
 */
private const val PLATFORM_DRAG_SLOP_FACTOR = 3f

/**
 * Every mouse gesture. The primary button places the caret on press (or extends with
 * shift), a second and third press select the word and the line, and a drag extends by
 * whatever unit the press selected. A plain press inside the selection is held instead:
 * moving past the slop hands the selection to [selectionDrag], and coming up in place
 * puts the caret there, as native editors do. The secondary button opens the context
 * menu. The middle button places the caret and runs [primaryPaste], where there is one;
 * any other button does nothing.
 */
private fun Modifier.handleMouseInput(
	state: TextEditorState,
	onSpanClick: SpanClickSink?,
	onContextMenuRequest: ((Offset) -> Unit)?,
	readOnly: Boolean,
	links: LinkClicks?,
	contentOrigin: () -> Offset,
	touchToolbar: TouchToolbar?,
	selectionDrag: SelectionDrag?,
	primaryPaste: (() -> Unit)?,
): Modifier = pointerInput(state, links, touchToolbar, selectionDrag, primaryPaste) {
	val clickCounter = ClickCounter(viewConfiguration)
	val touchSlop = viewConfiguration.touchSlop
	coroutineScope {
	val autoScrollScope = this
	awaitEachGesture {
		val down = awaitAnyPress()
		val press = currentEvent
		if (!press.isMouseLike(down)) return@awaitEachGesture
		val origin = contentOrigin()
		val downAt = down.inContent(origin)
		touchToolbar?.hide()

		val buttons = press.buttons
		when {
			(buttons.isPrimaryPressed && !buttons.isSecondaryPressed) || !buttons.areAnyPressed -> {
				val isShiftPressed = press.keyboardModifiers.isShiftPressed
				val clicks = clickCounter.register(down)
				val placesCaret = clicks == 1 && !isShiftPressed
				// Before anything under the pointer is read, so a behavior's edit is laid out first.
				state.finishCompositionIfPointerLeaves(state.pointerHitAt(downAt).position.takeIf { placesCaret })
				// The second and third press of a multi-click select; only a plain first
				// press can become a click on what is under it.
				val pressed = if (placesCaret) ClickTarget.at(state, downAt) else null
				val held = pressed != null && selectionDrag != null && state.selectionContains(downAt)
				val outcome = if (held) {
					selectionDrag.holdPress()
					var dragged = false
					val slop = if (selectionDrag.platformStartsDrags) touchSlop * PLATFORM_DRAG_SLOP_FACTOR else touchSlop
					val change = try {
						awaitMoveOrRelease(down, slop)
					} finally {
						dragged = selectionDrag.releasePress()
					}
					// The platform's drag has the press, which it ends as a cancel or a release.
					if (dragged) {
						clickCounter.reset()
						return@awaitEachGesture
					}
					change ?: return@awaitEachGesture
				} else {
					null
				}
				if (outcome != null && outcome.pressed && selectionDrag?.start(outcome.position) == true) {
					clickCounter.reset()
					return@awaitEachGesture
				}
				val release = if (outcome != null && !outcome.pressed) {
					PointerSelection.press(state, downAt, SelectionGranularity.forClickCount(clicks))
					outcome
				} else {
					val selection = PointerSelection.press(
						state,
						position = downAt,
						granularity = SelectionGranularity.forClickCount(clicks),
						isShiftPressed = isShiftPressed,
					)
					val autoScroll = DragAutoScroll(state, autoScrollScope, onDrag = selection::selectTo)
					followDrag(autoScroll, down, downAt, inContent(origin), touchSlop) ?: return@awaitEachGesture
				}
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

			buttons.isTertiaryPressed && primaryPaste != null -> {
				clickCounter.reset()
				PointerSelection.press(state, downAt, SelectionGranularity.Character)
				primaryPaste()
			}

			else -> clickCounter.reset()
		}
	}
	}
}

/**
 * A pointer selection in progress: the unit the press selected stays selected, and the
 * drag extends from it by the same [granularity]. The caret follows the moving end even
 * in a read-only view, where it is not drawn, because shift+click extends from wherever
 * the caret is. A finger selection ([isTouch]) gets handles and, while dragged, the
 * magnifier over its moving end.
 */
private class PointerSelection(
	private val state: TextEditorState,
	private val anchor: TextEditorRange,
	private val granularity: SelectionGranularity,
	private val isTouch: Boolean,
) {
	fun selectTo(position: Offset) = selectTo(state.pointerHitAt(position))

	private fun selectTo(hit: PointerHit) {
		state.selector.selectFromAnchor(anchor, granularity.unitOf(hit), granularity, isTouch, hit.affinity)
	}

	/** [selectTo] for a finger drag, which also magnifies the end it moves. */
	fun dragTo(position: Offset) {
		selectTo(position)
		state.selector.magnifierCenter = magnifierCenter(state, state.cursorPosition, position, state.cursor.affinity)
	}

	fun release() {
		state.selector.magnifierCenter = null
	}

	companion object {
		/** Selects for a press at [position]: a caret, word, or line, or a shift extension. */
		fun press(
			state: TextEditorState,
			position: Offset,
			granularity: SelectionGranularity,
			isShiftPressed: Boolean = false,
			isTouch: Boolean = false,
		): PointerSelection {
			var hit = state.pointerHitAt(position)
			val placesCaret = !isShiftPressed && granularity == SelectionGranularity.Character
			if (state.finishCompositionIfPointerLeaves(hit.position.takeIf { placesCaret })) {
				hit = state.pointerHitAt(position)
			}
			val anchor = if (isShiftPressed) {
				val fixed = state.selector.extensionAnchor(state.cursorPosition)
				TextEditorRange(fixed, fixed)
			} else {
				state.selector.rangeAt(granularity.unitOf(hit), granularity)
			}
			state.selector.hideCaretHandle()
			return PointerSelection(state, anchor, granularity, isTouch).also { it.selectTo(hit) }
		}
	}
}

/** What a pointer selection by this unit extends to: a caret for characters, else the character hit. */
private fun SelectionGranularity.unitOf(hit: PointerHit): CharLineOffset =
	if (this == SelectionGranularity.Character) hit.position else hit.character

/** Whether [position] in the canvas is over a selected character, not merely beside one. */
private fun TextEditorState.selectionContains(position: Offset): Boolean {
	val selection = selector.selection ?: return false
	val char = characterAt(position) ?: return false
	return char >= selection.start && char < selection.end
}

/**
 * Waits for [down] to move past [touchSlop] or to come up, answering that change (still
 * pressed for a move), or null when the pointer is gone.
 */
private suspend fun AwaitPointerEventScope.awaitMoveOrRelease(
	down: PointerInputChange,
	touchSlop: Float,
): PointerInputChange? {
	while (true) {
		val event = awaitPointerEvent()
		val change = event.changes.firstOrNull { it.id == down.id } ?: return null
		if (!change.pressed) return change
		if (event.leavesTap(down, change, touchSlop)) {
			change.consume()
			return change
		}
	}
}

/**
 * Feeds the drag from [down], at [downAt] in the canvas, to [autoScroll] until the pointer
 * is released. Returns the release when the pointer came up without having moved past
 * [touchSlop], null otherwise. [consumeAll] consumes every change rather than only the
 * moves, as a handle drag does.
 */
private suspend fun AwaitPointerEventScope.followDrag(
	autoScroll: DragAutoScroll,
	down: PointerInputChange,
	downAt: Offset,
	toContent: ContentPosition,
	touchSlop: Float = 0f,
	consumeAll: Boolean = false,
): PointerInputChange? {
	var dragged = false
	try {
		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull { it.id == down.id } ?: return null
			if (!change.pressed) return if (dragged) null else change
			val at = toContent(change)
			dragged = dragged || (at - downAt).getDistance() > touchSlop || event.hasOtherFingerDown(down)
			if (change.positionChanged()) {
				autoScroll.update(at)
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
			state.characterAt(offset)?.let { state.linkToOpenAt(it) },
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
	val rows = lineOffsets
	// Every row of a paragraph carries its top and its whole layout. The paragraph with a
	// row at y is the one hit, unless y is on its top edge, which the one above holds too.
	val atY = rows.getOrNull(rows.rowAtPoint(offset.x + scrollX, y)) ?: return null
	val above = rows.getOrNull(rows.lastRowOfLineAtOrBefore(atY.line - 1))
	fun LineWrap.holds(y: Float): Boolean {
		val height = blockHeight ?: textLayoutResult.size.height.toFloat()
		return y >= paragraphTop && y <= paragraphTop + height
	}
	val found = above?.takeIf { it.holds(y) } ?: atY.takeIf { it.holds(y) } ?: return null
	val layout = found.textLayoutResult.multiParagraph
	val relative = Offset(offset.x + scrollX - found.offset.x, y - found.paragraphTop)
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

/**
 * The URL of the [LinkSpanStyle] covering [position], if any. A destination the allowlist
 * refuses, which only a host attaching the span directly can place, is no link to open.
 */
private fun TextEditorState.linkToOpenAt(position: CharLineOffset): String? =
	lineOffsets.rowAt(position)
		?.richSpans
		?.firstOrNull { it.style is LinkSpanStyle && it.containsPosition(position) }
		?.let { sanitizeLinkUrl((it.style as LinkSpanStyle).url, allowedLinkSchemes) }

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
	val link = state.characterAt(offset)?.let { state.linkToOpenAt(it) }
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
				val event = awaitPointerEvent()
				val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
				if (event.leavesTap(down, change, touchSlop)) return@awaitEachGesture
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
 * a mouse drag. The touch toolbar hides for the drag and comes back when a selection
 * handle is dropped; the caret handle brings it up only when tapped, as Android's
 * insertion handle does.
 */
private fun Modifier.handleHandleDrag(
	state: TextEditorState,
	contentOrigin: () -> Offset,
	touchToolbar: TouchToolbar?,
	handles: HandleLook,
): Modifier {
	return pointerInput(state, touchToolbar, handles) {
		coroutineScope {
			val autoScrollScope = this
			awaitEachGesture {
				val down = awaitFirstDown(requireUnconsumed = false)
				// Consumed by handleTouchInteractions, which runs first: a double tap's second tap.
				if (currentEvent.isMouseLike(down) || down.isConsumed) return@awaitEachGesture
				val origin = contentOrigin()
				val downAt = down.inContent(origin)
				val touched = touchedHandle(downAt, state, handles) ?: return@awaitEachGesture
				dragTouchHandle(state, touched, handles, down, downAt, inContent(origin), touchToolbar, autoScrollScope)
			}
		}
	}
}

/**
 * Drags [touched], grabbed by [down] at [downAt] in the canvas, until the finger lifts.
 * A tap on the caret handle toggles the toolbar, as Android's insertion handle does.
 */
internal suspend fun AwaitPointerEventScope.dragTouchHandle(
	state: TextEditorState,
	touched: TouchHandle,
	handles: HandleLook,
	down: PointerInputChange,
	downAt: Offset,
	toContent: ContentPosition,
	touchToolbar: TouchToolbar?,
	autoScrollScope: CoroutineScope,
) {
	if (touched.role == HandleRole.Caret) {
		val wasShown = touchToolbar?.isShown == true
		touchToolbar?.hide()
		val tapped = dragCaretHandle(state, down, downAt, toContent, autoScrollScope)
		if (tapped && !wasShown) touchToolbar?.showOnRelease()
	} else {
		val isStart = touched.role == HandleRole.Start
		val handle = SelectionHandle(touched.position, isStart, handles.grabPoint(this, touched))
		touchToolbar?.hide()
		dragSelectionHandle(state, handle, down, downAt, toContent, autoScrollScope)
		if (state.selector.hasSelection()) touchToolbar?.showOnRelease()
	}
}

private suspend fun AwaitPointerEventScope.dragSelectionHandle(
	state: TextEditorState,
	handle: SelectionHandle,
	down: PointerInputChange,
	downAt: Offset,
	toContent: ContentPosition,
	autoScrollScope: CoroutineScope,
) {
	// A behavior's edit reaching into the selection clears it, and with it the handle.
	state.finishCompositionIfPointerLeaves(caretAt = null)
	val selection = state.selector.selection ?: return
	val anchor = if (handle.isStart) selection.end else selection.start
	val handleAffinity = handleAffinity(handle.isStart)
	val grabOffset = grabOffset(state, handle.position, downAt, handleAffinity)
	state.selector.setDraggingHandle(handle.isStart)
	state.selector.magnifierCenter = magnifierCenter(state, handle.position, downAt + grabOffset, handleAffinity)

	val autoScroll = DragAutoScroll(state, autoScrollScope, grabOffset, startAt = downAt) { target ->
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
		state.selector.magnifierCenter =
			magnifierCenter(state, position, target, handleAffinity(isStart = position isBefore anchor))
	}
	try {
		followDrag(autoScroll, down, downAt, toContent, consumeAll = true)
	} finally {
		state.selector.clearDraggingHandle()
		state.selector.magnifierCenter = null
	}
}

/**
 * Drags the touch caret handle, moving the caret. The handle stays up through the drag,
 * and its idle timeout restarts on release. Anything else that moves the caret, edits,
 * selects, or takes focus meanwhile takes the handle away and ends the drag. Returns
 * whether the finger lifted without dragging: a tap on the handle.
 */
private suspend fun AwaitPointerEventScope.dragCaretHandle(
	state: TextEditorState,
	down: PointerInputChange,
	downAt: Offset,
	toContent: ContentPosition,
	autoScrollScope: CoroutineScope,
): Boolean {
	val grabOffset = grabOffset(state, state.cursorPosition, downAt, state.cursor.affinity)
	state.selector.magnifierCenter =
		magnifierCenter(state, state.cursorPosition, downAt + grabOffset, state.cursor.affinity)
	val autoScroll = DragAutoScroll(state, autoScrollScope, grabOffset, startAt = downAt) { target ->
		if (!state.selector.isCaretHandleVisible) {
			state.selector.magnifierCenter = null
			return@DragAutoScroll
		}
		var hit = state.pointerHitAt(target)
		if (state.finishCompositionIfPointerLeaves(hit.position)) hit = state.pointerHitAt(target)
		state.selector.dragCaretHandleTo(hit.position, hit.affinity)
		state.selector.magnifierCenter = magnifierCenter(state, hit.position, target, hit.affinity)
	}
	try {
		return followDrag(autoScroll, down, downAt, toContent, viewConfiguration.touchSlop, consumeAll = true) != null
	} finally {
		state.selector.releaseCaretHandle()
		state.selector.magnifierCenter = null
	}
}

/**
 * The magnifier's point for a handle dragged to [position]: on the middle of its row
 * (the one [affinity] picks at a wrap offset), and level with the dragged point [target]
 * so it glides rather than jumping a character at a time, but never beyond the row's
 * text, as Android's own text magnifier.
 */
private fun magnifierCenter(
	state: TextEditorState,
	position: CharLineOffset,
	target: Offset,
	affinity: CaretAffinity = CaretAffinity.Downstream,
): Offset {
	val row = state.getPositionForOffset(position, affinity)
	val wrap = state.lineOffsets.getOrNull(state.lineOffsets.getWrappedLineIndex(position, affinity))
	val x = if (wrap == null) {
		row.position.x
	} else {
		val layout = wrap.textLayoutResult.multiParagraph
		val left = wrap.offset.x - state.scrollX + layout.getLineLeft(wrap.virtualLineIndex)
		val right = wrap.offset.x - state.scrollX + layout.getLineRight(wrap.virtualLineIndex)
		// The line's edges stop before trailing spaces, which a caret at the row's end is past.
		val caret = row.position.x
		target.x.coerceIn(minOf(left, right, caret), maxOf(left, right, caret))
	}
	return Offset(x, row.position.y + row.height / 2f)
}

/** From the finger at [down] to the middle of [position]'s row: what a handle drag moves. */
private fun grabOffset(
	state: TextEditorState,
	position: CharLineOffset,
	down: Offset,
	affinity: CaretAffinity = CaretAffinity.Downstream,
): Offset {
	val edge = state.getPositionForOffset(position, affinity)
	return Offset(edge.position.x, edge.position.y + edge.height / 2f) - down
}

/**
 * Finishes the IME composition before a pointer puts the caret at [caretAt], or a
 * selection (null), outside it, which is what keyboards do themselves when told of the
 * move. One that does not would replace the old composing word, wherever it is, with
 * its next keystroke. A caret placed inside the composition keeps it: some keyboards
 * edit mid-composition.
 *
 * The pointer owns the caret: a typed composition is offered to the behaviors first,
 * so what they make of it is laid out before the pointer's position is read, and the
 * caret or selection then goes where the pointer is on the substituted text, as after a
 * keyboard's own finish. Returns whether a behavior changed the document, in which case
 * the caller reads its hit again.
 */
private fun TextEditorState.finishCompositionIfPointerLeaves(caretAt: CharLineOffset?): Boolean {
	val composing = composingRange ?: return false
	val caretInside = caretAt != null &&
			(caretAt isAfterOrEqual composing.start) &&
			(caretAt isBeforeOrEqual composing.end)
	if (caretInside) return false
	val revisionBefore = revision
	finishComposition()
	return revision != revisionBefore
}

/**
 * Places the caret for a tap or a right-click, then offers the event to the [RichSpan]
 * under it. A tap reports only when it lifts on the span it landed on at [pressedAt].
 * Returns whether the caret was placed.
 */
private fun Density.handleSpanInteraction(
	state: TextEditorState,
	offset: Offset,
	clickType: SpanClickType,
	modifiers: PointerKeyboardModifiers,
	onSpanClick: SpanClickSink?,
	readOnly: Boolean,
	pressedAt: Offset? = null,
): Boolean {
	var hit = state.pointerHitAt(offset)
	val placesCaret = when (clickType) {
		SpanClickType.PRIMARY_CLICK, SpanClickType.TAP -> true
		// Like native editors, a right-click inside the selection keeps it for the context
		// menu, and one outside it moves the caret there first. Read-only views have no
		// caret to move, so they keep the selection either way.
		SpanClickType.SECONDARY_CLICK -> !readOnly && !state.selector.selectionContains(hit.character)
	}
	if (placesCaret) {
		if (state.finishCompositionIfPointerLeaves(hit.position)) hit = state.pointerHitAt(offset)
		if (!readOnly) {
			state.cursor.updatePosition(hit.position, hit.affinity)
		}
		state.selector.clearSelection()
	}

	// Both read after the composition is finished, since a behavior's edit moves the spans.
	val span = state.spanAt(offset)
	val pressedSpan = pressedAt?.let { state.spanAt(it) }
	if (span != null && (clickType != SpanClickType.TAP || span == pressedSpan)) {
		onSpanClick?.invoke(RichSpanClick(span, clickType, offset, modifiers))
	}
	return placesCaret
}

/**
 * Finger taps, double taps, and long presses. A tap places the caret; a second tap
 * within the platform's double-tap timeout selects the word under it; a long press
 * selects the word under it, or brings up the menu when it lands on the selection.
 * While the platform toolbar is up over the selection, a long press inside the selection
 * hands it to [selectionDrag] instead, as Android's `EditText` does.
 * Dragging on from a double tap or a long press extends the selection by word, as
 * Android's text fields do, and the moves are consumed so the ancestor scrollable does
 * not pan with them. Once the finger lifts, the [touchToolbar] shows over what was
 * selected, or over the caret a long press placed, which is how a finger reaches Paste.
 */
private fun Modifier.handleTouchInteractions(
	state: TextEditorState,
	onSpanClick: SpanClickSink?,
	onContextMenuRequest: ((Offset) -> Unit)?,
	readOnly: Boolean,
	links: LinkClicks?,
	caretHandle: Boolean,
	contentOrigin: () -> Offset,
	touchToolbar: TouchToolbar?,
	selectionDrag: SelectionDrag?,
	handles: HandleLook,
): Modifier {
	return pointerInput(state, links, caretHandle, touchToolbar, selectionDrag, handles) {
		val touchSlop = viewConfiguration.touchSlop
		val longPressTimeout = viewConfiguration.longPressTimeoutMillis
		val tapCounter = ClickCounter(viewConfiguration, slop = DOUBLE_TAP_SLOP.toPx(), fromRelease = true)
		coroutineScope {
			val autoScrollScope = this
			awaitEachGesture {
				val down = awaitFirstDown(requireUnconsumed = false)
				if (currentEvent.isMouseLike(down)) return@awaitEachGesture
				val origin = contentOrigin()
				val downAt = down.inContent(origin)
				val touched = touchedHandle(downAt, state, handles)
				val doubleTap = if (touched != null) {
					// A caret target on the caret's own text is where both taps of a double tap
					// land, so taps on it are counted, and the second selects the word as usual.
					if (touched.role != HandleRole.Caret || !handles.caretTargetOnText) {
						tapCounter.reset()
						return@awaitEachGesture
					}
					if (tapCounter.register(down) < 2) {
						// The handle takes this one; it counts as a tap if it lifts without dragging.
						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull { it.id == down.id } ?: break
							if (!change.pressed) {
								tapCounter.released(change)
								break
							}
							if (event.leavesTap(down, change, touchSlop)) {
								tapCounter.reset()
								break
							}
						}
						return@awaitEachGesture
					}
					down.consume()
					true
				} else {
					tapCounter.register(down) >= 2
				}

				if (doubleTap) {
					tapCounter.reset()
					touchToolbar?.hide()
					val selection = PointerSelection.press(state, downAt, SelectionGranularity.Word, isTouch = true)
					dragTouchSelection(state, selection, down, origin, autoScrollScope)
					touchToolbar?.showOnRelease()
					return@awaitEachGesture
				}

				var didLongPress = false
				var wasDrag = false
				var longPressSelection: PointerSelection? = null
				var showToolbarOnRelease = false
				val pressed = ClickTarget.at(state, downAt)
				val existingSelection = state.selector.selection
				val longPressJob = state.scope.launch {
					delay(longPressTimeout)
					val wordPosition = state.pointerHitAt(downAt).character

					val isOnSelection = existingSelection != null &&
							(wordPosition isAfterOrEqual existingSelection.start) &&
							(wordPosition isBeforeOrEqual existingSelection.end)

					if (isOnSelection) {
						// The toolbar waits for the finger to lift, as after every other
						// gesture; the fallback menu is modal, so it opens now, under the finger.
						// A drag that starts has the finger, and the toolbar goes, as Android's does;
						// with the toolbar gone the long press brings it back instead.
						when {
							touchToolbar == null -> onContextMenuRequest?.invoke(downAt)
							!touchToolbar.isNative -> touchToolbar.showMenuAt(downAt)
							touchToolbar.isShown && state.selectionContains(downAt) &&
									selectionDrag?.start(down.position, byFinger = true) == true -> touchToolbar.hide()
							else -> showToolbarOnRelease = true
						}
					} else {
						// Off any word this selects nothing and leaves the caret at the press. Past
						// a row's end nothing is under the finger, though the nearest character is
						// the row's last, so the caret goes to the row's end.
						val granularity = if (state.characterAt(downAt) == null) {
							SelectionGranularity.Character
						} else {
							SelectionGranularity.Word
						}
						longPressSelection = PointerSelection.press(state, downAt, granularity, isTouch = true)
						showToolbarOnRelease = true
					}

					didLongPress = true
				}

				try {
					while (true) {
						val event = awaitPointerEvent()
						val change = event.changes.firstOrNull { it.id == down.id } ?: break
						if (!change.pressed) {
							if (didLongPress || wasDrag) tapCounter.reset() else tapCounter.released(change)
							if (showToolbarOnRelease) touchToolbar?.showOnRelease()
							if (!didLongPress && !wasDrag) {
								touchToolbar?.hide()
								val releasedAt = change.inContent(origin)
								val placed = touchedHandle(releasedAt, state, handles) == null && handleSpanInteraction(
									state,
									releasedAt,
									SpanClickType.TAP,
									event.keyboardModifiers,
									onSpanClick,
									readOnly,
									pressedAt = downAt,
								)
								if (placed && caretHandle) state.selector.showCaretHandle()
								if (pressed.link != null && links?.opensOnTap == true &&
									ClickTarget.at(state, releasedAt).link == pressed.link
								) {
									links.open(pressed.link)
								}
							}
							break
						}
						val selection = longPressSelection
						if (selection != null) {
							tapCounter.reset()
							dragTouchSelection(state, selection, down, origin, autoScrollScope, first = change)
							touchToolbar?.showOnRelease()
							break
						}
						// Only a move past touch slop is a drag, so a high-precision touch screen's
						// micro-movements still tap. A second finger is a pinch or a scroll for
						// some ancestor, which Android's gesture detector also takes for neither
						// a tap nor a long press.
						if (!wasDrag && event.leavesTap(down, change, touchSlop)) {
							wasDrag = true
							tapCounter.reset()
							longPressJob.cancel()
						}
					}
				} finally {
					longPressJob.cancel()
				}
			}
		}
	}
}

/**
 * Extends a finger [selection] with the drag from [down] until the finger lifts, auto-
 * scrolling like a mouse drag. [first] is a move that arrived before the drag was picked
 * up, which the selection still has to follow.
 */
private suspend fun AwaitPointerEventScope.dragTouchSelection(
	state: TextEditorState,
	selection: PointerSelection,
	down: PointerInputChange,
	origin: Offset,
	autoScrollScope: CoroutineScope,
	first: PointerInputChange? = null,
) {
	val autoScroll = DragAutoScroll(state, autoScrollScope, onDrag = selection::dragTo)
	try {
		if (first != null && first.positionChanged()) {
			autoScroll.update(first.inContent(origin))
			first.consume()
		}
		followDrag(autoScroll, down, down.inContent(origin), inContent(origin))
	} finally {
		selection.release()
	}
}
