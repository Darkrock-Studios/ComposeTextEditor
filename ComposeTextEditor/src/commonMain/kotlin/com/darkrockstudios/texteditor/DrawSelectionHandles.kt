package com.darkrockstudios.texteditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.cursor.CursorMetrics
import com.darkrockstudios.texteditor.state.CaretAffinity
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.math.sqrt

internal fun DrawScope.DrawSelectionHandles(state: TextEditorState, handleColor: Color, look: HandleLook) {
	for (handle in state.visibleHandles()) look.draw(this, handle, handleColor)
}

/** Which handle: a selection's start or end, or the caret's. */
internal enum class HandleRole { Start, End, Caret }

/**
 * A touch handle that is up: its [role], the end it marks at [position], that end's
 * [at] metrics (its x, and its row's top and height), and whether its paragraph runs
 * right to left.
 */
internal class TouchHandle(
	val role: HandleRole,
	val position: CharLineOffset,
	val at: CursorMetrics,
	val rtl: Boolean,
) {
	/**
	 * Whether a selection handle hangs to the left of its end, as Compose's
	 * `isLeftSelectionHandle` decides: the start in left-to-right text, the end in
	 * right-to-left, so each hangs outside the selection.
	 */
	val hangsLeft: Boolean
		get() = when (role) {
			HandleRole.Start -> !rtl
			HandleRole.End -> rtl
			HandleRole.Caret -> false
		}
}

/** The touch handles up now: [touchHandles], while the editor has focus. */
internal fun TextEditorState.visibleHandles(): List<TouchHandle> = if (hasFocus) touchHandles() else emptyList()

/** The touch handles the editor has, focused or not: the caret's, or a touch selection's start and end. */
internal fun TextEditorState.touchHandles(): List<TouchHandle> {
	if (selector.isCaretHandleVisible) return listOf(handleAt(HandleRole.Caret, cursorPosition, cursor.affinity))
	val selection = selector.selection?.takeIf { selector.isTouchSelection } ?: return emptyList()
	return listOf(
		handleAt(HandleRole.Start, selection.start, handleAffinity(isStart = true)),
		handleAt(HandleRole.End, selection.end, handleAffinity(isStart = false)),
	)
}

/**
 * A handle's side follows its paragraph's direction, not the bidi run at its end as
 * Compose's does: the editor places an end by the paragraph's direction too (3.21).
 */
private fun TextEditorState.handleAt(role: HandleRole, position: CharLineOffset, affinity: CaretAffinity): TouchHandle {
	val layout = lineOffsets.rowAt(position)?.textLayoutResult
	val rtl = layout?.getParagraphDirection(position.char.coerceAtMost(layout.layoutInput.text.length)) == ResolvedTextDirection.Rtl
	return TouchHandle(role, position, getPositionForOffset(position, affinity), rtl)
}

/**
 * The row a selection handle stands on at a wrap offset: the end handle on the row the
 * selection ends, as its highlight does, and the start handle on the row it starts.
 */
internal fun handleAffinity(isStart: Boolean): CaretAffinity =
	if (isStart) CaretAffinity.Downstream else CaretAffinity.Upstream

/** The running platform's handles: [SelectionHandleShape.Bar] on iOS, [SelectionHandleShape.Teardrop] elsewhere. */
internal expect val platformSelectionHandleShape: SelectionHandleShape

internal val SelectionHandleShape.look: HandleLook
	get() = when (this) {
		SelectionHandleShape.Platform -> platformSelectionHandleShape.look
		SelectionHandleShape.Teardrop -> TeardropHandles
		SelectionHandleShape.Bar -> BarHandles
	}

/** One look for the touch handles: what each draws, where a finger takes it, and the point a drag of it starts from. */
internal sealed class HandleLook {
	abstract fun draw(scope: DrawScope, handle: TouchHandle, color: Color)

	/** What [draw] covers, which the touch toolbar keeps clear of; null when it draws nothing. */
	abstract fun drawnBounds(density: Density, handle: TouchHandle): Rect?

	/** Where a finger lands on the handle. */
	abstract fun touchTarget(density: Density, handle: TouchHandle): Rect

	/** The middle of what a finger grabs; where targets overlap, the nearer one wins. */
	abstract fun grabPoint(density: Density, handle: TouchHandle): Offset

	/**
	 * Whether the caret's target lies on the caret's own text, where a double tap's second
	 * tap lands, so taps there are counted and a double tap still selects the word.
	 */
	abstract val caretTargetOnText: Boolean
}

