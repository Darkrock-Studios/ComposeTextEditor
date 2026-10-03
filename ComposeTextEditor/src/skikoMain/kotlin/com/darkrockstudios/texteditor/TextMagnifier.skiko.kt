package com.darkrockstudios.texteditor

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Compose has no magnifier outside Android, so the editor draws its own on iOS, the web
 * and desktop (where a touch screen hides the text a finger drags over too).
 */
internal actual fun Modifier.textMagnifier(state: TextEditorState, style: TextEditorStyle): Modifier =
	drawnTextMagnifier(style.loupeBackdrop()) { state.selector.magnifierCenter }

/**
 * A loupe, as iOS text views show one while the caret or a handle is dragged: a capsule
 * a little above [center] showing the content around it enlarged on [backdrop], kept
 * inside the modified element's bounds, and below [center] where there is no room above.
 * [center] is in the element's coordinates and is read while drawing, so the loupe
 * follows it; with no center nothing is recorded or drawn.
 */
internal fun Modifier.drawnTextMagnifier(backdrop: Color, center: () -> Offset?): Modifier =
	this then DrawnMagnifierElement(backdrop, center)

private data class DrawnMagnifierElement(
	val backdrop: Color,
	val center: () -> Offset?,
) : ModifierNodeElement<DrawnMagnifierNode>() {
	override fun create() = DrawnMagnifierNode(backdrop, center)

	override fun update(node: DrawnMagnifierNode) {
		node.backdrop = backdrop
		node.center = center
		node.invalidateDraw()
	}
}

private class DrawnMagnifierNode(var backdrop: Color, var center: () -> Offset?) : Modifier.Node(), DrawModifierNode {
	private var layer: GraphicsLayer? = null

	override fun onDetach() = releaseLayer()

	private fun releaseLayer() {
		layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
		layer = null
	}

	override fun ContentDrawScope.draw() {
		val at = center()?.takeIf { it.isSpecified }
		if (at == null) {
			// The drag is over: let go of the canvas recorded for it.
			releaseLayer()
			drawContent()
			return
		}
		val content = layer ?: requireGraphicsContext().createGraphicsLayer().also { layer = it }
		content.record { this@draw.drawContent() }
		drawLayer(content)

		val width = LOUPE_WIDTH.toPx().coerceAtMost(size.width)
		val height = LOUPE_HEIGHT.toPx().coerceAtMost(size.height)
		val left = (at.x - width / 2).coerceIn(0f, size.width - width)
		val above = at.y - LOUPE_LIFT.toPx() - height / 2
		val top = (if (above >= 0f) above else at.y + LOUPE_LIFT.toPx() - height / 2).coerceIn(0f, size.height - height)
		val bounds = Rect(left, top, left + width, top + height)
		val outline = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(height / 2))) }

		clipPath(outline) {
			drawRect(backdrop)
			translate(bounds.center.x - at.x, bounds.center.y - at.y) {
				scale(LOUPE_ZOOM, pivot = at) { drawLayer(content) }
			}
		}
		drawPath(outline, LOUPE_EDGE, style = Stroke(1.dp.toPx()))
	}
}

private val Offset.isSpecified: Boolean get() = this != Offset.Unspecified

/** Close to iOS's text loupe: a capsule over the dragged row, enlarging it a quarter again. */
private val LOUPE_WIDTH = 120.dp
private val LOUPE_HEIGHT = 44.dp
private val LOUPE_LIFT = 52.dp
private const val LOUPE_ZOOM = 1.25f
private val LOUPE_EDGE = Color(0x40000000)
