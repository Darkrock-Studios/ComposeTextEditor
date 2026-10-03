package com.darkrockstudios.texteditor.sample

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.EditorLineLimits
import com.darkrockstudios.texteditor.TextEditor
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.behaviors.AutoLink
import com.darkrockstudios.texteditor.behaviors.SmartPunctuation
import com.darkrockstudios.texteditor.markdown.MarkdownShortcuts
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.EditorInputFilter
import com.darkrockstudios.texteditor.state.SpanClickType
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberSaveableTextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState

enum class DemoContent {
	Empty,
	Rich,
	Markdown
}

@Composable
fun TextEditorDemoUi(
	demo: Demo,
	onBack: (() -> Unit)?,
	demoContent: DemoContent,
	styles: RichTextStyles,
	modifier: Modifier = Modifier,
) {
	val imageProvider = rememberDemoImageProvider()
	val state: TextEditorState = when (demoContent) {
		DemoContent.Rich -> {
			rememberTextEditorState(createRichTextDemo())
			//rememberTextEditorState(createRichTextDemo2())
			//rememberTextEditorState(alice_wounder_land.toAnnotatedStringFromMarkdown())
		}

		DemoContent.Markdown -> {
			rememberTextEditorState()
		}

		// Survives rotation and process death, images included through the saver.
		DemoContent.Empty -> {
			val imageSaver = remember(imageProvider) {
				Saver<RichSpanStyle, Any>(
					save = { style ->
						(style as? ImageBlockSpanStyle)?.let { arrayListOf(it.source, it.alt, it.placeholderHeightDp) }
					},
					restore = { saved ->
						val (source, alt, height) = saved as List<*>
						ImageBlockSpanStyle(source as String, alt as String, imageProvider, height as Float)
					},
				)
			}
			rememberSaveableTextEditorState(richSpanStyleSaver = imageSaver)
		}
	}
	LocalEditorPlatformSetup.current(state, imageProvider)
	// Every demo takes the theme's styles; the plain rich text demo is a rich text editor
	// with nothing installed, and the others are markdown editors.
	remember(state, styles) { state.richTextStyles = styles }
	val markdownExtension = if (demoContent == DemoContent.Rich) {
		null
	} else {
		remember(state, imageProvider) { state.withMarkdown(imageProvider = imageProvider) }
	}

	LaunchedEffect(state, demoContent) {
		if (demoContent == DemoContent.Markdown) {
			// importMarkdown resolves block-level constructs (HR, images) into rich spans;
			// toAnnotatedStringFromMarkdown alone leaves them as raw text.
			markdownExtension?.importMarkdown(SIMPLE_MARKDOWN)
		}
	}

	LaunchedEffect(Unit) {
		if (demoContent == DemoContent.Rich) {
			//state.selector.updateSelection(CharLineOffset(0, 10), CharLineOffset(0, 20))
			state.addStyleSpan(
				TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 11)),
				state.richTextStyles.highlightStyle,
			)
			state.addRichSpan(16, 31, SpellCheckStyle)
		}

		state.editOperations.collect { operation ->
			println("Applying Operation: $operation")
		}
	}

	var options by remember { mutableStateOf(EditorOptions()) }
	val editable = options.enabled && !options.readOnly
	LaunchedEffect(state, options.limited) {
		state.inputFilter = if (options.limited) EditorInputFilter.maxLength(280) else null
	}
	LaunchedEffect(state, options.punctuation) {
		state.editBehaviors.removeAll { it is SmartPunctuation }
		if (options.punctuation != NO_SMART_PUNCTUATION) state.editBehaviors += options.punctuation
	}
	LaunchedEffect(state, options.autoLink) {
		state.editBehaviors.removeAll { it is AutoLink }
		// Ahead of the line block behavior, so Enter on a list item links too.
		if (options.autoLink.typed || options.autoLink.pasted) state.editBehaviors.add(0, options.autoLink)
	}
	LaunchedEffect(state, options.markdownShortcuts) {
		state.editBehaviors.removeAll { it is MarkdownShortcuts }
		// Ahead of the line block behavior, so Enter on a fence line reaches it.
		if (options.markdownShortcuts) state.editBehaviors.add(0, MarkdownShortcuts())
	}

	DemoScaffold(
		demo = demo,
		onBack = onBack,
		modifier = modifier,
		actions = {
			// The editor's enabled and read-only flags gate user input only; the toolbar and
			// the round trip act on the state directly, so they hide with them.
			if (editable && markdownExtension != null) {
				ActionButton(Icons.Default.SyncAlt, "Markdown round trip") {
					val markdown = markdownExtension.exportAsMarkdown()
					println("Roundtrip export:\n$markdown")
					markdownExtension.importMarkdown(markdown)
				}
			}
			DemoOptionsButton(
				groups = editorOptionGroups(options, markdown = markdownExtension != null) { options = it },
				modified = options != EditorOptions(),
				onReset = { options = EditorOptions() },
			)
		},
	) {
		if (editable) {
			TextEditorToolbar(
				state = state,
				markdownControls = markdownExtension != null,
				modifier = Modifier.padding(horizontal = 12.dp),
			)
		}

		val uriHandler = LocalUriHandler.current
		val style = rememberFramedEditorStyle(placeholderText = "Enter text here")
		// A grown editor must not be made to fill the height.
		val fills = !options.grow && !options.singleLine

		EditorFrame(
			focused = state.hasFocus,
			modifier = Modifier
				.padding(start = 12.dp, end = 12.dp, top = 8.dp)
				.fillMaxWidth()
				.then(if (fills) Modifier.weight(1f) else Modifier),
		) {
			TextEditor(
				state = state,
				modifier = if (fills) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
				style = style,
				enabled = options.enabled,
				readOnly = options.readOnly,
				lineLimits = when {
					options.singleLine -> EditorLineLimits.SingleLine
					options.grow -> EditorLineLimits.MultiLine(minLines = 3, maxLines = 8)
					else -> EditorLineLimits.Fill
				},
				softWrap = options.softWrap,
				contentDescription = "Document",
				onRichSpanClick = { span, clickType, _ ->
					when (clickType) {
						SpanClickType.TAP -> println("Touch tap on span: $span")
						SpanClickType.PRIMARY_CLICK -> println("Left click on span: $span")
						SpanClickType.SECONDARY_CLICK -> println("Right click on span: $span")
					}
					true
				},
				onLinkClick = uriHandler::openUri,
			)
		}

		if (!fills) Spacer(modifier = Modifier.weight(1f))
		StatusBar("${state.wordCount} words")
	}
}

