package utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min

/** A rectangle or line [block] drew, as its bounds and colour. */
data class DrawnShape(val bounds: Rect, val color: Color)

/**
 * Runs [block] on a real canvas of [size] that also records each `drawRect` and
 * `drawLine`, so a test can assert drawing geometry without reading pixels. A line is
 * recorded as its end points' bounding box. Other primitives are drawn, not recorded.
 */
fun recordDrawing(
	size: Size,
	density: Density = Density(1f),
	block: DrawScope.() -> Unit,
): List<DrawnShape> {
	val bitmap = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
	val recorder = RecordingCanvas(Canvas(bitmap))
	CanvasDrawScope().draw(density, LayoutDirection.Ltr, recorder, size, block)
	return recorder.shapes
}

private class RecordingCanvas(private val inner: Canvas) : Canvas by inner {
	val shapes = mutableListOf<DrawnShape>()

	override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
		shapes += DrawnShape(Rect(left, top, right, bottom), paint.color)
		inner.drawRect(left, top, right, bottom, paint)
	}

	override fun drawRect(rect: Rect, paint: Paint) = drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)

	override fun drawLine(p1: Offset, p2: Offset, paint: Paint) {
		shapes += DrawnShape(
			Rect(min(p1.x, p2.x), min(p1.y, p2.y), max(p1.x, p2.x), max(p1.y, p2.y)),
			paint.color,
		)
		inner.drawLine(p1, p2, paint)
	}
}
