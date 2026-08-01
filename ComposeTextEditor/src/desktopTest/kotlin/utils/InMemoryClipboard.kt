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

	/**
	 * Puts [clipEntry] on the clipboard the way another application would, without
	 * going through the suspend API, so a test can stage clipboard contents from a
	 * non-suspending scope.
	 */
	fun seed(clipEntry: ClipEntry?) {
		entry = clipEntry
	}

	/** Offers [value] on the string flavor only, as an external application would. */
	fun setPlainText(value: String) {
		seed(ClipEntry(java.awt.datatransfer.StringSelection(value)))
	}
}
