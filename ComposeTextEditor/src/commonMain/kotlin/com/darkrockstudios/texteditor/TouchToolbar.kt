package com.darkrockstudios.texteditor

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Whether [LocalTextToolbar] shows anything on this platform. Android's floating toolbar
 * and iOS's edit menu do; desktop's is inert, and the web's draws only inside foundation's
 * own text fields.
 */
internal expect fun hasNativeTextToolbar(): Boolean

/** [hasNativeTextToolbar], overridable so a test can stand a recording toolbar in for the platform's. */
internal val LocalNativeTextToolbar = staticCompositionLocalOf { hasNativeTextToolbar() }

/**
 * Whether a right-click opens the platform's text toolbar at the pointer rather than the
 * editor's context menu: on iOS, where a text view answers a mouse or trackpad's secondary
 * click with the same edit menu as a long press. Android keeps the context menu, as its
 * text fields do for a mouse.
 */
internal expect fun pointerMenuIsTextToolbar(): Boolean

/** [pointerMenuIsTextToolbar], overridable for tests. */
internal val LocalPointerMenuIsTextToolbar = staticCompositionLocalOf { pointerMenuIsTextToolbar() }

/**
 * The menu a finger reaches Cut, Copy, Paste and Select all through: the platform's text
 * [toolbar] where there is one, else the editor's context menu through [fallback]. It
 * shows once a long press or a double tap lifts, when a handle is tapped or dropped, and
 * goes when anything moves the caret or the selection under it, or focus leaves; a
 * scroll moves it with the text.
 *
 * The fallback is modal, so it would eat the tap after every selection; without a
 * platform toolbar it opens only where nothing else reaches Paste, at a bare caret, and
 * a selection's menu stays a long press on the selection away. It dismisses itself, so
 * only the toolbar is tracked here.
 */
/** How long a [TouchToolbar.show] waits for the editor's input session before it is dropped. */
private const val PENDING_SHOW_TIMEOUT = 1_000L

