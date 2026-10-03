package com.darkrockstudios.texteditor.sample

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.ui.graphics.vector.ImageVector

enum class DemoSection(val title: String) {
	Editors("Editors"),
	Features("Features"),
}

enum class Demo(
	val title: String,
	val description: String,
	val icon: ImageVector,
	val section: DemoSection,
) {
	RichText(
		title = "Rich text",
		description = "Styled spans, no markdown installed",
		icon = Icons.Default.FormatPaint,
		section = DemoSection.Editors,
	),
	Markdown(
		title = "Markdown",
		description = "Blocks, inline styles and a round trip",
		icon = Icons.AutoMirrored.Filled.Article,
		section = DemoSection.Editors,
	),
	BlankMarkdown(
		title = "Blank document",
		description = "Empty markdown, survives process death",
		icon = Icons.Default.EditNote,
		section = DemoSection.Editors,
	),
	Code(
		title = "Code editor",
		description = "Line numbers and syntax highlighting",
		icon = Icons.Default.Code,
		section = DemoSection.Editors,
	),
	SpellCheck(
		title = "Spell check",
		description = "Platform dictionaries and suggestions",
		icon = Icons.Default.Spellcheck,
		section = DemoSection.Features,
	),
	Find(
		title = "Find",
		description = "Search the document with Ctrl+F",
		icon = Icons.Default.Search,
		section = DemoSection.Features,
	),
	RichTextView(
		title = "Read-only view",
		description = "RichTextView, selectable and linkable",
		icon = Icons.Default.Preview,
		section = DemoSection.Features,
	),
}
