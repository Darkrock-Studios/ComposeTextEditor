# Modules

How the library is split into published artifacts, what each one owns, and the
seam between the core editor and the formats layered on it. Motivating item:
roadmap 7.52, from the owner's direction that this is a rich text editor which
can be used as a markdown editor, so markdown is layered on top of core rather
than baked into it.

## Design rules

1. **Core is a rich text editor.** `ComposeTextEditor` holds the document
   model, the block model (headings, lists and their nesting, quotes, code
   blocks with a language, rules, images, links), paragraph formats, the
   character styles those blocks and the formatting actions use, and HTML,
   which is rich text's interchange format (the clipboard speaks it). Core
   names no markdown type and pulls in no markdown parser.
2. **A format is an addon.** A textual format the document can be read from
   and written to lives in its own module, like find and spell check do, and
   builds on core's public API alone: the snapshot, the span API, the block
   API and the style configuration. If an addon needs a private door into
   core, the door is the design defect; the fix is a public seam, not a
   friend path.
3. **One owner for the styles.** The `SpanStyle`s that bold, headings, links,
   code and the rest use live on the state, in one place, that the built-in
   actions, the clipboard, HTML and every addon read. An addon never keeps a
   copy it has to sync.
4. **The package stays put.** The markdown package keeps its name,
   `com.darkrockstudios.texteditor.markdown`, so a host that adds the artifact
   changes no import. What moves between modules is code, not names.

## The modules

| Module | Artifact | Owns | Depends on |
| --- | --- | --- | --- |
| `ComposeTextEditor` | `composetexteditor` | The editor, its state, the block model, `RichTextStyles`, HTML import and export, the clipboard | Compose, ksoup |
| `ComposeTextEditorMarkdown` | `composetexteditor-markdown` | `MarkdownExtension`, markdown import and export, escaping, tables, inline HTML, the syntax options | core, `org.jetbrains:markdown` |
| `ComposeTextEditorFind` | `composetexteditor-find` | Find and replace | core |
| `ComposeTextEditorSpellCheck` | `composetexteditor-spellcheck` | Spell check and diagnostics | core, SymSpell, the platform checkers |

The addons depend on core (`api`) and never on each other. The sample app
depends on all four; its markdown demos install the markdown module and its
plain rich text demo does not. Each module is published under
`com.darkrockstudios` with the same version and has a `Module.md` for Dokka.
Four places list the modules by name and each names the new one: the root
`build.gradle.kts` Dokka aggregate, `deploy.yml` (assemble, publish, and the
summary), the desktop macOS and Windows jobs of `ci-build.yml` (the desktop
suites), and its iOS job (the iOS compile and simulator tests).

```
                 ┌───────────────────────────┐
                 │ ComposeTextEditor (core)  │
                 │ state, blocks, styles,    │
                 │ HTML, clipboard           │
                 └─────▲─────────▲─────▲─────┘
                       │         │     │
   ComposeTextEditorMarkdown   Find   SpellCheck
                       ▲         ▲     ▲
                       └────── sampleApp ┘
```

## The style configuration: `RichTextStyles`

`com.darkrockstudios.texteditor.RichTextStyles` is the one bundle of character
styles rich text formatting uses:

```kotlin
data class RichTextStyles(
	val defaultTextStyle: SpanStyle,   // the body text an importer lays over every paragraph
	val boldStyle: SpanStyle,
	val italicStyle: SpanStyle,
	val codeStyle: SpanStyle,
	val linkStyle: SpanStyle,
	val strikethroughStyle: SpanStyle,
	val underlineStyle: SpanStyle,
	val highlightStyle: SpanStyle,
	val blockquoteStyle: SpanStyle,
	val header1Style: SpanStyle, /* ... */ val header6Style: SpanStyle,
) {
	fun getHeaderStyle(level: Int): SpanStyle
	fun headingLook(level: Int): SpanStyle  // what a heading line bakes (line-blocks.md)
	fun exportedHeadingLooks(retired: List<RichTextStyles> = emptyList()): List<Set<SpanStyle>>  // what HTML and markdown export leave out of a heading line
	companion object { val DEFAULT: RichTextStyles; val DEFAULT_DARK: RichTextStyles }
}
```

