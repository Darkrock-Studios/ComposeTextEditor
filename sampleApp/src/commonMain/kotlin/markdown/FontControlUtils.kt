package markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.state.TextEditorState

private val FONT_SIZES = listOf(
	12f, 14f, 16f, 18f, 20f, 24f, 32f
)

/**
 * Calculate the next font size (up or down) based on the current size
 */
private fun getNextFontSize(currentSize: Float?, increase: Boolean): Float {
	val defaultIndex = 2

	if (currentSize == null) {
		return if (increase) FONT_SIZES[defaultIndex + 1] else FONT_SIZES[defaultIndex - 1]
	}

	val currentIndex = FONT_SIZES.indexOfFirst { it >= currentSize }
	val safeCurrentIndex = if (currentIndex == -1) defaultIndex else currentIndex

	val newIndex = if (increase) {
		(safeCurrentIndex + 1).coerceAtMost(FONT_SIZES.size - 1)
	} else {
		(safeCurrentIndex - 1).coerceAtLeast(0)
	}

	return FONT_SIZES[newIndex]
}

/** This style at [size], keeping everything else it sets. */
private fun SpanStyle.atSize(size: Float): SpanStyle = copy(fontSize = size.sp)

/**
 * Change the font size in the editor by scaling every style of its
 * [RichTextStyles]: the body and inline styles to the new base size, the
 * headings by the same factor.
 */
private fun changeFontSize(state: TextEditorState, increase: Boolean) {
	val current = state.richTextStyles

	val baselineFontSize = current.defaultTextStyle.fontSize.value
	val newBaseFontSize = getNextFontSize(baselineFontSize, increase)
	println("Font size OLD: $baselineFontSize NEW: $newBaseFontSize")

	val scaleFactor = newBaseFontSize / baselineFontSize
	fun SpanStyle.scaled(): SpanStyle = atSize(fontSize.value * scaleFactor)

	val newStyles = RichTextStyles(
		defaultTextStyle = current.defaultTextStyle.atSize(newBaseFontSize),
		boldStyle = current.boldStyle.atSize(newBaseFontSize),
		italicStyle = current.italicStyle.atSize(newBaseFontSize),
		codeStyle = current.codeStyle.atSize(newBaseFontSize),
		linkStyle = current.linkStyle.atSize(newBaseFontSize),
		strikethroughStyle = current.strikethroughStyle.atSize(newBaseFontSize),
		underlineStyle = current.underlineStyle.atSize(newBaseFontSize),
		highlightStyle = current.highlightStyle.atSize(newBaseFontSize),
		blockquoteStyle = current.blockquoteStyle.atSize(newBaseFontSize),
		header1Style = current.header1Style.scaled(),
		header2Style = current.header2Style.scaled(),
		header3Style = current.header3Style.scaled(),
		header4Style = current.header4Style.scaled(),
		header5Style = current.header5Style.scaled(),
		header6Style = current.header6Style.scaled(),
	)

	state.updateRichTextStyles(newStyles)
}

fun increaseFontSize(state: TextEditorState) = changeFontSize(state, true)
fun decreaseFontSize(state: TextEditorState) = changeFontSize(state, false)
