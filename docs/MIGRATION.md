# Migration

What changes for a host, such as Hammer, upgrading from 2.8.0 to
3.0.0. Two changes need code: markdown moved into its own module, and a
host's own key bindings should match letters on `layoutKey`. The rest is new
API that existing code does not need to touch, with notes for a host that
adopts it.

## Markdown as a module

Markdown import and export are out of `composetexteditor` and in
`composetexteditor-markdown`, and the character styles the editor formats with
moved from the markdown configuration onto the state. The package name, `com.darkrockstudios.texteditor.markdown`, is
unchanged, so the imports of `MarkdownExtension`, `withMarkdown`,
`MarkdownConfiguration`, `HighlightSyntax` and `ParagraphSeparator` stay as
they are; `RichTextStyles` (`com.darkrockstudios.texteditor`) and the block
API (`com.darkrockstudios.texteditor.state`, extension functions) are new
imports. The reasoning is in `docs/design/modules.md`.

1. **Add the artifact** beside the editor:

   ```kotlin
   implementation("com.darkrockstudios:composetexteditor-markdown:<version>")
   ```

2. **Styles live on the state.** `MarkdownConfiguration` holds the syntax
   choices alone (`highlightSyntax`, `paragraphSeparator`). Its style
   parameters, `defaultTextStyle`, `boldStyle`, `italicStyle`, `codeStyle`,
   `linkStyle`, `strikethroughStyle`, `underlineStyle`, `highlightStyle`,
   `blockquoteStyle` and `header1Style` to `header6Style`, are the same
   parameters of `com.darkrockstudios.texteditor.RichTextStyles`, assigned to
   `TextEditorState.richTextStyles` before the document is loaded:

   ```kotlin
   // Before
   val markdown = state.withMarkdown(MarkdownConfiguration(header1Style = big, boldStyle = red))

   // After
   state.richTextStyles = RichTextStyles(header1Style = big, boldStyle = red)
   val markdown = state.withMarkdown()
   ```

   `MarkdownConfiguration.DEFAULT_DARK` is `RichTextStyles.DEFAULT_DARK`;
   the old name is an error-level deprecation whose message says so, with no
   quick fix (an assignment to the state cannot be one), so the dark styles
   are assigned by hand. Assigning
   `richTextStyles` retires the old styles and restyles every heading line, as
   assigning `markdownConfiguration` on the extension did; the retired styles
   are `state.retiredRichTextStyles`.

3. **Reads of `state.markdownConfiguration`** become `state.richTextStyles`.
   `markdownExtension.markdownStyles` (`MarkdownStyles`) is deprecated; read
   `state.richTextStyles` directly (`BOLD` is `boldStyle`, `BASE_TEXT` is
   `defaultTextStyle`, `header(n)` is `getHeaderStyle(n)`).

4. **The block API is on the state.** `toggleBlockquote`, `toggleBulletList`,
   `toggleOrderedList`, `toggleCodeFence`, `toggleHeader`, `isBlockquote`,
   `isBulletList`, `isOrderedList`, `isCodeFence`, `listLevel`,
   `headerLevel`, `codeFenceLanguage`, `setCodeFenceLanguage`, `setLink` and
   `linkAt` are extension functions on `TextEditorState` in
   `com.darkrockstudios.texteditor.state`; `nestList` and `unnestList` are
   `nestListItems` and `unnestListItems` in
   `com.darkrockstudios.texteditor.richstyle`. The members on
   `MarkdownExtension` forward to them, deprecated, for one release. A toolbar
   no longer needs the extension.

5. **HTML takes no configuration.** `withHtml(configuration, imageProvider)`
   is `withHtml(imageProvider)` and `HtmlExtension(state, configuration,
   imageProvider)` is `HtmlExtension(state, imageProvider)`; both read
   `state.richTextStyles`. `HtmlExtension.configuration` is gone.
   `AnnotatedString.toHtml(styles)`, `String.toAnnotatedStringFromHtml(styles)`
   and `ClipboardHelper.getText(clipboard, styles)` and
   `setText(clipboard, text, styles, copyId, html)` take a `RichTextStyles`
   where they took the configuration.

