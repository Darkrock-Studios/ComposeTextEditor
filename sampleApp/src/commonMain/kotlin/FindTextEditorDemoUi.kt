package com.darkrockstudios.texteditor.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.TextEditor
import com.darkrockstudios.texteditor.find.FindBar
import com.darkrockstudios.texteditor.find.findShortcut
import com.darkrockstudios.texteditor.find.rememberFindState
import com.darkrockstudios.texteditor.rememberTextEditorStyle
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
	modifier: Modifier = Modifier,
	navigateTo: (Destination) -> Unit,
) {
	val textState = rememberTextEditorState(FIND_DEMO_TEXT)
	val findState = rememberFindState(textState)
	var showFindBar by remember { mutableStateOf(false) }

	Column(modifier = modifier) {
		Row {
			Text(
				"Find Demo",
				modifier = Modifier.padding(8.dp),
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold
			)
			Spacer(modifier = Modifier.weight(1f))

			Button(
				onClick = { showFindBar = !showFindBar },
				modifier = Modifier.padding(end = 8.dp)
			) {
				Text(if (showFindBar) "Hide Find" else "Show Find (Ctrl+F)")
			}

			Button(onClick = { navigateTo(Destination.Menu) }) {
				Text("X")
			}
		}

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

		val style = rememberTextEditorStyle(
			placeholderText = "Enter text here",
			textColor = MaterialTheme.colorScheme.onSurface,
		)

		TextEditor(
			state = textState,
			modifier = Modifier
				.padding(8.dp)
				.fillMaxSize()
				.findShortcut(findState) { showFindBar = !showFindBar },
			style = style,
		)
	}
}