It is the old `MarkdownConfiguration` less its two syntax choices, with the
field names kept so a host's construction migrates by renaming the type. It is
a separate type from `TextEditorStyle`, not a set of fields on it, because the
two live at different levels: `TextEditorStyle` is a composable's colours and
text style, rebuilt from the theme on recomposition and passed to `TextEditor`,
while these styles are baked into the document (a heading line carries its
style in its `AnnotatedString`, and the exporters recognise a span as bold by
equality with the configured bold), so they belong to the state, must be set
before content loads, and hold for the document's life.

`TextEditorState.richTextStyles` holds them, readable and assignable by the
host. Assigning a different value retires the old one
(`TextEditorState.retiredRichTextStyles`, oldest first) and swaps every heading
line's baked style for the new one, off the undo history, as
`MarkdownExtension` did for its own configuration; the serializers read a span
still carrying a retired configuration's style as that style's marker rather
than as the text's own colour. Assigning the styles, the default included, also
makes `defaultTextStyle` the style of text typed where the document carries
none, as the importers give every paragraph that style; an editor never
assigned styles types in `textStyle` alone. That is the rule
`hasMarkdownConfiguration` encoded, now on the property that a host sets.
`MarkdownExtension` and `HtmlExtension` assign it when they are installed, as
both do today, so a markdown editor types in the body style from its first
keystroke and not from its first import.

Everything in core that read `markdownConfiguration` reads `richTextStyles`:
the block registry (heading styles), normalization, the formatting toggles and
clear formatting, HTML import and export, the clipboard on every platform, and
drag and drop. `HtmlExtension` reads the state's. The converters and the
clipboard helper, which have no state, take a `RichTextStyles` where they took
the configuration: `toHtml(styles)`, `toAnnotatedStringFromHtml(styles)`,
`ClipboardHelper.getText(clipboard, styles)` and `setText(clipboard, text,
styles, copyId, html)`.

## The block API on the state

A rich text editor toggles a list without a markdown parser, so the block
operations `MarkdownExtension` offered are public on `TextEditorState`, in
`state/TextEditorStateBlockExt.kt`:

| Function | Does |
| --- | --- |
| `toggleBlockquote(lines)`, `toggleBulletList(lines)`, `toggleOrderedList(lines)`, `toggleCodeFence(lines)` | The sweeps of `docs/design/line-blocks.md`, "Toggle semantics" |
| `toggleHeader(lines, level)` | A heading of `level`, or off where every line already has it |
| `isBlockquote(line)`, `isBulletList(line)`, `isOrderedList(line)`, `isCodeFence(line)` | Whether the line carries the block, a list at any level |
| `headerLevel(line)`, `listLevel(line)` | The level, or null |
| `nestListItems(lines)`, `unnestListItems(lines)` | Tab and Shift+Tab on list items |
| `codeFenceLanguage(line)`, `setCodeFenceLanguage(line, language)` | The fence run's info string |
| `setLink(range, url)`, `linkAt(position)` | A link with the configured link style; refuses what `sanitizeLinkUrl` refuses |

`nestListItems` and `unnestListItems` are the internal functions of
`richstyle/ListNesting.kt`, moved to that file and made public, so one
extension of each name exists; the private `linkAt` of the pointer handling
is replaced by the public one. `MarkdownExtension` keeps each of these as a
deprecated forwarder for one release.

## The importer seam

An importer parses a document into text plus decorations and loads them as one
revision, off the undo history: loading a document is not something a user
undoes one list item at a time. Core offers that as public API, the internal
`applyDocumentBlocks` of `richstyle/DocumentBlocks.kt` made public and keyed
by span style:

```kotlin
fun TextEditorState.applyDocumentBlocks(
	horizontalRuleLines: Collection<Int> = emptyList(),
	imageLines: Map<Int, ImageBlockSpanStyle> = emptyMap(),
	blockLines: Map<RichSpanStyle, Collection<Int>> = emptyMap(),
	richSpans: Collection<RichSpan> = emptyList(),
)
```

`blockLines` is keyed by the block's span style, the per-level singletons a
host already names (`BlockquoteSpanStyle`, `HeaderSpanStyle.of(level)`,
`BulletListSpanStyle.of(level)`, `OrderedListSpanStyle.of(level)`,
`CodeFenceSpanStyle`); any other key is refused. Each line's blocks resolve in
registry order through the one resolution point, so a stack lands the same
whether a toggle, HTML or markdown placed it. `richSpans` are attached as they
are (links, fence languages). The HTML importer and the HTML paste use the same
function, so `HtmlDocument` keys its blocks by span style too. `setText`
before it, inside `editGroup`, makes the load one revision.

