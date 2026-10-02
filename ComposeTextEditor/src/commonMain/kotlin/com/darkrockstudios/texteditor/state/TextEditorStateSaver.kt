package com.darkrockstudios.texteditor.state

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.GenericFontFamily
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.TextUnitType
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.BLOCKQUOTE_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.BULLET_LIST_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.CodeFenceLanguageSpanStyle
import com.darkrockstudios.texteditor.richstyle.MAX_LIST_LEVEL
import com.darkrockstudios.texteditor.richstyle.listParagraphStyle
import com.darkrockstudios.texteditor.richstyle.CODE_FENCE_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.CodeFenceSpanStyle
import com.darkrockstudios.texteditor.richstyle.HEADER_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.ORDERED_LIST_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import kotlinx.coroutines.CoroutineScope

/*
 * The saved form is nested lists of strings and numbers only, so it fits an Android
 * Bundle (as a Serializable ArrayList) and every other platform's registry:
 *
 *   [format, lines, spans, paragraphs, richSpans, caret, selection, firstVisible]
 *
 * spans:      line, start, end, encoded SpanStyle, repeated
 * paragraphs: line, start, end, a block's paragraph style by name or an encoded
 *             ParagraphStyle, repeated, in each line's order so stacked blocks nest
 *             as they did
 * richSpans:  kind, start line, start char, end line, end char, argument, repeated
 */
private const val FORMAT = 1

private const val KIND_CUSTOM = "custom"

private val builtinKinds: Map<RichSpanStyle, String> = mapOf(
	BulletListSpanStyle to "bullet",
	OrderedListSpanStyle to "ordered",
	BlockquoteSpanStyle to "quote",
	CodeFenceSpanStyle to "fence",
	HorizontalRuleSpanStyle to "rule",
)

private val builtinStyles: Map<String, RichSpanStyle> = builtinKinds.entries.associate { (style, kind) -> kind to style }

/**
 * The line blocks' indents, kept by name: blocks strip their paragraph style by
 * equality, so a restored line must carry an equal one. Several are equal to each
 * other, which is harmless for the same reason.
 */
private val blockParagraphs: Map<String, ParagraphStyle> = mapOf(
	"bullet" to BULLET_LIST_PARAGRAPH_STYLE,
	"ordered" to ORDERED_LIST_PARAGRAPH_STYLE,
	"quote" to BLOCKQUOTE_PARAGRAPH_STYLE,
	"fence" to CODE_FENCE_PARAGRAPH_STYLE,
	"header" to HEADER_PARAGRAPH_STYLE,
	// A nested item's indent, one name per level; both list kinds share it.
) + (1..MAX_LIST_LEVEL).associate { level -> "list:$level" to listParagraphStyle(level) }

private val textAligns = listOf(TextAlign.Left, TextAlign.Right, TextAlign.Center, TextAlign.Justify, TextAlign.Start, TextAlign.End)

private val textDirections = listOf(
	TextDirection.Ltr, TextDirection.Rtl, TextDirection.Content, TextDirection.ContentOrLtr, TextDirection.ContentOrRtl,
)
private val lineBreaks = listOf(LineBreak.Simple, LineBreak.Heading, LineBreak.Paragraph)
private val hyphenations = listOf(Hyphens.None, Hyphens.Auto)

/**
 * Saves a [TextEditorState] for [androidx.compose.runtime.saveable.rememberSaveable];
 * see [rememberSaveableTextEditorState] for what is kept. A saved value this version
 * cannot read restores nothing, so `rememberSaveable` builds a fresh state instead.
 */
internal fun textEditorStateSaver(
	scope: CoroutineScope,
	measurer: TextMeasurer,
	richSpanStyleSaver: Saver<RichSpanStyle, Any>?,
): Saver<TextEditorState, Any> = Saver(
	save = { state -> saveState(state, richSpanStyleSaver) },
	restore = { saved ->
		(saved as? List<*>)?.takeIf { it.getOrNull(0) == FORMAT }?.let { list ->
			TextEditorState(scope, measurer).also { it.restoreFrom(list, richSpanStyleSaver) }
		}
	},
)

