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
internal class TouchToolbar(
	private val state: TextEditorState,
	private val toolbar: TextToolbar?,
	private val actions: ContextMenuActions,
	private val fallback: (Offset) -> Unit,
) {
	private data class Anchor(val selection: TextEditorRange?, val caret: CharLineOffset, val scroll: Int)

	private var shownFor: Anchor? = null

	/** Whether there is a platform toolbar, which shows on release, rather than the modal menu. */
	val isNative: Boolean get() = toolbar != null

	/** Whether the platform toolbar is up. */
	val isShown: Boolean get() = shownFor != null

	/** Shows the items the editor can act on now, over the selection or the caret. */
	fun show() {
		if (toolbar == null) {
			if (state.selector.selection == null) {
				val caret = contentRect()
				fallback(Offset(caret.left, caret.bottom))
			}
			return
		}
		toolbar.showMenu(
			rect = rootRect(contentRect()),
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
	}

	/** The context menu at the finger, for a long press on the selection where there is no toolbar. */
	fun showMenuAt(finger: Offset) {
		if (toolbar == null) fallback(finger)
	}

	fun hide() {
		if (shownFor == null) return
		shownFor = null
		toolbar?.hide()
	}

	/**
	 * Hides the toolbar when what it was shown over moves, or the editor loses focus, and
	 * moves it with the text when only the scroll changed, the editor's own scroll into
	 * view included.
	 */
	suspend fun watch() {
		snapshotFlow { anchor() to state.isFocused }.collect { (anchor, focused) ->
			val shown = shownFor ?: return@collect
			when {
				!focused || anchor.selection != shown.selection || anchor.caret != shown.caret -> hide()
				anchor.scroll != shown.scroll -> show()
			}
		}
	}

	private fun anchor() = Anchor(state.selector.selection, state.cursorPosition, state.scrollState.value)

	/**
	 * The selection's rows, or the caret, in canvas coordinates, kept inside the viewport
	 * so the platform places its toolbar by the visible rows rather than off screen.
	 */
	private fun contentRect(): Rect {
		val selection = state.selector.selection
		val start = state.getPositionForOffset(selection?.start ?: state.cursorPosition)
		val end = state.getPositionForOffset(selection?.end ?: state.cursorPosition)
		val sameRow = start.position.y == end.position.y
		val left = if (sameRow) minOf(start.position.x, end.position.x) else 0f
		val right = if (sameRow) maxOf(start.position.x, end.position.x) else state.viewportSize.width
		val width = state.viewportSize.width
		val height = state.viewportSize.height
		return Rect(
			left.coerceIn(0f, width),
			start.position.y.coerceIn(0f, height),
			right.coerceIn(0f, width),
			(end.position.y + end.height).coerceIn(0f, height),
		)
	}

	/** [rect] in the root's coordinates, where the platform positions its toolbar. */
	private fun rootRect(rect: Rect): Rect {
		val canvas = state.canvasLayoutCoordinates?.takeIf { it.isAttached } ?: return rect
		return Rect(canvas.localToRoot(rect.topLeft), rect.size)
	}
}
