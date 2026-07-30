package utils

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard

/**
 * Isolated in-memory [Clipboard] for tests. Keeps copy/paste specs off the real
 * OS clipboard, which is shared global state and flaky in headless CI.
 */
@OptIn(ExperimentalComposeUiApi::class)
class InMemoryClipboard : Clipboard {
	private var entry: ClipEntry? = null

	override suspend fun getClipEntry(): ClipEntry? = entry

	override suspend fun setClipEntry(clipEntry: ClipEntry?) {
		entry = clipEntry
	}

	override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("in-memory-test")

	val isEmpty: Boolean get() = entry == null
}
