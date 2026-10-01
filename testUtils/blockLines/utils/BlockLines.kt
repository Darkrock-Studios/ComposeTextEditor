package utils

import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle

/** The lines currently carrying a rich span of exactly [style], sorted. */
fun MarkdownExtension.linesWith(style: RichSpanStyle): List<Int> =
	editorState.richSpanManager.getAllRichSpans()
		.filter { it.style === style }
		.map { it.range.start.line }
		.sorted()

/** The lines currently carrying an image block span, sorted. */
fun MarkdownExtension.imageLines(): List<Int> =
	editorState.richSpanManager.getAllRichSpans()
		.filter { it.style is ImageBlockSpanStyle }
		.map { it.range.start.line }
		.sorted()
