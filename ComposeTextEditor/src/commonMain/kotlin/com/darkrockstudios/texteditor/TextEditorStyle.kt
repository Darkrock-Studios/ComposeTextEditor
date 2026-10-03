package com.darkrockstudios.texteditor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The colors and text style applied to a [TextEditor]. Any `Color.Unspecified` field
 * falls back to a sensible default at draw time, so you only set what you want to
 * change.
 *
 * Prefer [rememberTextEditorStyle], which derives these from the active
 * [androidx.compose.material3.MaterialTheme] color scheme and tracks theme changes.
 * Construct directly only when you need colors independent of the theme.
 */
data class TextEditorStyle(
	val textColor: Color = Color.Unspecified,
	val backgroundColor: Color = Color.Unspecified,
	val placeholderText: String = "",
	val placeholderColor: Color = Color.Unspecified,
	val cursorColor: Color = Color.Unspecified,
	val selectionColor: Color = Color.Unspecified,
	val focusedBorderColor: Color = Color.Unspecified,
	val unfocusedBorderColor: Color = Color.Unspecified,
	/**
	 * Its `textDirection` sets each paragraph's direction. Left unspecified, every
	 * paragraph follows the app's layout direction, as in `BasicTextField`; set
	 * `TextDirection.Content` to resolve each paragraph from its first strong character,
	 * so a right-to-left paragraph in a left-to-right app lays out right to left.
	 */
	val textStyle: TextStyle = TextStyle.Default,
	/**
	 * Color of the bullet-list dot drawn in the gutter. `Color.Unspecified` falls
	 * back to `Color.DarkGray` at draw time — a sensible light-mode default that
	 * users running dark themes will want to override.
	 */
	val bulletColor: Color = Color.Unspecified,
	/**
	 * Color of the blockquote bar drawn in the gutter. `Color.Unspecified` falls
	 * back to `Color.Gray.copy(alpha = 0.6f)` at draw time.
	 */
	val blockquoteBarColor: Color = Color.Unspecified,
	/**
	 * Tinted background fill drawn across the full width of every blockquote
	 * line, behind the bar and the text. `Color.Unspecified` skips the fill
	 * entirely — set to a low-alpha color to mark the quoted area as a soft
	 * card. The fill is drawn on top of the text (rich spans render after
	 * `drawText`), so use a low alpha to avoid muddying the body.
	 */
	val blockquoteBackgroundColor: Color = Color.Unspecified,
	/**
	 * Color of the ordered-list numeral drawn in the gutter. `Color.Unspecified`
	 * inherits the editor's text color (via `drawText`'s color override).
	 */
	val orderedListMarkerColor: Color = Color.Unspecified,
	/**
	 * Tinted background fill drawn behind every line in a fenced code block.
	 * `Color.Unspecified` falls back to `Color.Gray.copy(alpha = 0.18f)`.
	 * Use a stronger alpha than [blockquoteBackgroundColor] so the two cards
	 * don't read as the same treatment.
	 */
	val codeFenceBackgroundColor: Color = Color.Unspecified,
	/**
	 * Hairline border drawn around the run of fenced code lines — top edge on
	 * the first line, bottom edge on the last, sides on every line.
	 * `Color.Unspecified` falls back to `Color.Gray.copy(alpha = 0.55f)`.
	 */
	val codeFenceBorderColor: Color = Color.Unspecified,
	/** Width of the caret. The default matches `BasicTextField`'s. */
	val cursorWidth: Dp = 2.dp,
	/**
	 * The space below every paragraph, as a word processor's "space after". Zero, as
	 * plain text fields have it; a paragraph's own format
	 * ([com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle]) overrides it.
	 */
	val paragraphSpacing: Dp = 0.dp,
	/**
	 * Selection colour while the editor does not have focus, dimmed as native editors
	 * dim it. `Color.Unspecified` falls back to [selectionColor] at half its alpha.
	 */
	val unfocusedSelectionColor: Color = Color.Unspecified,
	/** Colour of the touch selection and caret handles. `Color.Unspecified` falls back to a blue. */
	val handleColor: Color = Color.Unspecified,
	/** Shape of the touch selection and caret handles, and so where a finger takes them. */
	val handleShape: SelectionHandleShape = SelectionHandleShape.Platform,
)

/** How the touch selection and caret handles look. */
enum class SelectionHandleShape {
	/** The running platform's: [Bar] on iOS, [Teardrop] everywhere else. */
	Platform,

	/**
	 * Android's, which `BasicTextField` draws there: a disc with one square corner at the
	 * end it marks, hanging below the row, and a teardrop pointing up under the caret.
	 */
	Teardrop,

