package com.darkrockstudios.texteditor.dragdrop

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.DragEvent
import android.view.View
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.requireView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

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
 * drops them. An [ownDrag] takes its markup only when it re-parses to the text. An item
 * that carries only a content URI (a text file dragged from Files) is read through
 * [target]'s activity, under the permissions the drop grants, as `TextView` reads one.
 */
internal actual fun DragAndDropEvent.droppedText(
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
	ownDrag: Boolean,
	target: DelegatableNode?,
): DroppedText? = toAndroidDragEvent().droppedText(styles, allowedLinkSchemes, ownDrag) { target?.activity() }

internal fun DragEvent.droppedText(
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
	ownDrag: Boolean,
	activity: () -> Activity?,
): DroppedText? {
	val clip = clipData ?: return null
	val grant = lazy { activity()?.let { it to it.requestDragAndDropPermissions(this) } }
	var budget = MAX_DROPPED_FILE_BYTES
	try {
		return clip.droppedText(styles, allowedLinkSchemes, ownDrag) { item ->
			val (context, permissions) = grant.value ?: return@droppedText null
			// Only what the drag grants is read, never through this app's own access.
			if (permissions == null) return@droppedText null
			item.uriContent(context, clipDescription, budget)?.also { budget -= it.bytes }
		}
	} finally {
		if (grant.isInitialized()) grant.value?.second?.release()
	}
}

private fun ClipData.droppedText(
	styles: RichTextStyles,
	allowedLinkSchemes: Set<String>,
	ownDrag: Boolean,
	readUri: (ClipData.Item) -> UriContent?,
): DroppedText? {
	val items = (0 until itemCount).map(::getItemAt)
	var firstHtml: String? = null
	val styled = items.mapIndexedNotNull { index, item ->
		val text = item.text?.toString()
		val html = item.htmlText?.takeIf { it.isNotEmpty() }
		val fromUri = if (text == null && html == null && item.uri != null) readUri(item) else null
		val markup = html ?: fromUri?.html
		if (index == 0) firstHtml = markup
		markup
			?.toAnnotatedStringFromHtml(styles, allowedLinkSchemes)
			?.takeIf { it.text.isNotEmpty() && (!ownDrag || it.text == text) }
			?: (text ?: fromUri?.text)?.let(::AnnotatedString)
	}
	if (styled.isEmpty()) return null
	val joined = styled.singleOrNull() ?: buildAnnotatedString {
		styled.forEachIndexed { index, text ->
			if (index > 0) append('\n')
			append(text)
		}
	}
	return DroppedText(joined, firstHtml)
}

/** What a dropped file held, its markup for an HTML file, else its text, and its size. */
internal class UriContent(val text: String?, val html: String?, val bytes: Int)

/**
 * The content of the file [ClipData.Item.getUri] names, when it is text no larger than
 * [budget] bytes: read whole, at the drop, as `TextView` reads one. Null for another kind
 * of file, or one that cannot be read. [description] gives the type where the provider
 * does not.
 */
internal fun ClipData.Item.uriContent(context: Context, description: ClipDescription?, budget: Int): UriContent? {
	val uri = uri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } ?: return null
	return try {
		val resolver = context.contentResolver
		val type = resolver.getType(uri)
			?: description?.takeIf { it.mimeTypeCount == 1 }?.getMimeType(0)
			?: return null
		val mime = type.substringBefore(';').trim().lowercase()
		if (!mime.startsWith("text/")) return null
		val bytes = resolver.openInputStream(uri)?.use { it.readAtMost(budget) } ?: return null
		if (bytes.size > budget) {
			Log.w(TAG, "A drop's files over $MAX_DROPPED_FILE_BYTES bytes were not all read")
			return null
		}
		val content = bytes.decodeText(charsetOf(type)).ifEmpty { return null }
		if (mime == "text/html") UriContent(null, content, bytes.size) else UriContent(content, null, bytes.size)
	} catch (e: Exception) {
		// A provider that refuses the read (no permission, a file gone) drops nothing.
		Log.w(TAG, "Could not read a dropped file", e)
		null
	}
}

/** The most a drop reads of the files it carries: they are read at the drop, on the main thread. */
internal const val MAX_DROPPED_FILE_BYTES = 1 shl 20

/** Up to [limit] bytes, and one more when there are more. */
private fun InputStream.readAtMost(limit: Int): ByteArray {
	val out = ByteArrayOutputStream()
	val buffer = ByteArray(8192)
	while (out.size() <= limit) {
		val read = read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
		if (read < 0) break
		out.write(buffer, 0, read)
	}
	return out.toByteArray()
}

/** The charset a MIME type's parameters name, or null. */
private fun charsetOf(type: String): Charset? = type.split(';').drop(1)
	.map { it.trim() }
	.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
	?.substringAfter('=')?.trim('"', ' ')
	?.let { runCatching { Charset.forName(it) }.getOrNull() }

/** [this] as text: by its byte order mark where it has one, else [charset], else UTF-8. */
private fun ByteArray.decodeText(charset: Charset?): String {
	fun at(index: Int) = getOrNull(index)?.toInt()?.and(0xFF)
	return when {
		at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> String(this, 3, size - 3, Charsets.UTF_8)
		at(0) == 0xFE && at(1) == 0xFF -> String(this, 2, size - 2, Charsets.UTF_16BE)
		at(0) == 0xFF && at(1) == 0xFE -> String(this, 2, size - 2, Charsets.UTF_16LE)
		else -> String(this, charset ?: Charsets.UTF_8)
	}
}

/** The activity [this] node's view is in, which grants a drop's URI permissions. */
private fun DelegatableNode.activity(): Activity? {
	var context: Context? = requireView().context
	while (context is ContextWrapper) {
		if (context is Activity) return context
		context = context.baseContext
	}
	return null
}

private const val TAG = "PlatformTextDrag"

/** The event's position is in the Compose view's pixels, which is the root. */
internal actual fun DragAndDropEvent.pointerInRoot(density: Density): Offset? {
	val event = toAndroidDragEvent()
	return Offset(event.x, event.y)
}

internal actual fun DragAndDropEvent.requestsCopy(): Boolean = false
