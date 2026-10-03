package com.darkrockstudios.texteditor.sample

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Markdown import on a real Android runtime. Common code compiled against a recent
 * android.jar can bind a collection call to a java.util member newer than minSdk
 * (List.removeLast arrived in API 35), which no JVM test sees; the emulator CI job
 * runs below that level.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownImportSmokeTest {
	@get:Rule
	val compose = createAndroidComposeRule<ComponentActivity>()

	@Test
	fun markdownImportsOnThisApiLevel() {
		lateinit var markdown: MarkdownExtension
		var imported = false
		compose.setContent {
			val state = rememberTextEditorState()
			markdown = remember { state.withMarkdown() }
			LaunchedEffect(Unit) {
				markdown.importMarkdown("# Title\n\nSome **bold**, *italic* and <u>underlined</u> text.")
				imported = true
			}
			BasicTextEditor(state = state, modifier = Modifier.fillMaxSize())
		}
		compose.waitUntil(timeoutMillis = 10_000) { imported }

		assertEquals(
			"Title\nSome bold, italic and underlined text.",
			markdown.editorState.getAllText().text,
		)
	}
}
