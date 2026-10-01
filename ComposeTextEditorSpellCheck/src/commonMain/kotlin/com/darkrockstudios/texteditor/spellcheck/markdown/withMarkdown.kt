package com.darkrockstudios.texteditor.spellcheck.markdown

import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.ImageProvider
import com.darkrockstudios.texteditor.spellcheck.SpellCheckState

/**
 * Enable markdown import and export on a spell-checked editor.
 *
 * Builds a [MarkdownExtension] over this [SpellCheckState]'s underlying text state, letting the
 * editor parse markdown into rich text and serialize it back out while spell checking remains active.
 *
 * @param initialConfiguration The initial [MarkdownConfiguration], the markdown syntax choices.
 * @param imageProvider Optional [ImageProvider] used to resolve images referenced in markdown.
 * @return A [MarkdownExtension] bound to this editor's text state.
 */
fun SpellCheckState.withMarkdown(
	initialConfiguration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT,
	imageProvider: ImageProvider? = null,
): MarkdownExtension {
	return MarkdownExtension(textState, initialConfiguration, imageProvider)
}
