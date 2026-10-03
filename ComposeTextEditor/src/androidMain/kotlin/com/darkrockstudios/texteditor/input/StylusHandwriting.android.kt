package com.darkrockstudios.texteditor.input

import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.stylusHoverIcon
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DpTouchBoundsExpansion
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.TouchBoundsExpansion
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.state.PlatformTextEditorExtensions
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * From Android 14, as Compose's text fields start it: the platform call exists from 13, but
 * Compose leaves 13 out.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal fun stylusHandwritingSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
	sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/** A password is never written by hand, as Compose decides for its text fields. */
internal fun KeyboardSettings.allowsHandwriting(): Boolean = when (keyboardType) {
	KeyboardType.Password, KeyboardType.PasswordVisible, KeyboardType.NumberPassword,
	KeyboardType.DecimalPassword, KeyboardType.NumberPasswordSigned, KeyboardType.DecimalPasswordSigned -> false

	else -> true
}

internal actual fun Modifier.stylusHandwriting(
	state: TextEditorState,
	enabled: Boolean,
	onStroke: (start: Offset, focused: Boolean) -> Unit,
): Modifier {
	if (!enabled || !stylusHandwritingSupported() || !state.keyboardSettings.allowsHandwriting()) return this
	return this
		.stylusHoverIcon(HandwritingIcon, overrideDescendants = false, touchBoundsExpansion = HandwritingBounds)
		.then(StylusHandwritingElement(state, onStroke))
}

/**
 * Asks the keyboard to take the stylus each time a stroke requests it, for the life of the
 * input session on [view]. A request made before the session starts (the stroke that
 * focused the editor) is replayed into it, then forgotten, so no later session takes it.
 */
internal suspend fun startStylusHandwriting(view: View, extensions: PlatformTextEditorExtensions) {
	if (!stylusHandwritingSupported()) return
	extensions.stylusHandwriting.collect {
		extensions.forgetStylusHandwriting()
		view.context.getSystemService(InputMethodManager::class.java)?.startStylusHandwriting(view)
	}
}

/** Compose's: strokes may start a little outside the editor, more above and below than beside it. */
private val HandwritingBounds = DpTouchBoundsExpansion(
	start = 10.dp,
	top = 40.dp,
	end = 10.dp,
	bottom = 40.dp,
	isLayoutDirectionAware = true,
)

private val HandwritingIcon = PointerIcon(android.view.PointerIcon.TYPE_HANDWRITING)

private data class StylusHandwritingElement(
	val state: TextEditorState,
	val onStroke: (Offset, Boolean) -> Unit,
) : ModifierNodeElement<StylusHandwritingNode>() {
	override fun create() = StylusHandwritingNode(state, onStroke)

	override fun update(node: StylusHandwritingNode) {
		node.state = state
		node.onStroke = onStroke
	}
}

/**
 * Compose's `StylusHandwritingNode`: on the initial pass, so ahead of the editor's own
 * gestures and the scroll, it watches for a stylus that travels past the handwriting slop
 * before the long press timeout, and takes that stroke for the keyboard. A stylus that
 * stays put (a tap, a long press) is left to the editor.
 */
private class StylusHandwritingNode(
	var state: TextEditorState,
	var onStroke: (Offset, Boolean) -> Unit,
) : DelegatingNode(), PointerInputModifierNode, FocusEventModifierNode, CompositionLocalConsumerModifierNode {
	private var focused = false

	override fun onFocusEvent(focusState: FocusState) {
		focused = focusState.isFocused
	}

	override val touchBoundsExpansion: TouchBoundsExpansion
		get() = HandwritingBounds.roundToTouchBoundsExpansion(requireDensity())

	private val pointerInput = delegate(SuspendingPointerInputModifierNode {
		awaitEachGesture {
			val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Initial)
			if (down.type != PointerType.Stylus && down.type != PointerType.Eraser) return@awaitEachGesture
			if (!keyboardWrites()) return@awaitEachGesture
			val inBounds = down.position.x >= 0 && down.position.x < size.width &&
					down.position.y >= 0 && down.position.y < size.height
			// A focused editor, or one the stroke starts on, comes before an editor whose
			// expanded bounds merely overlap it.
			val pass = if (focused || inBounds) PointerEventPass.Initial else PointerEventPass.Main

			var travelled: PointerInputChange? = null
			while (true) {
				val event = awaitPointerEvent(pass)
				val change = event.tracked(down) ?: break
				if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) break
				if (event.classification == MotionEvent.CLASSIFICATION_DEEP_PRESS) break
				if ((change.position - down.position).getDistance() > viewConfiguration.handwritingSlop) {
					travelled = change
					break
				}
			}
			if (travelled == null) return@awaitEachGesture

			onStroke(down.position, focused)
			state.platformExtensions.requestStylusHandwriting()
			travelled.consume()
			// The keyboard takes the stylus from here; until it does, the stroke is not the editor's.
			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Initial)
				(event.tracked(down) ?: return@awaitEachGesture).consume()
			}
		}
	})

	private fun PointerEvent.tracked(down: PointerInputChange): PointerInputChange? =
		changes.firstOrNull { !it.isConsumed && it.id == down.id && it.pressed }

	/** Whether the keyboard in use can write; without one, a stroke stays the editor's. */
	private fun keyboardWrites(): Boolean {
		if (!state.keyboardSettings.allowsHandwriting()) return false
		val imm = currentValueOf(LocalView).context.getSystemService(InputMethodManager::class.java) ?: return false
		return stylusHandwritingSupported() && imm.isStylusHandwritingAvailable
	}

	override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) =
		pointerInput.onPointerEvent(pointerEvent, pass, bounds)

	override fun onCancelPointerInput() = pointerInput.onCancelPointerInput()
}
