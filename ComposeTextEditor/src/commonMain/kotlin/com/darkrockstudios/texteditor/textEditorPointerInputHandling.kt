package com.darkrockstudios.texteditor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import com.darkrockstudios.texteditor.state.SpanClickType
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

data class SelectionHandle(
	val position: CharLineOffset,
	val isStart: Boolean,
	val bounds: Offset
)

/**
 * Whether the tap in flight was answered by a [RichSpan], passed from the gesture
 * handler on the text canvas to the focus handler on the enclosing container.
 *
 * Deliberately a private object rather than pointer consumption. Consumption is
 * broadcast: it changes what every other handler in the tree sees, so using it to
 * carry this one bit also tells the ancestor scroll container its drag was taken,
 * which cancels flings and aborts scrolls.
 */
internal class SpanTapGate {
	var claimed: Boolean = false
		private set

	fun claim() {
		claimed = true
	}

	fun reset() {
		claimed = false
	}
}

internal fun Modifier.textEditorPointerInputHandling(
	state: TextEditorState,
	onSpanClick: RichSpanClickListener? = null,
	onContextMenuRequest: ((Offset) -> Unit)? = null,
	readOnly: Boolean = false,
	spanTapGate: SpanTapGate? = null,
): Modifier {
	return this
		.handleDragInput(state, readOnly)
		.handleTextInteractions(state, onSpanClick, onContextMenuRequest, readOnly, spanTapGate)
		.detectMouseClicksImperatively(
			onClick = { offset: Offset, isShiftPressed: Boolean ->
				// On shift+click handleDragInput extends the selection; clearing it here
				// (handlers run in parallel) would race away the extension.
				if (!isShiftPressed) {
					val position = state.getOffsetAtPosition(offset)
					if (!readOnly) {
						state.cursor.updatePosition(position)
					}
					state.selector.clearSelection()
				}
			},
			onDoubleClick = { offset: Offset ->
				val position = state.getOffsetAtPosition(offset)
				state.selector.startSelection(position, isTouch = false)
				state.selector.selectWordAt(position)
			},
			onTripleClick = { offset: Offset ->
				val position = state.getOffsetAtPosition(offset)
				state.selector.startSelection(position, isTouch = false)
				state.selector.selectLineAt(position)
			}
		)
}

