@file:OptIn(ExperimentalWasmJsInterop::class)

package com.darkrockstudios.texteditor.input

import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.CoroutineStart
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
 * The options come from the editor's `keyboardSettings`. Compose maps them to the
 * textarea's input mode and Enter key hint but ignores the capitalisation; see
 * [presetAutocapitalize].
 */
actual class TextEditorTextInputService actual constructor(
	private val state: TextEditorState
) {
	actual suspend fun startInput(session: PlatformTextInputSession): Nothing {
		val field = BackingField()
		// A change of keyboard settings starts the input method again, and Compose
		// replaces its textarea, so each run adopts the new one.
		state.startSkikoInputSession(
			session,
			{ state.skikoImeOptions() },
			imeResync = SkikoImeResync.Rewrite(field::rewrite),
			onRun = { options ->
				val autocapitalize = options.capitalization.autocapitalize
				// onRun runs just before the run starts Compose's input method, which
				// creates and focuses the field before it suspends.
				val preset = presetAutocapitalize(BACKING_FIELD, autocapitalize)
				launch(start = CoroutineStart.UNDISPATCHED) {
					suspendCancellableCoroutine<Nothing> { continuation ->
						continuation.invokeOnCancellation { stopPresetting(preset) }
					}
				}
				launch { field.adopt(autocapitalize, preset) }
			},
		)
	}
}

/** The class Compose's `DomInputStrategy` gives the backing textarea. */
private const val BACKING_FIELD = ".compose-backing-field"

/** Compose's hidden textarea for one session, found through the viewport's shadow root that holds it. */
private class BackingField {
	private var root: JsAny? = null

	/** The root holding the current run's field, looked up on first use. */
	private fun findRoot(): JsAny? = root ?: backingFieldRoot(BACKING_FIELD)?.also { root = it }

	/**
	 * Finds the field, which the session creates when it starts, a dispatch or two after
	 * this is launched, so it is looked for over a few frames. Then keeps DOM focus on it
	 * until the session ends; the focus listener is removed as the session is cancelled,
	 * not on a later dispatch, so it can never move focus for a session that has ended.
	 *
	 * A session ends when Compose focus leaves the editor, a Tab the editor left to the
	 * focus system among them. Compose removes the field then, and DOM focus, if the field
	 * held it, would fall to the page body, where no key reaches Compose again until a
	 * click; so it goes back to the canvas.
	 */
	suspend fun adopt(autocapitalize: String, preset: JsAny) {
		// The previous run's field is gone; look for this run's.
		root = null
		var found = findRoot()
		var attempts = 1
		while (found == null && attempts < ROOT_LOOKUP_ATTEMPTS) {
			delay(ROOT_LOOKUP_INTERVAL_MS)
			found = findRoot()
			attempts++
		}
		val root = found ?: return
		// Set already unless the preset missed the field's first focus, in a shadow root
		// it was not listening on.
		stopPresetting(preset)
		setAutocapitalize(root, BACKING_FIELD, autocapitalize)
		val handle = refocusFromCanvas(root, BACKING_FIELD)
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
		val selection = value.selection
		rewriteBackingField(root, BACKING_FIELD, value.text, selection.min, selection.max, selection.reversed)
	}
}

private const val ROOT_LOOKUP_ATTEMPTS = 10
private const val ROOT_LOOKUP_INTERVAL_MS = 16L

/**
 * The shadow root holding this session's backing field: the innermost one on the
 * active element's path (Compose focuses the field as it creates it), else the only
 * open shadow root on the page that holds one. Null while that is not certain, since a
 * second viewport's field belongs to another window's session.
 */
