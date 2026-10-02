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
 * A [BlockSpanStyle] that renders a full-width image. Pair with [IMAGE_PLACEHOLDER]
 * (a single space) on a line of its own — the editor sizes the line to the image's
 * intrinsic height (fitted to viewport width, preserving aspect ratio) and the
 * style draws the bitmap into that area.
 *
 * The bitmap is fetched lazily from [provider]; while loading or on failure the
 * style draws a placeholder rectangle of [placeholderHeightDp].
 */
data class ImageBlockSpanStyle(
	val source: String,
	val alt: String = "",
	val provider: ImageProvider,
	val placeholderHeightDp: Float = 200f,
) : BlockSpanStyle {

	override fun blockHeight(density: Density, viewportWidth: Float): Float {
		val resource = provider.resolve(source).value
		return when (resource) {
			is ImageBlockResource.Loaded -> {
				val bw = resource.bitmap.width.toFloat()
				val bh = resource.bitmap.height.toFloat()
				if (bw <= 0f || bh <= 0f) {
					with(density) { placeholderHeightDp.dp.toPx() }
				} else {
					val scale = (viewportWidth / bw).coerceAtMost(1f)
					bh * scale
				}
			}

			is ImageBlockResource.Loading,
			is ImageBlockResource.Failed -> with(density) { placeholderHeightDp.dp.toPx() }
		}
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
				val bw = bitmap.width.toFloat()
				val bh = bitmap.height.toFloat()
				val scale = if (bw > 0f) (width / bw).coerceAtMost(1f) else 1f
				val drawWidth = bw * scale
				val drawHeight = bh * scale
				val left = (width - drawWidth) / 2f
				drawImage(
					image = bitmap,
					srcOffset = IntOffset.Zero,
					srcSize = IntSize(bitmap.width, bitmap.height),
					dstOffset = IntOffset(left.toInt(), 0),
					dstSize = IntSize(drawWidth.toInt(), drawHeight.toInt()),
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
