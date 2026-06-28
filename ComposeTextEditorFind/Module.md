# Module Find & Replace

A drop-in find & replace UI for the Compose Text Editor: a ready-made search bar,
live-updating match highlighting, next/previous navigation, and replace / replace-all.

> **Try it live:
** [open the Find demo on Wasm »](https://darkrock-studios.github.io/ComposeTextEditorLibrary/)

```kotlin
implementation("com.darkrockstudios:composetexteditor-find:2.0.0")
```

## Recipe

Create a [FindState][com.darkrockstudios.texteditor.find.FindState] for your editor's
state with [rememberFindState][com.darkrockstudios.texteditor.find.rememberFindState],
show the [FindBar][com.darkrockstudios.texteditor.find.FindBar], and wire up the
standard <kbd>Ctrl/Cmd+F</kbd> shortcut with
[Modifier.findShortcut][com.darkrockstudios.texteditor.find.findShortcut]:

```kotlin
@Composable
fun EditorWithFind() {
    val textState = rememberTextEditorState()
    val findState = rememberFindState(textState)
    var showFind by remember { mutableStateOf(false) }

    Column {
        AnimatedVisibility(visible = showFind) {
            FindBar(
                state = findState,
                onClose = { showFind = false },
            )
        }

        TextEditor(
            state = textState,
            modifier = Modifier
                .fillMaxSize()
                .findShortcut { showFind = !showFind },
        )
    }
}
```

The highlights update automatically as the user edits the text while a search is active.

## Driving search from code

[FindState][com.darkrockstudios.texteditor.find.FindState] exposes the full feature set
if you want to build your own UI:

```kotlin
findState.search("needle")          // also: caseSensitive via toggleCaseSensitive()
findState.findNext()                // and findPrevious()
findState.replaceCurrent("thread")  // replace the active match, advance to the next
val replaced = findState.replaceAll("thread")
findState.clearSearch()
```

For a headless, one-shot search with no state object, use
[TextEditorState.findAll][com.darkrockstudios.texteditor.find.findAll], which returns the
matching [TextEditorRange][com.darkrockstudios.texteditor.TextEditorRange]s in document
order.

# Package com.darkrockstudios.texteditor.find

Find & replace for the Compose Text Editor: the
[FindBar][com.darkrockstudios.texteditor.find.FindBar] UI, the
[FindState][com.darkrockstudios.texteditor.find.FindState] holder and its
[rememberFindState][com.darkrockstudios.texteditor.find.rememberFindState] factory, the
[findShortcut][com.darkrockstudios.texteditor.find.findShortcut] modifier, localizable
[FindBarStrings][com.darkrockstudios.texteditor.find.FindBarStrings], and the match
highlight styles.
