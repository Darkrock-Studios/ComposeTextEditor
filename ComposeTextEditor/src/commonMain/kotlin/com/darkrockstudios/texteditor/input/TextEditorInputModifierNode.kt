package com.darkrockstudios.texteditor.input

import androidx.compose.ui.focus.FocusDirection
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import androidx.compose.ui.text.input.ImeAction
import com.darkrockstudios.texteditor.state.FocusedEditor
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
	keyBindings: KeyBindings,
	private var inputRequester: TextInputRequester?,
	private var singleLine: Boolean?,
) : androidx.compose.ui.Modifier.Node(),
	KeyInputModifierNode,
	SoftKeyboardInterceptionModifierNode,
	FocusEventModifierNode,
	PlatformTextInputModifierNode,
	CompositionLocalConsumerModifierNode {

	private val keyCommandHandler = TextEditorKeyCommandHandler(keyBindings)

	/** The soft keyboard's action key without a host handler, as Compose's text fields answer it. */
	private val defaultImeAction: (ImeAction) -> Unit = { action ->
		when (action) {
			ImeAction.Next -> currentValueOf(LocalFocusManager).moveFocus(FocusDirection.Next)
			ImeAction.Previous -> currentValueOf(LocalFocusManager).moveFocus(FocusDirection.Previous)
			ImeAction.Done -> currentValueOf(LocalSoftwareKeyboardController)?.hide()
			else -> Unit
		}
	}

	/** The focused editor is the one the keyboard types into, so its default and line limit answer. */
	private fun holdFocus(state: TextEditorState) {
		state.focusedEditor = FocusedEditor(defaultImeAction, singleLine)
	}

	private fun releaseFocus(state: TextEditorState) {
		if (state.focusedEditor?.defaultImeAction === defaultImeAction) state.focusedEditor = null
	}

	private var inputSessionJob: Job? = null
	private var imeCursorSync: ImeCursorSync? = null
	private var isFocused = false

	override fun onAttach() {
		inputRequester?.node = this
	}

	override fun onDetach() {
		if (inputRequester?.node === this) inputRequester?.node = null
		releaseFocus(state)
		stopTextInputSession()
		if (isFocused) state.hasFocus = false
		isFocused = false
	}

	override fun onFocusEvent(focusState: FocusState) {
		// Compose re-sends the focus event, unchanged, for reasons that have nothing to do
		// with the user (a tap on the focused editor, focus-property invalidation). Taps
		// reach the keyboard through requestInput, so only a change of focus acts here.
		if (focusState.isFocused == isFocused) return
		isFocused = focusState.isFocused
		state.hasFocus = isFocused
		if (isFocused) holdFocus(state) else releaseFocus(state)
		keyCommandHandler.onFocusChanged()
		state.heldKey.clear()
		syncInputSession(startSession = true)
	}

	/**
	 * Matches the input session to focus and [enabled]. A disabled editor still receives
	 * key events but neither shows as focused nor takes IME input. A session starts only
	 * when [startSession] says the user asked for input, because starting one raises the
	 * soft keyboard, unless [showKeyboard] is false.
	 */
	private fun syncInputSession(startSession: Boolean, showKeyboard: Boolean = true) {
		val wantsInput = enabled && isFocused
		state.updateFocus(wantsInput)
		if (!wantsInput) {
			stopTextInputSession()
		} else if (startSession && inputSessionJob?.isActive != true) {
			launchTextInputSession(showKeyboard)
		}
	}

	/**
	 * A tap on the focused editor: the user wants to type, so bring back a keyboard they
	 * dismissed, starting a session if none is live. A live session is kept rather than
	 * restarted, which would reset the keyboard mid-word and drop whatever the IME had in
	 * flight.
	 */
	internal fun requestInput() {
		if (!enabled || !isFocused) return
		if (inputSessionJob?.isActive == true) {
			currentValueOf(LocalSoftwareKeyboardController)?.show()
		} else {
			launchTextInputSession()
		}
	}

	private fun stopTextInputSession() {
		inputSessionJob?.cancel()
		inputSessionJob = null
		state.hasInputSession = false
		imeCursorSync?.stopSync()
		imeCursorSync = null
	}

	private fun launchTextInputSession(showKeyboard: Boolean = true) {
		inputSessionJob?.cancel()

		// Start IME cursor synchronization (syncs cursor/selection changes to keyboard)
		imeCursorSync?.stopSync()
		imeCursorSync = ImeCursorSync(state).also { sync ->
			sync.startSync()
		}

		inputSessionJob = coroutineScope.launch {
			val job = coroutineContext[Job]
			state.hasInputSession = true
			try {
				establishTextInputSession {
					// Dispatched, so it lands after the start below has asked to show the
					// keyboard: no session came before this one to wait on.
					if (!showKeyboard) launch { currentValueOf(LocalSoftwareKeyboardController)?.hide() }
					// The platform's session: an InputConnection on Android, the shared
					// skiko request elsewhere. See TextEditorTextInputService.
					TextEditorTextInputService(state).startInput(this)
				}
			} finally {
				if (inputSessionJob === job) state.hasInputSession = false
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
		keyBindings: KeyBindings,
		inputRequester: TextInputRequester?,
		singleLine: Boolean?,
	) {
		val stateChanged = state !== this.state
		val enabledChanged = enabled != this.enabled
		val singleLineChanged = singleLine != this.singleLine
		// A session is bound to its state, so a swapped state needs a new one. Only a live
		// session carries over: turning input back on must not raise the keyboard unasked,
		// so where the platform can, it starts one that keeps the keyboard down.
		val restartSession = stateChanged && inputSessionJob?.isActive == true
		val quietSession = enabledChanged && enabled && !restartSession && startsInputQuietly
		if (isFocused && stateChanged) {
			stopTextInputSession()
			this.state.updateFocus(false)
			this.state.hasFocus = false
			state.hasFocus = true
			releaseFocus(this.state)
		}
		this.state = state
		this.singleLine = singleLine
		if (isFocused && (stateChanged || singleLineChanged)) holdFocus(state)
		this.clipboard = clipboard
		this.enabled = enabled
		keyCommandHandler.keyBindings = keyBindings
		if (inputRequester !== this.inputRequester) {
			if (this.inputRequester?.node === this) this.inputRequester?.node = null
			this.inputRequester = inputRequester
			inputRequester?.node = this
		}
		if (isFocused && (stateChanged || enabledChanged)) {
			syncInputSession(startSession = restartSession || quietSession, showKeyboard = !quietSession)
		}
	}
}

/**
 * ModifierNodeElement that creates and manages TextEditorInputModifierNode.
 */
internal data class TextEditorInputModifierElement(
	val state: TextEditorState,
	val clipboard: Clipboard,
	val enabled: Boolean,
	val keyBindings: KeyBindings,
	val inputRequester: TextInputRequester?,
	/** The editor's line limit; null for a view that takes no input and so sets none. */
	val singleLine: Boolean?,
) : ModifierNodeElement<TextEditorInputModifierNode>() {

	override fun create(): TextEditorInputModifierNode {
		return TextEditorInputModifierNode(state, clipboard, enabled, keyBindings, inputRequester, singleLine)
	}

	override fun update(node: TextEditorInputModifierNode) {
		node.update(state, clipboard, enabled, keyBindings, inputRequester, singleLine)
	}

	override fun InspectorInfo.inspectableProperties() {
		name = "textEditorInput"
		properties["enabled"] = enabled
	}
}
