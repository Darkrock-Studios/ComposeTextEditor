@file:OptIn(ExperimentalWasmJsInterop::class, ExperimentalComposeUiApi::class)

package com.darkrockstudios.texteditor.dragdrop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.domDataTransferOrNull
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.clipboard.parsePasteHtml
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.platformKeyBindings
import org.w3c.dom.DataTransfer
import kotlin.js.ExperimentalWasmJsInterop

/*
 * The browser starts a drag itself, from a press on the canvas (which Compose makes
 * draggable) that moves a few pixels, and Compose asks the editor for it in `dragstart`;
 * the editor gives one only for a press it holds inside the selection.
 *
 * Compose's `DragAndDropEvent` here carries neither the DOM event nor a position the
 * editor can read, and at a drop it carries the last drag Compose started rather than
 * the one dropped when that one never landed on the canvas. So a window listener records
 * each drag event in the capture phase, before the canvas sees it, and the functions
 * below read the one being dispatched. The canvas sits in a shadow root, where
 * `window.event` is unset. Compose does not tell a target that the drag left the canvas
 * or ended, so the listener ends the drop carets then ([DropCarets]).
 *
 * Compose ignores the first `dragenter` after a `dragstart`, meaning its own drag's,
 * but keeps ignoring it after a `dragstart` it refused, which every mouse selection on the
 * canvas fires; the next drag from outside would then never be taken. A refused
 * `dragstart` on a Compose canvas is followed by a `dragenter` there that Compose drops.
 */

private const val DRAG_ID_TYPE = "application/x-compose-text-editor-drag-id"

/** The drag event the canvas is handling now, recorded at the window before it gets there. */
private var currentDragEvent: JsAny? = null

/** Where [currentDragEvent] is from its canvas's corner, in CSS pixels, or null off a canvas. */
private var currentDragOffset: Offset? = null

/** The text a `dragstart` this editor answered offers as `text/plain`, which Compose clears after asking. */
private var pendingPlainText: String? = null

/** Reading this installs the window listeners, so an editor reads it as it is created. */
internal actual val platformDragsText: Boolean = run {
	listenForDragEvents(
		onCapture = { event, x, y ->
			currentDragEvent = event
			currentDragOffset = if (x.isNaN() || y.isNaN()) null else Offset(x.toFloat(), y.toFloat())
			when (eventType(event)) {
				// A text this editor offered that never reached the bubble listener is stale.
				"dragstart" -> pendingPlainText = null
				"dragleave" -> if (currentDragOffset != null) DropCarets.endAll()
			}
		},
		onDragStartBubbled = { event ->
			val text = pendingPlainText
			pendingPlainText = null
			text?.let { setEventData(event, "text/plain", it) }
			if (eventDefaultPrevented(event)) clearComposeDragStart(event)
			text != null
		},
		onDragEnded = {
			currentDragEvent = null
			currentDragOffset = null
			DropCarets.endAll()
		},
	)
	true
}

/**
 * The text, its markup and the drag id, both on the `dragstart` event, which is what
 * leaves the page, and in a `DataTransfer` of its own, which Compose hands back at a
 * drop on the same canvas. A drop elsewhere copies, whatever effect it reports: Compose
 * reports every drop on the page as a move, a target that took nothing included, so
 * only a drop back into this editor moves, and [onEnded] has nothing to do.
 */
internal actual fun textDragTransferData(
	text: AnnotatedString,
	html: String?,
	dragId: Long,
	styles: RichTextStyles,
	allowMove: Boolean,
	onEnded: (moved: Boolean) -> Unit,
): DragAndDropTransferData? {
	val own = newDataTransfer()
	fill(own, text.text, html, dragId)
	currentDragEvent?.takeIf { eventType(it) == "dragstart" }?.let { start ->
		val transfer = eventDataTransfer(start) ?: return@let
		fill(transfer, text.text, html, dragId)
		// Compose proposes a move for every drop on the page, which a copy-only drag would
		// refuse, a read-only editor's dropped into another editor included.
		setEffectAllowed(transfer, "copyMove")
		pendingPlainText = text.text
	}
	return DragAndDropTransferData(own.unsafeCast<DataTransfer>())
}

private fun fill(transfer: JsAny, text: String, html: String?, dragId: Long) {
	setTransferData(transfer, "text/plain", text)
	if (html != null) setTransferData(transfer, "text/html", html)
	setTransferData(transfer, DRAG_ID_TYPE, dragId.toString())
}

/** The dispatched event's, which at a drop is the drag dropped; Compose's may be an earlier drag's. */
private val DragAndDropEvent.dataTransfer: JsAny?
	get() = currentDragEvent?.let(::eventDataTransfer) ?: transferData?.domDataTransferOrNull