private fun backingFieldRoot(selector: String): JsAny? = js(
	"""{
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
 * Moves DOM focus from [root]'s canvas to its backing field whenever the canvas takes it.
 * The listener sits on the shadow root because a focus move inside a shadow tree is not
 * reported outside it.
 *
 * A touch leaves focus where the browser put it: a tap Compose did not consume blurs the
 * field to hide the keyboard, and giving focus back there would raise the keyboard again
 * over whatever the tap was for.
 *
 * The handle also follows whether the field holds DOM focus, for [stopRefocusing], and
 * whether the last press on the page landed outside this viewport.
 */
private fun refocusFromCanvas(root: JsAny, selector: String): JsAny = js(
	"""{
	const field = root.querySelector(selector);
	const handle = { root, field, fieldFocused: root.activeElement === field, pointerType: 'mouse', pressedOutside: false };
	handle.onPointerDown = (event) => { handle.pointerType = event.pointerType; };
	handle.onPagePointerDown = (event) => { handle.pressedOutside = !event.composedPath().includes(root); };
	handle.onFocusIn = (event) => {
		if (event.target === field) handle.fieldFocused = true;
		if (event.target.tagName !== 'CANVAS' || handle.pointerType !== 'mouse') return;
		if (field) field.focus({ preventScroll: true });
	};
	// A move to another element names it. The session's end blurs the field to nowhere
	// (Compose hides the keyboard, then removes the field), which leaves this set.
	handle.onBlur = (event) => { if (event.relatedTarget) handle.fieldFocused = false; };
	root.addEventListener('pointerdown', handle.onPointerDown, true);
	document.addEventListener('pointerdown', handle.onPagePointerDown, true);
	root.addEventListener('focusin', handle.onFocusIn);
	if (field) field.addEventListener('blur', handle.onBlur);
	return handle;
}"""
)

/**
 * Removes [refocusFromCanvas]'s listeners as the session ends, and when the session's
 * field held DOM focus and the last press was not outside the viewport, gives it to the
 * canvas once Compose has removed the field, unless something else has taken it by then
 * (the next session's field). A task later, so no focus event runs inside the
 * cancellation.
 */
private fun stopRefocusing(handle: JsAny): Unit = js(
	"""{
	handle.root.removeEventListener('pointerdown', handle.onPointerDown, true);
	document.removeEventListener('pointerdown', handle.onPagePointerDown, true);
	handle.root.removeEventListener('focusin', handle.onFocusIn);
	if (handle.field) handle.field.removeEventListener('blur', handle.onBlur);
	if (!handle.field || !handle.fieldFocused || handle.pressedOutside) return;
	setTimeout(() => {
		const active = document.activeElement;
		const stranded = handle.root.activeElement === handle.field || !active || active === document.body;
		if (!stranded) return;
		const canvas = handle.root.querySelector('canvas');
		if (canvas) canvas.focus({ preventScroll: true });
	});
}"""
)

/** The `autocapitalize` value for a capitalisation; the browser's default, sentences, when unspecified. */
private val KeyboardCapitalization.autocapitalize: String
	get() = when (this) {
		KeyboardCapitalization.None -> "off"
		KeyboardCapitalization.Characters -> "characters"
		KeyboardCapitalization.Words -> "words"
		else -> "sentences"
	}

/**
 * Compose creates every backing field with `autocapitalize="off"`, whatever the options
 * say, and focuses it at once, and a phone keyboard reads the attribute as it rises. A
 * `focusin` is dispatched inside that `focus()` call, before the browser updates its
 * keyboard, so the first backing field focused after this is given [value] there.
 *
 * A focus move inside a shadow tree is reported only inside it, so the listener sits on
 * the viewport's shadow root, which exists before its field does: the innermost one on
 * the active element's path holding a canvas (the press that focused the editor focused
 * its canvas), else every open root in the document.
 */
private fun presetAutocapitalize(selector: String, value: String): JsAny = js(
	"""{
	const handle = { targets: [] };
	let element = document.activeElement;
	while (element && element.shadowRoot) {
		if (element.shadowRoot.querySelector('canvas')) handle.targets = [element.shadowRoot];
		element = element.shadowRoot.activeElement;
	}
	if (handle.targets.length === 0) {
		for (const host of document.querySelectorAll('*')) {
			if (host.shadowRoot) handle.targets.push(host.shadowRoot);
		}
	}
	handle.onFocusIn = (event) => {
		const target = event.composedPath()[0];
		if (!(target instanceof Element) || !target.matches(selector)) return;
		target.setAttribute('autocapitalize', value);
		for (const each of handle.targets) each.removeEventListener('focusin', handle.onFocusIn, true);
	};
	for (const each of handle.targets) each.addEventListener('focusin', handle.onFocusIn, true);
	return handle;
}"""
)

private fun stopPresetting(handle: JsAny): Unit = js(
	"{ for (const each of handle.targets) each.removeEventListener('focusin', handle.onFocusIn, true); }"
)

private fun setAutocapitalize(root: JsAny, selector: String, value: String): Unit = js(
	"""{
	const field = root.querySelector(selector);
	if (field) field.setAttribute('autocapitalize', value);
}"""
)

private fun rewriteBackingField(
	root: JsAny,
	selector: String,
	text: String,
	start: Int,
	end: Int,
	backward: Boolean,
): Unit = js(
	"""{
	const field = root.querySelector(selector);
	if (!field) return;
	if (field.value !== text) field.value = text;
	field.setSelectionRange(start, end, backward ? 'backward' : 'forward');
}"""
)

// Starting a session focuses the backing text area, which raises a phone's keyboard, and
// hiding it blurs the text area and ends the typing it is there for.
internal actual val startsInputQuietly: Boolean = false
