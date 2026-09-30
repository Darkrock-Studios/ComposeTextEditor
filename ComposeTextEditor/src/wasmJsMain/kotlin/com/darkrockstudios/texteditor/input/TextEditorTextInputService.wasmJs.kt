@file:OptIn(ExperimentalWasmJsInterop::class)

package com.darkrockstudios.texteditor.input

import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.input.ImeOptions
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Web: the shared skiko session. Compose creates a hidden `<textarea>` for the request
 * and focuses it, which is what raises the soft keyboard on mobile browsers, and turns
 * its `beforeinput` and composition events into `EditCommand`s for the request's
 * `onEditCommand`. Key events the textarea does not turn into text (arrows, Backspace,
 * Enter, chords) are forwarded to Compose's key dispatch and reach the key handler.
 *
 * While the session is live the editor holds Compose focus, so typing belongs in the
 * textarea. A mouse press on the canvas (a right-click, a toolbar button that takes no
 * focus, a context menu item) moves DOM focus to the canvas, whose key events cannot tell
 * some typed characters from named keys (see `isCharacterInputCandidate`), so the session
 * hands DOM focus straight back.
 *
 * `ImeOptions.Default` maps to a multiline textarea with a plain text input mode.
 */
actual class TextEditorTextInputService actual constructor(
	private val state: TextEditorState
) {
	actual suspend fun startInput(session: PlatformTextInputSession): Nothing = coroutineScope {
		launch { keepBackingFieldFocused() }
		state.startSkikoInputSession(session, ImeOptions.Default)
	}
}

/**
 * The session creates the backing textarea when it starts, which may be a dispatch or two
 * after this is launched, so its root is looked for over a few frames. The listener is
 * removed as the session is cancelled, not on a later dispatch, so it can never move focus
 * for a session that has ended.
 */
private suspend fun keepBackingFieldFocused() {
	var handle: JsAny? = null
	for (attempt in 1..ROOT_LOOKUP_ATTEMPTS) {
		handle = refocusBackingFieldFromCanvas()
		if (handle != null) break
		delay(ROOT_LOOKUP_INTERVAL_MS)
	}
	val found = handle ?: return
	suspendCancellableCoroutine<Nothing> { continuation ->
		continuation.invokeOnCancellation { stopRefocusing(found) }
	}
}

private const val ROOT_LOOKUP_ATTEMPTS = 10
private const val ROOT_LOOKUP_INTERVAL_MS = 16L

/**
 * Finds the viewport's shadow root that holds the backing textarea and moves DOM focus
 * from that root's canvas to the textarea whenever the canvas takes it. The listener sits
 * on the shadow root because a focus move inside a shadow tree is not reported outside it.
 * The root is the one on the active element's path (Compose focuses the textarea as it
 * creates it), else the first open shadow root holding one. The class is the one
 * Compose's `DomInputStrategy` gives the textarea. Returns null while there is none.
 *
 * A touch leaves focus where the browser put it: a tap Compose did not consume blurs the
 * textarea to hide the keyboard, and giving focus back there would raise the keyboard
 * again over whatever the tap was for.
 */
private fun refocusBackingFieldFromCanvas(): JsAny? = js(
	"""{
	const selector = '.compose-backing-field';
	let root = null;
	let element = document.activeElement;
	while (element && element.shadowRoot) {
		if (element.shadowRoot.querySelector(selector)) root = element.shadowRoot;
		element = element.shadowRoot.activeElement;
	}
	if (!root) {
		for (const host of document.querySelectorAll('*')) {
			if (host.shadowRoot && host.shadowRoot.querySelector(selector)) {
				root = host.shadowRoot;
				break;
			}
		}
	}
	if (!root) return null;
	let lastPointerType = 'mouse';
	const onPointerDown = (event) => { lastPointerType = event.pointerType; };
	const onFocusIn = (event) => {
		if (event.target.tagName !== 'CANVAS' || lastPointerType !== 'mouse') return;
		const field = root.querySelector(selector);
		if (field) field.focus({ preventScroll: true });
	};
	root.addEventListener('pointerdown', onPointerDown, true);
	root.addEventListener('focusin', onFocusIn);
	return { root, onPointerDown, onFocusIn };
}"""
)

private fun stopRefocusing(handle: JsAny): Unit = js(
	"""{
	handle.root.removeEventListener('pointerdown', handle.onPointerDown, true);
	handle.root.removeEventListener('focusin', handle.onFocusIn);
}"""
)
