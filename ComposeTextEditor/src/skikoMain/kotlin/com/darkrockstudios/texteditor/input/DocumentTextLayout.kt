package com.darkrockstudios.texteditor.input

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * A whole-document [TextLayoutResult] for a platform that moves the caret by hit
 * testing the text itself: iOS's spacebar trackpad (the floating cursor) starts at
 * `getCursorRect` and follows the finger with `getOffsetForPosition`, and UIKit's
 * vertical moves read the lines. The editor lays out line by line and has no such
 * layout, so this one is built when first asked for, as plain text in the base style
 * at the viewport's width, and kept until the text, width, or style changes. Rich
 * styles and block heights are left out, so only positions relative to each other are
 * meaningful, which is all the floating cursor uses.
 */
internal class DocumentTextLayout(private val state: TextEditorState) {
	private data class Key(
		val lines: List<AnnotatedString>,
		val width: Int,
		val style: TextStyle,
		val measurer: TextMeasurer,
	)

	private var key: Key? = null
	private var layout: TextLayoutResult? = null

	fun get(): TextLayoutResult? {
		val width = state.viewportSize.width.toInt()
		// The viewport starts at 1x1 until the editor is laid out.
		if (width <= 1) return null
		val current = Key(state.textLines, width, state.textStyle, state.textMeasurer)
		if (!current.matches(key)) {
			layout = state.textMeasurer.measure(
				text = AnnotatedString(state.getAllPlainText()),
				style = current.style,
				constraints = Constraints(maxWidth = width),
				// Keep the document out of the measurer's cache, which the line layouts use.
				skipCache = true,
			)
			key = current
		}
		return layout
	}

	/** Compares the lines by identity: every text edit publishes a new list, and equality is O(document). */
	private fun Key.matches(other: Key?): Boolean =
		other != null && lines === other.lines && width == other.width &&
			style == other.style && measurer === other.measurer
}
