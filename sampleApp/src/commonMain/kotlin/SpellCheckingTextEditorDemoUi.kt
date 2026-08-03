package com.darkrockstudios.texteditor.sample

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.rememberTextEditorStyle
import com.darkrockstudios.texteditor.spellcheck.SpellCheckMode
import com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor
import com.darkrockstudios.texteditor.spellcheck.markdown.withMarkdown
import com.darkrockstudios.texteditor.spellcheck.rememberSpellCheckState

@Composable
fun SpellCheckingTextEditorDemoUi(
	modifier: Modifier = Modifier,
	navigateTo: (Destination) -> Unit,
	configuration: MarkdownConfiguration,
) {
	val spellChecker by rememberSampleSpellChecker()
	val imageProvider = rememberDemoImageProvider()
	// Start with empty content and load via `importMarkdown` so the line-block
	// pre-pass (HR, image, blockquote, list, code fence) gets a chance to run —
	// `toAnnotatedStringFromMarkdown` alone only handles inline markdown and
	// would leave block markers as literal text.
	val state = rememberSpellCheckState(
		spellChecker = spellChecker,
		initialText = null,
		enableSpellChecking = true,
		spellCheckMode = SpellCheckMode.Word,
	)
	val markdownExtension = remember(state, configuration, imageProvider) {
		state.withMarkdown(configuration, imageProvider = imageProvider)
	}

	LaunchedEffect(markdownExtension) {
		markdownExtension.importMarkdown(SIMPLE_MARKDOWN)
	}

	// `setText` (called inside `importMarkdown`) doesn't emit an editOperation,
	// so the spell-check listener never sees the import. The `LaunchedEffect`
	// inside `rememberSpellCheckState` runs `runFullSpellCheck` once when the
	// platform spellChecker first becomes non-null — but on Android the system
	// SpellCheckerSession initialises faster than `importMarkdown` runs, so
	// that one-shot check fires over empty text and squiggles never appear.
	// Re-running here keyed on `(spellChecker, markdownExtension)` makes the
	// check deterministic regardless of which side wins the init race.
	LaunchedEffect(spellChecker, markdownExtension) {
		if (spellChecker != null) {
			state.runFullSpellCheck()
		}
	}

	Column(modifier = modifier) {
		Row {
			Text(
				"Spell Checking",
				modifier = Modifier.padding(8.dp),
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold
			)
			if (spellChecker == null) {
				CircularProgressIndicator()
			}
			Spacer(modifier = Modifier.weight(1f))
			Button(onClick = { navigateTo(Destination.Menu) }) {
				Text("X")
			}
		}

		TextEditorToolbar(
			mardkown = markdownExtension,
			markdownControls = true
		)

		SpellCheckingTextEditor(
			state = state,
			// `textIndent` here gives plain paragraphs (and headers) a first-line
			// indent for a more "document-like" look. The block-style rich spans
			// (lists, blockquotes, code fences) read the actual text-left position
			// from the laid-out line when drawing their gutter markers, so they
			// line up correctly regardless of how this default indent merges with
			// their own per-paragraph indents: important on Compose Android, where
			// the per-paragraph override of `TextStyle.textIndent` doesn't reliably
			// win the merge.
			style = rememberTextEditorStyle(
				textStyle = TextStyle.Default.copy(
					textIndent = TextIndent(firstLine = 24.sp)
				)
			),
			modifier = Modifier
				.padding(16.dp)
				.fillMaxSize(),
		)
	}
}