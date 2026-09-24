package com.darkrockstudios.texteditor.input

import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.SoftKeyboardInterceptionModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Core modifier node for text input handling in the text editor.
 *
 * This node combines:
 * - KeyInputModifierNode: For handling keyboard shortcuts and commands
 * - SoftKeyboardInterceptionModifierNode: For catching hardware-keyboard
 *   shortcuts on Android *before* the IME consumes them. Without this, key
 *   events (e.g. Ctrl+C, Ctrl+V) from a Bluetooth/USB keyboard never reach
 *   `onPreKeyEvent` because the soft-keyboard intercept chain runs first.
 * - FocusEventModifierNode: For managing focus state
 * - PlatformTextInputModifierNode: For platform-specific text input (IME) handling
 */
internal class TextEditorInputModifierNode(
	var state: TextEditorState,
	var clipboard: Clipboard,
	var enabled: Boolean,
	keyBindings: KeyBindings
) : androidx.compose.ui.Modifier.Node(),
	KeyInputModifierNode,
	SoftKeyboardInterceptionModifierNode,
	FocusEventModifierNode,
	PlatformTextInputModifierNode,
	CompositionLocalConsumerModifierNode {

	private val keyCommandHandler = TextEditorKeyCommandHandler(keyBindings)
	private var inputSessionJob: Job? = null
	private var imeCursorSync: ImeCursorSync? = null
	private var isFocused = false

	override fun onFocusEvent(focusState: FocusState) {
		isFocused = focusState.isFocused
		syncInputSession()
	}

	override fun onDetach() {
		stopTextInputSession()
	}

	/**
	 * Matches the input session to focus and [enabled]. A disabled editor still receives
	 * key events but neither shows as focused nor takes IME input.
	 *
	 * Compose re-sends the focus event when a focused editor is tapped again. Restarting a
	 * live session then would reset the keyboard mid-word and throw away whatever the IME
	 * had in flight, so the session is kept and only a keyboard the user dismissed is
	 * brought back.
	 */
	private fun syncInputSession() {
		val wantsInput = enabled && isFocused
		state.updateFocus(wantsInput)
		when {
			!wantsInput -> stopTextInputSession()
			inputSessionJob?.isActive != true -> launchTextInputSession()
			else -> currentValueOf(LocalSoftwareKeyboardController)?.show()
		}
	}

	private fun stopTextInputSession() {
		inputSessionJob?.cancel()
		inputSessionJob = null
		imeCursorSync?.stopSync()
		imeCursorSync = null
	}

	private fun launchTextInputSession() {
		inputSessionJob?.cancel()

		// Start IME cursor synchronization (syncs cursor/selection changes to keyboard)
		imeCursorSync?.stopSync()
		imeCursorSync = ImeCursorSync(state).also { sync ->
			sync.startSync()
		}

		inputSessionJob = coroutineScope.launch {
			establishTextInputSession {
				// Start platform-specific input method.
				// Android: opens the soft keyboard and establishes an InputConnection.
				// Desktop/iOS: opens a platform input-method session for composed input
				//   (dead keys, accents, CJK, emoji picker); plain typing on desktop
				//   still arrives separately as KEY_TYPED.
				// WASM: suspends indefinitely — browser keyboard events are used instead.
				TextEditorTextInputService(state).startInput(this)
			}
		}
	}

	override fun onPreKeyEvent(event: KeyEvent): Boolean {
		return keyCommandHandler.handleKeyEvent(event, state, clipboard, coroutineScope, enabled)
	}

	override fun onKeyEvent(event: KeyEvent): Boolean {
		if (!enabled) return false
		// Handle character input (KEY_TYPED events on desktop arrive here as Unknown type)
		return keyCommandHandler.handleCharacterInput(event, state)
	}

	// On Android, hardware-keyboard events with an active IME are routed through
	// the soft-keyboard intercept chain before reaching onPreKeyEvent. Mirror the
	// shortcut handler here so Ctrl+C / Ctrl+V / arrow keys / etc. work with a
	// physical keyboard. We only intercept what handleKeyEvent claims; everything
	// else (typed characters) falls through to the IME, which delivers them via
	// commitText through InputConnection. The pre-intercept (top-down) phase is
	// where we want to win — bottom-up just returns false.
	override fun onPreInterceptKeyBeforeSoftKeyboard(event: KeyEvent): Boolean {
		return keyCommandHandler.handleKeyEvent(event, state, clipboard, coroutineScope, enabled)
	}

	override fun onInterceptKeyBeforeSoftKeyboard(event: KeyEvent): Boolean = false

	fun update(
		state: TextEditorState,
		clipboard: Clipboard,
		enabled: Boolean,
		keyBindings: KeyBindings
	) {
		val stateChanged = state !== this.state
		val enabledChanged = enabled != this.enabled
		if (isFocused && stateChanged) {
			// The running session and its sync are bound to the old state.
			stopTextInputSession()
			this.state.updateFocus(false)
		}
		this.state = state
		this.clipboard = clipboard
		this.enabled = enabled
		keyCommandHandler.keyBindings = keyBindings
		if (isFocused && (stateChanged || enabledChanged)) syncInputSession()
	}
}

/**
 * ModifierNodeElement that creates and manages TextEditorInputModifierNode.
 */
internal data class TextEditorInputModifierElement(
	val state: TextEditorState,
	val clipboard: Clipboard,
	val enabled: Boolean,
	val keyBindings: KeyBindings
) : ModifierNodeElement<TextEditorInputModifierNode>() {

	override fun create(): TextEditorInputModifierNode {
		return TextEditorInputModifierNode(state, clipboard, enabled, keyBindings)
	}

	override fun update(node: TextEditorInputModifierNode) {
		node.update(state, clipboard, enabled, keyBindings)
	}

	override fun InspectorInfo.inspectableProperties() {
		name = "textEditorInput"
		properties["enabled"] = enabled
	}
}
