package com.darkrockstudios.texteditor.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import com.darkrockstudios.texteditor.input.EditorActionSpec
import com.darkrockstudios.texteditor.input.EditorCommand
import com.darkrockstudios.texteditor.input.KeyBindings
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.input.isCtrlShortcut
import com.darkrockstudios.texteditor.input.platformKeyBindings
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getSpanStylesInRange

/**
 * Bold as an editor action rather than a toolbar-only function, which is what
 * lets a key chord reach it. Any id works as long as it does not collide with
 * the `editor.` namespace the built-ins use.
 */
val ToggleBold = EditorCommand.Action("sample.toggleBold", isEdit = true)

/**
 * Registers [ToggleBold] on [markdown]'s state and returns bindings that add
 * Ctrl+B (Cmd+B on macOS) on top of the platform's own. Delegating the chords we
 * do not claim is what keeps copy, paste, undo and every motion working.
 */
@Composable
fun rememberBoldShortcut(markdown: MarkdownExtension): KeyBindings = remember(markdown) {
	markdown.editorState.actions.register(
		EditorActionSpec(ToggleBold) { it.state.toggleBold(markdown) }
	)

	val platform = platformKeyBindings()
	// macOS puts shortcuts on Cmd and reserves Ctrl for its Emacs-style bindings,
	// so which modifier means "shortcut" is the platform's call, not ours.
	val isMac = platform === MacKeyBindings
	KeyBindings { event ->
		val shortcut = if (isMac) event.isMetaPressed else event.isCtrlShortcut
		if (event.key == Key.B && shortcut) ToggleBold else platform.commandFor(event)
	}
}

private fun TextEditorState.toggleBold(markdown: MarkdownExtension) {
	val bold = markdown.markdownStyles.BOLD
	val selection = selector.selection
	if (selection == null) {
		cursor.toggleStyle(bold)
	} else if (getSpanStylesInRange(selection).contains(bold)) {
		removeStyleSpan(selection, bold)
	} else {
		addStyleSpan(selection, bold)
	}
}
