package com.darkrockstudios.texteditor.sample

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.darkrockstudios.texteditor.input.KeyboardContentReceiver
import com.darkrockstudios.texteditor.input.keyboardContentReceiver
import com.darkrockstudios.texteditor.richstyle.IMAGE_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.InMemoryImageProvider
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [App] with the Android additions to its editors. */
@Composable
fun AndroidApp() {
	CompositionLocalProvider(LocalEditorPlatformSetup provides { state, images -> KeyboardImages(state, images) }) {
		App()
	}
}

/**
 * Takes the images a keyboard commits (Gboard's GIFs and stickers, its clipboard's
 * screenshots) and inserts each as an image block at the caret. The bitmaps are held in
 * memory only, so a block the Empty demo restores after a rotation shows its placeholder.
 */
@Composable
private fun KeyboardImages(state: TextEditorState, images: InMemoryImageProvider) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	DisposableEffect(state, images) {
		val receiver = KeyboardContentReceiver(listOf("image/*")) { content, _ ->
			scope.launch {
				val uri = content.contentUri
				// The grant lasts as long as `content` does; it is given back once the bytes are read.
				val bitmap = try {
					withContext(Dispatchers.IO) {
						context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
					}
				} catch (e: Exception) {
					Log.w("KeyboardImages", "Could not read $uri", e)
					null
				} finally {
					content.releasePermission()
				}
				if (bitmap == null) return@launch
				val source = uri.toString()
				images.put(source, bitmap.asImageBitmap())
				state.insertImageBlock(ImageBlockSpanStyle(source, content.description.label?.toString().orEmpty(), images))
			}
			true
		}
		state.keyboardContentReceiver = receiver
		onDispose {
			if (state.keyboardContentReceiver === receiver) state.keyboardContentReceiver = null
		}
	}
}

/** Puts [image] on a line of its own at the caret, starting one if the caret is mid-line. */
private fun TextEditorState.insertImageBlock(image: ImageBlockSpanStyle) = editGroup {
	if (cursorPosition.char > 0) insertNewlineAtCursor()
	val line = cursorPosition.line
	insertStringAtCursor(IMAGE_PLACEHOLDER)
	insertNewlineAtCursor()
	applyDocumentBlocks(imageLines = mapOf(line to image))
}
