package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * The info string of a fenced code block (` ```kotlin `), carried on every
 * line of a run of [CodeFenceSpanStyle] lines, like the fence marker itself,
 * and written back by the markdown exporter from the run's first line. It
 * paints nothing. Line-block normalization gives every line of a run the
 * run's language and drops a span off a fence or one that is not [isWritable],
 * so the model never holds a language the text form cannot express. Set a
 * run's language through `MarkdownExtension.setCodeFenceLanguage`, which
 * replaces the whole run's spans in one undo step.
 *
 * [language] is the whole info string as written after the fence marker, of
 * which the first word is the language by convention.
 */
data class CodeFenceLanguageSpanStyle(val language: String) : RichSpanStyle {
	override val stickyAtStart: Boolean get() = true
	override val isHitTestable: Boolean get() = false

	companion object {
		/** A backtick ends a backtick fence's info string and a line break ends its line. */
		fun isWritable(language: String): Boolean =
			language.none { it == '`' || it == '\n' || it == '\r' }
	}

	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
	}
}