/** The handle up at [position] (in canvas coordinates) under [look], the nearest where targets overlap. */
internal fun Density.touchedHandle(position: Offset, state: TextEditorState, look: HandleLook): TouchHandle? =
	state.visibleHandles()
		.filter { look.touchTarget(this, it).contains(position) }
		.minByOrNull { (position - look.grabPoint(this, it)).getDistance() }

/**
 * Compose's `SelectionHandle` and `CursorHandle` on Android, which `BasicTextField` draws:
 * a disc with one square corner at the end it marks, hanging below the row, its corner at
 * its top right when it hangs left, at its top left when it hangs right; the caret's
 * points straight up. Each takes a finger anywhere in a 40 dp box on its disc's side of
 * the corner, `BasicTextField`'s minimum, from Android's `TextView`.
 */
internal data object TeardropHandles : HandleLook() {
	/** Compose's `HandleWidth`: the selection handle's box, twice its disc's radius. */
	val SelectionSize: Dp = 25.dp

	/** Compose's `CursorHandleHeight`: from the caret handle's point to its disc's bottom. */
	val CaretHeight: Dp = 25.dp

	val MinTouchTarget: Dp = 40.dp

	private val sqrt2 = sqrt(2f)

	private fun Density.radius(handle: TouchHandle): Float = when (handle.role) {
		HandleRole.Caret -> CaretHeight.toPx() / (1 + sqrt2)
		else -> SelectionSize.toPx() / 2f
	}

	override fun draw(scope: DrawScope, handle: TouchHandle, color: Color) {
		val r = scope.radius(handle)
		val x = handle.at.position.x
		val bottom = handle.at.lineBottom
		val disc = Rect(grabPoint(scope, handle), r)
		val path = Path().apply {
			moveTo(x, bottom)
			// From the corner along one straight side to the disc, around it, and back.
			when {
				handle.role == HandleRole.Caret -> {
					lineTo(x + r / sqrt2, bottom + r / sqrt2)
					// Split on the disc's axes, so the arcs' control points stay inside its box.
					arcTo(disc, startAngleDegrees = -45f, sweepAngleDegrees = 45f, forceMoveTo = false)
					arcTo(disc, startAngleDegrees = 0f, sweepAngleDegrees = 180f, forceMoveTo = false)
					arcTo(disc, startAngleDegrees = 180f, sweepAngleDegrees = 45f, forceMoveTo = false)
				}
				handle.hangsLeft -> {
					lineTo(x, bottom + r)
					arcTo(disc, startAngleDegrees = 0f, sweepAngleDegrees = 270f, forceMoveTo = false)
				}
				else -> {
					lineTo(x + r, bottom)
					arcTo(disc, startAngleDegrees = -90f, sweepAngleDegrees = 270f, forceMoveTo = false)
				}
			}
			close()
		}
		scope.drawPath(path, color)
	}

	override fun drawnBounds(density: Density, handle: TouchHandle): Rect {
		val r = density.radius(handle)
		val x = handle.at.position.x
		val bottom = handle.at.lineBottom
		return when {
			handle.role == HandleRole.Caret -> Rect(x - r, bottom, x + r, bottom + r * (1 + sqrt2))
			handle.hangsLeft -> Rect(x - 2 * r, bottom, x, bottom + 2 * r)
			else -> Rect(x, bottom, x + 2 * r, bottom + 2 * r)
		}
	}

	override fun touchTarget(density: Density, handle: TouchHandle): Rect {
		val drawn = drawnBounds(density, handle)
		val width = maxOf(drawn.width, with(density) { MinTouchTarget.toPx() })
		val height = maxOf(drawn.height, with(density) { MinTouchTarget.toPx() })
		val x = handle.at.position.x
		val left = when {
			handle.role == HandleRole.Caret -> x - width / 2f
			handle.hangsLeft -> x - width
			else -> x
		}
		return Rect(left, drawn.top, left + width, drawn.top + height)
	}

	override fun grabPoint(density: Density, handle: TouchHandle): Offset {
		val r = density.radius(handle)
		val x = handle.at.position.x
		val bottom = handle.at.lineBottom
		return when {
			handle.role == HandleRole.Caret -> Offset(x, bottom + r * sqrt2)
			handle.hangsLeft -> Offset(x - r, bottom + r)
			else -> Offset(x + r, bottom + r)
		}
	}

	override val caretTargetOnText: Boolean get() = false
}

