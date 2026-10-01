package com.darkrockstudios.texteditor.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import org.jetbrains.compose.ui.tooling.preview.Preview

val HIGHLIGHT = HighlightSpanStyle(Color(0x40FF0000))

enum class Destination {
	Menu,
	TextEditor,
	MarkdownEditor,
	EmptyTextEditor,
	SpellChecking,
	CodeEditor,
	FindDemo,
	RichTextView,
}

@Composable
@Preview
fun App() {
	val lightColorScheme = remember { lightColorScheme(primary = Color(0xFF1EB980)) }
	val darkColorScheme = remember { darkColorScheme(primary = Color(0xFF66ffc7)) }

	var colorScheme by remember { mutableStateOf(lightColorScheme) }
	var richTextStyles by remember { mutableStateOf(RichTextStyles.DEFAULT) }
	fun toggleDarkMode(on: Boolean) {
		colorScheme = if (on) {
			darkColorScheme
		} else {
			lightColorScheme
		}
		richTextStyles = if (on) {
			RichTextStyles.DEFAULT_DARK
		} else {
			RichTextStyles.DEFAULT
		}
	}

	MaterialTheme(colorScheme = colorScheme) {
		Surface {
			var curLocation by rememberSaveable { mutableStateOf(Destination.Menu) }

			fun navigateTo(destination: Destination) {
				curLocation = destination
			}

			when (curLocation) {
				Destination.Menu -> HomeMenu(
					navigateTo = ::navigateTo,
					isDarkMode = (colorScheme == darkColorScheme),
					toggleDarkMode = ::toggleDarkMode
				)

				Destination.TextEditor -> TextEditorDemoUi(
					navigateTo = ::navigateTo,
					demoContent = DemoContent.Rich,
					styles = richTextStyles,
				)

				Destination.MarkdownEditor -> TextEditorDemoUi(
					navigateTo = ::navigateTo,
					demoContent = DemoContent.Markdown,
					styles = richTextStyles,
				)

				Destination.EmptyTextEditor -> TextEditorDemoUi(
					navigateTo = ::navigateTo,
					demoContent = DemoContent.Empty,
					styles = richTextStyles,
				)

				Destination.SpellChecking -> SpellCheckingTextEditorDemoUi(
					navigateTo = ::navigateTo,
					styles = richTextStyles,
				)

				Destination.CodeEditor -> CodeEditorDemoUi(
					navigateTo = ::navigateTo,
				)

				Destination.FindDemo -> FindTextEditorDemoUi(
					navigateTo = ::navigateTo,
				)

				Destination.RichTextView -> RichTextViewDemoUi(
					navigateTo = ::navigateTo,
					styles = richTextStyles,
				)
			}
		}
	}
}
