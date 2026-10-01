# Module Editor

A Kotlin Multiplatform rich text editor for Compose — a from-scratch alternative to
`BasicTextField` that supports rich spans, block decorations (lists, blockquotes, code
fences, images), efficient long-form rendering, and a per-edit change stream.

> **Try it live:
** [interactive demo running on Wasm »](https://darkrock-studios.github.io/ComposeTextEditor/)

```kotlin
implementation("com.darkrockstudios:composetexteditor:2.0.0")
```

Markdown, find and replace, and spell check live in separate add-on modules; see the
**Markdown**, **Find & Replace** and **Spell Check** modules. The editor is rich text;
installing the markdown module on a state is how it is used as a markdown editor.

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

## Styles and blocks

The character styles rich text formatting uses (bold, italic, code, links, the
heading sizes, the body text) are one bundle,
[RichTextStyles][com.darkrockstudios.texteditor.RichTextStyles], on
[TextEditorState.richTextStyles][com.darkrockstudios.texteditor.state.TextEditorState.richTextStyles].
The built-in formatting actions, HTML, the clipboard and the format addons all read
it; assign it before loading content:

```kotlin
val state = rememberTextEditorState()
state.richTextStyles = RichTextStyles.DEFAULT_DARK
```

Lists, blockquotes, code fences, headings and links are toggled and queried on the
state: [toggleBulletList][com.darkrockstudios.texteditor.state.toggleBulletList],
[toggleHeader][com.darkrockstudios.texteditor.state.toggleHeader],
[headerLevel][com.darkrockstudios.texteditor.state.headerLevel],
[setLink][com.darkrockstudios.texteditor.state.setLink] and the rest of the block
API in `com.darkrockstudios.texteditor.state`.

## HTML

Wrap a state with [withHtml][com.darkrockstudios.texteditor.html.withHtml] to
read and write the document as HTML:

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

Both directions carry the whole document: headings, bold, italic, underline,
strikethrough, inline code, lists, blockquotes, code fences, horizontal rules
and images. The output is a fragment — no `<html>` or `<body>` wrapper — so it
can be embedded directly.

Import and export can share a state with the markdown module's `withMarkdown`,
which is how a document is converted between the two formats:

```kotlin
val markdown = remember(state) { state.withMarkdown() }
val html = remember(state) { state.withHtml() }

markdown.importMarkdown(source)
val asHtml: String = html.exportAsHtml()
```

Images are only reconstructed on import when an
[ImageProvider][com.darkrockstudios.texteditor.richstyle.ImageProvider] is
supplied (`state.withHtml(imageProvider = myProvider)`); without one every
`<img>` is dropped. Custom heading sizes only survive a round trip when the
state's [RichTextStyles][com.darkrockstudios.texteditor.RichTextStyles] are the
same in both directions, since a spanless heading is matched by font size.

To convert an `AnnotatedString` alone, without block structure, use
[AnnotatedString.toHtml][com.darkrockstudios.texteditor.html.toHtml] and
[String.toAnnotatedStringFromHtml][com.darkrockstudios.texteditor.html.toAnnotatedStringFromHtml]
— these are also what the clipboard uses, so pasting from a browser or word
processor keeps its formatting and its list/quote/code-block structure.

## Snapshots

[snapshot][com.darkrockstudios.texteditor.state.TextEditorState.snapshot] returns the
document as an immutable
[DocumentSnapshot][com.darkrockstudios.texteditor.state.DocumentSnapshot]: its lines
and rich spans, both from the same revision. It is safe to hold and to read from any
thread, which makes it the right input for an autosave or a background exporter.

[setDocument][com.darkrockstudios.texteditor.state.TextEditorState.setDocument] loads
one back, rich spans included, so a document can move between editors without a
Markdown round trip:

```kotlin
// Keep an unsaved document in memory while its editor is gone:
val buffer: DocumentSnapshot = state.snapshot()

// Later, load it into a newly composed editor:
newState.setDocument(buffer)
```

`setText` would drop the rules, images, code fences, and list and quote markers,
since those live in rich spans rather than in the `AnnotatedString`.

Like any document load, `setDocument` clears undo history and the selection, and
it is not reported on `editOperations`; watch
[documentGeneration][com.darkrockstudios.texteditor.state.TextEditorState.documentGeneration]
to learn the document was replaced. Spell-check underlines and find highlights
are not carried over, because the target editor computes its own. A hand-built
`DocumentSnapshot(lines, richSpans)` is accepted too; spans that fall outside its
lines are clamped onto them.

# Package com.darkrockstudios.texteditor

The editor composables ([TextEditor][com.darkrockstudios.texteditor.TextEditor],
[BasicTextEditor][com.darkrockstudios.texteditor.BasicTextEditor],
[RichTextView][com.darkrockstudios.texteditor.RichTextView]), styling
([TextEditorStyle][com.darkrockstudios.texteditor.TextEditorStyle],
[RichTextStyles][com.darkrockstudios.texteditor.RichTextStyles]), and the core
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

# Package com.darkrockstudios.texteditor.html

HTML import/export: [withHtml][com.darkrockstudios.texteditor.html.withHtml]
for whole documents, and the `AnnotatedString` ⇄ HTML converters for inline
styling alone.

# Package com.darkrockstudios.texteditor.contextmenu

The cut/copy/paste context menu — its state, actions, and localizable strings. Pass a
[TextEditorContextMenuState][com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState]
to [BasicTextEditor][com.darkrockstudios.texteditor.BasicTextEditor] to add your own
items (for example, spell-check suggestions).
