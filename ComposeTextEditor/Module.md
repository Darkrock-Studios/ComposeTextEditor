# Module Editor

A Kotlin Multiplatform rich text editor for Compose — a from-scratch alternative to
`BasicTextField` that supports rich spans, block decorations (lists, blockquotes, code
fences, images), efficient long-form rendering, and a per-edit change stream.

> **Try it live:
** [interactive demo running on Wasm »](https://darkrock-studios.github.io/ComposeTextEditorLibrary/)

```kotlin
implementation("com.darkrockstudios:composetexteditor:2.0.0")
```

The find/replace and spell-check features live in separate add-on modules; see the
**Find & Replace** and **Spell Check** modules.

## Getting started

Hoist a [TextEditorState][com.darkrockstudios.texteditor.state.TextEditorState] with
[rememberTextEditorState][com.darkrockstudios.texteditor.state.rememberTextEditorState]
and hand it to [TextEditor][com.darkrockstudios.texteditor.TextEditor]:

```kotlin
@Composable
fun Notepad() {
    val state = rememberTextEditorState()

    TextEditor(
        state = state,
        modifier = Modifier.fillMaxSize(),
    )
}
```

Seed the editor with content, then read it back or react to edits through the state:

```kotlin
val state = rememberTextEditorState(AnnotatedString("Hello, world!"))

// Read the whole document at any time:
val text: AnnotatedString = state.getAllText()

// Or react to individual edits as the user types:
LaunchedEffect(state) {
    state.editOperations.collect { op -> /* one change per emission */ }
}
```

Colors and text style come from
[TextEditorStyle][com.darkrockstudios.texteditor.TextEditorStyle]. Use
[rememberTextEditorStyle][com.darkrockstudios.texteditor.rememberTextEditorStyle] to
derive sensible defaults from your `MaterialTheme`:

```kotlin
TextEditor(
    state = state,
    style = rememberTextEditorStyle(
        placeholderText = "Start typing…",
    ),
)
```

For an editor with no surface/border chrome, a custom context menu, or per-line
decoration, drop down to
[BasicTextEditor][com.darkrockstudios.texteditor.BasicTextEditor]. For a read-only
rendering of the same content, use
[RichTextView][com.darkrockstudios.texteditor.RichTextView].

## Markdown

Wrap a state with [withMarkdown][com.darkrockstudios.texteditor.markdown.withMarkdown]
to import and export GitHub-flavored Markdown and toggle block styles (lists,
blockquotes, code fences):

```kotlin
val state = rememberTextEditorState()
val markdown = remember(state) { state.withMarkdown() }

// Import handles both inline (**bold**, *italic*, `code`) and block elements
// (headings, lists, blockquotes, code fences, horizontal rules):
LaunchedEffect(markdown) {
    markdown.importMarkdown(
        """
        # Title

        Some **bold** and *italic* text.

        - one
        - two
        """.trimIndent()
    )
}

TextEditor(state = state)

// Export the current document back to a Markdown string:
val source: String = markdown.exportAsMarkdown()
```

To render only inline Markdown into an `AnnotatedString` (no block handling), use
[String.toAnnotatedStringFromMarkdown][com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown]
— but prefer `importMarkdown` whenever the source contains block elements.

# Package com.darkrockstudios.texteditor

The editor composables ([TextEditor][com.darkrockstudios.texteditor.TextEditor],
[BasicTextEditor][com.darkrockstudios.texteditor.BasicTextEditor],
[RichTextView][com.darkrockstudios.texteditor.RichTextView]), styling
([TextEditorStyle][com.darkrockstudios.texteditor.TextEditorStyle]), and the core
coordinate types ([CharLineOffset][com.darkrockstudios.texteditor.CharLineOffset],
[TextEditorRange][com.darkrockstudios.texteditor.TextEditorRange]) used throughout the API.

# Package com.darkrockstudios.texteditor.state

[TextEditorState][com.darkrockstudios.texteditor.state.TextEditorState] — the single
source of truth for a document (text, cursor, selection, rich spans, scroll, undo
history) —
its [rememberTextEditorState][com.darkrockstudios.texteditor.state.rememberTextEditorState]
factory, and the extension functions for editing and querying it.

# Package com.darkrockstudios.texteditor.richstyle

Rich span styles: the [RichSpanStyle][com.darkrockstudios.texteditor.richstyle.RichSpanStyle]
contract and the built-in decorations (bullet/ordered lists, blockquotes, code fences,
horizontal rules, images, and highlights). Implement `RichSpanStyle` to draw your own.

# Package com.darkrockstudios.texteditor.markdown

Markdown import/export and configuration:
[withMarkdown][com.darkrockstudios.texteditor.markdown.withMarkdown],
[MarkdownConfiguration][com.darkrockstudios.texteditor.markdown.MarkdownConfiguration],
and the `AnnotatedString` ⇄ Markdown converters.

# Package com.darkrockstudios.texteditor.contextmenu

The cut/copy/paste context menu — its state, actions, and localizable strings. Pass a
[TextEditorContextMenuState][com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState]
to [BasicTextEditor][com.darkrockstudios.texteditor.BasicTextEditor] to add your own
items (for example, spell-check suggestions).
