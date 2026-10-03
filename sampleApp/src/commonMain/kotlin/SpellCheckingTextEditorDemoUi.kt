package com.darkrockstudios.texteditor.sample

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.spellcheck.SpellCheckMode
import com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor
import com.darkrockstudios.texteditor.spellcheck.rememberSpellCheckState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SpellCheckingTextEditorDemoUi(
	demo: Demo,
	onBack: (() -> Unit)?,
	styles: RichTextStyles,
	modifier: Modifier = Modifier,
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
	remember(state, styles) { state.textState.richTextStyles = styles }
	val markdownExtension = remember(state, imageProvider) {
		state.textState.withMarkdown(imageProvider = imageProvider)
	}

	LaunchedEffect(markdownExtension) {
		markdownExtension.importMarkdown(SIMPLE_MARKDOWN)
	}

	DemoScaffold(
		demo = demo,
		onBack = onBack,
		modifier = modifier,
		actions = {
			if (spellChecker == null) {
				LoadingIndicator(modifier = Modifier.padding(end = 8.dp).size(40.dp))
			}
		},
	) {
		TextEditorToolbar(
			state = state.textState,
			markdownControls = true,
			modifier = Modifier.padding(horizontal = 12.dp),
		)

		EditorFrame(
			focused = state.textState.hasFocus,
			modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp).fillMaxSize(),
		) {
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
				style = rememberFramedEditorStyle(
					textStyle = TextStyle.Default.copy(
						textIndent = TextIndent(firstLine = 24.sp)
					)
				),
				modifier = Modifier.fillMaxSize(),
				onLinkClick = LocalUriHandler.current::openUri,
			)
		}
	}
}