private fun SaverScope.saveState(state: TextEditorState, custom: Saver<RichSpanStyle, Any>?): Any {
	val document = state.snapshot()
	val spans = arrayListOf<Any>()
	val paragraphs = arrayListOf<Any>()
	document.lines.forEachIndexed { index, line ->
		line.spanStyles.forEach { range ->
			spans.addAll(listOf(index, range.start, range.end))
			spans.add(range.item.encode())
		}
		line.paragraphStyles.forEach { range ->
			paragraphs.addAll(listOf(index, range.start, range.end))
			paragraphs.add(range.item.encode())
		}
	}

	val richSpans = arrayListOf<Any>()
	document.richSpans.forEach { span ->
		if (span.style.isDecoration) return@forEach
		val (kind, argument) = span.style.encode(this, custom) ?: return@forEach
		richSpans.addAll(listOf(kind, span.range.start.line, span.range.start.char, span.range.end.line, span.range.end.char))
		richSpans.add(argument)
	}

	val selection = state.selector.selection
	// A restored state not yet laid out has no top line of its own; it still owes the saved one.
	val firstVisible = state.restoredFirstVisible ?: state.scrollManager.firstVisibleOffset
	return arrayListOf(
		FORMAT,
		ArrayList(document.lines.map { it.text }),
		spans,
		paragraphs,
		richSpans,
		arrayListOf(state.cursorPosition.line, state.cursorPosition.char),
		if (selection == null) {
			arrayListOf()
		} else {
			arrayListOf(selection.start.line, selection.start.char, selection.end.line, selection.end.char)
		},
		arrayListOf(firstVisible.line, firstVisible.char),
	)
}

private fun TextEditorState.restoreFrom(saved: List<*>, custom: Saver<RichSpanStyle, Any>?) {
	val texts = (saved[1] as List<*>).map { it as String }
	val spans = saved[2] as List<*>
	val paragraphs = saved[3] as List<*>
	val richSaved = saved[4] as List<*>

	val lineSpans = HashMap<Int, MutableList<AnnotatedString.Range<SpanStyle>>>()
	for (i in spans.indices step 4) {
		// A style of only what was not kept (a loaded font, a brush) comes back as nothing.
		val style = decodeSpanStyle(spans[i + 3] as List<*>).takeIf { it != SpanStyle() } ?: continue
		lineSpans.getOrPut(spans[i] as Int) { mutableListOf() } += AnnotatedString.Range(style, spans[i + 1] as Int, spans[i + 2] as Int)
	}
	val lineParagraphs = HashMap<Int, MutableList<AnnotatedString.Range<ParagraphStyle>>>()
	for (i in paragraphs.indices step 4) {
		val style = decodeParagraphStyle(paragraphs[i + 3]!!)
		lineParagraphs.getOrPut(paragraphs[i] as Int) { mutableListOf() } +=
			AnnotatedString.Range(style, paragraphs[i + 1] as Int, paragraphs[i + 2] as Int)
	}
	val lines = texts.mapIndexed { index, text ->
		val paragraphRanges = lineParagraphs[index]
		if (paragraphRanges == null) {
			AnnotatedString(text, lineSpans[index].orEmpty())
		} else {
			buildAnnotatedString {
				append(AnnotatedString(text, lineSpans[index].orEmpty()))
				paragraphRanges.forEach { addStyle(it.item, it.start, it.end) }
			}
		}
	}

	val richSpans = HashSet<RichSpan>()
	for (i in richSaved.indices step 6) {
		val style = decodeRichSpanStyle(richSaved[i] as String, richSaved[i + 5], custom) ?: continue
		val range = TextEditorRange(
			CharLineOffset(richSaved[i + 1] as Int, richSaved[i + 2] as Int),
			CharLineOffset(richSaved[i + 3] as Int, richSaved[i + 4] as Int),
		)
		richSpans += RichSpan(range, style)
	}
	setDocument(DocumentSnapshot(lines, richSpans))

	val caret = saved[5] as List<*>
	cursor.updatePosition(CharLineOffset(caret[0] as Int, caret[1] as Int))
	val selection = saved[6] as List<*>
	if (selection.size == 4) {
		selector.updateSelection(
			CharLineOffset(selection[0] as Int, selection[1] as Int),
			CharLineOffset(selection[2] as Int, selection[3] as Int),
		)
	}
	val firstVisible = saved[7] as List<*>
	restoredFirstVisible = CharLineOffset(firstVisible[0] as Int, firstVisible[1] as Int)
}

