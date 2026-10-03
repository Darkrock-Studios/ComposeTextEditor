package com.darkrockstudios.texteditor.scrollbar

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.darkrockstudios.texteditor.state.TextEditorScrollState
import kotlin.math.roundToInt

/**
 * Compose's scrollbar reading and driving a [TextEditorScrollState]. Offsets run from the
 * top of the scroll range, which the top content padding puts below zero.
 */
internal class TextEditorScrollbarAdapter(
	private val scrollState: TextEditorScrollState,
) : ScrollbarAdapter {
	override val scrollOffset: Double
		get() = (scrollState.value - scrollState.minValue).toDouble()

	override val viewportSize: Double
		get() = scrollState.viewportHeight.toDouble()

	override val contentSize: Double
		get() = (scrollState.maxValue - scrollState.minValue) + viewportSize

	override suspend fun scrollTo(scrollOffset: Double) {
		val target = scrollOffset.roundToInt() + scrollState.minValue
		// A user's drag outranks an animated scroll to the caret.
		scrollState.scroll(MutatePriority.UserInput) {
			scrollBy((scrollState.value - target).toFloat())
		}
	}
}

/**
 * The editor with Compose's desktop scrollbar in a gutter along its end edge: dragged by
 * its thumb, paging while the track is pressed, hidden when everything fits, and
 * scrolling the editor under a mouse wheel too. A host's [LocalScrollbarStyle] styles
 * it; without one it takes its colours from the Material theme, so it shows on a dark
 * background as well.
 */
@Composable
internal fun EditorWithVerticalScrollbar(
	modifier: Modifier,
	scrollState: TextEditorScrollState,
	content: @Composable (modifier: Modifier) -> Unit,
) {
	val adapter = remember(scrollState) { TextEditorScrollbarAdapter(scrollState) }
	Row(modifier = modifier) {
		content(Modifier.weight(1f))
		VerticalScrollbar(
			adapter = adapter,
			modifier = Modifier
				.fillMaxHeight()
				.scrollable(scrollState, Orientation.Vertical),
			style = themedScrollbarStyle(),
		)
	}
}

@Composable
private fun themedScrollbarStyle(): ScrollbarStyle {
	val style = LocalScrollbarStyle.current
	if (style != defaultScrollbarStyle()) return style
	val onSurface = MaterialTheme.colorScheme.onSurface
	return remember(style, onSurface) {
		style.copy(unhoverColor = onSurface.copy(alpha = 0.2f), hoverColor = onSurface.copy(alpha = 0.5f))
	}
}