internal class TouchToolbar(
	private val state: TextEditorState,
	private val toolbar: TextToolbar?,
	private val actions: ContextMenuActions,
	private val fallback: (Offset) -> Unit,
	/** Whether this editor runs an input session when focused: an editable one, not a view. */
	private val takesInput: () -> Boolean = { false },
	/** The look of the handles drawn, whose bounds the toolbar keeps clear of. */
	private val handles: () -> HandleLook,
) {
	private data class Anchor(val selection: TextEditorRange?, val caret: CharLineOffset, val scroll: Int, val scrollX: Int) {
		/** Over the same text: a show waiting for the session measures where it is when it shows, scrolled or not. */
		fun sameText(other: Anchor): Boolean = selection == other.selection && caret == other.caret
	}

	private var shownFor: Anchor? = null

	/** The pointer the toolbar was shown at, in canvas coordinates, for a right-click's; else null. */
	private var shownAt: Offset? = null

	/**
	 * What a [show] waits to be shown over: an editor that takes input, before its session
	 * runs, as when a long press focuses it. The platform's menu needs the session's view (on iOS
	 * the first responder that hosts the edit menu), and shows nothing without it.
	 */
	private var pendingFor: Anchor? = null
	private var pendingAt: Offset? = null

	/** Whether there is a platform toolbar, which shows on release, rather than the modal menu. */
	val isNative: Boolean get() = toolbar != null

	/** Whether the platform toolbar is up. */
	val isShown: Boolean get() = shownFor != null

	/**
	 * Shows the items the editor can act on now, over the selection or the caret, or at
	 * [pointer] (canvas coordinates) for a right-click's menu.
	 */
	fun show(pointer: Offset? = null) {
		if (toolbar != null && takesInput() && !state.inputSessionRunning) {
			val pending = anchor()
			pendingFor = pending
			pendingAt = pointer
			// A press that never focuses the editor must not leave a menu waiting for a later session.
			state.scope.launch {
				delay(PENDING_SHOW_TIMEOUT)
				if (pendingFor == pending) pendingFor = null
			}
			return
		}
		pendingFor = null
		if (toolbar == null) {
			if (state.selector.selection == null) {
				val caret = contentRect(coverHandles = false)
				fallback(Offset(caret.left, caret.bottom))
			}
			return
		}
		toolbar.showMenu(
			rect = rootRect(pointer?.let { Rect(it, it) } ?: contentRect()),
			onCopyRequested = if (actions.canCopy()) ({ actions.copy(); hide() }) else null,
			onPasteRequested = if (actions.canPaste()) ({ actions.paste(); hide() }) else null,
			onCutRequested = if (actions.canCut()) ({ actions.cut(); hide() }) else null,
			onSelectAllRequested = if (actions.canPerform(EditorCommand.Action.SelectAll)) {
				{
					actions.selectAll()
					state.selector.markTouchSelection()
					// Android keeps the toolbar up over the new selection, now with handles.
					// Its action mode finishes itself once this click returns, so the
					// toolbar comes back a frame later.
					state.scope.launch {
						if (coroutineContext[MonotonicFrameClock] != null) withFrameNanos { } else yield()
						show()
					}
				}
			} else {
				null
			},
		)
		shownFor = anchor()
		shownAt = pointer
	}

	/**
	 * [show] for a gesture that ends with the toolbar, as its finger lifts. The editor
	 * asks for the keyboard on that same release, after the canvas has acted on it, and
	 * on iOS that request dismisses an edit menu shown during the release, so the
	 * platform's toolbar comes a frame later, if nothing has moved under it since. The
	 * fallback menu opens at once.
	 */
	fun showOnRelease() {
		if (toolbar == null) return show()
		val at = anchor()
		state.scope.launch {
			if (coroutineContext[MonotonicFrameClock] != null) withFrameNanos { } else yield()
			// A scroll in that frame moves the menu; only a moved caret or selection drops it.
			val now = anchor()
			if (now.caret == at.caret && now.selection == at.selection) show()
		}
	}

	/**
	 * The platform's toolbar at [point], in canvas coordinates, for a right-click where the
	 * platform answers one with it ([pointerMenuIsTextToolbar]). False where there is no
	 * platform toolbar, for the caller to open the context menu instead.
	 */
	fun showAtPointer(point: Offset): Boolean {
		if (toolbar == null) return false
		show(point)
		return true
	}

	/** The context menu at the finger, for a long press on the selection where there is no toolbar. */
	fun showMenuAt(finger: Offset) {
		if (toolbar == null) fallback(finger)
	}

	fun hide() {
		pendingFor = null
		if (shownFor == null) return
		shownFor = null
		shownAt = null
		toolbar?.hide()
	}

	/**
	 * Hides the toolbar when what it was shown over moves, or the editor loses focus, and
	 * moves it with the text when only the scroll changed, the editor's own scroll into
	 * view included. Focus is [TextEditorState.hasFocus], the signal the handles and the
	 * selection colour follow: a read-only editor or view is never `isFocused`.
	 */
	suspend fun watch(): Unit = coroutineScope {
		launch {
			snapshotFlow { state.inputSessionRunning }.collect { running ->
				val pending = pendingFor ?: return@collect
				if (!running) return@collect
				// A frame, so the session's view has taken first responder.
				if (coroutineContext[MonotonicFrameClock] != null) withFrameNanos { } else yield()
				if (pendingFor == pending && anchor().sameText(pending) && state.hasFocus) show(pendingAt)
			}
		}
		snapshotFlow { anchor() to state.hasFocus }.collect { (anchor, focused) ->
			pendingFor?.let { pending -> if (!focused || !anchor.sameText(pending)) pendingFor = null }
			val shown = shownFor ?: return@collect
			when {
				!focused || anchor.selection != shown.selection || anchor.caret != shown.caret -> hide()
				// A right-click's menu stays with the text under the pointer.
				anchor.scroll != shown.scroll || anchor.scrollX != shown.scrollX ->
					show(shownAt?.let { it - Offset((anchor.scrollX - shown.scrollX).toFloat(), (anchor.scroll - shown.scroll).toFloat()) })
			}
		}
	}

	private fun anchor() = Anchor(state.selector.selection, state.cursorPosition, state.scrollState.value, state.horizontalScrollState.value)

	/**
	 * The selection's rows, or the caret, in canvas coordinates, kept inside the viewport
	 * so the platform places its toolbar by the visible rows rather than off screen. With
	 * [coverHandles] it reaches over the touch handles above and below them too, as
	 * Android's `TextView` adds its handles' height, so a toolbar placed beside the rows
	 * does not cover them.
	 */
	private fun contentRect(coverHandles: Boolean = true): Rect {
		val selection = state.selector.selection
		// A bare caret anchors on the row it is drawn on.
		val caret = state.getPositionForOffset(state.cursorPosition, state.cursor.affinity)
		val start = selection?.let { state.getPositionForOffset(it.start, handleAffinity(isStart = true)) } ?: caret
		val end = selection?.let { state.getPositionForOffset(it.end, handleAffinity(isStart = false)) } ?: caret
		val sameRow = start.position.y == end.position.y
		val left = if (sameRow) minOf(start.position.x, end.position.x) else 0f
		val right = if (sameRow) maxOf(start.position.x, end.position.x) else state.viewportSize.width
		var top = start.position.y
		var bottom = end.position.y + end.height
		val density = state.density?.takeIf { coverHandles }
		if (density != null) {
			val look = handles()
			for (handle in state.visibleHandles()) {
				val drawn = look.drawnBounds(density, handle) ?: continue
				top = minOf(top, drawn.top)
				bottom = maxOf(bottom, drawn.bottom)
			}
		}
		val width = state.viewportSize.width
		val height = state.viewportSize.height
		return Rect(
			left.coerceIn(0f, width),
			top.coerceIn(0f, height),
			right.coerceIn(0f, width),
			bottom.coerceIn(0f, height),
		)
	}

	/** [rect] in the root's coordinates, where the platform positions its toolbar. */
	private fun rootRect(rect: Rect): Rect {
		val canvas = state.canvasLayoutCoordinates?.takeIf { it.isAttached } ?: return rect
		return Rect(canvas.localToRoot(rect.topLeft), rect.size)
	}
}
