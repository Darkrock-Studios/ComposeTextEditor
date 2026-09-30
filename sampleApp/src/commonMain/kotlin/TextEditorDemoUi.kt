package com.darkrockstudios.texteditor.sample

import androidx.compose.foundation.layout.*
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.TextEditor
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.rememberTextEditorStyle
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.SpanClickType
import com.darkrockstudios.texteditor.state.TextEditorState
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
	configuration: MarkdownConfiguration
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

		DemoContent.Empty -> {
			rememberTextEditorState()
		}
	}
	val markdownExtension = remember(state, configuration, imageProvider) {
		state.withMarkdown(configuration, imageProvider = imageProvider)
	}

	LaunchedEffect(state, demoContent) {
		if (demoContent == DemoContent.Markdown) {
			// importMarkdown resolves block-level constructs (HR, images) into rich spans;
			// toAnnotatedStringFromMarkdown alone leaves them as raw text.
			markdownExtension.importMarkdown(SIMPLE_MARKDOWN)
		}
	}

	LaunchedEffect(Unit) {
		if (demoContent == DemoContent.Rich) {
			//state.selector.updateSelection(CharLineOffset(0, 10), CharLineOffset(0, 20))
			state.addRichSpan(6, 11, HIGHLIGHT)
			state.addRichSpan(16, 31, SpellCheckStyle)

			//state.addRichSpan(30, 35, HIGHLIGHT)
		}

		state.editOperations.collect { operation ->
			println("Applying Operation: $operation")
		}
	}

	var enabled by remember { mutableStateOf(true) }

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
			// The editor's enabled flag gates user input only; the toolbar and Roundtrip
			// act on the state directly, so they hide with it.
			Row(
				modifier = Modifier
					.toggleable(value = enabled, role = Role.Switch, onValueChange = { enabled = it })
					.padding(horizontal = 8.dp),
				verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
			) {
				Text("Enabled", modifier = Modifier.padding(end = 4.dp))
				Switch(checked = enabled, onCheckedChange = null)
			}
			if (enabled && demoContent != DemoContent.Rich) {
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

		if (enabled) {
			TextEditorToolbar(
				mardkown = markdownExtension,
				markdownControls = (demoContent != DemoContent.Rich)
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
				.fillMaxSize(),
			style = style,
			enabled = enabled,
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