package com.darkrockstudios.texteditor.contextmenu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import com.darkrockstudios.texteditor.input.EditorCommand
import kotlin.math.roundToInt

/**
 * Context menu dropdown for Cut, Copy, Paste, and Select All operations.
 * Supports extra items that appear before the standard items.
 *
 * @param position The position where the menu should appear
 * @param actions The context menu actions handler
 * @param strings Localizable strings for menu items
 * @param enabled Whether editing operations (cut, paste) are enabled
 * @param extraItems Extra menu items to display before standard items
 * @param onDismiss Callback when the menu should be dismissed
 */
@Composable
internal fun TextEditorContextMenu(
	position: Offset,
	actions: ContextMenuActions,
	strings: ContextMenuStrings,
	enabled: Boolean,
	extraItems: List<ContextMenuItem> = emptyList(),
	onDismiss: () -> Unit,
) {
	Box(modifier = Modifier.offset {
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
			extraItems.forEach { item ->
				DropdownMenuItem(
					text = { Text(item.label) },
					enabled = item.enabled,
					onClick = {
						item.onClick()
						onDismiss()
					},
				)
			}

			// Divider between extra items and standard items
			if (extraItems.isNotEmpty()) {
				HorizontalDivider()
			}

			// Cut - requires selection and enabled
			if (actions.canCut() && enabled) {
				DropdownMenuItem(
					text = { Text(strings.cut) },
					onClick = {
						actions.cut()
						onDismiss()
					},
				)
			}

			// Copy - requires selection
			if (actions.canCopy()) {
				DropdownMenuItem(
					text = { Text(strings.copy) },
					onClick = {
						actions.copy()
						onDismiss()
					},
				)
			}

			// Paste - requires enabled
			if (enabled && actions.canPaste()) {
				DropdownMenuItem(
					text = { Text(strings.paste) },
					onClick = {
						actions.paste()
						onDismiss()
					},
				)
			}

			if (actions.canPerform(EditorCommand.Action.SelectAll)) {
				DropdownMenuItem(
					text = { Text(strings.selectAll) },
					onClick = {
						actions.selectAll()
						onDismiss()
					},
				)
			}
		}
	}
}
