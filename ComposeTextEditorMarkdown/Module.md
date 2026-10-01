# Module Markdown

Markdown import and export for the Compose Text Editor: install it on a state to use
the rich text editor as a markdown editor. The document stays rich text; markdown is
the text it is read from and written to.

> **Try it live:** [open the Markdown demo on Wasm »](https://darkrock-studios.github.io/ComposeTextEditor/)

```kotlin
implementation("com.darkrockstudios:composetexteditor-markdown:2.0.0")
```

## Recipe

Wrap a state with [withMarkdown][com.darkrockstudios.texteditor.markdown.withMarkdown]
to import and export GitHub-flavored Markdown:

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

The styles the document is rendered and recognised with are the editor's own,
[TextEditorState.richTextStyles][com.darkrockstudios.texteditor.state.TextEditorState.richTextStyles]
(a [RichTextStyles][com.darkrockstudios.texteditor.RichTextStyles]); assign them
before importing, and change them through the state, which rebakes the headings.
[MarkdownConfiguration][com.darkrockstudios.texteditor.markdown.MarkdownConfiguration]
holds the syntax choices CommonMark leaves open: how a highlight is written
(`==text==` or `<mark>`) and whether a line is a paragraph, with a blank line after
it, or a source line.

The block toggles and queries (`toggleBulletList`, `toggleHeader`, `headerLevel`,
`setLink` and the rest) are on the state, in `com.darkrockstudios.texteditor.state`,
so a toolbar needs no markdown to drive them.

To format by typing markdown, install
[MarkdownShortcuts][com.darkrockstudios.texteditor.markdown.MarkdownShortcuts]:
`- `, `1. `, `# ` and `> ` at a line's start make the block, `**bold**` and the other
inline syntax the style, and one undo gives back what was typed. It needs no
`withMarkdown`; any rich text editor can take it:

```kotlin
state.editBehaviors.add(0, MarkdownShortcuts())
```

Images are only reconstructed on import when an
[ImageProvider][com.darkrockstudios.texteditor.richstyle.ImageProvider] is supplied
(`state.withMarkdown(imageProvider = myProvider)`); without one every `![alt](url)`
stays literal text.

To render only inline Markdown into an `AnnotatedString` (no block handling), use
[String.toAnnotatedStringFromMarkdown][com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown];
prefer `importMarkdown` whenever the source contains block elements.
[AnnotatedString.toMarkdown][com.darkrockstudios.texteditor.markdown.toMarkdown] is
the inverse.

## With spell check

A spell-checked editor is a `TextEditorState` underneath:

```kotlin
val state = rememberSpellCheckState(spellChecker = spellChecker)
val markdown = remember(state) { state.textState.withMarkdown() }

LaunchedEffect(markdown) { markdown.importMarkdown(source) }
```

# Package com.darkrockstudios.texteditor.markdown

Markdown import and export:
[withMarkdown][com.darkrockstudios.texteditor.markdown.withMarkdown],
[MarkdownExtension][com.darkrockstudios.texteditor.markdown.MarkdownExtension],
the syntax choices in
[MarkdownConfiguration][com.darkrockstudios.texteditor.markdown.MarkdownConfiguration],
and the `AnnotatedString` to Markdown converters.
