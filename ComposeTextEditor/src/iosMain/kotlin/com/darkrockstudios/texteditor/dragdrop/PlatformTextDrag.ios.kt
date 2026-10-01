package com.darkrockstudios.texteditor.dragdrop

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.RichTextStyles

// Here the editor neither starts a drag nor accepts a drop (roadmap 6.20, 6.46). Compose's
// iOS `DragAndDropEvent` keeps where a drop lands internal, and its drag interaction
// starts drags itself from a long press that races the edit menu, and only as copies.

internal actual val platformDragsText: Boolean = false

internal actual fun textDragTransferData(
	text: AnnotatedString,
	html: String?,
	dragId: Long,
	styles: RichTextStyles,
	allowMove: Boolean,
	onEnded: (moved: Boolean) -> Unit,
): DragAndDropTransferData? = null

internal actual fun DragAndDropEvent.dragId(): Long? = null

internal actual fun DragAndDropEvent.carriesText(): Boolean = false

internal actual fun DragAndDropEvent.droppedText(
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
	ownDrag: Boolean,
	target: DelegatableNode?,
): DroppedText? = null

internal actual fun DragAndDropEvent.pointerInRoot(density: Density): Offset? = null

internal actual fun DragAndDropEvent.requestsCopy(): Boolean = false
