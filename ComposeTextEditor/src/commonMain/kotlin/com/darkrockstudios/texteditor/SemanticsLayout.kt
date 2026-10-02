package com.darkrockstudios.texteditor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import com.darkrockstudios.texteditor.state.RowList
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The whole document laid out as one text, one per state, for `getTextLayoutResult`
 * (screen readers read line boundaries and character bounds from it) and for iOS's
 * input session (the floating cursor and vertical moves hit-test it). A
 * `TextLayoutResult` is one measured text and cannot be put together from the editor's
 * per-line layouts, so this is measured on request from the editor's rows: each line as
 * the editor shaped it (its block's or format's paragraph style, the baked outer
 * indent) at the same width, so the rows break and align as drawn, and the space the
 * editor leaves between rows (a block's height, paragraph spacing) as a placeholder on
 * the last row above it. A request after a change that left all of that alone (a span pass) reuses
 * the last layout; any text edit measures the whole document again.
 *
 * What cannot match: the layout starts at the first row's top and the text's left
 * edge (iOS's input session places it there), so it leaves out the content padding, the space above the first paragraph and
 * in the editor the scroll offsets (Compose has no way to move a layout, and moving the
 * semantics node would move the field's bounds with it; [CharacterBounds] answers from
 * the rows where a platform bridge can ask for it instead); a block shorter than its
 * line's text, a block on any row but its line's last, and a block on the last line
 * (which has no line break to carry a placeholder) keep the text's height. With
 * wrapping off, a right-to-left or centred paragraph aligns within the widest line
 * rather than within the viewport.
 */
internal class SemanticsLayout(private val state: TextEditorState) {
	private var layoutInput: LayoutInput? = null
	private var layout: TextLayoutResult? = null

	/** What [layoutInput] was built from: while it stands, it would build the same input. */
	private var layoutSources: LayoutSources? = null

	/** Compared by identity for the document and rows, since comparing rows by content would build every row. */
	private class LayoutSources(
		val content: DocumentSnapshot,
		val rows: List<LineWrap>,
		val width: Float,
		val style: TextStyle,
		val measurer: TextMeasurer,
		val density: Density?,
		val softWrap: Boolean,
	) {
		fun same(other: LayoutSources?): Boolean = other != null && content === other.content && rows === other.rows &&
			width == other.width && style == other.style && measurer === other.measurer && density == other.density &&
			softWrap == other.softWrap
	}

	/** What the semantics layout is measured from; an equal one measures the same. */
	private data class LayoutInput(
		val text: AnnotatedString,
		val style: TextStyle,
		val placeholders: List<AnnotatedString.Range<Placeholder>>,
		/** For each placeholder, the line whose last row it stretches and how far below that row's top the next line starts. */
		val steps: List<RowStep>,
		val width: Int,
		/** Unwrapped as the editor's lines are (7.41): at least [width] wide, and as wide as the widest line. */
		val softWrap: Boolean,
		val measurer: TextMeasurer,
		val density: Density?,
	)

	private data class RowStep(val line: Int, val height: Float)

	/** The layout, measured again only when the text or the rows' inputs changed; null when it cannot be measured. */
	fun get(): TextLayoutResult? {
		val content = state.snapshot()
		val sources = LayoutSources(content, state.lineOffsets, state.viewportSize.width, state.textStyle, state.textMeasurer, state.density, state.softWrap)
		val current = layout?.takeIf { sources.same(layoutSources) } ?: run {
			val input = layoutInput(content)
			// A text edit always measures again, so only a span change is worth comparing.
			val sameText = content.lines === layoutSources?.content?.lines
			layout?.takeIf { sameText && input == layoutInput } ?: measure(input)?.also {
				layout = it
				layoutInput = input
			}
		} ?: return null
		layoutSources = sources
		return current
	}

	private fun layoutInput(content: DocumentSnapshot): LayoutInput {
		val style = state.textStyle
		val outerIndent = style.textIndent?.takeIf { it != TextIndent.None }
		val measureStyle = if (outerIndent == null) style else style.copy(textIndent = TextIndent.None)
		val lines = content.lines
		// Rows laid out for another revision (a collapsed viewport leaves them behind) are not used.
		val rows = (state.lineOffsets as? RowList)?.takeIf { it.lineCount == lines.size && it.spans === content.spanIndex }
		val viewport = maxOf(1, state.viewportSize.width.toInt())
		// Unwrapped, at least the widest line wide: the intrinsic width leaves out an indent, which would break that line.
		val width = if (state.softWrap || rows == null) viewport else maxOf(viewport, ceil(rows.contentWidth).toInt().coerceAtMost(MAX_FIXED_PX))
		// Each line as the editor measured it, unless its rows are behind the text.
		val shaped = List(lines.size) { index ->
			val line = lines[index]
			rows?.layoutOf(index)?.layout?.layoutInput?.text?.takeIf { it.text == line.text } ?: line
		}
		val density = state.density
		var steps = emptyList<RowStep>()
		var placeholders = emptyList<AnnotatedString.Range<Placeholder>>()
		if (rows != null && density != null) {
			steps = rowSteps(rows)
			placeholders = steps.map { step ->
				val lineBreak = content.lineStart(step.line + 1) - 1
				AnnotatedString.Range(placeholder(step.height, density), lineBreak, lineBreak + 1)
			}
		}
		if (outerIndent == null && placeholders.isEmpty() && shaped.none { it.paragraphStyles.isNotEmpty() }) {
			return LayoutInput(content.getAllText(), measureStyle, placeholders, steps, width, state.softWrap, state.textMeasurer, density)
		}
		// Every line becomes its own paragraph, with the style it was measured with or the
		// baked indent. A paragraph style already breaks the line, so the line break
		// between two becomes a zero-width space inside the first: a line break there
		// would add an empty row, and the offsets must stay the document's.
		val plain = outerIndent?.let { ParagraphStyle(textIndent = it) } ?: ParagraphStyle()
		val text = buildAnnotatedString {
			shaped.forEachIndexed { index, line ->
				withStyle(line.paragraphStyles.firstOrNull()?.item ?: plain) {
					append(AnnotatedString(line.text, line.spanStyles))
					if (index < shaped.lastIndex) append(ZERO_WIDTH_SPACE)
				}
			}
		}
		return LayoutInput(text, measureStyle, placeholders, steps, width, state.softWrap, state.textMeasurer, density)
	}

	/**
	 * The last row of each line that the editor draws taller than its text, or leaves
	 * space under, with how far below its top the next line's first row starts. Each
	 * gets a top-aligned placeholder that tall on the line break after it, which
	 * stretches the row down to there.
	 */
	private fun rowSteps(rows: RowList): List<RowStep> {
		val steps = ArrayList<RowStep>()
		for (line in 0 until rows.lineCount - 1) {
			val layout = rows.layoutOf(line)
			val next = rows.layoutOf(line + 1)
			val lastRow = layout.rowCount - 1
			val step = layout.rowTops[lastRow + 1] - layout.rowTops[lastRow] + layout.spaceAfter + next.spaceBefore
			// A placeholder can only make a row taller than its text.
			if (step > layout.layout.multiParagraph.getLineHeight(lastRow) + 0.5f) steps += RowStep(line, step)
		}
		return steps
	}

	private fun placeholder(height: Float, density: Density) =
		Placeholder(0.sp, with(density) { height.toSp() }, PlaceholderVerticalAlign.Top)

	/**
	 * Measures [input], then once more with any placeholder that missed its step
	 * corrected by what it missed: a paragraph's line height spreads over a placeholder
	 * as over text, so a row with one can come out taller than the placeholder.
	 */
	private fun measure(input: LayoutInput): TextLayoutResult? {
		val first = measure(input, input.placeholders) ?: return null
		val density = input.density ?: return first
		var missed = false
		val corrected = input.placeholders.mapIndexed { index, range ->
			val step = input.steps[index]
			val row = first.getLineForOffset(range.start)
			if (row + 1 >= first.lineCount) return@mapIndexed range
			val miss = step.height - (first.getLineTop(row + 1) - first.getLineTop(row))
			// A first row's top can sit a pixel above zero.
			if (abs(miss) <= 1f || step.height + miss <= 0f) return@mapIndexed range
			missed = true
			AnnotatedString.Range(placeholder(step.height + miss, density), range.start, range.end)
		}
		return if (missed) measure(input, corrected) ?: first else first
	}

	private fun measure(input: LayoutInput, placeholders: List<AnnotatedString.Range<Placeholder>>): TextLayoutResult? = try {
		input.measurer.measure(
			text = input.text,
			style = input.style,
			softWrap = input.softWrap,
			constraints = if (input.softWrap) Constraints.fixedWidth(input.width) else Constraints(minWidth = input.width),
			placeholders = placeholders,
			skipCache = true,
		)
	} catch (_: IllegalArgumentException) {
		null
	}
}

private const val ZERO_WIDTH_SPACE = '​'