Reading goes through `snapshot()`: the lines and the rich spans of one revision,
from which an exporter finds each line's blocks by their span styles. Core
publishes the registry order as `LINE_BLOCK_STYLES`, the block span styles in
the order stacks resolve, and the markdown module orders its peel by it rather
than restating it, so a block added or reordered in core peels as core
resolves it. The two predicates the serializers share with the editor are
public in `richstyle`: `isNestingBlank(text, spansOnLine)` (the blank line
nesting looks through, so what the editor nests the file holds) and
`lineBlocksConflict(a, b)` (the stacking rules, so an importer peels only what
could share a line). The HTML helpers markdown's inline HTML shares,
`sanitizeLinkUrl`, `cssColorAndSize`, `parseCssColor` and `formatCssNumber`,
are public in `html`.

`LineBlockStyle`, core's internal bundle of a block's span, indent and baked
text style, loses its `markdownPrefix` and `markdownPattern`: the markdown
module keeps its own table of prefix and pattern per block span style, ordered
by `LINE_BLOCK_STYLES`. Adding a block style is one core instance plus one row
there.

## Where every public type lands

Core, `com.darkrockstudios.texteditor`:

| Type or member | Before | After |
| --- | --- | --- |
| `RichTextStyles` | did not exist | new, the style configuration |
| `TextEditorState.markdownConfiguration` | public get, internal set | removed; `richTextStyles`, public get and set |
| `TextEditorState.retiredRichTextStyles` | private to `MarkdownExtension` | new, read-only |
| Block functions above | members of `MarkdownExtension` | extension functions on `TextEditorState` |
| `applyDocumentBlocks` | internal, keyed by `LineBlockStyle` | public, keyed by `RichSpanStyle`, takes `richSpans` |
| `LINE_BLOCK_STYLES` | internal registry order | public list of the block span styles, in resolution order |
| `isNestingBlank`, `lineBlocksConflict` (was `conflicts`) | internal | public in `richstyle` |
| `sanitizeLinkUrl`, `cssColorAndSize`, `parseCssColor`, `formatCssNumber` | internal | public in `html` |
| `HtmlExtension(editorState, initialConfiguration, imageProvider)`, `withHtml(initialConfiguration, imageProvider)` | took a `MarkdownConfiguration` | `HtmlExtension(editorState, imageProvider)`, `withHtml(imageProvider)`; the styles are the state's |
| `HtmlExtension.configuration` | `MarkdownConfiguration` | removed: its type would change, so it cannot forward; read `editorState.richTextStyles` |
| `AnnotatedString.toHtml(configuration)` | `MarkdownConfiguration` | `toHtml(styles: RichTextStyles)` |
| `String.toAnnotatedStringFromHtml(configuration)` | `MarkdownConfiguration` | `toAnnotatedStringFromHtml(styles: RichTextStyles)` |
| `ClipboardHelper.getText(clipboard, configuration)`, `setText(clipboard, text, configuration, copyId, html)` | `MarkdownConfiguration` | `RichTextStyles` |
| `EditorCommand.Action.ToggleBold` and the other toggles | applied `markdownConfiguration`'s styles | apply `richTextStyles`' |

Markdown module, `com.darkrockstudios.texteditor.markdown` (the package name is
unchanged):

| Type or member | Before | After |
| --- | --- | --- |
| `MarkdownConfiguration` | styles and syntax | `MarkdownConfiguration(highlightSyntax, paragraphSeparator)`; `DEFAULT` |
| `MarkdownConfiguration.DEFAULT_DARK` | dark styles | deprecated at error level, pointing at `RichTextStyles.DEFAULT_DARK` |
| `HighlightSyntax`, `ParagraphSeparator` | core | unchanged, in the module |
| `MarkdownExtension`, `withMarkdown(initialConfiguration, imageProvider)` | core | unchanged signature; reads the styles from `editorState.richTextStyles` |
| `MarkdownExtension.markdownConfiguration` | set styles and syntax | sets the syntax |
| `MarkdownExtension.markdownStyles`, `MarkdownStyles` | the configuration's styles | deprecated forwarders over `richTextStyles` |
| `MarkdownExtension.toggleBlockquote` and the block members | the implementation | deprecated forwarders to the state's |
| `AnnotatedString.toMarkdown(configuration, links)` | core | `toMarkdown(configuration, links, styles)` |
| `String.toAnnotatedStringFromMarkdown(configuration)` | core | `toAnnotatedStringFromMarkdown(styles)`; the syntax choices play no part in a parse |
| `SpellCheckState.withMarkdown` | spell check module | removed: spell check does not depend on markdown; use `spellCheckState.textState.withMarkdown()` |

