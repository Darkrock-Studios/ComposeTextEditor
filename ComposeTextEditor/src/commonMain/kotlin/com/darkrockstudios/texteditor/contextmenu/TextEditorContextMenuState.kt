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
	 * Show the context menu at the specified position.
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
	 * Dismiss the context menu and clear extra and trailing items.
	 */
	fun dismissMenu() {
		menuPosition.value = null
		extraItems.value = emptyList()
		trailingItems.value = emptyList()
	}
}
