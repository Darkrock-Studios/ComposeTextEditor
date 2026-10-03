package utils

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathSegment
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min

/** What a recorded primitive was. */
enum class ShapeKind { Rect, Line, Circle, Path, Point }

/**
 * A rectangle, line, circle, path, or point [recordDrawing] saw, as its bounds, colour,
 * and the paint's stroke width (which a line, path, or point is drawn with).
 */
data class DrawnShape(
	val kind: ShapeKind,
	val bounds: Rect,
	val color: Color,
	val strokeWidth: Float = 0f,
	/** For a path, the bounds of each of its contours, in order. */
	val contours: List<Rect> = emptyList(),
)

/**
 * Runs [block] on a real canvas of [size] that also records each `drawRect`,
 * `drawLine`, `drawCircle`, `drawPath`, and point of `drawPoints`, so a test can assert
 * drawing geometry without reading pixels. A line or path is recorded as its bounding
 * box. Other primitives are drawn, not recorded.
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
		shapes += DrawnShape(ShapeKind.Rect, Rect(left, top, right, bottom), paint.color)
		inner.drawRect(left, top, right, bottom, paint)
	}

	override fun drawRect(rect: Rect, paint: Paint) = drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)

	override fun drawLine(p1: Offset, p2: Offset, paint: Paint) {
		shapes += DrawnShape(
			ShapeKind.Line,
			Rect(min(p1.x, p2.x), min(p1.y, p2.y), max(p1.x, p2.x), max(p1.y, p2.y)),
			paint.color,
			paint.strokeWidth,
		)
		inner.drawLine(p1, p2, paint)
	}

	override fun drawCircle(center: Offset, radius: Float, paint: Paint) {
		shapes += DrawnShape(ShapeKind.Circle, Rect(center, radius), paint.color)
		inner.drawCircle(center, radius, paint)
	}

	override fun drawPath(path: Path, paint: Paint) {
		shapes += DrawnShape(ShapeKind.Path, path.getBounds(), paint.color, paint.strokeWidth, path.contourBounds())
		inner.drawPath(path, paint)
	}

	override fun drawPoints(pointMode: PointMode, points: List<Offset>, paint: Paint) {
		points.forEach { shapes += DrawnShape(ShapeKind.Point, Rect(it, it), paint.color, paint.strokeWidth) }
		inner.drawPoints(pointMode, points, paint)
	}
}

/** The bounds of each contour of this path, from its points (control points included). */
private fun Path.contourBounds(): List<Rect> {
	val contours = mutableListOf<MutableList<Offset>>()
	for (segment in this) {
		if (segment.type == PathSegment.Type.Move) contours += mutableListOf<Offset>()
		val points = segment.points
		for (i in 0 until points.size / 2) contours.lastOrNull()?.add(Offset(points[2 * i], points[2 * i + 1]))
	}
	return contours.filter { it.isNotEmpty() }.map { points ->
		Rect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
	}
}
