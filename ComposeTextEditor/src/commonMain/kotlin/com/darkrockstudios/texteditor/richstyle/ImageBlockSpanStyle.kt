package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.effectiveHeight
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * A [BlockSpanStyle] that renders an image centred on its line. Pair with
 * [IMAGE_PLACEHOLDER] (a single space) on a line of its own: the editor sizes the
 * line to the image's height within its bounds, [size] or the editor's
 * [TextEditorState.imageSize], and the style draws the bitmap into that area.
 *
 * The bitmap is fetched lazily from [provider]; while loading or on failure the
 * style draws a placeholder rectangle of [placeholderHeightDp].
 */
data class ImageBlockSpanStyle(
	val source: String,
	val alt: String = "",
	val provider: ImageProvider,
	val placeholderHeightDp: Float = 200f,
	/** This image's bounds, in place of the editor's [TextEditorState.imageSize]. */
	val size: ImageBlockSize? = null,
) : BlockSpanStyle {

	/** The height with [size] or [ImageBlockSize.DEFAULT], the viewport's height unknown. */
	override fun blockHeight(density: Density, viewportWidth: Float): Float =
		blockHeight(density, Size(viewportWidth, Float.POSITIVE_INFINITY), ImageBlockSize.DEFAULT)

	/** The height in a viewport of [viewport] pixels, the image bounded by [size] or, without one, by [imageSize]. */
	override fun blockHeight(density: Density, viewport: Size, imageSize: ImageBlockSize): Float =
		drawnSize(density, viewport, imageSize)?.height ?: with(density) { placeholderHeightDp.dp.toPx() }

	/** The size the loaded bitmap is drawn at, or null while there is none to draw. */
	private fun drawnSize(density: Density, viewport: Size, imageSize: ImageBlockSize): Size? {
		val bitmap = (provider.resolve(source).value as? ImageBlockResource.Loaded)?.bitmap ?: return null
		if (bitmap.width <= 0 || bitmap.height <= 0) return null
		return (size ?: imageSize).fit(bitmap.width.toFloat(), bitmap.height.toFloat(), viewport, density)
	}

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
		val height = lineWrap.blockHeight ?: lineWrap.effectiveHeight
		// Unwrapped, the scope is as wide as the content, and the block is sized by the viewport.
		val width = if (state.softWrap) size.width else state.viewportSize.width
		when (val resource = provider.resolve(source).value) {
			is ImageBlockResource.Loaded -> {
				val bitmap = resource.bitmap
				val drawn = drawnSize(this, state.viewportSize, state.imageSize) ?: return
				val left = (width - drawn.width) / 2f
				drawImage(
					image = bitmap,
					srcOffset = IntOffset.Zero,
					srcSize = IntSize(bitmap.width, bitmap.height),
					dstOffset = IntOffset(left.toInt(), 0),
					dstSize = IntSize(drawn.width.toInt(), drawn.height.toInt()),
				)
			}

			is ImageBlockResource.Loading -> drawPlaceholder(width, height, "loading: $alt")
			is ImageBlockResource.Failed -> drawPlaceholder(width, height, "image: ${resource.reason}")
		}
	}

	private fun DrawScope.drawPlaceholder(width: Float, height: Float, label: String) {
		drawRect(
			color = Color.Gray.copy(alpha = 0.15f),
			topLeft = Offset.Zero,
			size = Size(width, height),
		)
		drawRect(
			color = Color.Gray.copy(alpha = 0.5f),
			topLeft = Offset.Zero,
			size = Size(width, height),
			style = Stroke(width = 1f),
		)
	}
}

/** Single space character that anchors an [ImageBlockSpanStyle] to a line. */
const val IMAGE_PLACEHOLDER: String = " "
