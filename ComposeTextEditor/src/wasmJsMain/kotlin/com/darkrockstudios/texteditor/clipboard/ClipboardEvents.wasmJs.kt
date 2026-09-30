@file:OptIn(ExperimentalWasmJsInterop::class, InternalComposeUiApi::class)

package com.darkrockstudios.texteditor.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.window.LocalActiveClipEventsTarget
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.input.EditorCommand.Action
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.platformKeyBindings
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.js.ExperimentalWasmJsInterop

/**
 * A browser answers Ctrl/Cmd+C, X and V in the backing text area with a `copy`,
 * `cut` or `paste` event while the key is still down, and Compose hands the key to
 * the editor a frame later, where the key bindings run Copy, Cut or Paste. The event
 * is the one moment the page may read and write the clipboard with no permission
 * prompt, so it moves the data and the actions do the editing: copy and cut write
 * the selection's `text/html` and `text/plain` into the event, and paste keeps the
 * event's for the Paste action to take ([ClipboardHelper] reads it instead of
 * `navigator.clipboard`). The text area's own copy or paste of its plain mirror is
 * prevented.
 *
 * Without a text area to type into (a disabled or read-only editor, or a selectable
 * `RichTextView`, has no input session, and a touch can leave the canvas focused) the key
 * lands on the canvas, where Compose takes it before the browser fires a `copy` event, so
 * a copy chord there asks the browser for one with `execCommand('copy')` first, still
 * inside the key press. Which canvas is this editor's is not known, only that it is a
 * Compose one (in a shadow root): on a page of several viewports, the one whose editor
 * has focus and a selection answers.
 */
@Composable
internal actual fun ClipboardEventsEffect(state: TextEditorState) {
	val activeTarget = LocalActiveClipEventsTarget.current
	DisposableEffect(state, activeTarget) {
		var copyingFromCanvas = false
		val handle = listenForClipboardEvents { type, event ->
			// The copy the canvas's key press asked for is this editor's wherever it lands.
			val asked = copyingFromCanvas && type == "copy"
			if (!(state.isFocused || asked) || !hasClipboardData(event)) return@listenForClipboardEvents false
			if (!asked) {
				// Only the event aimed at this viewport's input; one for another element on
				// the page is that element's.
				val target = activeTarget() ?: return@listenForClipboardEvents false
				if (!isTargetedAt(event, target)) return@listenForClipboardEvents false
			}
			when (type) {
				"copy", "cut" -> {
					val selection = state.selector.selection ?: return@listenForClipboardEvents false
					val text = state.selector.getSelectedText().text
					setClipboardData(event, "text/html", state.selectionAsHtml(selection))
					setClipboardData(event, "text/plain", text)
					ClipboardHelper.eventWrote(text)
					true
				}

				"paste" -> {
					if (state.actions[Action.Paste] == null) return@listenForClipboardEvents false
					ClipboardHelper.eventPasted(
						html = clipboardData(event, "text/html"),
						text = clipboardData(event, "text/plain"),
					)
					true
				}

				else -> false
			}
		}
		val keys = listenForCanvasCopyChord(
			apple = platformKeyBindings() === MacKeyBindings,
			wanted = { state.hasFocus && state.selector.selection != null && state.actions[Action.Copy] != null },
			copy = {
				copyingFromCanvas = true
				try {
					execCopy()
				} finally {
					copyingFromCanvas = false
				}
			},
		)
		onDispose {
			stopListening(handle)
			stopListeningForKeys(keys)
		}
	}
}

/**
 * Calls [copy] for each copy chord pressed on a Compose canvas, when [wanted], before the
 * canvas's own key handling runs: Cmd+C on [apple] systems, else Ctrl+C or Ctrl+Insert.
 * Keys are matched by `code`, the physical key, as Compose maps them; without one Compose
 * runs no Copy action either.
 */
private fun listenForCanvasCopyChord(apple: Boolean, wanted: () -> Boolean, copy: () -> Unit): JsAny = js(
	"""{
		const listener = (event) => {
			if (event.defaultPrevented || event.altKey) return;
			const primary = apple ? event.metaKey && !event.ctrlKey : event.ctrlKey && !event.metaKey;
			if (!primary || !(event.code === 'KeyC' || (!apple && event.code === 'Insert'))) return;
			const origin = event.composedPath()[0];
			if (!(origin instanceof HTMLCanvasElement) || !(origin.getRootNode() instanceof ShadowRoot)) return;
			if (wanted()) copy();
		};
		document.addEventListener('keydown', listener, true);
		return listener;
	}"""
)

private fun stopListeningForKeys(listener: JsAny): Unit = js("document.removeEventListener('keydown', listener, true)")

/**
 * Has the browser fire a `copy` event now, as the key press lets the page. WebKit enables
 * the command without a DOM selection only when a `beforecopy` handler prevents its
 * default, so one does for the call.
 */
private fun execCopy(): Boolean = js(
	"""{
		const enable = (event) => event.preventDefault();
		document.addEventListener('beforecopy', enable, true);
		try {
			return document.execCommand('copy');
		} finally {
			document.removeEventListener('beforecopy', enable, true);
		}
	}"""
)

/** Calls [handler] with each copy, cut and paste event in the page; true prevents its default. */
private fun listenForClipboardEvents(handler: (String, JsAny) -> Boolean): JsAny = js(
	"""{
		const listener = (event) => { if (handler(event.type, event)) event.preventDefault(); };
		document.addEventListener('copy', listener, true);
		document.addEventListener('cut', listener, true);
		document.addEventListener('paste', listener, true);
		return listener;
	}"""
)

private fun stopListening(listener: JsAny): Unit = js(
	"""{
		document.removeEventListener('copy', listener, true);
		document.removeEventListener('cut', listener, true);
		document.removeEventListener('paste', listener, true);
	}"""
)

private fun isTargetedAt(event: JsAny, element: JsAny): Boolean = js("event.composedPath()[0] === element")

private fun hasClipboardData(event: JsAny): Boolean = js("Boolean(event.clipboardData)")

private fun clipboardData(event: JsAny, type: String): String? = js("event.clipboardData.getData(type)")

private fun setClipboardData(event: JsAny, type: String, value: String): Unit =
	js("event.clipboardData.setData(type, value)")
