# Migration

## Chords matched on `layoutKey`

From the first release after 2.8.0, the built-in key bindings match letter
chords on `KeyEvent.layoutKey` (`com.darkrockstudios.texteditor.input`)
rather than `KeyEvent.key`. On desktop Linux, `key` names a letter by the
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

## Keyboard content on Android

From the first release after 2.8.0, an Android host can take the GIFs,
stickers and images a keyboard commits by setting
`TextEditorState.keyboardContentReceiver`
(`com.darkrockstudios.texteditor.input`). Without one the editor refuses them,
as before, and keyboards are not offered any. Inserting what arrives is the
host's work: for an image block, register the bitmap with the
`ImageProvider` the document uses. The sample app's
`KeyboardImages.android.kt` does both.

```kotlin
state.keyboardContentReceiver = KeyboardContentReceiver(listOf("image/gif", "image/png")) { content, _ ->
	// Read content.contentUri off the main thread, then content.releasePermission().
	true
}
```

## Markdown as a module

From the first release after 2.8.0, markdown import and export are out of
`composetexteditor` and in `composetexteditor-markdown`, and the character
styles the editor formats with moved from the markdown configuration onto the
state. The package name, `com.darkrockstudios.texteditor.markdown`, is
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
