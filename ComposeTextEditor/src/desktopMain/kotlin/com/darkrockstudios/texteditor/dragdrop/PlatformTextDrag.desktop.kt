package com.darkrockstudios.texteditor.dragdrop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.clipboard.AnnotatedStringTransferable
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.clipboard.offersText
import com.darkrockstudios.texteditor.clipboard.readPaste
import com.darkrockstudios.texteditor.RichTextStyles
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent

internal actual val platformDragsText: Boolean = true

/**
 * The same flavors a copy offers, the drag id riding as the copy id. AWT serializes
 * object flavors for a drop even within the process, which the `AnnotatedString`
 * flavor cannot survive, so drops take the markup.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun textDragTransferData(
	text: AnnotatedString,
	html: String?,
	dragId: Long,
	styles: RichTextStyles,
	allowMove: Boolean,
	onEnded: (moved: Boolean) -> Unit,
): DragAndDropTransferData? = DragAndDropTransferData(
	transferable = DragAndDropTransferable(AnnotatedStringTransferable(text, styles, copyId = dragId, blockHtml = html)),
	supportedActions = if (allowMove) {
		listOf(DragAndDropTransferAction.Move, DragAndDropTransferAction.Copy)
	} else {
		listOf(DragAndDropTransferAction.Copy)
	},
	onTransferCompleted = { action -> onEnded(action == DragAndDropTransferAction.Move) },
)

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun DragAndDropEvent.dragId(): Long? = runCatching {
	val transferable = awtTransferable
	if (!transferable.isDataFlavorSupported(ClipboardHelper.copyIdFlavor)) return null
	transferable.getTransferData(ClipboardHelper.copyIdFlavor) as? Long
}.getOrNull()

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun DragAndDropEvent.carriesText(): Boolean =
	runCatching { awtTransferable.offersText() }.getOrDefault(false)

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun DragAndDropEvent.droppedText(
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
	ownDrag: Boolean,
	target: DelegatableNode?,
): DroppedText? {
	val transferable = runCatching { awtTransferable }.getOrNull() ?: return null
	val paste = transferable.readPaste(styles, allowedLinkSchemes) ?: return null
	return DroppedText(paste.text, paste.html, paste.document)
}

/** AWT reports the location in the root's points, which Compose scales by the density. */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun DragAndDropEvent.pointerInRoot(density: Density): Offset? {
	val location = when (val native = nativeEvent) {
		is DropTargetDragEvent -> native.location
		is DropTargetDropEvent -> native.location
		else -> return null
	}
	return Offset(location.x * density.density, location.y * density.density)
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun DragAndDropEvent.requestsCopy(): Boolean = action == DragAndDropTransferAction.Copy
