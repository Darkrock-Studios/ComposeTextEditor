# Migration

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

7. **Spell check.** `SpellCheckState.withMarkdown()` is gone (spell check does
   not depend on markdown): use `spellCheckState.textState.withMarkdown()`.