private fun Modifier.handleDragInput(state: TextEditorState, readOnly: Boolean): Modifier {
	return pointerInput(Unit) {
		awaitEachGesture {
			val down = awaitFirstDown(requireUnconsumed = false)

			// Android reports external mouse input as PointerType.Touch but still populates
			// PointerButtons correctly, so detect "mouse-like" input by the presence of a
			// primary button rather than the pointer type alone. A real finger has no buttons.
			val hasPrimaryButton = currentEvent.buttons.isPrimaryPressed &&
					!currentEvent.buttons.isSecondaryPressed
			val isMouseLike = down.type == PointerType.Mouse || hasPrimaryButton
			val isFingerTouch = down.type == PointerType.Touch && !hasPrimaryButton
			val isShiftPressed = currentEvent.keyboardModifiers.isShiftPressed

			val initialPosition = down.position

			var mouseSelectionAnchor: CharLineOffset? = null

			if (isFingerTouch) {
				val handle = findHandleAtPosition(initialPosition, state)
				if (handle != null) {
					state.selector.setDraggingHandle(handle.isStart)
				}
			} else if (isMouseLike && hasPrimaryButton) {
				// Only start selection drag on primary (left) mouse button
				// Secondary (right) click should preserve existing selection for context menu
				val clickedPosition = state.getOffsetAtPosition(initialPosition)
				if (isShiftPressed) {
					// This handler owns the shift+click extension; the other two pointer
					// handlers must leave the selection alone while shift is held.
					val anchor = state.cursorPosition
					mouseSelectionAnchor = state.selector.extendSelection(anchor, clickedPosition)
					if (!readOnly) {
						state.cursor.updatePosition(clickedPosition)
					}
				} else {
					mouseSelectionAnchor = clickedPosition
					state.selector.startSelection(position = clickedPosition, isTouch = false)
				}
			}

			val pointerId = down.id
			var currentPosition: Offset

			// Continue reading pointer events until release
			while (true) {
				val event = awaitPointerEvent()
				val dragEvent = event.changes.firstOrNull { it.id == pointerId } ?: break

				if (!dragEvent.pressed) {
					state.selector.clearDraggingHandle()
					break
				}

				currentPosition = dragEvent.position

				if (state.selector.isDraggingHandle()) {
					// Offset the finger position well above where the finger is touching
					// so the user can clearly see the text being selected above their finger.
					// This includes: handle visual offset + handle size + extra clearance for finger
					val dragOffset = SELECTION_HANDLE_OFFSET + SELECTION_HANDLE_DIAMETER + 60f
					val adjustedPosition = currentPosition.copy(y = currentPosition.y - dragOffset)
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
				} else {
					if (isMouseLike && mouseSelectionAnchor != null) {
						val currentOffset = state.getOffsetAtPosition(currentPosition)
						state.selector.updateSelection(mouseSelectionAnchor, currentOffset)
						if (!readOnly) {
							state.cursor.updatePosition(currentOffset)
						}
						dragEvent.consume()
					}
				}
			}
		}
	}
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
 * Places the caret for a click or tap and offers the event to any [RichSpan] under
 * it. Returns true when a span (or a selection handle) claimed the event, meaning
 * the click was answered by something other than plain caret placement.
 */
private fun handleSpanInteraction(
	state: TextEditorState,
	offset: Offset,
	clickType: SpanClickType,
	onSpanClick: RichSpanClickListener?,
	readOnly: Boolean,
	isShiftPressed: Boolean = false,
): Boolean {
	if (findHandleAtPosition(offset, state) != null) {
		return true
	}

	val position = state.getOffsetAtPosition(offset)
	val clickedSpan = state.findSpanAtPosition(position)

	// A shift+click extends the selection; handleDragInput owns that, so leave the
	// cursor and selection untouched here rather than collapsing them.
	if (!isShiftPressed && (clickType == SpanClickType.PRIMARY_CLICK || clickType == SpanClickType.TAP)) {
		if (!readOnly) {
			state.cursor.updatePosition(position)
		}
		state.selector.clearSelection()
	}

	return !isShiftPressed && clickedSpan != null && onSpanClick != null &&
			onSpanClick.invoke(clickedSpan, clickType, offset)
}

private fun Modifier.handleTextInteractions(
	state: TextEditorState,
	onSpanClick: RichSpanClickListener?,
	onContextMenuRequest: ((Offset) -> Unit)?,
	readOnly: Boolean,
	spanTapGate: SpanTapGate?,
): Modifier {
	return pointerInput(Unit) {
		val touchSlop = viewConfiguration.touchSlop
		awaitEachGesture {
			var didHandlePress = false
			var longPressJob: Job? = null
			var didLongPress = false
			var wasDrag = false
			var initialPressPosition: Offset? = null
			// Whether this gesture is a real finger touch (vs mouse / mouse-as-touch on Android).
			// Set on Press; controls whether Release fires a TAP. See android_mouse_pointer_type memo.
			var isFingerTouchGesture = false

			while (true) {
				val event = awaitPointerEvent()
				val eventChange = event.changes.first()

				when (event.type) {
					PointerEventType.Press -> {
						val position = eventChange.position
						val hasPrimaryButton = event.buttons.isPrimaryPressed &&
								!event.buttons.isSecondaryPressed
						val hasSecondaryButton = event.buttons.isSecondaryPressed
						val isMouseLike = eventChange.type == PointerType.Mouse ||
								hasPrimaryButton || hasSecondaryButton
						isFingerTouchGesture = !isMouseLike

						// Only check for handle interaction on real finger-touch events
						if (isFingerTouchGesture) {
							val handle = findHandleAtPosition(position, state)
							if (handle != null) {
								didHandlePress = true
								break
							}
						}

						if (isMouseLike) {
							if (hasPrimaryButton) {
								handleSpanInteraction(
									state,
									position,
									SpanClickType.PRIMARY_CLICK,
									onSpanClick,
									readOnly,
									isShiftPressed = event.keyboardModifiers.isShiftPressed,
								)
								didHandlePress = true
							} else if (hasSecondaryButton) {
								handleSpanInteraction(
									state,
									position,
									SpanClickType.SECONDARY_CLICK,
									onSpanClick,
									readOnly,
								)
								onContextMenuRequest?.invoke(position)
								didHandlePress = true
							}
						} else {
							// Real finger touch: long-press to select word / show context menu.
							wasDrag = false
							didLongPress = false
							initialPressPosition = position
							val existingSelection = state.selector.selection
							longPressJob = state.scope.launch {
								delay(500)
								val wordPosition = state.getOffsetAtPosition(position)

								val isOnSelection = existingSelection != null &&
										(wordPosition isAfterOrEqual existingSelection.start) &&
										(wordPosition isBeforeOrEqual existingSelection.end)

								if (isOnSelection) {
									onContextMenuRequest?.invoke(position)
								} else {
									state.selector.startSelection(wordPosition, isTouch = true)
									state.selector.selectWordAt(wordPosition)
								}

								didLongPress = true
								didHandlePress = true
							}
						}
					}

					PointerEventType.Release -> {
						// Only treat as a tap on real finger touch — mouse-like presses already
						// positioned the cursor on Press, and a TAP here would clobber any
						// selection a parallel double-click handler just set.
						if (isFingerTouchGesture && !didLongPress && !wasDrag) {
							val position = eventChange.position
							val claimedBySpan = handleSpanInteraction(
								state,
								position,
								SpanClickType.TAP,
								onSpanClick,
								readOnly,
							)
							// A tap a span answered opened something — spell-check suggestions,
							// a followed link — that the keyboard would cover, so tell the focus
							// handler on the enclosing container not to treat it as a request
							// to start typing.
							if (claimedBySpan) spanTapGate?.claim()
						}

						longPressJob?.cancel()
						longPressJob = null

						if (didHandlePress) {
							break
						}
					}

					PointerEventType.Move -> {
						val movement = event.changes.first()
						// Only consider it a drag if movement exceeds touch slop threshold
						// This prevents high-precision touch screens from treating micro-movements as drags
						initialPressPosition?.let { pressPosition ->
							if (movement.positionChanged()) {
								val distance = (movement.position - pressPosition).getDistance()
								if (distance > touchSlop) {
									wasDrag = true
									longPressJob?.cancel()
									longPressJob = null
								}
							}
						}
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalTime::class)
private fun Modifier.detectMouseClicksImperatively(
	onClick: (Offset, Boolean) -> Unit,
	onDoubleClick: (Offset) -> Unit,
	onTripleClick: (Offset) -> Unit,
): Modifier {
	return pointerInput(Unit) {
		awaitPointerEventScope {
			var lastTapTime = 0L
			var secondLastTapTime = 0L
			var lastTapPosition: Offset? = null
			var secondLastTapPosition: Offset? = null

			while (true) {
				val down = awaitFirstDown(requireUnconsumed = false)

				// Treat anything with a primary mouse button as mouse-like, since Android
				// reports external mice as PointerType.Touch (see android_mouse_pointer_type
				// memo). Real finger touches have no buttons and are skipped here so they
				// don't trigger the click/double-click handlers.
				val hasPrimaryButton = currentEvent.buttons.isPrimaryPressed &&
						!currentEvent.buttons.isSecondaryPressed
				val isMouseLike = down.type == PointerType.Mouse || hasPrimaryButton
				if (!isMouseLike) {
					do {
						val event = awaitPointerEvent()
					} while (event.changes.any { it.pressed })
					continue
				}

				val downTime = kotlin.time.Clock.System.now().toEpochMilliseconds()
				val downPosition = down.position

				onClick(downPosition, currentEvent.keyboardModifiers.isShiftPressed)

				do {
					val event = awaitPointerEvent()
				} while (event.changes.any { it.pressed })

				val isTripleTap = lastTapPosition?.let { lastPos ->
					secondLastTapPosition?.let { secondLastPos ->
						val firstToSecondTimeDiff = lastTapTime - secondLastTapTime
						val secondToThirdTimeDiff = downTime - lastTapTime
						val firstToSecondPosDiff = (lastPos - secondLastPos).getDistance()
						val secondToThirdPosDiff = (downPosition - lastPos).getDistance()

						firstToSecondTimeDiff < 300L &&
								secondToThirdTimeDiff < 300L &&
								firstToSecondPosDiff < 20f &&
								secondToThirdPosDiff < 20f
					}
				} ?: false

				val isDoubleTap = if (!isTripleTap) {
					lastTapPosition?.let { lastPos ->
						val timeDiff = downTime - lastTapTime
						val posDiff = (downPosition - lastPos).getDistance()
						timeDiff < 300L && posDiff < 20f
					} ?: false
				} else false

				when {
					isTripleTap -> {
						onTripleClick(downPosition)
						lastTapTime = 0L
						secondLastTapTime = 0L
						lastTapPosition = null
						secondLastTapPosition = null
					}

					isDoubleTap -> {
						onDoubleClick(downPosition)
						secondLastTapTime = lastTapTime
						secondLastTapPosition = lastTapPosition
						lastTapTime = downTime
						lastTapPosition = downPosition
					}

					else -> {
						secondLastTapTime = lastTapTime
						secondLastTapPosition = lastTapPosition
						lastTapTime = downTime
						lastTapPosition = downPosition
					}
				}
			}
		}
	}
}
