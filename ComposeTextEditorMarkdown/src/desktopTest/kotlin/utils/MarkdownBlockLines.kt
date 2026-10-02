package utils

import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle

/** The lines currently carrying a rich span of exactly [style], sorted. */
fun MarkdownExtension.linesWith(style: RichSpanStyle): List<Int> = editorState.linesWith(style)

/** The lines currently carrying an image block span, sorted. */
fun MarkdownExtension.imageLines(): List<Int> = editorState.imageLines()
