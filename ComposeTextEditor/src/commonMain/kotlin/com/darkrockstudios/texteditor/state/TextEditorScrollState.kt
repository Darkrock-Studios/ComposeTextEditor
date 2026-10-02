package com.darkrockstudios.texteditor.state

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * One axis of a text editor's scroll position, as a Compose [ScrollableState]: the
 * vertical one ([TextEditorState.scrollState]) or, with wrapping off, the horizontal one
 * ([TextEditorState.horizontalScrollState]).
 *
 * Position is tracked in pixels and clamped to [minValue]..[maxValue]; setting
 * those bounds re-clamps the current [value]. Use with `Modifier.scrollable`, or
 * drive it directly via [scrollTo], [scrollBy], and [animateScrollTo].
 *
 * @param initial Initial scroll position in pixels.
 */
class TextEditorScrollState(
	initial: Int = 0
) : ScrollableState {
	private var _value by mutableStateOf(initial)
	private var _minValue by mutableStateOf(0)
	private var _maxValue by mutableStateOf(0)
	private var _isScrollInProgress by mutableStateOf(false)

	/** The viewport's length along this axis when the range was computed, which a scrollbar sizes its thumb by. */
	internal var viewportLength by mutableStateOf(0)
	private val scrollMutex = MutatorMutex()

	private val scrollScope: ScrollScope = object : ScrollScope {
		override fun scrollBy(pixels: Float): Float {
			return dispatchRawDelta(pixels)
		}
	}

	/** Current scroll position in pixels, clamped to [minValue]..[maxValue]. */
	val value: Int get() = _value

	/** Lower scroll bound in pixels. Setting it re-clamps [value]. */
	var minValue: Int
		get() = _minValue
		set(value) {
			val wasAtMin = _value == _minValue
			_minValue = value
			if (_maxValue < _minValue) _maxValue = _minValue
			_value = if (wasAtMin) _minValue else _value.coerceIn(_minValue, _maxValue)
		}

	/** Upper scroll bound in pixels, never below [minValue]. Setting it re-clamps [value]. */
	var maxValue: Int
		get() = _maxValue
		set(value) {
			_maxValue = value.coerceAtLeast(_minValue)
			_value = _value.coerceIn(_minValue, _maxValue)
		}

	// Compose's forward is a positive delta, which [dispatchRawDelta] turns into a smaller
	// value: toward the document's start.
	override val canScrollForward: Boolean
		get() = _value > _minValue

	override val canScrollBackward: Boolean
		get() = _value < _maxValue

	/** `true` while a [scroll] or [animateScrollTo] is running. */
	override val isScrollInProgress: Boolean
		get() = _isScrollInProgress

	/**
	 * Runs [block] with exclusive scroll access at the given [scrollPriority],
	 * marking [isScrollInProgress] for its duration.
	 */
	override suspend fun scroll(
		scrollPriority: MutatePriority,
		block: suspend ScrollScope.() -> Unit
	) {
		scrollMutex.mutateWith(scrollScope, scrollPriority) {
			_isScrollInProgress = true
			try {
				block()
			} finally {
				_isScrollInProgress = false
			}
		}
	}

	/**
	 * Applies a raw scroll [delta] (positive scrolls content up), clamping to the
	 * bounds, and returns the pixels actually consumed.
	 */
	override fun dispatchRawDelta(delta: Float): Float {
		if (delta.isNaN()) return 0f

		val oldValue = _value
		val newValue = (oldValue - delta).roundToInt()
		val coercedValue = newValue.coerceIn(_minValue, maxValue)

		val consumed = (oldValue - coercedValue).toFloat()
		_value = coercedValue

		return consumed
	}

	/** Jumps to [value] (in pixels) immediately, clamped to the bounds. */
	fun scrollTo(value: Int) {
		_value = value.coerceIn(_minValue, maxValue)
	}

	/**
	 * Animates the position to [value] (in pixels), clamped to the bounds, using
	 * [animationSpec]. Suspends until the animation completes.
	 */
	suspend fun animateScrollTo(
		value: Int,
		animationSpec: AnimationSpec<Float> = spring(
			stiffness = Spring.StiffnessMediumLow,
			visibilityThreshold = 1f
		)
	) {
		scrollMutex.mutate {
			val targetValue = value.coerceIn(_minValue, maxValue).toFloat()
			_isScrollInProgress = true
			try {
				animate(
					initialValue = _value.toFloat(),
					targetValue = targetValue,
					animationSpec = animationSpec
				) { value, _ ->
					// The range can shrink mid-animation (an editor growing to its text).
					_value = value.roundToInt().coerceIn(_minValue, maxValue)
				}
			} finally {
				_isScrollInProgress = false
			}
		}
	}

	/**
	 * Moves the position by [delta] pixels, clamped to the bounds, and returns the
	 * pixels actually consumed.
	 */
	fun scrollBy(delta: Float): Float {
		val oldValue = _value
		val newValue = (oldValue + delta).roundToInt().coerceIn(_minValue, maxValue)
		val consumed = (newValue - oldValue).toFloat()
		_value = newValue
		return consumed
	}
}