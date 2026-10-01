package com.darkrockstudios.texteditor.markdown

/**
 * The markdown written for a highlight. Both forms are read on import.
 *
 * [DOUBLE_EQUALS] is `==text==`, the syntax Obsidian, Typora, iA Writer and
 * markdown-it's mark plugin share; [MARK_TAG] is the HTML `<mark>` every
 * CommonMark renderer displays, for documents that leave those editors.
 */
enum class HighlightSyntax { DOUBLE_EQUALS, MARK_TAG }

/**
 * What an editor line is in markdown terms.
 *
 * [BLANK_LINE]: a line is a paragraph. Export puts a blank line between
 * blocks (not between the items of one list or the lines of one fence), as
 * CommonMark needs to keep them apart, and writes an editor's own blank line
 * as one more; import takes one blank line after each block away again, so
 * the editor's round trip is exact and other renderers show every line as its
 * own paragraph. [NEWLINE]: a line is a source line, written and read as is;
 * other renderers then merge adjacent lines into one paragraph.
 */
enum class ParagraphSeparator { BLANK_LINE, NEWLINE }

/**
 * The markdown syntax choices: which of the forms CommonMark leaves open a
 * document is written in. The styles the document is rendered and recognised
 * with are the editor's
 * [RichTextStyles][com.darkrockstudios.texteditor.RichTextStyles], on
 * [TextEditorState.richTextStyles][com.darkrockstudios.texteditor.state.TextEditorState.richTextStyles].
 *
 * Styles CommonMark has no syntax for take the de facto forms: underline is
 * `<u>` (what Obsidian and Typora write, and what every renderer shows),
 * highlight is [highlightSyntax], and a colour or a font size other than the
 * body text's is an inline `<span style="...">`.
 */
data class MarkdownConfiguration(
	val highlightSyntax: HighlightSyntax = HighlightSyntax.DOUBLE_EQUALS,
	val paragraphSeparator: ParagraphSeparator = ParagraphSeparator.BLANK_LINE,
) {
	companion object {
		val DEFAULT = MarkdownConfiguration()

		@Deprecated(
			"The styles live on TextEditorState.richTextStyles: assign RichTextStyles.DEFAULT_DARK there and use MarkdownConfiguration.DEFAULT here.",
			level = DeprecationLevel.ERROR,
		)
		val DEFAULT_DARK: MarkdownConfiguration
			get() = DEFAULT
	}
}