private fun RichSpanStyle.encode(scope: SaverScope, custom: Saver<RichSpanStyle, Any>?): Pair<String, Any>? {
	builtinKinds[this]?.let { return it to "" }
	return when (this) {
		is HeaderSpanStyle -> "h$level" to ""
		is LinkSpanStyle -> "link" to url
		// Level 0 is the plain kind above; a nested item carries its level.
		is BulletListSpanStyle -> "bullet:$level" to ""
		is OrderedListSpanStyle -> "ordered:$level" to ""
		is CodeFenceLanguageSpanStyle -> "fence-language" to language
		is ParagraphFormatSpanStyle -> "paragraph" to arrayListOf<Any>(
			if (spaceBefore.isSpecified) spaceBefore.value else Float.NaN,
			if (spaceAfter.isSpecified) spaceAfter.value else Float.NaN,
			textAlign?.let { textAligns.indexOf(it) } ?: -1,
			indent.typeCode(), indent.valueOrZero(),
			firstLineIndent.typeCode(), firstLineIndent.valueOrZero(),
			lineHeight.typeCode(), lineHeight.valueOrZero(),
		)
		else -> {
			val saved = custom?.let { saver -> with(saver) { scope.save(this@encode) } } ?: return null
			require(scope.canBeSaved(saved)) {
				"richSpanStyleSaver saved $this as a ${saved::class.simpleName}, which this platform cannot save. " +
					"Save it as strings, numbers, and lists of them."
			}
			KIND_CUSTOM to saved
		}
	}
}

private fun decodeRichSpanStyle(kind: String, argument: Any?, custom: Saver<RichSpanStyle, Any>?): RichSpanStyle? {
	builtinStyles[kind]?.let { return it }
	return when {
		kind == "link" -> LinkSpanStyle(argument as String)
		kind.startsWith("h") && kind.length == 2 -> kind[1].digitToIntOrNull()?.let { HeaderSpanStyle.of(it) }
		kind.startsWith("bullet:") -> kind.substringAfter(':').toIntOrNull()?.let { BulletListSpanStyle.of(it) }
		kind.startsWith("ordered:") -> kind.substringAfter(':').toIntOrNull()?.let { OrderedListSpanStyle.of(it) }
		kind == "fence-language" -> (argument as? String)?.let { CodeFenceLanguageSpanStyle(it) }
		kind == "paragraph" -> (argument as? List<*>)?.let { saved ->
			ParagraphFormatSpanStyle(
				spaceBefore = (saved[0] as Float).let { if (it.isNaN()) Dp.Unspecified else it.dp },
				spaceAfter = (saved[1] as Float).let { if (it.isNaN()) Dp.Unspecified else it.dp },
				textAlign = textAligns.getOrNull(saved[2] as Int),
				indent = textUnit(saved[3] as Int, saved[4] as Float),
				firstLineIndent = textUnit(saved[5] as Int, saved[6] as Float),
				lineHeight = textUnit(saved[7] as Int, saved[8] as Float),
			)
		}
		kind == KIND_CUSTOM && argument != null -> custom?.restore(argument)
		else -> null
	}
}

/** The fields of a [SpanStyle] that are plain values; a font family is kept only when generic. */
private fun SpanStyle.encode(): ArrayList<Any> = arrayListOf(
	color.value.toLong(),
	fontSize.typeCode(),
	fontSize.valueOrZero(),
	fontWeight?.weight ?: -1,
	fontStyle?.value ?: -1,
	(if (textDecoration?.contains(TextDecoration.Underline) == true) 1 else 0) or
		(if (textDecoration?.contains(TextDecoration.LineThrough) == true) 2 else 0),
	background.value.toLong(),
	letterSpacing.typeCode(),
	letterSpacing.valueOrZero(),
	baselineShift?.multiplier ?: Float.NaN,
	fontFamily.genericName(),
	fontFeatureSettings ?: "",
)

