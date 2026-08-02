package com.darkrockstudios.texteditor.richstyle

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Marks a character range as a hyperlink to [url]. The span is the link's
 * semantic identity: serializers read the destination from it instead of
 * reverse-matching the underline decoration, so restyling the document cannot
 * fabricate or destroy a link. The visual treatment is the configuration's
 * link [androidx.compose.ui.text.SpanStyle] baked into the text; this span
 * paints nothing itself.
 *
 * Host apps receive it through `onRichSpanClick` and can navigate with [url].
 *
 * Equality is by [url], so identical links over identical ranges dedupe in the
 * span set.
 */
class LinkSpanStyle(val url: String) : RichSpanStyle {
	override fun DrawScope.drawCustomStyle(
		layoutResult: TextLayoutResult,
		lineWrap: LineWrap,
		textRange: TextRange,
		state: TextEditorState,
	) {
	}

	override fun equals(other: Any?): Boolean =
		this === other || (other is LinkSpanStyle && url == other.url)

	override fun hashCode(): Int = url.hashCode()

	override fun toString(): String = "LinkSpanStyle(url=$url)"
}
