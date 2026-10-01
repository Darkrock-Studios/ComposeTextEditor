package com.darkrockstudios.texteditor.dragdrop

import android.content.ClipData
import android.view.View
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml

internal actual val platformDragsText: Boolean = true

/**
 * A global drag of the text and its markup, as `TextView` starts one, with the drag id
 * as the local state, which only this process sees. Android has no move to another view
 * or app: a drop elsewhere copies, and only a drop back into this editor moves, so
 * [allowMove] and [onEnded] have nothing to do here.
 */
internal actual fun textDragTransferData(
	text: AnnotatedString,
	html: String?,
	dragId: Long,
	styles: RichTextStyles,
	allowMove: Boolean,
	onEnded: (moved: Boolean) -> Unit,
): DragAndDropTransferData? = DragAndDropTransferData(
	clipData = if (html != null) ClipData.newHtmlText("text", text.text, html) else ClipData.newPlainText("text", text.text),
	localState = dragId,
	flags = View.DRAG_FLAG_GLOBAL,
)

internal actual fun DragAndDropEvent.dragId(): Long? = toAndroidDragEvent().localState as? Long

/** The description, unlike the clip, is there for every event of the drag. */
internal actual fun DragAndDropEvent.carriesText(): Boolean =
	toAndroidDragEvent().clipDescription?.hasMimeType("text/*") == true

/**
 * Each item's markup, or its text where it has none, one item per line as `TextView`
 * drops them. An [ownDrag] takes its markup only when it re-parses to the text.
 */
internal actual fun DragAndDropEvent.droppedText(
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
	ownDrag: Boolean,
): DroppedText? {
	val clip = toAndroidDragEvent().clipData ?: return null
	val items = (0 until clip.itemCount).map(clip::getItemAt)
	val styled = items.mapNotNull { item ->
		val text = item.text?.toString()
		item.htmlText
			?.toAnnotatedStringFromHtml(styles, allowedLinkSchemes)
			?.takeIf { it.text.isNotEmpty() && (!ownDrag || it.text == text) }
			?: text?.let(::AnnotatedString)
	}
	if (styled.isEmpty()) return null
	val joined = styled.singleOrNull() ?: buildAnnotatedString {
		styled.forEachIndexed { index, text ->
			if (index > 0) append('\n')
			append(text)
		}
	}
	return DroppedText(joined, items.first().htmlText?.takeIf { it.isNotEmpty() })
}

/** The event's position is in the Compose view's pixels, which is the root. */
internal actual fun DragAndDropEvent.pointerInRoot(density: Density): Offset? {
	val event = toAndroidDragEvent()
	return Offset(event.x, event.y)
}

internal actual fun DragAndDropEvent.requestsCopy(): Boolean = false