6. **The converters.** `String.toAnnotatedStringFromMarkdown(styles)` takes a
   `RichTextStyles`; `AnnotatedString.toMarkdown(configuration, links,
   styles)` keeps the syntax choices first and takes the styles last.

7. **Headings come from blocks.** `HtmlExtension.exportAsHtml`, copy and
   `MarkdownExtension.exportAsMarkdown` write a line as a heading only when it
   carries a heading block (`HeaderSpanStyle`, which the importers and
   `toggleHeader` give it), not when its text is bold at a heading's size
   (markdown writes that as bold with its size); text loaded with `setText`
   from `toAnnotatedStringFromHtml` or `toAnnotatedStringFromMarkdown` has no
   blocks, so load documents through `importHtml` or `importMarkdown`. The
   standalone `AnnotatedString.toHtml` and `AnnotatedString.toMarkdown` still
   read a heading's size as the heading.

8. **Spell check.** `SpellCheckState.withMarkdown()` is gone (spell check does
   not depend on markdown): use `spellCheckState.textState.withMarkdown()`.

9. **A heading style equal to an inline style.** A heading line carries
   `RichTextStyles.headingLook(level)`, which is `getHeaderStyle(level)` unless
   that equals an inline style (`header4Style = boldStyle`); then it is the
   same look with the default platform style, so the user's bold inside the
   heading stays theirs when the heading goes. Code that finds a heading's look
   among a line's spans compares with `headingLook(level)`. Bold removed inside
   such a heading no longer removes the heading's look, as with any heading.

## Chords matched on `layoutKey`

The built-in key bindings match letter chords on `KeyEvent.layoutKey`
(`com.darkrockstudios.texteditor.input`) rather than `KeyEvent.key`. On desktop Linux, `key` names a letter by the
first keyboard layout installed, not the active one, so on BÉPO or Dvorak the
two can name different letters for the same key. On desktop macOS `key` names
a letter by what the key types without Cmd, so under "Dvorak - QWERTY ⌘" it
names the Dvorak letter where the Cmd chord is the QWERTY one. In a browser `key` is always
the key's US QWERTY position, so on any other layout (AZERTY, QWERTZ, BÉPO,
Dvorak) they differ for every letter a layout moves. A host's own `KeyBindings`
that tests `event.key` and delegates the rest to `platformKeyBindings()` should
test `event.layoutKey` instead, or its chords and the built-in ones land on
different keys:

```kotlin
// Before
if (event.key == Key.D && event.isCtrlShortcut) InsertDate else platformKeyBindings().commandFor(event)

// After
if (event.layoutKey == Key.D && event.isCtrlShortcut) InsertDate else platformKeyBindings().commandFor(event)
```

## Single line and wrapping off

`lineLimits = EditorLineLimits.SingleLine` keeps an editor's text on one row
that scrolls sideways to follow the caret, as `BasicTextField`'s single line
does. `softWrap = false` on `BasicTextEditor`, `TextEditor` and
`SpellCheckingTextEditor` does the same for a whole document (a code editor).
Nothing changes for an editor that wraps.

