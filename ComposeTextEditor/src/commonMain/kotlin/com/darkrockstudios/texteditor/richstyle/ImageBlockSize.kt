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
 * The bounds an image block is drawn within. The image keeps its aspect ratio and is
 * scaled down to fit [maxWidth], [maxHeight] and the viewport's width; it is enlarged
 * to them only with [upscale], and is otherwise drawn no larger than its bitmap.
 *
 * The editor's bounds are
 * [TextEditorStyle.imageSize][com.darkrockstudios.texteditor.TextEditorStyle.imageSize];
 * an [ImageBlockSpanStyle.size] replaces them for one image.
 */
data class ImageBlockSize(
	val maxWidth: ImageLength = ImageLength.Fraction(1f),
	val maxHeight: ImageLength? = null,
	val upscale: Boolean = false,
) {
	/**
	 * The size an image of [imageWidth] by [imageHeight] pixels is drawn at in a viewport
	 * of [viewport] pixels.
	 */
	fun fit(imageWidth: Float, imageHeight: Float, viewport: Size, density: Density): Size {
		require(imageWidth > 0f && imageHeight > 0f) { "image size must be positive: $imageWidth x $imageHeight" }
		val widthBound = minOf(viewport.width, maxWidth.toPx(viewport.width, density))
		val heightBound = maxHeight?.toPx(viewport.height, density) ?: Float.POSITIVE_INFINITY
		val scale = minOf(widthBound / imageWidth, heightBound / imageHeight)
			.let { if (upscale) it else it.coerceAtMost(1f) }
		return Size(imageWidth * scale, imageHeight * scale)
	}

	companion object {
		/** The viewport's width, with no height bound. */
		val DEFAULT = ImageBlockSize()
	}
}
