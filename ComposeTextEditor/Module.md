# Module Editor

A Kotlin Multiplatform rich text editor for Compose — a from-scratch alternative to
`BasicTextField` that supports rich spans, block decorations (lists, blockquotes, code
fences, images), efficient long-form rendering, and a per-edit change stream.

> **Try it live:
** [interactive demo running on Wasm »](https://darkrock-studios.github.io/ComposeTextEditor/)

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

## HTML

Wrap a state with [withHtml][com.darkrockstudios.texteditor.html.withHtml] to read and
write the document as HTML:

```kotlin
val state = rememberTextEditorState()
val html = remember(state) { state.withHtml() }

LaunchedEffect(html) {
    html.importHtml(
        """
        <h1>Title</h1>
        <p>Some <strong>bold</strong> and <em>italic</em> text.</p>
        <ul><li>one</li><li>two</li></ul>
        """.trimIndent()
    )
}

TextEditor(state = state)

// Export the current document back to an HTML fragment:
val source: String = html.exportAsHtml()
```

Both directions carry the whole document: headings, bold/italic/underline/strikethrough,
inline code, lists, blockquotes, code fences, horizontal rules and images. The output is a
fragment — no `<html>` or `<body>` wrapper — so it can be embedded directly.

Import and export can share a state with `withMarkdown`, which is how a document is
converted between the two formats:

```kotlin
val markdown = remember(state) { state.withMarkdown() }
val html = remember(state) { state.withHtml() }

markdown.importMarkdown(source)
val asHtml: String = html.exportAsHtml()
```

Images are only reconstructed on import when an
[ImageProvider][com.darkrockstudios.texteditor.richstyle.ImageProvider] is supplied
(`state.withHtml(imageProvider = myProvider)`); without one every `<img>` is dropped.
Custom heading sizes only survive a round trip when the same
[MarkdownConfiguration][com.darkrockstudios.texteditor.markdown.MarkdownConfiguration] is
used in both directions, since heading levels are matched by font size.

To convert an `AnnotatedString` alone, without block structure, use
[AnnotatedString.toHtml][com.darkrockstudios.texteditor.html.toHtml] and
[String.toAnnotatedStringFromHtml][com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml]
— these are also what the clipboard uses, so pasting from a browser or word processor
keeps its formatting and its list/quote/code-block structure.

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

# Package com.darkrockstudios.texteditor.html

HTML import/export: [withHtml][com.darkrockstudios.texteditor.html.withHtml] for whole
documents, and the `AnnotatedString` ⇄ HTML converters for inline styling alone.

# Package com.darkrockstudios.texteditor.contextmenu

The cut/copy/paste context menu — its state, actions, and localizable strings. Pass a
[TextEditorContextMenuState][com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState]
to [BasicTextEditor][com.darkrockstudios.texteditor.BasicTextEditor] to add your own
items (for example, spell-check suggestions).
