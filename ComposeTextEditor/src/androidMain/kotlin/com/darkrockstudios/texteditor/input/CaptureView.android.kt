package com.darkrockstudios.texteditor.input

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Android implementation - captures the current View from LocalView.
 * This stores the View in the state's platformExtensions so it can be used
 * by ImeCursorSync for updateSelection calls and cursor anchor info.
 *
 * It also lends the state the window's bottom, which the keyboard cover is measured
 * from. A move of the view within the window (a scrolling parent) reaches the editor's
 * `onGloballyPositioned`, which measures the cover again.
 */
@Composable
actual fun CaptureViewForIme(state: TextEditorState) {
	val view = LocalView.current

	DisposableEffect(view, state) {
		state.platformExtensions.view = view
		val windowBottom = WindowBottom(view)::inView
		state.windowBottomInRoot = windowBottom
		onDispose {
			// Only clear if it's still our view
			if (state.platformExtensions.view === view) {
				state.platformExtensions.view = null
			}
			if (state.windowBottomInRoot === windowBottom) state.windowBottomInRoot = null
		}
	}
}

/**
 * The bottom of [view]'s window in the view's own coordinates, which are the Compose
 * root's: the root view fills the window, and a `ComposeView` embedded in views may end
 * above its bottom. Translations of the view and its ancestors count; a scale is not
 * converted, as the IME anchor's translate-only matrix does not convert one either.
 */
internal class WindowBottom(private val view: View) {
	private val location = IntArray(2)

	fun inView(): Float {
		view.getLocationInWindow(location)
		return (view.rootView.height - location[1]).toFloat()
	}
}
