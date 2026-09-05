package com.darkrockstudios.texteditor.contextmenu

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun TextEditorContextMenuProvider(
	modifier: Modifier,
	menuState: TextEditorContextMenuState,
	actions: ContextMenuActions,
	strings: ContextMenuStrings,
	enabled: Boolean,
	content: @Composable () -> Unit
) {
	Box(modifier = modifier) {
		content()

		menuState.menuPosition.value?.let { position ->
			TextEditorContextMenu(
				position = position,
				actions = actions,
				strings = strings,
				enabled = enabled,
				extraItems = menuState.extraItems.value,
				trailingItems = menuState.trailingItems.value,
				onDismiss = menuState::dismissMenu
			)
		}
	}
}
