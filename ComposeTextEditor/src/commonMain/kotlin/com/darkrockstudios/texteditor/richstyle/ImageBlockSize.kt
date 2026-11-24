package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A length an image block is bounded by. */
sealed interface ImageLength {
	/** A length in density-independent pixels. */
	data class Absolute(val length: Dp) : ImageLength {
		init {
			require(length > 0.dp) { "length must be positive: $length" }
		}
	}

	/**
	 * A share of the editor's viewport: of its width for a width, of its height for a height.
	 * A view that grows with its content has no bounded height, so there a height fraction
	 * leaves the image unbounded.
	 */
	data class Fraction(val value: Float) : ImageLength {
		init {
			require(value > 0f) { "value must be positive: $value" }
		}
	}

	/** This length in pixels, against a viewport side of [viewportLength] pixels. */
	fun toPx(viewportLength: Float, density: Density): Float = when (this) {
		is Absolute -> with(density) { length.toPx() }
		is Fraction -> viewportLength * value
	}
}

/**
 * How an image block is sized: [Bounded] by limits it is fitted within, its height following
 * the image, or [Framed] in a fixed box. Either way the image keeps its aspect ratio and is
 * enlarged only with [upscale], otherwise drawn no larger than its bitmap.
 *
 * The editor's sizing is
 * [TextEditorStyle.imageSize][com.darkrockstudios.texteditor.TextEditorStyle.imageSize];
 * an [ImageBlockSpanStyle.size] replaces it for one image.
 */
sealed interface ImageBlockSize {
	val upscale: Boolean

	/** The image is fitted within [maxWidth], [maxHeight] and the viewport's width. */
	data class Bounded(
		val maxWidth: ImageLength = ImageLength.Fraction(1f),
		val maxHeight: ImageLength? = null,
		override val upscale: Boolean = false,
	) : ImageBlockSize

	/**
	 * A fixed box [height] tall and [width] wide, no wider than the viewport, which the block
	 * takes whether its image is loading, loaded or failed. The image is fitted inside it and
	 * centred. A height [ImageLength.Fraction] in a view with no bounded height leaves the
	 * block unframed, its image fitted to [width] alone.
	 */
	data class Framed(
		val height: ImageLength,
		val width: ImageLength = ImageLength.Fraction(1f),
		override val upscale: Boolean = false,
	) : ImageBlockSize

	/** The box the block takes in a viewport of [viewport] pixels, or null when it has none. */
	fun frameSize(viewport: Size, density: Density): Size? {
		if (this !is Framed) return null
		val height = height.toPx(viewport.height, density)
		if (!height.isFinite()) return null
		return Size(minOf(viewport.width, width.toPx(viewport.width, density)), height)
	}

	/**
	 * The size an image of [imageWidth] by [imageHeight] pixels is drawn at in a viewport
	 * of [viewport] pixels.
	 */
	fun fit(imageWidth: Float, imageHeight: Float, viewport: Size, density: Density): Size {
		require(imageWidth > 0f && imageHeight > 0f) { "image size must be positive: $imageWidth x $imageHeight" }
		val widthBound: Float
		val heightBound: Float?
		when (this) {
			is Bounded -> {
				widthBound = minOf(viewport.width, maxWidth.toPx(viewport.width, density))
				heightBound = maxHeight?.toPx(viewport.height, density)
			}

			is Framed -> {
				widthBound = minOf(viewport.width, width.toPx(viewport.width, density))
				heightBound = frameSize(viewport, density)?.height
			}
		}
		val scale = minOf(widthBound / imageWidth, (heightBound ?: Float.POSITIVE_INFINITY) / imageHeight)
			.let { if (upscale) it else it.coerceAtMost(1f) }
		return Size(imageWidth * scale, imageHeight * scale)
	}

	companion object {
		/** The viewport's width, with no height bound. */
		val DEFAULT: ImageBlockSize = Bounded()
	}
}

/** [ImageBlockSize.Bounded] by these limits. */
fun ImageBlockSize(
	maxWidth: ImageLength = ImageLength.Fraction(1f),
	maxHeight: ImageLength? = null,
	upscale: Boolean = false,
): ImageBlockSize.Bounded = ImageBlockSize.Bounded(maxWidth, maxHeight, upscale)