private fun decodeSpanStyle(saved: List<*>): SpanStyle {
	val decoration = saved[5] as Int
	return SpanStyle(
		color = Color((saved[0] as Long).toULong()),
		fontSize = textUnit(saved[1] as Int, saved[2] as Float),
		fontWeight = (saved[3] as Int).takeIf { it >= 0 }?.let { FontWeight(it) },
		fontStyle = (saved[4] as Int).takeIf { it >= 0 }?.let { FontStyle(it) },
		textDecoration = when (decoration) {
			0 -> null
			1 -> TextDecoration.Underline
			2 -> TextDecoration.LineThrough
			else -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
		},
		background = Color((saved[6] as Long).toULong()),
		letterSpacing = textUnit(saved[7] as Int, saved[8] as Float),
		baselineShift = (saved[9] as Float).takeUnless { it.isNaN() }?.let { BaselineShift(it) },
		fontFamily = genericFamily(saved[10] as String),
		fontFeatureSettings = (saved[11] as String).ifEmpty { null },
	)
}

/**
 * A block's indent by name; any other paragraph style by its indent, line height,
 * alignment, direction, line breaking and hyphenation.
 */
private fun ParagraphStyle.encode(): Any = blockParagraphs.entries.firstOrNull { it.value == this }?.key
	?: arrayListOf(
		textIndent?.firstLine?.typeCode() ?: -1,
		textIndent?.firstLine?.valueOrZero() ?: 0f,
		textIndent?.restLine?.typeCode() ?: -1,
		textIndent?.restLine?.valueOrZero() ?: 0f,
		lineHeight.typeCode(),
		lineHeight.valueOrZero(),
		TextAlign.values().indexOf(textAlign),
		textDirections.indexOf(textDirection),
		lineBreaks.indexOf(lineBreak),
		hyphenations.indexOf(hyphens),
	)

private fun decodeParagraphStyle(saved: Any): ParagraphStyle {
	if (saved is String) return blockParagraphs.getValue(saved)
	saved as List<*>
	return ParagraphStyle(
		textAlign = TextAlign.values().getOrElse(saved[6] as Int) { TextAlign.Unspecified },
		textDirection = textDirections.getOrElse(saved[7] as Int) { TextDirection.Unspecified },
		lineHeight = textUnit(saved[4] as Int, saved[5] as Float),
		textIndent = if ((saved[0] as Int) < 0) {
			null
		} else {
			TextIndent(textUnit(saved[0] as Int, saved[1] as Float), textUnit(saved[2] as Int, saved[3] as Float))
		},
		lineBreak = lineBreaks.getOrElse(saved[8] as Int) { LineBreak.Unspecified },
		hyphens = hyphenations.getOrElse(saved[9] as Int) { Hyphens.Unspecified },
	)
}

private fun TextUnit.typeCode(): Int = when (type) {
	TextUnitType.Sp -> 1
	TextUnitType.Em -> 2
	else -> 0
}

private fun TextUnit.valueOrZero(): Float = if (type == TextUnitType.Unspecified) 0f else value

private fun textUnit(type: Int, value: Float): TextUnit = when (type) {
	1 -> TextUnit(value, TextUnitType.Sp)
	2 -> TextUnit(value, TextUnitType.Em)
	else -> TextUnit.Unspecified
}

private val genericFamilies = listOf(FontFamily.SansSerif, FontFamily.Serif, FontFamily.Monospace, FontFamily.Cursive)

private fun FontFamily?.genericName(): String = when (this) {
	FontFamily.Default -> "default"
	is GenericFontFamily -> name
	else -> ""
}

private fun genericFamily(name: String): FontFamily? = when (name) {
	"" -> null
	"default" -> FontFamily.Default
	else -> genericFamilies.firstOrNull { it.name == name }
}
