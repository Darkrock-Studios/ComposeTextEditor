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

/** The same indicator along the bottom edge for a sideways scroll. */
@Composable
internal actual fun EditorHorizontalScrollbar(scrollState: TextEditorScrollState, modifier: Modifier) {
	val onSurface = MaterialTheme.colorScheme.onSurface
	HorizontalScrollIndicator(
		scrollState = scrollState,
		modifier = modifier,
		thumbColor = onSurface.copy(alpha = 0.38f),
		thickness = 4.dp,
		trackColor = onSurface.copy(alpha = 0.12f),
	)
}
