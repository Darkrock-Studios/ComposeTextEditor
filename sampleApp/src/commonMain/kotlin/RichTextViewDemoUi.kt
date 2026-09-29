package com.darkrockstudios.texteditor.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState

private const val DEMO_MARKDOWN = """# RichTextView with HR

This is the read-only renderer using the same draw pipeline as the editor.

---

The line above is a horizontal rule rendered via `HorizontalRuleSpanStyle`. Inline styles like **bold**, *italic*, and ~~strikethrough~~ flow through too.

## Sub-section

A second section after the rule, to verify spacing and span isolation."""

@Composable
fun RichTextViewDemoUi(
	modifier: Modifier = Modifier,
	navigateTo: (Destination) -> Unit,
	configuration: MarkdownConfiguration,
) {
	val singleState = rememberMarkdownState(DEMO_MARKDOWN, configuration)
	val cardSamples = listOf(
		rememberMarkdownState(
			"# Quick note\n\nA short **bold** opener with *italic* aside and a [link](https://example.com).",
			configuration,
		),
		rememberMarkdownState(
			"## Meeting recap\n\nDiscussed ~~old approach~~ and the new plan.\n\n---\n\nFollow-up items below the rule.",
			configuration,
		),
		rememberMarkdownState(
			"Plain paragraph with no markdown markers — should render as body text only.",
			configuration,
		),
	)

	Column(modifier = modifier.fillMaxSize()) {
		Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
			Text(
				"RichTextView Demo",
				modifier = Modifier.padding(8.dp),
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold,
			)
			Spacer(modifier = Modifier.weight(1f))
			Button(onClick = { navigateTo(Destination.Menu) }) { Text("X") }
		}

		Column(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(24.dp),
		) {
			Text(
				"Selectable RichTextView (drag to select, Ctrl+C to copy, right-click for menu):",
				style = MaterialTheme.typography.titleMedium
			)
			RichTextView(
				state = singleState,
				modifier = Modifier.fillMaxWidth(),
				isSelectable = true,
				onLinkClick = LocalUriHandler.current::openUri,
			)

			HorizontalDivider()

			Text("Inside list-style cards:", style = MaterialTheme.typography.titleMedium)
			cardSamples.forEach { state ->
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.border(
							width = 1.dp,
							color = MaterialTheme.colorScheme.outlineVariant,
							shape = RoundedCornerShape(8.dp),
						)
						.background(
							color = MaterialTheme.colorScheme.surfaceContainerLow,
							shape = RoundedCornerShape(8.dp),
						)
						.padding(12.dp),
				) {
					RichTextView(state = state, isSelectable = true)
				}
			}
		}
	}
}

@Composable
private fun rememberMarkdownState(
	markdown: String,
	configuration: MarkdownConfiguration,
): TextEditorState {
	val state = rememberTextEditorState()
	val markdownExtension = remember(state, configuration) { state.withMarkdown(configuration) }
	LaunchedEffect(markdownExtension, markdown) {
		markdownExtension.importMarkdown(markdown)
	}
	return state
}
