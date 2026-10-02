package com.darkrockstudios.texteditor.markdown

data class MarkdownStyles(
	private val config: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
) {
	val BASE_TEXT = config.defaultTextStyle
	val BOLD = config.boldStyle
	val ITALICS = config.italicStyle
	val CODE = config.codeStyle
	val LINK = config.linkStyle
	val STRIKETHROUGH = config.strikethroughStyle
	val UNDERLINE = config.underlineStyle
	val HIGHLIGHT = config.highlightStyle
	val BLOCKQUOTE = config.blockquoteStyle

	fun header(level: Int) = config.getHeaderStyle(level)
}