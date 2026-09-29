package com.darkrockstudios.texteditor.scrollbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.state.TextEditorScrollState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

/** A scrollbar thumb: its distance from the track's top and its length, in pixels. */
internal data class ScrollThumb(val offset: Float, val length: Float)

/**
 * The thumb for [scrollState] on a track [trackLength] long beside a viewport
 * [viewportLength] long: its share of the track is the share of the content in view,
 * never under [minLength]. Null when there is nothing to scroll.
 */
internal fun scrollThumb(
	scrollState: TextEditorScrollState,
	trackLength: Float,
	viewportLength: Float,
	minLength: Float,
): ScrollThumb? {
	val range = scrollState.maxValue - scrollState.minValue
	if (range <= 0 || trackLength <= 0f || viewportLength <= 0f) return null
	val length = (trackLength * viewportLength / (viewportLength + range))
		.coerceAtLeast(minOf(minLength, trackLength))
	val fraction = (scrollState.value - scrollState.minValue).toFloat() / range
	return ScrollThumb(offset = (trackLength - length) * fraction, length = length)
}

/** How long a mobile scroll indicator stays after scrolling stops, and how long it takes to fade. */
internal const val SCROLL_INDICATOR_FADE_DELAY_MS = 500L
internal const val SCROLL_INDICATOR_FADE_MS = 250

/**
 * Opacity of a mobile scroll indicator: shown while [scrollState] moves or a scroll is in
 * progress, then faded out once it has been still for a moment, as Android and iOS do.
 * Hidden until the first scroll.
 */
@Composable
internal fun rememberScrollIndicatorAlpha(scrollState: TextEditorScrollState): State<Float> {
	val alpha = remember(scrollState) { Animatable(0f) }
	LaunchedEffect(scrollState) {
		snapshotFlow { scrollState.value to scrollState.isScrollInProgress }
			.drop(1)
			.collectLatest { (_, inProgress) ->
				alpha.snapTo(1f)
				if (inProgress) return@collectLatest
				delay(SCROLL_INDICATOR_FADE_DELAY_MS)
				alpha.animateTo(0f, tween(SCROLL_INDICATOR_FADE_MS))
			}
	}
	return alpha.asState()
}

/**
 * The display-only scroll indicator of Android and iOS, along the end edge: a thumb as
 * long as the share of the document in view, over an optional [trackColor], that fades
 * when scrolling stops. It takes no input; on both platforms the content is what scrolls.
 */
@Composable
internal fun BoxScope.ScrollIndicator(
	scrollState: TextEditorScrollState,
	thumbColor: Color,
	width: Dp,
	trackColor: Color? = null,
	minThumbLength: Dp = 24.dp,
) {
	val alpha = rememberScrollIndicatorAlpha(scrollState)
	val inset = 2.dp
	Canvas(
		modifier = Modifier
			.align(Alignment.CenterEnd)
			.fillMaxHeight()
			.padding(inset)
			.width(width)
	) {
		val opacity = alpha.value
		if (opacity <= 0f) return@Canvas
		val viewport = size.height + 2 * inset.toPx()
		val thumb = scrollThumb(scrollState, size.height, viewport, minThumbLength.toPx()) ?: return@Canvas
		val corner = CornerRadius(size.width / 2f)
		if (trackColor != null) {
			drawRoundRect(color = trackColor, cornerRadius = corner, alpha = opacity)
		}
		drawRoundRect(
			color = thumbColor,
			topLeft = Offset(0f, thumb.offset),
			size = Size(size.width, thumb.length),
			cornerRadius = corner,
			alpha = opacity,
		)
	}
}
