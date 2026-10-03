package osinput

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.rememberTextEditorStyle
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import utils.TestFontFamily
import java.io.File

/**
 * A window holding one focused editor, for the nightly real-input job
 * (`testUtils/osInput/drive.sh`): a driver presses real keys on the display, and this
 * writes the document to `<dir>/text` whenever it changes, and `<dir>/ready` once the
 * editor has focus, since nothing outside the process can read the editor.
 *
 * Run with `./gradlew :ComposeTextEditor:runOsInputProbe --args=<dir>`.
 */
fun main(args: Array<String>) {
	val dir = File(requireNotNull(args.firstOrNull()) { "usage: OsInputProbe <output dir>" }).apply { mkdirs() }
	val text = File(dir, "text")
	val ready = File(dir, "ready")
	text.delete()
	ready.delete()
	application {
		Window(
			onCloseRequest = ::exitApplication,
			title = "OsInputProbe",
			state = rememberWindowState(width = 640.dp, height = 400.dp, position = WindowPosition(0.dp, 0.dp)),
		) {
			val state = rememberTextEditorState()
			BasicTextEditor(
				state = state,
				modifier = Modifier.fillMaxSize(),
				autoFocus = true,
				style = rememberTextEditorStyle(textStyle = TextStyle(fontFamily = TestFontFamily)),
			)
			LaunchedEffect(state) {
				var written: String? = null
				var focused = false
				while (true) {
					val current = state.getAllText().text
					val focusedNow = !focused && state.hasFocus
					if (current != written || focusedNow) {
						withContext(Dispatchers.IO) {
							if (current != written) {
								// Written whole, then renamed, so a reader never sees half a file.
								val partial = File(dir, "text.partial")
								partial.writeText(current)
								partial.renameTo(text)
							}
							if (focusedNow) ready.writeText("focused\n")
						}
						written = current
						focused = focused || focusedNow
					}
					delay(50)
				}
			}
		}
	}
}