With wrapping off, the sideways scroll is `TextEditorState.horizontalScrollState`,
and what the state reports in view coordinates accounts for it as for the
vertical scroll: `getPositionForOffset`, `calculateCursorPosition` and
`lastCursorMetrics` subtract it, `getOffsetAtPosition` adds it. A `decorateLine`
decorator's offset is where the line's text is drawn, so it moves with the
sideways scroll; a gutter that placed itself by `offset.x` should use a fixed x
instead (the sample's `CodeEditor` does). Wrapping is the layout's, which the
state owns: a single-line editor stops every editor showing the same state from
wrapping.

## Decoration layers

A host can draw its own overlays on the text without making them part of it:
syntax colours, lint underlines, search matches. They enter no undo step, copy,
drag, saved state or export, lay out no line, and move with edits until
replaced. Each owner keys its own with a `DecorationLayer`
(`com.darkrockstudios.texteditor.decoration`), so several coexist. Nothing
changes for existing code; a host that marked its own `RichSpanStyle`s
`isDecoration` and filtered `getAllRichSpans()` to replace them can move to a
layer:

```kotlin
val syntax = DecorationLayer("syntax")
val keyword = Decoration(syntax, textColor = Color(0xFFCC7832))

state.setDecorations(syntax, listOf(RichSpan(range, keyword)))
state.replaceDecorations(syntax, lines = 10..12, spans = rescanned)
state.clearDecorations(syntax)
```

`Decoration` draws a text colour, a background and a solid, wavy or dotted
underline; implement `DecorationStyle` for a look of your own or one that
carries data for a click. A text colour tints the drawn text, so it wins over a
colour the text has of its own. `updateRichSpans` with only such spans no
longer re-resolves the lines they touch. Design: `docs/design/decorations.md`.

### Spell check and find on decoration layers

Find, spell check and diagnostics draw on decoration layers, and read and
replace their marks there instead of filtering `getAllRichSpans()`. Nothing
changes on screen, and their styles become `DecorationStyle`s:

- `FindMatchStyle` and `FindCurrentMatchStyle` are made with a colour and a
  `layer`. Each `FindState` draws on a layer of its own; the constructor taking
  only a colour, which existing code calls, puts a style on a layer no
  `FindState` reads or clears, which no `FindState` touched before either.
- `SpellCheckStyle` (core) takes a `layer` in a new protected constructor. The
  companion, and a subclass made with the no-argument constructor, are on spell
  check's layer, which `SpellCheckState` replaces and clears as before.
- `DiagnosticStyle` gains a last parameter, `layer`, defaulting to the layer
  `TextDiagnosticsState` replaces and clears. It takes part in `equals`. Calls
  compile unchanged, but code compiled against the old constructor or `copy`
  must be recompiled.

A `SpellCheckStyle` or `DiagnosticStyle` given a layer of the host's own is
left to the host: spell check and diagnostics neither clear it nor treat it as
one of their flags.

## Tables

Tables are a block now (`docs/design/tables.md`): a table is a run of cell lines,
each carrying a `TableCellSpanStyle`, laid out side by side. Nothing needs code,
but three things a host may notice:

- **Markdown.** A GFM pipe table outside a quote imports as a table where it
  imported as literal lines, and is written back in the editor's form
  (`| a | b |`, a `---` delimiter row, a blank line after it). A document that
  held a pipe table on purpose as text now shows it as a table. A table inside a
  quote stays literal text.
- **HTML.** A pasted or imported `<table>` becomes a table where its cells were
  tab-separated lines, and `toAnnotatedStringFromHtml` gives a cell a line.
- **Editing.** `editBehaviors` starts with `TableEditBehavior` ahead of
  `LineBlockEditBehavior`. A deletion or replace (`delete`, `replace`) across a
  table's cells clears them rather than joining them, and line breaks landing
  in a cell become spaces, ahead of a host's `inputFilter`.

The table API is in `com.darkrockstudios.texteditor.state`: `insertTable`,
`insertTableRow`, `deleteTableRow`, `insertTableColumn`, `deleteTableColumn`,
`setTableColumnAlignment`, `convertTableToText`, `deleteTable`, and `tableAt`
and `tableCellAt` to read one. `TextEditorStyle` gains `tableBorderColor` and
`tableHeaderBackgroundColor`.

## Keyboard content on Android

An Android host can take the GIFs, stickers and images a keyboard commits by
setting `TextEditorState.keyboardContentReceiver`
(`com.darkrockstudios.texteditor.input`). Without one the editor refuses them,
as before, and keyboards are not offered any. Inserting what arrives is the
host's work: for an image block, register the bitmap with the `ImageProvider`
the document uses. The sample app's `KeyboardImages.android.kt` does both.

```kotlin
state.keyboardContentReceiver = KeyboardContentReceiver(listOf("image/gif", "image/png")) { content, _ ->
	// Read content.contentUri off the main thread, then content.releasePermission().
	true
}
```