	/**
	 * iOS's: a bar the row's height at each end of the selection, with a dot above the
	 * start and below the end. No caret handle is drawn; the caret itself is dragged.
	 */
	Bar,
}

internal val TextEditorStyle.effectiveHandleColor: Color
	get() = handleColor.takeOrElse { DefaultSelectionHandleColor }

/** The selection colour for an editor that has focus, or lacks it. */
internal fun TextEditorStyle.selectionColorFor(focused: Boolean): Color = when {
	focused -> selectionColor
	unfocusedSelectionColor.isSpecified -> unfocusedSelectionColor
	selectionColor.isSpecified -> selectionColor.copy(alpha = selectionColor.alpha / 2f)
	else -> selectionColor
}

/**
 * Builds and remembers a [TextEditorStyle] whose defaults are drawn from the active
 * [MaterialTheme] color scheme, so the editor matches your theme out of the box and
 * recolors when the theme changes. Override any parameter to customize a single role.
 */
@Composable
fun rememberTextEditorStyle(
	textColor: Color = MaterialTheme.colorScheme.onBackground,
	backgroundColor: Color = MaterialTheme.colorScheme.background,
	placeholderText: String = "",
	placeholderColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
	cursorColor: Color = MaterialTheme.colorScheme.onSurface,
	selectionColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
	focusedBorderColor: Color = MaterialTheme.colorScheme.outline,
	unfocusedBorderColor: Color = MaterialTheme.colorScheme.outlineVariant,
	textStyle: TextStyle = TextStyle.Default,
	// Markers default to Material color roles that read as "subtle but visible UI
	// chrome" in both light and dark schemes — `onSurfaceVariant` for the bullet
	// dot, `outline` (the role intended for dividers/borders) for the blockquote
	// bar, and full `onSurface` for the ordered numeral since it's content-
	// bearing and should match the text it's labeling.
	bulletColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
	blockquoteBarColor: Color = MaterialTheme.colorScheme.outline,
	// Subtle tinted card behind the blockquote text — the alpha keeps the body
	// readable since the fill paints on top of `drawText`.
	blockquoteBackgroundColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
	orderedListMarkerColor: Color = MaterialTheme.colorScheme.onSurface,
	// `surfaceContainerHigh` is Material 3's "elevated surface" role — designed
	// for cards, chips, and code blocks. It's clearly distinct from `surface`
	// (the editor body) in both light and dark schemes, and pairs naturally with
	// `onSurface`-colored text (the editor's text color), giving the monospace
	// body solid contrast inside the card. Used at full opacity since the role
	// already encodes the intended subtlety.
	codeFenceBackgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
	codeFenceBorderColor: Color = MaterialTheme.colorScheme.outline,
	cursorWidth: Dp = 2.dp,
	// A neutral grey, as macOS and browsers draw a selection whose editor lost focus.
	unfocusedSelectionColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
	handleColor: Color = MaterialTheme.colorScheme.primary,
	paragraphSpacing: Dp = 0.dp,
	handleShape: SelectionHandleShape = SelectionHandleShape.Platform,
): TextEditorStyle = remember(
	textColor, backgroundColor, placeholderText, placeholderColor,
	cursorColor, selectionColor, focusedBorderColor, unfocusedBorderColor, textStyle,
	bulletColor, blockquoteBarColor, blockquoteBackgroundColor, orderedListMarkerColor,
	codeFenceBackgroundColor, codeFenceBorderColor, cursorWidth, unfocusedSelectionColor, handleColor,
	paragraphSpacing, handleShape,
) {
	TextEditorStyle(
		textColor = textColor,
		backgroundColor = backgroundColor,
		placeholderText = placeholderText,
		placeholderColor = placeholderColor,
		cursorColor = cursorColor,
		selectionColor = selectionColor,
		focusedBorderColor = focusedBorderColor,
		unfocusedBorderColor = unfocusedBorderColor,
		textStyle = textStyle,
		bulletColor = bulletColor,
		blockquoteBarColor = blockquoteBarColor,
		blockquoteBackgroundColor = blockquoteBackgroundColor,
		orderedListMarkerColor = orderedListMarkerColor,
		codeFenceBackgroundColor = codeFenceBackgroundColor,
		codeFenceBorderColor = codeFenceBorderColor,
		cursorWidth = cursorWidth,
		unfocusedSelectionColor = unfocusedSelectionColor,
		handleColor = handleColor,
		paragraphSpacing = paragraphSpacing,
		handleShape = handleShape,
	)
}
