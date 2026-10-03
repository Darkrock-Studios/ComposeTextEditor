package com.darkrockstudios.texteditor.dragdrop

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.RichTextStyles

/** Text a drag carried: its styled characters and, when it offered them, its markup. */
internal class DroppedText(val text: AnnotatedString, val html: String?)

/** Whether this platform drags text out of the editor and drops it in. */
internal expect val platformDragsText: Boolean

/**
 * What a drag of [text] out of the editor carries: the text, [html] beside it, [dragId]
 * to tell the drag apart at a drop, and the move and copy actions ([allowMove] false
 * offers copy alone). [onEnded] hears whether the drag ended as a move. Null where the
 * platform cannot start one.
 */
internal expect fun textDragTransferData(
	text: AnnotatedString,
	html: String,
	dragId: Long,
	styles: RichTextStyles,
	allowMove: Boolean,
	onEnded: (moved: Boolean) -> Unit,
): DragAndDropTransferData?

/** The [textDragTransferData] drag id this drag carries, or null for a drag from elsewhere. */
internal expect fun DragAndDropEvent.dragId(): Long?

/** Whether this drag carries text the editor can take. */
internal expect fun DragAndDropEvent.carriesText(): Boolean

/** The text this drag carries, read at the drop. */
internal expect fun DragAndDropEvent.droppedText(styles: RichTextStyles, allowedLinkSchemes: Set<String>): DroppedText?

/** Where the pointer is, in the root's pixels, or null where the platform does not say. */
internal expect fun DragAndDropEvent.pointerInRoot(density: Density): Offset?

/** Whether the user asked for a copy (a modifier key held) rather than a move. */
internal expect fun DragAndDropEvent.requestsCopy(): Boolean
