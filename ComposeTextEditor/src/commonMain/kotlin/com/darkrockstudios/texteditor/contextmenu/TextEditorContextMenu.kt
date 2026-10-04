package com.darkrockstudios.texteditor.contextmenu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import kotlin.math.roundToInt

/**
 * The context menu: any [extraItems] and [trailingItems] first, then Undo and Redo, the
 * clipboard items and Delete, and Select All, each group behind a divider. A standard item shows
 * when its action is registered and allowed here (a read-only editor has no editing
 * items), and is disabled while it has nothing to act on, as in native menus.
 *
 * @param position Where the menu opens, in the provider's coordinates
 * @param actions The context menu actions handler
 * @param strings Localizable strings for menu items
 * @param enabled Whether editing operations are enabled; [actions] already enforces it
 * @param extraItems Extra menu items to display before standard items
 * @param trailingItems Items shown after [extraItems] in their own divider-delimited group
 * @param onDismiss Callback when the menu should be dismissed
 */
@Composable
internal fun TextEditorContextMenu(
	position: Offset,
	actions: ContextMenuActions,
	strings: ContextMenuStrings,
	@Suppress("UNUSED_PARAMETER") enabled: Boolean,
	extraItems: List<ContextMenuItem> = emptyList(),
	trailingItems: List<ContextMenuItem> = emptyList(),
	onDismiss: () -> Unit,
) {
	val standardGroups = listOf(
		listOf(Action.Undo to strings.undo, Action.Redo to strings.redo),
		listOf(
			Action.Cut to strings.cut,
			Action.Copy to strings.copy,
			Action.Paste to strings.paste,
			Action.PasteAsPlainText to strings.pasteAsPlainText,
			Action.DeleteSelection to strings.delete,
		),
		listOf(Action.SelectAll to strings.selectAll),
	).map { group -> group.filter { (action, _) -> actions.isAvailable(action) } }
		.filter { it.isNotEmpty() }

	// A registry with every standard item dropped would otherwise pop an empty dropdown
	// the user has to click away.
	if (extraItems.isEmpty() && trailingItems.isEmpty() && standardGroups.isEmpty()) {
		onDismiss()
		return
	}

	// The position is physical, measured from the left even in a right-to-left layout.
	Box(modifier = Modifier.absoluteOffset {
		IntOffset(
			position.x.roundToInt(),
			position.y.roundToInt()
		)
	}) {
		DropdownMenu(
			expanded = true,
			onDismissRequest = onDismiss,
		) {
			// Extra items first (e.g., spell check suggestions)
			CustomItems(extraItems, onDismiss)

			if (extraItems.isNotEmpty() && trailingItems.isNotEmpty()) {
				HorizontalDivider()
			}
			CustomItems(trailingItems, onDismiss)

			standardGroups.forEachIndexed { index, group ->
				if (index > 0 || extraItems.isNotEmpty() || trailingItems.isNotEmpty()) {
					HorizontalDivider()
				}
				group.forEach { (action, label) ->
					DropdownMenuItem(
						text = { Text(label) },
						enabled = actions.canPerform(action),
						onClick = {
							actions.perform(action)
							onDismiss()
						},
					)
				}
			}
		}
	}
}

@Composable
private fun CustomItems(items: List<ContextMenuItem>, onDismiss: () -> Unit) {
	items.forEach { item ->
		DropdownMenuItem(
			text = { Text(item.label) },
			enabled = item.enabled,
			onClick = {
				item.onClick()
				onDismiss()
			},
		)
	}
}
