package clipboard

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.clipboard.readClipboardHtml
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull

/** AWT's `getContents` throws while another application holds the clipboard open (6.36). */
@OptIn(ExperimentalComposeUiApi::class)
class ClipboardReadFailureTest {

	private object BusyClipboard : Clipboard {
		override suspend fun getClipEntry(): ClipEntry? = throw IllegalStateException("cannot open system clipboard")
		override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
		override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("busy")
	}

	@Test
	fun `a clipboard that cannot be opened reads as nothing instead of throwing`() = runTest {
		assertNull(ClipboardHelper.getText(BusyClipboard))
		assertNull(ClipboardHelper.getPlainText(BusyClipboard))
		assertNull(ClipboardHelper.readCopyId(BusyClipboard))
		assertNull(readClipboardHtml(BusyClipboard))
	}
}
