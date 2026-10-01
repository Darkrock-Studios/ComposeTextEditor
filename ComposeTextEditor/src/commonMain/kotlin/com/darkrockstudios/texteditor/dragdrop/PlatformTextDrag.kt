package com.darkrockstudios.texteditor.dragdrop

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.html.HtmlDocument

/**
 * Text a drag carried: its styled characters and, when they came from it, its markup and
 * [document], the markup as parsed for them.
 */
internal class DroppedText(val text: AnnotatedString, val html: String?, val document: HtmlDocument? = null)

/**
 * The editors drawing a drop caret, for a platform that does not tell a drop target its
 * drag left or ended (the web) to end them all.
 */
internal object DropCarets {
	private val showing = mutableSetOf<TextDragAndDrop>()

	fun shown(dragAndDrop: TextDragAndDrop) {
		showing += dragAndDrop
	}

	fun hidden(dragAndDrop: TextDragAndDrop) {
		showing -= dragAndDrop
	}

	fun endAll() {
		showing.toList().forEach { it.endHover() }
	}
}

/** Whether this platform drags text out of the editor and drops it in. */
internal expect val platformDragsText: Boolean

/**
 * What a drag of [text] out of the editor carries: the text, [html] beside it where
 * given, [dragId]
 * to tell the drag apart at a drop, and the move and copy actions ([allowMove] false
 * offers copy alone). [onEnded] hears whether the drag ended as a move. Null where the
 * platform cannot start one.
 */
internal expect fun textDragTransferData(
	text: AnnotatedString,
	html: String?,
	dragId: Long,
	styles: RichTextStyles,
	allowMove: Boolean,
	onEnded: (moved: Boolean) -> Unit,
): DragAndDropTransferData?

/** The [textDragTransferData] drag id this drag carries, or null for a drag from elsewhere. */
internal expect fun DragAndDropEvent.dragId(): Long?

/** Whether this drag carries text the editor can take. */
internal expect fun DragAndDropEvent.carriesText(): Boolean

/**
 * The text this drag carries, read at the drop. An [ownDrag], this editor's own, must
 * drop the characters it dragged, which its source's rich spans are matched against.
 * [target] is the node taking the drop, for a platform that reads what the drag carries
 * through the window it lands in (Android's content URIs).
 */
internal expect fun DragAndDropEvent.droppedText(
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
	ownDrag: Boolean,
	target: DelegatableNode?,
): DroppedText?

/** Where the pointer is, in the root's pixels, or null where the platform does not say. */
internal expect fun DragAndDropEvent.pointerInRoot(density: Density): Offset?

/** Whether the user asked for a copy (a modifier key held) rather than a move. */
internal expect fun DragAndDropEvent.requestsCopy(): Boolean
