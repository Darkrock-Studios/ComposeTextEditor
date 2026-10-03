package com.darkrockstudios.texteditor.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.darkrockstudios.texteditor.richstyle.InMemoryImageProvider
import com.darkrockstudios.texteditor.state.TextEditorState

/** What a platform's app adds to the rich text demos' editor; Android takes images from the keyboard. */
val LocalEditorPlatformSetup = staticCompositionLocalOf<@Composable (TextEditorState, InMemoryImageProvider) -> Unit> {
	{ _, _ -> }
}
