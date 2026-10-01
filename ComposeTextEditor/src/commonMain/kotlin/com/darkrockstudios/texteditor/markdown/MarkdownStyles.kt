package com.darkrockstudios.texteditor.markdown

import com.darkrockstudios.texteditor.RichTextStyles

/** The styles under their old names; read [RichTextStyles] directly. */
@Deprecated("Read the styles from TextEditorState.richTextStyles (RichTextStyles).")
data class MarkdownStyles(
	private val styles: RichTextStyles = RichTextStyles.DEFAULT
) {
	val BASE_TEXT = styles.defaultTextStyle
	val BOLD = styles.boldStyle
	val ITALICS = styles.italicStyle
	val CODE = styles.codeStyle
	val LINK = styles.linkStyle
	val STRIKETHROUGH = styles.strikethroughStyle
	val UNDERLINE = styles.underlineStyle
	val HIGHLIGHT = styles.highlightStyle
	val BLOCKQUOTE = styles.blockquoteStyle

	fun header(level: Int) = styles.getHeaderStyle(level)
}
