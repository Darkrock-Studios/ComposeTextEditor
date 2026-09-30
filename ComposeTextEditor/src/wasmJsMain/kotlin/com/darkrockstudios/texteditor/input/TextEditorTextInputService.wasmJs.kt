@file:OptIn(ExperimentalWasmJsInterop::class)

package com.darkrockstudios.texteditor.input

import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.TextFieldValue
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
 * hands DOM focus straight back. The textarea is also the one platform copy of the text
 * that can go stale without the editor's value changing, so an IME resync rewrites it.
 *
 * `ImeOptions.Default` maps to a multiline textarea with a plain text input mode.
 */
actual class TextEditorTextInputService actual constructor(
	private val state: TextEditorState
) {
	actual suspend fun startInput(session: PlatformTextInputSession): Nothing = coroutineScope {
		val field = BackingField()
		launch { field.keepFocused() }
		state.startSkikoInputSession(session, ImeOptions.Default, imeResync = SkikoImeResync.Rewrite(field::rewrite))
	}
}

/**
 * Compose's hidden textarea for one session, found through the viewport's shadow root
 * that holds it. The class is the one Compose's `DomInputStrategy` gives it.
 */
private class BackingField {
	private var root: JsAny? = null

	private fun findRoot(): JsAny? = root ?: backingFieldRoot()?.also { root = it }

	/**
	 * The session creates the textarea when it starts, which may be a dispatch or two
	 * after this is launched, so its root is looked for over a few frames. The listener
	 * is removed as the session is cancelled, not on a later dispatch, so it can never
	 * move focus for a session that has ended.
	 */
	suspend fun keepFocused() {
		var found = findRoot()
		var attempts = 1
		while (found == null && attempts < ROOT_LOOKUP_ATTEMPTS) {
			delay(ROOT_LOOKUP_INTERVAL_MS)
			found = findRoot()
			attempts++
		}
		val handle = refocusFromCanvas(found ?: return)
		suspendCancellableCoroutine<Nothing> { continuation ->
			continuation.invokeOnCancellation { stopRefocusing(handle) }
		}
	}

	/**
	 * Compose leaves a key's default action to the textarea and mirrors the editor back
	 * only when the editor's value changes, so a key the editor answered without an edit
	 * (Enter leaving a list) leaves the browser's own edit in the textarea, where the next
	 * ranged `beforeinput` would read its offsets. A resync follows a commit or a key the
	 * browser does not send mid-composition, so no composition is open to disturb.
	 */
	fun rewrite(value: TextFieldValue) {
		val root = findRoot() ?: return
		rewriteBackingField(root, value.text, value.selection.min, value.selection.max, value.selection.reversed)
	}
}

private const val ROOT_LOOKUP_ATTEMPTS = 10
private const val ROOT_LOOKUP_INTERVAL_MS = 16L

/**
 * The shadow root holding this session's backing textarea: the innermost one on the
 * active element's path (Compose focuses the textarea as it creates it), else the only
 * open shadow root on the page that holds one. Null while that is not certain, since a
 * second viewport's textarea belongs to another window's session.
 */
private fun backingFieldRoot(): JsAny? = js(
	"""{
	const selector = '.compose-backing-field';
	let found = null;
	let element = document.activeElement;
	while (element && element.shadowRoot) {
		if (element.shadowRoot.querySelector(selector)) found = element.shadowRoot;
		element = element.shadowRoot.activeElement;
	}
	if (found) return found;
	for (const host of document.querySelectorAll('*')) {
		if (!host.shadowRoot || !host.shadowRoot.querySelector(selector)) continue;
		if (found) return null;
		found = host.shadowRoot;
	}
	return found;
}"""
)

/**
 * Moves DOM focus from [root]'s canvas to its textarea whenever the canvas takes it. The
 * listener sits on the shadow root because a focus move inside a shadow tree is not
 * reported outside it.
 *
 * A touch leaves focus where the browser put it: a tap Compose did not consume blurs the
 * textarea to hide the keyboard, and giving focus back there would raise the keyboard
 * again over whatever the tap was for.
 */
private fun refocusFromCanvas(root: JsAny): JsAny = js(
	"""{
	let lastPointerType = 'mouse';
	const onPointerDown = (event) => { lastPointerType = event.pointerType; };
	const onFocusIn = (event) => {
		if (event.target.tagName !== 'CANVAS' || lastPointerType !== 'mouse') return;
		const field = root.querySelector('.compose-backing-field');
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

private fun rewriteBackingField(root: JsAny, text: String, start: Int, end: Int, backward: Boolean): Unit = js(
	"""{
	const field = root.querySelector('.compose-backing-field');
	if (!field) return;
	if (field.value !== text) field.value = text;
	field.setSelectionRange(start, end, backward ? 'backward' : 'forward');
}"""
)