A `@Deprecated typealias` in core cannot name a type in the module without a
dependency cycle, so `TextEditorState.markdownConfiguration` and the
`MarkdownConfiguration` parameters of core's HTML and clipboard API are
removed outright; the deprecated names live in the module alone.

## Tests

The markdown suite (`markdown/` in core's desktop tests: round trip, fuzz,
fixpoint, escaping, tables, links, images, rules, fences, paragraphs, nesting)
moves with the code to the module's desktop tests, the import relayout cost
test and the typed body style test with it. Those tests reached into core
internals (`Blockquote` and the other `LineBlockStyle` instances,
`headerBlock`, `getRichSpansStartingOn`); they were rewritten onto the public
API (the span styles, the block API, the snapshot) while the package still
lived in core, so the move was a move.

Core's block-model tests build their documents and read their results in
block lines, a core-only notation over `applyDocumentBlocks` and the snapshot
(`testUtils/blockLines`; `docs/TESTING.md`, "Block lines"):
`setBlockLines("- a\n  - b")`, `assertEquals("- a\n  - b", blockLines())`.
It keeps the markdown-like markers a reviewer reads at a glance but is
strictly a line per line, with no inline syntax and nothing between lines, so
it holds only block structure and needs no parser beyond a prefix match.
Inline styles and links a test needs are set through the state. Core's
desktop tests depend on no markdown, so a markdown change cannot fail core's
suite and the IDE sees no project cycle (7.62).

What tests markdown itself is in the module: the round-trip torture test, the
fuzz fixpoint (the state fuzz is shared through `testUtils/stateFuzz`; core
keeps its undo-to-origin storms and checks the UI storms' blocks reload through
block lines), the markdown link safety and line ending cases, and the export of
a paragraph format. They use core's public API only, on a state with a mocked
measurer. The UI storms reach a markdown fixpoint there too (7.65), through a
small composed harness of the module's own (`markdownUiTest`) on core's public
API; the script driver (`testUtils/uiFuzz`) and the typing and clipboard helpers
(`testUtils/uiTest`) are shared with core's harness, which implements the
driver's `FuzzUiDriver` as the module's does. Core tests that used
`withMarkdown()` only for the toggles or the styles use the state's block API
and `richTextStyles` instead, and the Android and iOS host tests, which used
only the configuration, use `RichTextStyles`.

## Compatibility

For one release the module keeps deprecated forwarders wherever a member kept
its type, and error-level deprecations where a member's meaning moved (the
dark styles). `docs/MIGRATION.md`, written with the move, lists what a host
changes. The migration is mechanical:

1. Add `com.darkrockstudios:composetexteditor-markdown` beside
   `composetexteditor`.
2. `MarkdownConfiguration(...)` with style arguments becomes
   `RichTextStyles(...)` on `state.richTextStyles`; the syntax arguments stay
   on `MarkdownConfiguration`.
3. `state.markdownConfiguration` reads become `state.richTextStyles`.
4. `withHtml(configuration, provider)` becomes `withHtml(provider)` after
   setting `state.richTextStyles`.
5. `spellCheckState.withMarkdown()` becomes
   `spellCheckState.textState.withMarkdown()`.

## Chunks

1. This document.
2. Core: `RichTextStyles` and `richTextStyles`, every core reader switched,
   the block API, the importer seam, `LINE_BLOCK_STYLES`, the public
   predicates and HTML helpers; `MarkdownConfiguration` reduced to the syntax
   choices; the markdown package and its tests rewritten onto the public API
   while still in core. `docs/design/line-blocks.md` (the serialization hooks
   and the registry), `docs/design/editor-actions.md` (the toggles' styles,
   the block toggles) and the kdoc that names `markdownConfiguration` follow.
3. The module: the package and its tests moved, the parser dependency with
   them, the test-only dependency for core's fixtures (dropped by 7.62).
4. The deprecated forwarders, `SpellCheckState.withMarkdown` removed, the
   sample app, CI and publishing, `Module.md`s, the README, `MIGRATION.md`,
   the roadmap.
