package com.darkrockstudios.texteditor.contextmenu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Opens a composable's context menu at a point in the text canvas's coordinates, the ones
 * [TextEditorState.canvasLayoutCoordinates], the pointer handlers and the touch toolbar
 * use. The menu is placed in its provider's coordinates, which the content padding and any
 * host modifier shift, so the point is converted through the layout.
 *
 * [modifier] goes first on the provider's content. That content is placed at the
 * provider's origin, so its outer bounds are the provider's frame.
 */
internal class ContextMenuPlacement(
	private val state: TextEditorState,
	private val menuState: TextEditorContextMenuState,
) {
	private var frame: LayoutCoordinates? = null

	val modifier: Modifier = Modifier.onGloballyPositioned { frame = it }

	fun showAtContent(offset: Offset) {
		menuState.showMenu(toMenu(offset))
	}

	/**
	 * Lets [TextEditorContextMenuState.showMenuAtText] convert through this placement until
	 * the returned function is called.
	 */
	fun lendTextConversion(): () -> Unit {
		val conversion: (Offset) -> Offset = ::toMenu
		menuState.textConversions += conversion
		return { menuState.textConversions -= conversion }
	}

	private fun toMenu(offset: Offset): Offset {
		val canvas = state.canvasLayoutCoordinates?.takeIf { it.isAttached }
		val frame = frame?.takeIf { it.isAttached }
		return if (canvas != null && frame != null) frame.localPositionOf(canvas, offset) else offset
	}

	/** Below the caret, kept inside the viewport: where the keyboard opens the menu, as native editors do. */
	fun showAtCaret() {
		val caret = state.getPositionForOffset(state.cursorPosition, state.cursor.affinity)
		showAtContent(
			Offset(
				caret.position.x.coerceIn(0f, state.viewportSize.width),
				(caret.position.y + caret.height).coerceIn(0f, state.viewportSize.height),
			)
		)
	}
}

/**
 * Lets [TextEditorState]'s `editor.showContextMenu` action open [placement]'s menu while
 * this is composed. When several composables show one state, the latest to compose opens.
 * Also gives [TextEditorContextMenuState.showMenuAtText] its conversion.
 */
@Composable
internal fun ContextMenuOpener(state: TextEditorState, placement: ContextMenuPlacement) {
	DisposableEffect(state, placement) {
		val opener: () -> Unit = placement::showAtCaret
		state.contextMenuOpeners += opener
		val returnConversion = placement.lendTextConversion()
		onDispose {
			state.contextMenuOpeners -= opener
			returnConversion()
		}
	}
}
