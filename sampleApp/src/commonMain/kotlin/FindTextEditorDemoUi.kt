package com.darkrockstudios.texteditor.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.TextEditor
import com.darkrockstudios.texteditor.find.FindBar
import com.darkrockstudios.texteditor.find.findShortcut
import com.darkrockstudios.texteditor.find.rememberFindState
import com.darkrockstudios.texteditor.state.rememberTextEditorState

private val FIND_DEMO_TEXT = AnnotatedString(
	"""
Welcome to the Find Demo!

This demonstrates the Find feature for the Compose Text Editor library.
Try pressing Ctrl+F (or Cmd+F on Mac) to open the find bar.

You can search for any text in this document. For example:
- Try searching for "find" to see multiple matches
- Search for "Compose" to find it in the text
- The current match is highlighted in orange
- Other matches are highlighted in yellow

Use the "Prev" and "Next" buttons to navigate between matches.
You can also press Enter or F3 to go to the next match, or Shift+Enter or Shift+F3 for previous.
Select a word before opening the find bar to search for it.

Press Escape to close the find bar.

The find feature automatically updates when you edit the text while searching.
Try typing something and see how the search results update in real-time!
""".trimIndent()
)

@Composable
fun FindTextEditorDemoUi(
	demo: Demo,
	onBack: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	val textState = rememberTextEditorState(FIND_DEMO_TEXT)
	val findState = rememberFindState(textState)
	var showFindBar by remember { mutableStateOf(false) }

	DemoScaffold(
		demo = demo,
		onBack = onBack,
		modifier = modifier,
		actions = {
			ActionButton(
				icon = if (showFindBar) Icons.Default.SearchOff else Icons.Default.Search,
				label = if (showFindBar) "Hide find bar" else "Show find bar",
			) { showFindBar = !showFindBar }
		},
	) {
		AnimatedVisibility(
			visible = showFindBar,
			enter = expandVertically(expandFrom = Alignment.Top),
			exit = shrinkVertically(shrinkTowards = Alignment.Top)
		) {
			FindBar(
				state = findState,
				onClose = { showFindBar = false }
			)
		}

		EditorFrame(
			focused = textState.hasFocus,
			modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp).fillMaxSize(),
		) {
			TextEditor(
				state = textState,
				modifier = Modifier
					.fillMaxSize()
					.findShortcut(findState) { showFindBar = !showFindBar },
				style = rememberFramedEditorStyle(placeholderText = "Enter text here"),
				contentDescription = "Document",
			)
		}
	}
}
