package com.darkrockstudios.texteditor.sample

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.RichTextStyles
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
	demo: Demo,
	onBack: (() -> Unit)?,
	styles: RichTextStyles,
	modifier: Modifier = Modifier,
) {
	val singleState = rememberMarkdownState(DEMO_MARKDOWN, styles)
	val cardSamples = listOf(
		rememberMarkdownState(
			"# Quick note\n\nA short **bold** opener with *italic* aside and a [link](https://example.com).",
			styles,
		),
		rememberMarkdownState(
			"## Meeting recap\n\nDiscussed ~~old approach~~ and the new plan.\n\n---\n\nFollow-up items below the rule.",
			styles,
		),
		rememberMarkdownState(
			"Plain paragraph with no markdown markers — should render as body text only.",
			styles,
		),
	)

	DemoScaffold(demo = demo, onBack = onBack, modifier = modifier) {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp, vertical = 8.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			SectionLabel("Selectable", "Drag to select, Ctrl+C to copy, right-click for the menu")
			Surface(
				shape = MaterialTheme.shapes.large,
				color = MaterialTheme.colorScheme.surfaceContainerLow,
				modifier = Modifier.fillMaxWidth(),
			) {
				RichTextView(
					state = singleState,
					modifier = Modifier.padding(20.dp),
					isSelectable = true,
					onLinkClick = LocalUriHandler.current::openUri,
				)
			}

			SectionLabel("In list cards", "Short documents laid out like feed items")
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				cardSamples.forEachIndexed { index, state ->
					Surface(
						shape = cardShape(index, cardSamples.size),
						color = MaterialTheme.colorScheme.surfaceContainerHigh,
						modifier = Modifier.fillMaxWidth(),
					) {
						RichTextView(
							state = state,
							modifier = Modifier.padding(16.dp),
							isSelectable = true,
							onLinkClick = LocalUriHandler.current::openUri,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun SectionLabel(title: String, detail: String) {
	Column(modifier = Modifier.padding(start = 4.dp, top = 12.dp)) {
		Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
		Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

/** Large outer corners and small inner ones, so the cards read as one group. */
private fun cardShape(index: Int, count: Int): RoundedCornerShape {
	val outer = 20.dp
	val inner = 4.dp
	return RoundedCornerShape(
		topStart = if (index == 0) outer else inner,
		topEnd = if (index == 0) outer else inner,
		bottomStart = if (index == count - 1) outer else inner,
		bottomEnd = if (index == count - 1) outer else inner,
	)
}

@Composable
private fun rememberMarkdownState(
	markdown: String,
	styles: RichTextStyles,
): TextEditorState {
	val state = rememberTextEditorState()
	remember(state, styles) { state.richTextStyles = styles }
	val markdownExtension = remember(state) { state.withMarkdown() }
	LaunchedEffect(markdownExtension, markdown) {
		markdownExtension.importMarkdown(markdown)
	}
	return state
}