private data class EditorOptions(
	val enabled: Boolean = true,
	val readOnly: Boolean = false,
	val singleLine: Boolean = false,
	val grow: Boolean = false,
	val softWrap: Boolean = true,
	val limited: Boolean = false,
	val punctuation: SmartPunctuation = NO_SMART_PUNCTUATION,
	val autoLink: AutoLink = AutoLink(typed = false, pasted = false),
	val markdownShortcuts: Boolean = false,
)

private fun editorOptionGroups(
	options: EditorOptions,
	markdown: Boolean,
	onChange: (EditorOptions) -> Unit,
): List<DemoOptionGroup> {
	val punctuation = options.punctuation
	val autoLink = options.autoLink
	return listOfNotNull(
		DemoOptionGroup(
			"Editor",
			listOf(
				DemoOption("Enabled", options.enabled) { onChange(options.copy(enabled = it)) },
				DemoOption("Read only", options.readOnly, enabled = options.enabled) {
					onChange(options.copy(readOnly = it))
				},
				DemoOption("Single line", options.singleLine) { onChange(options.copy(singleLine = it)) },
				DemoOption("Grow with content", options.grow, supportingText = "3 to 8 lines") {
					onChange(options.copy(grow = it))
				},
				DemoOption("Soft wrap", options.softWrap) { onChange(options.copy(softWrap = it)) },
				DemoOption("Limit to 280 characters", options.limited) { onChange(options.copy(limited = it)) },
			),
		),
		DemoOptionGroup(
			"Smart punctuation",
			listOf(
				DemoOption("Curly double quotes", punctuation.doubleQuotes, supportingText = "\"a\" to \u201Ca\u201D") {
					onChange(options.copy(punctuation = punctuation.copy(doubleQuotes = it)))
				},
				DemoOption("Curly single quotes", punctuation.singleQuotes, supportingText = "'a' to \u2018a\u2019") {
					onChange(options.copy(punctuation = punctuation.copy(singleQuotes = it)))
				},
				DemoOption("Em dashes", punctuation.emDashes, supportingText = "-- to \u2014") {
					onChange(options.copy(punctuation = punctuation.copy(emDashes = it)))
				},
				DemoOption("En dashes", punctuation.enDashes, supportingText = "a - b to a \u2013 b") {
					onChange(options.copy(punctuation = punctuation.copy(enDashes = it)))
				},
				DemoOption("Ellipses", punctuation.ellipses, supportingText = "... to \u2026") {
					onChange(options.copy(punctuation = punctuation.copy(ellipses = it)))
				},
			),
		),
		DemoOptionGroup(
			"Links",
			listOf(
				DemoOption("Link typed URLs", autoLink.typed) {
					onChange(options.copy(autoLink = autoLink.copy(typed = it)))
				},
				DemoOption("Link pasted URLs", autoLink.pasted) {
					onChange(options.copy(autoLink = autoLink.copy(pasted = it)))
				},
			),
		),
		if (markdown) {
			DemoOptionGroup(
				"Markdown",
				listOf(
					DemoOption("Markdown shortcuts", options.markdownShortcuts, supportingText = "Format as you type: **bold**, # heading") {
						onChange(options.copy(markdownShortcuts = it))
					},
				),
			)
		} else null,
	)
}

private val NO_SMART_PUNCTUATION = SmartPunctuation(
	doubleQuotes = false,
	singleQuotes = false,
	emDashes = false,
	enDashes = false,
	ellipses = false,
)
