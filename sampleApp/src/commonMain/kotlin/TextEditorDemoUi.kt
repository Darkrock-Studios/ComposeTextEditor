package com.darkrockstudios.texteditor.sample

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.darkrockstudios.texteditor.rememberTextEditorStyle
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
	modifier: Modifier = Modifier,
	navigateTo: (Destination) -> Unit,
	demoContent: DemoContent,
	styles: RichTextStyles,
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

	var enabled by remember { mutableStateOf(true) }
	var readOnly by remember { mutableStateOf(false) }
	var grow by remember { mutableStateOf(false) }
	var singleLine by remember { mutableStateOf(false) }
	var softWrap by remember { mutableStateOf(true) }
	var limited by remember { mutableStateOf(false) }
	val editable = enabled && !readOnly
	LaunchedEffect(state, limited) {
		state.inputFilter = if (limited) EditorInputFilter.maxLength(280) else null
	}
	var punctuation by remember { mutableStateOf(NO_SMART_PUNCTUATION) }
	LaunchedEffect(state, punctuation) {
		state.editBehaviors.removeAll { it is SmartPunctuation }
		if (punctuation != NO_SMART_PUNCTUATION) state.editBehaviors += punctuation
	}
	var autoLink by remember { mutableStateOf(AutoLink(typed = false, pasted = false)) }
	LaunchedEffect(state, autoLink) {
		state.editBehaviors.removeAll { it is AutoLink }
		// Ahead of the line block behavior, so Enter on a list item links too.
		if (autoLink.typed || autoLink.pasted) state.editBehaviors.add(0, autoLink)
	}
	var markdownShortcuts by remember { mutableStateOf(false) }
	LaunchedEffect(state, markdownShortcuts) {
		state.editBehaviors.removeAll { it is MarkdownShortcuts }
		// Ahead of the line block behavior, so Enter on a fence line reaches it.
		if (markdownShortcuts) state.editBehaviors.add(0, MarkdownShortcuts())
	}

	Column(modifier = modifier) {
		Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
			Text(
				"Compose Text Editor",
				modifier = Modifier.padding(8.dp).weight(1f),
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text("${state.wordCount} words", style = MaterialTheme.typography.labelMedium)
			if (editable && markdownExtension != null) {
				Button(
					onClick = {
						val markdown = markdownExtension.exportAsMarkdown()
						println("Roundtrip export:\n$markdown")
						markdownExtension.importMarkdown(markdown)
					},
					modifier = Modifier.padding(end = 8.dp),
				) { Text("Roundtrip") }
			}
			Button(onClick = { navigateTo(Destination.Menu) }) {
				Text("X")
			}
		}

		Row(
			modifier = Modifier.horizontalScroll(rememberScrollState()),
			verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
		) {
			// The editor's enabled and read-only flags gate user input only; the toolbar and
			// Roundtrip act on the state directly, so they hide with them.
			LabeledSwitch("Enabled", enabled) { enabled = it }
			if (enabled) LabeledSwitch("Read only", readOnly) { readOnly = it }
			LabeledSwitch("Grow", grow) { grow = it }
			LabeledSwitch("Single line", singleLine) { singleLine = it }
			LabeledSwitch("Soft wrap", softWrap) { softWrap = it }
			LabeledSwitch("280 max", limited) { limited = it }
			LabeledSwitch("Curly \"quotes\"", punctuation.doubleQuotes) {
				punctuation = punctuation.copy(doubleQuotes = it)
			}
			LabeledSwitch("Curly 'quotes'", punctuation.singleQuotes) {
				punctuation = punctuation.copy(singleQuotes = it)
			}
			LabeledSwitch("-- em dash", punctuation.emDashes) { punctuation = punctuation.copy(emDashes = it) }
			LabeledSwitch("a - b en dash", punctuation.enDashes) { punctuation = punctuation.copy(enDashes = it) }
			LabeledSwitch("... ellipsis", punctuation.ellipses) { punctuation = punctuation.copy(ellipses = it) }
			LabeledSwitch("Link typed URLs", autoLink.typed) { autoLink = autoLink.copy(typed = it) }
			LabeledSwitch("Link pasted URLs", autoLink.pasted) { autoLink = autoLink.copy(pasted = it) }
			if (markdownExtension != null) {
				LabeledSwitch("Markdown shortcuts", markdownShortcuts) { markdownShortcuts = it }
			}
		}

		if (editable) {
			TextEditorToolbar(
				state = state,
				markdownControls = markdownExtension != null,
			)
		}

		val uriHandler = LocalUriHandler.current
		val style = rememberTextEditorStyle(
			placeholderText = "Enter text here",
			textColor = MaterialTheme.colorScheme.onSurface,
		)

		TextEditor(
			state = state,
			modifier = Modifier
				.padding(8.dp)
				// A grown editor must not be made to fill the height.
				.then(if (grow || singleLine) Modifier.fillMaxWidth() else Modifier.fillMaxSize()),
			style = style,
			enabled = enabled,
			readOnly = readOnly,
			lineLimits = when {
				singleLine -> EditorLineLimits.SingleLine
				grow -> EditorLineLimits.MultiLine(minLines = 3, maxLines = 8)
				else -> EditorLineLimits.Fill
			},
			softWrap = softWrap,
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
}

private val NO_SMART_PUNCTUATION = SmartPunctuation(
	doubleQuotes = false,
	singleQuotes = false,
	emDashes = false,
	enDashes = false,
	ellipses = false,
)

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
	Row(
		modifier = Modifier
			.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
			.padding(horizontal = 8.dp),
		verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
	) {
		Text(label, modifier = Modifier.padding(end = 4.dp))
		Switch(checked = checked, onCheckedChange = null)
	}
}
