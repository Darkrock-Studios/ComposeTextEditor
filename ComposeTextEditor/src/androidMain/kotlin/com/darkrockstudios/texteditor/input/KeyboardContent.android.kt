package com.darkrockstudios.texteditor.input

import android.os.Bundle
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Takes the content a keyboard commits that is not text: a GIF, a sticker, an image from
 * its clipboard (`InputConnection.commitContent`). Set one on
 * [TextEditorState.keyboardContentReceiver].
 *
 * @param mimeTypes What the keyboard is told the editor accepts, such as `image/gif` and
 *   `image/png`, or a wildcard subtype for every image. Keyboards offer only content that
 *   matches.
 * @param onReceive Called on the main thread with what the keyboard committed: its
 *   [InputContentInfo.getContentUri], its MIME types in [InputContentInfo.getDescription],
 *   and an optional [InputContentInfo.getLinkUri] to the content on the web. When the
 *   keyboard asked for it, read access to the URI is already granted; it lasts until
 *   [InputContentInfo.releasePermission] is called or the [InputContentInfo] is garbage
 *   collected, so keep it until the content has been read. `extras` are the keyboard's
 *   own. Return true if the host took the content; false refuses it, and the keyboard
 *   may fall back to something else, such as committing the link as text. Inserting the
 *   content (an image block, a link) is the host's work.
 */
class KeyboardContentReceiver(
	val mimeTypes: List<String>,
	val onReceive: (content: InputContentInfo, extras: Bundle?) -> Boolean,
) {
	init {
		require(mimeTypes.isNotEmpty()) { "A receiver accepts at least one MIME type" }
	}
}

/**
 * What this state does with content a keyboard commits that is not text, or null to
 * refuse it, the default. Only while one is set does the keyboard hear of any MIME types,
 * so it offers no GIFs or stickers the host cannot take. Setting or clearing one restarts
 * input, so a keyboard already open learns of the change. Android only.
 */
var TextEditorState.keyboardContentReceiver: KeyboardContentReceiver?
	get() = platformExtensions.keyboardContentReceiver
	set(value) {
		platformExtensions.keyboardContentReceiver = value
	}

/**
 * Hands committed content to the state's receiver. A grant the keyboard asks for is taken
 * before the host sees the content, as `InputConnectionCompat` does, and given back if the
 * host refuses it.
 */
internal fun TextEditorState.receiveKeyboardContent(content: InputContentInfo, flags: Int, extras: Bundle?): Boolean {
	val receiver = keyboardContentReceiver ?: return false
	val grant = flags and InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0
	if (grant) {
		try {
			content.requestPermission()
		} catch (_: Exception) {
			return false
		}
	}
	val taken = receiver.onReceive(content, extras)
	if (!taken && grant) content.releasePermission()
	return taken
}
