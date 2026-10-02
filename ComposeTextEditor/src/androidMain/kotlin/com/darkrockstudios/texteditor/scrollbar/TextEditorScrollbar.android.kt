package com.darkrockstudios.texteditor.scrollbar

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.state.TextEditorScrollState

@Composable
actual fun TextEditorScrollbar(
	modifier: Modifier,
	scrollState: TextEditorScrollState,
	content: @Composable (modifier: Modifier) -> Unit
) {
	Box(modifier = modifier) {
		content(Modifier)

		val onSurface = MaterialTheme.colorScheme.onSurface
		ScrollIndicator(
			scrollState = scrollState,
			thumbColor = onSurface.copy(alpha = 0.38f),
			trackColor = onSurface.copy(alpha = 0.12f),
			width = 4.dp,
		)
	}
}