/** Readable only at the drop; until then a drag from elsewhere answers empty. */
internal actual fun DragAndDropEvent.dragId(): Long? =
	dataTransfer?.let { getTransferData(it, DRAG_ID_TYPE) }?.toLongOrNull()

/** The types, unlike the data, are there from the drag's start. */
internal actual fun DragAndDropEvent.carriesText(): Boolean {
	val transfer = dataTransfer ?: return false
	return hasType(transfer, "text/plain") || hasType(transfer, "text/html")
}

/**
 * The markup, or the text where there is none. An [ownDrag] takes its markup only when
 * it re-parses to the text.
 */
internal actual fun DragAndDropEvent.droppedText(
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
	ownDrag: Boolean,
	target: DelegatableNode?,
): DroppedText? {
	val transfer = dataTransfer ?: return null
	val plain = getTransferData(transfer, "text/plain")?.takeIf { it.isNotEmpty() }
	val html = getTransferData(transfer, "text/html")?.takeIf { it.isNotEmpty() }
	val document = html
		?.let { parsePasteHtml(it, styles, allowedLinkSchemes) }
		?.takeIf { !ownDrag || it.text.text == plain }
	val text = document?.text ?: plain?.let(::AnnotatedString) ?: return null
	return DroppedText(text, html.takeIf { document != null }, document)
}

/** Compose's own reckoning: the CSS pixels from the canvas's corner, scaled by the density. */
internal actual fun DragAndDropEvent.pointerInRoot(density: Density): Offset? =
	currentDragOffset?.times(density.density)

/** Option on Apple systems and Ctrl elsewhere, as the browsers' own text fields copy. */
internal actual fun DragAndDropEvent.requestsCopy(): Boolean {
	val event = currentDragEvent ?: return false
	return if (platformKeyBindings() === MacKeyBindings) altKey(event) else ctrlKey(event)
}

/**
 * [onCapture] hears each drag event with the pointer's place from the corner of the
 * canvas it is aimed at (inside the border, as `offsetX` is), NaN when it is not aimed at
 * one; [onDragEnded] hears a drop or a drag's end once the page has handled it. A drag
 * this editor started ([onDragStartBubbled] answers true) shows a transparent picture,
 * as Compose's own 1 by 1 one is, where Compose leaves the browser's picture of the whole
 * canvas because no target took the drag at its start (a read-only editor's).
 */
private fun listenForDragEvents(
	onCapture: (JsAny, Double, Double) -> Unit,
	onDragStartBubbled: (JsAny) -> Boolean,
	onDragEnded: () -> Unit,
): Unit = js(
	"""{
		const blank = new Image(1, 1);
		blank.src = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
		for (const type of ['dragstart', 'dragenter', 'dragover', 'dragleave', 'drop']) {
			window.addEventListener(type, (event) => {
				const target = event.composedPath()[0];
				if (target instanceof HTMLCanvasElement) {
					const rect = target.getBoundingClientRect();
					onCapture(event, event.clientX - rect.left - target.clientLeft, event.clientY - rect.top - target.clientTop);
				} else {
					onCapture(event, NaN, NaN);
				}
			}, true);
		}
		window.addEventListener('dragstart', (event) => {
			if (onDragStartBubbled(event) && event.dataTransfer) event.dataTransfer.setDragImage(blank, 0, 0);
		});
		window.addEventListener('drop', () => onDragEnded());
		window.addEventListener('dragend', () => onDragEnded());
	}"""
)

private fun newDataTransfer(): JsAny = js("new DataTransfer()")

private fun eventDefaultPrevented(event: JsAny): Boolean = js("event.defaultPrevented")

private fun clearComposeDragStart(event: JsAny): Unit = js(
	"""{
		const target = event.composedPath()[0];
		if (target instanceof HTMLCanvasElement && target.getRootNode() instanceof ShadowRoot) {
			target.dispatchEvent(new DragEvent('dragenter', { bubbles: true, composed: true }));
		}
	}"""
)

private fun eventType(event: JsAny): String = js("event.type")

private fun eventDataTransfer(event: JsAny): JsAny? = js("event.dataTransfer")

private fun setEventData(event: JsAny, type: String, value: String): Unit =
	js("{ if (event.dataTransfer) event.dataTransfer.setData(type, value); }")

private fun setEffectAllowed(transfer: JsAny, effect: String): Unit = js("transfer.effectAllowed = effect")

private fun setTransferData(transfer: JsAny, type: String, value: String): Unit = js("transfer.setData(type, value)")

private fun getTransferData(transfer: JsAny, type: String): String? = js("transfer.getData(type)")

private fun hasType(transfer: JsAny, type: String): Boolean = js("Array.from(transfer.types).includes(type)")

private fun altKey(event: JsAny): Boolean = js("Boolean(event.altKey)")

private fun ctrlKey(event: JsAny): Boolean = js("Boolean(event.ctrlKey)")
