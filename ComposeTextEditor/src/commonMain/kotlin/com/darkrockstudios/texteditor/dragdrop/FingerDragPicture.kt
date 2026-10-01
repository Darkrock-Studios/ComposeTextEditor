package com.darkrockstudios.texteditor.dragdrop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.followingGraphemeBoundary

/** What a drag shows under the finger, [size] in pixels. */
internal class DragPicture(val size: Size, val draw: DrawScope.() -> Unit)

/**
 * The start of the dragged text a finger drag shows, cut as `TextView` cuts its drag shadow:
 * past 20 characters, through the end of the cluster the 21st begins.
 */
internal fun AnnotatedString.dragPictureText(): AnnotatedString =
	if (length <= DRAG_PICTURE_MAX_CHARS) this else subSequence(0, text.followingGraphemeBoundary(DRAG_PICTURE_MAX_CHARS))

/**
 * The picture of [text] a finger drag shows, as `TextView` draws its drag shadow: the
 * text's start ([dragPictureText]) unwrapped and centred, in its span styles over the
 * editor's at the large text size (`TextAppearance.Large`), in [color], on nothing. The
 * platform centres it on the finger. Blocks' indents and line heights are left out, so
 * the text sits where the finger is. Null for nothing to show.
 */
internal fun TextEditorState.dragPicture(text: AnnotatedString, color: Color): DragPicture? {
	val cut = text.dragPictureText()
	if (cut.isEmpty()) return null
	val shown = AnnotatedString(cut.text, cut.spanStyles)
	val style = textStyle.copy(
		fontSize = DRAG_PICTURE_FONT_SIZE,
		textIndent = TextIndent.None,
		lineHeight = TextUnit.Unspecified,
		lineHeightStyle = null,
		textAlign = TextAlign.Center,
	)
	val layout = textMeasurer.measure(shown, style)
	if (layout.size.width <= 0 || layout.size.height <= 0) return null
	val ink = color.takeIf { it.isSpecified } ?: textStyle.color.takeIf { it.isSpecified } ?: Color.Black
	return DragPicture(Size(layout.size.width.toFloat(), layout.size.height.toFloat())) {
		drawText(layout, color = ink)
	}
}

private const val DRAG_PICTURE_MAX_CHARS = 20
private val DRAG_PICTURE_FONT_SIZE = 22.sp
