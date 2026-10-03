package com.darkrockstudios.texteditor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The touch handles, each drawn in a popup at its end as `BasicTextField`'s are, so one
 * hanging past the editor's edge (a teardrop under the last visible row, a bar's dot over
 * the first) still draws, past any ancestor's clip, and takes a finger there. A handle
 * whose [TouchHandle.anchor] is outside the canvas's visible bounds, scrolled away or
 * clipped by an ancestor, is hidden. A handle that draws nothing has no popup; the
 * editor's own pointer input takes it.
 *
 * Called in the layout that holds the text canvas, whose content box is the canvas: the
 * popups are placed from it.
 */
@Composable
internal fun BoxScope.TouchHandlePopups(
	state: TextEditorState,
	look: HandleLook,
	color: Color,
	touchToolbar: TouchToolbar?,
) {
	var visibleBounds by remember { mutableStateOf<Rect?>(null) }
	// The handle a finger holds: the others take no finger meanwhile, as the canvas takes one gesture at a time.
	var held by remember { mutableStateOf<HandleRole?>(null) }
	Spacer(Modifier.matchParentSize().onGloballyPositioned { visibleBounds = it.visibleBounds() })
	if (!state.hasFocus) return
	for (role in state.touchHandleRoles()) {
		if (!look.draws(role)) continue
		key(role) {
			TouchHandleSlot(state, role, look, color, { visibleBounds }, touchToolbar, { held }) { held = it }
		}
	}
}

/**
 * The popup for the handle in [role], while its end is in view or a finger holds it. A
 * drag that crosses the other end swaps the roles, so the finger's popup can come to
 * show an end that then scrolls away; it stays, drawing nothing, until the finger lifts.
 */
@Composable
private fun TouchHandleSlot(
	state: TextEditorState,
	role: HandleRole,
	look: HandleLook,
	color: Color,
	visibleBounds: () -> Rect?,
	touchToolbar: TouchToolbar?,
	held: () -> HandleRole?,
	onHeld: (HandleRole?) -> Unit,
) {
	val density = LocalDensity.current
	val currentLook by rememberUpdatedState(look)
	val placement = remember(state, role, density) {
		HandlePlacement {
			val handle = state.touchHandle(role)
			val view = visibleBounds()
			val bounds = handle?.let { currentLook.popupBounds(density, it) }
			if (handle != null && bounds != null && view != null && view.containsInclusive(handle.anchor)) {
				ShownHandle(handle, currentLook, bounds)
			} else {
				null
			}
		}
	}
	val inView by remember(placement) { derivedStateOf { placement.shown != null } }
	if (inView || held() == role) {
		TouchHandlePopup(state, placement, color, touchToolbar, grabbable = { held() == null }) { holding ->
			onHeld(if (holding) role else null)
		}
	}
}

@Composable
private fun TouchHandlePopup(
	state: TextEditorState,
	placement: HandlePlacement,
	color: Color,
	touchToolbar: TouchToolbar?,
	grabbable: () -> Boolean,
	onHeld: (Boolean) -> Unit,
) {
	val currentColor by rememberUpdatedState(color)
	val currentToolbar by rememberUpdatedState(touchToolbar)
	val currentGrabbable by rememberUpdatedState(grabbable)
	val currentOnHeld by rememberUpdatedState(onHeld)
	Popup(popupPositionProvider = placement, properties = PopupProperties(clippingEnabled = false)) {
		Box(
			Modifier
				.layout { measurable, _ ->
					val size = placement.size()
					val placeable = measurable.measure(Constraints.fixed(size.width, size.height))
					layout(size.width, size.height) { placeable.place(0, 0) }
				}
				.drawBehind {
					val shown = placement.shown ?: return@drawBehind
					translate(-shown.origin.x.toFloat(), -shown.origin.y.toFloat()) {
						shown.look.draw(this, shown.handle, currentColor)
					}
				}
				.pointerInput(state, placement) {
					coroutineScope {
						val autoScrollScope = this
						awaitEachGesture {
							val down = awaitFirstDown(requireUnconsumed = false)
							val shown = placement.shown ?: return@awaitEachGesture
							if (!currentGrabbable()) return@awaitEachGesture
							val placedAt = placement.placedAt
							val downAt = Offset(placedAt.x.toFloat(), placedAt.y.toFloat()) + down.position
							down.consume()
							// A finger on a handle stops a fling, as one on the text does.
							autoScrollScope.launch { state.scrollState.stopScroll() }
							currentOnHeld(true)
							try {
								dragTouchHandle(
									state, shown.handle, shown.look, down, downAt, FollowedFinger(downAt),
									currentToolbar, autoScrollScope,
								)
							} finally {
								currentOnHeld(false)
							}
						}
					}
				}
		)
	}
}

/** A handle that shows, its look, and where its popup goes in the canvas. */
private class ShownHandle(val handle: TouchHandle, val look: HandleLook, bounds: Rect) {
	/** Whole pixels at or above and left of the bounds, so the drawing stays inside the popup. */
	val origin = IntOffset(floor(bounds.left).toInt(), floor(bounds.top).toInt())
	val size = IntSize(ceil(bounds.right - origin.x).toInt(), ceil(bounds.bottom - origin.y).toInt())
}

/**
 * Where a handle's popup goes: the handle [shown], found once for every pass that reads
 * it. A hidden handle's popup keeps its last place and size: an Android popup window
 * shrunk to nothing goes on showing what it last drew.
 */
private class HandlePlacement(find: () -> ShownHandle?) : PopupPositionProvider {
	private val shownState = derivedStateOf(find)
	val shown: ShownHandle? get() = shownState.value

	/** Where the popup was last placed in the canvas. */
	var placedAt = IntOffset.Zero
		private set
	private var lastSize = IntSize.Zero

	fun size(): IntSize {
		shown?.let { lastSize = it.size }
		return lastSize
	}

	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		shown?.let { placedAt = it.origin }
		return anchorBounds.topLeft + placedAt
	}
}

/** The part of these coordinates not clipped by an ancestor, in their own space, as Compose's selection finds it. */
private fun LayoutCoordinates.visibleBounds(): Rect {
	val inWindow = boundsInWindow()
	return Rect(windowToLocal(inWindow.topLeft), windowToLocal(inWindow.bottomRight))
}

private fun Rect.containsInclusive(point: Offset): Boolean =
	point.x in left..right && point.y in top..bottom
