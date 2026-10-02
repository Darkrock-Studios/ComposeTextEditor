package com.darkrockstudios.texteditor.scrollbar

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.state.TextEditorScrollState

/**
 * A thin grey thumb with no track, display-only as on native iOS scroll views, where the
 * content is what the user scrolls.
 */
@Composable
actual fun TextEditorScrollbar(
	modifier: Modifier,
	scrollState: TextEditorScrollState,
	content: @Composable (modifier: Modifier) -> Unit
) {
	Box(modifier = modifier) {
		content(Modifier)

		ScrollIndicator(
			scrollState = scrollState,
			thumbColor = Color.Gray.copy(alpha = 0.5f),
			width = 3.dp,
		)
	}
}

/** The same thumb along the bottom edge for a sideways scroll, as a native scroll view shows one. */
@Composable
internal actual fun EditorHorizontalScrollbar(scrollState: TextEditorScrollState, modifier: Modifier) =
	HorizontalScrollIndicator(
		scrollState = scrollState,
		modifier = modifier,
		thumbColor = Color.Gray.copy(alpha = 0.5f),
		thickness = 3.dp,
	)
