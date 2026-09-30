package com.darkrockstudios.texteditor.contextmenu

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset

/**
 * Represents a custom menu item that can be added to the context menu.
 */
data class ContextMenuItem(
	val label: String,
	val enabled: Boolean = true,
	val onClick: () -> Unit
)

/**
 * State holder for the text editor context menu.
 * Tracks whether the menu is visible, its position, and any extra menu items.
 */
class TextEditorContextMenuState {
	/**
	 * The position where the menu should be displayed, or null if hidden.
	 */
	val menuPosition: MutableState<Offset?> = mutableStateOf(null)

	/**
	 * Extra menu items to display before the standard items (Cut, Copy, Paste, Select All).
	 * These are rendered first, followed by a divider if non-empty.
	 */
	val extraItems: MutableState<List<ContextMenuItem>> = mutableStateOf(emptyList())

	/**
	 * Items rendered after [extraItems] as a separate, divider-delimited group: host actions
	 * such as "Add to dictionary" that must not read as one more suggestion.
	 */
	val trailingItems: MutableState<List<ContextMenuItem>> = mutableStateOf(emptyList())

	/**
	 * Whether the menu is currently visible.
	 */
	val isVisible: Boolean
		get() = menuPosition.value != null

	/**
	 * Show the context menu at [position], in the coordinates of the editor's outer bounds. For a
	 * point in the text, such as a span click's, use [showMenuAtText].
	 */
	fun showMenu(position: Offset) {
		menuPosition.value = position
	}

	/**
	 * Show the context menu at the specified position with extra items, and optionally a
	 * trailing group of items.
	 */
	fun showMenu(
		position: Offset,
		items: List<ContextMenuItem>,
		trailingItems: List<ContextMenuItem> = emptyList(),
	) {
		extraItems.value = items
		this.trailingItems.value = trailingItems
		menuPosition.value = position
	}

	/**
	 * Convert a point in the text's coordinates to [menuPosition]'s, one for each editor
	 * composed with this menu. The last converts.
	 */
	internal val textConversions = mutableListOf<(Offset) -> Offset>()

	/**
	 * Show the context menu at [offset] in the text's coordinates, those of
	 * [com.darkrockstudios.texteditor.RichSpanClick.offset] and the state's layout
	 * queries, with [items] and [trailingItems] replacing any shown before. The content
	 * padding shifts the text from where the menu is placed; this converts through the
	 * layout of the editor showing the menu.
	 */
	fun showMenuAtText(
		offset: Offset,
		items: List<ContextMenuItem> = emptyList(),
		trailingItems: List<ContextMenuItem> = emptyList(),
	) {
		showMenu(textConversions.lastOrNull()?.invoke(offset) ?: offset, items, trailingItems)
	}

	/**
	 * Dismiss the context menu and clear extra and trailing items.
	 */
	fun dismissMenu() {
		menuPosition.value = null
		extraItems.value = emptyList()
		trailingItems.value = emptyList()
	}
}
