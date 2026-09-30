@file:OptIn(ExperimentalWasmJsInterop::class, InternalComposeUiApi::class)

package com.darkrockstudios.texteditor.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.window.LocalActiveClipEventsTarget
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.input.EditorCommand.Action
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
 */
@Composable
internal actual fun ClipboardEventsEffect(state: TextEditorState) {
	val activeTarget = LocalActiveClipEventsTarget.current
	DisposableEffect(state, activeTarget) {
		val handle = listenForClipboardEvents { type, event ->
			if (!state.isFocused || !hasClipboardData(event)) return@listenForClipboardEvents false
			// Only the event aimed at this viewport's input; one for another element on
			// the page is that element's.
			val target = activeTarget() ?: return@listenForClipboardEvents false
			if (!isTargetedAt(event, target)) return@listenForClipboardEvents false
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
		onDispose { stopListening(handle) }
	}
}

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