/**
 * iOS's, as Compose draws them on iOS 17 and later: a 2 dp bar the row's height at each
 * end of the selection, with a shadowed dot above the bar when the handle hangs left (the
 * start, in left-to-right text) and below it otherwise. No caret handle is drawn; while
 * one is up the caret itself takes a finger, 12 dp either side of it on its row, so taps
 * beside it still move it. A selection handle takes a finger where Compose's does: the
 * dot and its bar, widened by 5 dp past the dot.
 */
internal data object BarHandles : HandleLook() {
	val DotDiameter: Dp = 16.7.dp
	val BarWidth: Dp = 2.dp
	val ShadowRadius: Dp = 13.dp
	const val SHADOW_ALPHA = 0.3f

	/** Compose's clickable padding past the dot. */
	val DotPadding: Dp = 5.dp
	val CaretTargetWidth: Dp = 24.dp

	override fun draw(scope: DrawScope, handle: TouchHandle, color: Color) {
		if (handle.role == HandleRole.Caret) return
		val at = handle.at
		val dot = grabPoint(scope, handle)
		val dotRadius = with(scope) { DotDiameter.toPx() / 2f }
		val barWidth = with(scope) { BarWidth.toPx() }
		// The bar runs from the dot's centre to the row's far edge.
		val top = if (handle.hangsLeft) dot.y else at.lineTop
		val bottom = if (handle.hangsLeft) at.lineBottom else dot.y
		scope.drawShadow(dot, dotRadius)
		scope.drawRect(color, Offset(at.position.x - barWidth / 2f, top), Size(barWidth, bottom - top))
		scope.drawCircle(color, dotRadius, dot)
	}

	/**
	 * A Gaussian blur of the dot, which Compose draws with a Skia mask filter, as a radial
	 * gradient with that blur's falloff (sigma from Compose's `BlurEffect` conversion).
	 */
	private fun DrawScope.drawShadow(center: Offset, dotRadius: Float) {
		val sigma = 0.57735f * ShadowRadius.toPx() + 0.5f
		val reach = dotRadius + 3 * sigma
		val black = Color.Black
		drawCircle(
			brush = Brush.radialGradient(
				0f to black.copy(alpha = SHADOW_ALPHA * 0.46f),
				dotRadius / reach to black.copy(alpha = SHADOW_ALPHA * 0.3f),
				(dotRadius + sigma) / reach to black.copy(alpha = SHADOW_ALPHA * 0.12f),
				(dotRadius + 2 * sigma) / reach to black.copy(alpha = SHADOW_ALPHA * 0.03f),
				1f to Color.Transparent,
				center = center,
				radius = reach,
			),
			radius = reach,
			center = center,
		)
	}

	override fun drawnBounds(density: Density, handle: TouchHandle): Rect? {
		if (handle.role == HandleRole.Caret) return null
		val at = handle.at
		val dotRadius = with(density) { DotDiameter.toPx() / 2f }
		val x = at.position.x
		return if (handle.hangsLeft) {
			Rect(x - dotRadius, at.lineTop - 2 * dotRadius, x + dotRadius, at.lineBottom)
		} else {
			Rect(x - dotRadius, at.lineTop, x + dotRadius, at.lineBottom + 2 * dotRadius)
		}
	}

	override fun touchTarget(density: Density, handle: TouchHandle): Rect {
		val at = handle.at
		val x = at.position.x
		if (handle.role == HandleRole.Caret) {
			val half = with(density) { CaretTargetWidth.toPx() / 2f }
			return Rect(x - half, at.lineTop, x + half, at.lineBottom)
		}
		val drawn = checkNotNull(drawnBounds(density, handle))
		val padding = with(density) { DotPadding.toPx() }
		return if (handle.hangsLeft) {
			Rect(drawn.left - padding, drawn.top - padding, drawn.right + padding, drawn.bottom)
		} else {
			Rect(drawn.left - padding, drawn.top, drawn.right + padding, drawn.bottom + padding)
		}
	}

	override fun grabPoint(density: Density, handle: TouchHandle): Offset {
		val at = handle.at
		val dotRadius = with(density) { DotDiameter.toPx() / 2f }
		val x = at.position.x
		return when {
			handle.role == HandleRole.Caret -> Offset(x, at.lineTop + at.height / 2f)
			handle.hangsLeft -> Offset(x, at.lineTop - dotRadius)
			else -> Offset(x, at.lineBottom + dotRadius)
		}
	}

	override val caretTargetOnText: Boolean get() = true
}

/** The handle colour when the style leaves it unspecified. */
internal val DefaultSelectionHandleColor = Color(0xFF2196F3)
