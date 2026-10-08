# Compose Text Editor

[![Maven Central](https://img.shields.io/maven-central/v/com.darkrockstudios/composetexteditor.svg)](https://search.maven.org/artifact/com.darkrockstudios/composetexteditor)
![License](https://img.shields.io/badge/license-MIT-blue.svg)
[![CI Build](https://github.com/Darkrock-Studios/ComposeTextEditor/actions/workflows/ci-build.yml/badge.svg)](https://github.com/Darkrock-Studios/ComposeTextEditor/actions/workflows/ci-build.yml)
[![API Docs](https://img.shields.io/badge/docs-API_reference-blue.svg)](https://darkrock-studios.github.io/ComposeTextEditor/api/)

[![KMP](https://img.shields.io/badge/platforms:-blue.svg?logo=kotlin)](http://kotlinlang.org)
![badge-jvm] ![badge-android] ![badge-wasm] ![badge-ios]

Compose has been missing a **Rich Text Editor** since its inception.
I've [taken a crack](https://github.com/Wavesonics/richtext-compose-multiplatform) at this
previously, as have [others](https://github.com/MohamedRejeb/Compose-Rich-Editor).

However, they have all suffered from fundamental limitations in `BasicTextField`, the foundation of
all text entry in Compose.

This project is an attempt to re-implement text entry from scratch to finally have a
solution to the various problems.

### Why?

I've been trying to implement a [spell checking](https://github.com/Wavesonics/SymSpellKt) text
field in Compose, and keep running up against
the limitations of `BasicTextField`. Pretty much out of options, I decided to see what it might take
to replace `BasicTextField` with something that solved all of my needs.

And now, it's working, and at this point, working pretty well.

- ✅ 100% Compose Multiplatform
- ✅ Behaves like each platform's own text fields: keys, mouse, touch, IME, clipboard
- ✅ Fast on long documents
- ✅ Rich text with custom spans
- ✅ Block structure: headings, nested lists, task lists, quotes, code fences, tables, rules, images, links
- ✅ Spell check, find & replace, and diagnostics from your own checker
- ✅ Screen readers
- ✅ HTML import, export and clipboard
- ☑️ Markdown, as an addon
  - CommonMark: 483 of the spec's 652 examples ([spec support](#markdown-spec-support))
  - GitHub Flavored Markdown: tables, task lists and strikethrough
  - Underline (`<u>`), highlight (`==text==` or `<mark>`), colour and size (`<span style>`)
  - Opt-in shortcuts that format as you type (`# `, `- `, `**bold**`)

You can [Give it a try here](https://darkrock-studios.github.io/ComposeTextEditor/), or
browse
the [API reference & recipes](https://darkrock-studios.github.io/ComposeTextEditor/api/).

![sample_screenshot_00.png](sample_screenshot_00.png)

### Features:

- Only what is visible is drawn: a 200,000 character document opens in about 7 ms on desktop
- Exposed scroll state, so we can render scroll bars (_BTF1 can't do this_)
- Doesn't copy and return full contents on each edit, so better for longer form text (_BTF2
  also works this way, but doesn't support AnnotatedString for rich content_)
- Custom Rich Span drawing (_this lets us render the traditional Spell Check red squiggle_)
- Emits edit events: collect a Flow and know exactly what changed, so spell check only
  re-checks the word that was edited (_BTF2 now finally offers this!_)
- Undo and redo, one step per user action
- Decoration layers: highlights such as find matches or syntax colours that stay out of
  undo and export
- Paragraph formatting: spacing, alignment, indents and line height
- Read-only, single line, max length, input filters and no-wrap modes
- State that survives process death, or lives in your view model
- Opt-in smart punctuation and auto-linking ([docs/design/behaviors.md](docs/design/behaviors.md))

#### Markdown spec support

Green is supported and yellow is not supported yet. Grey is not planned:

- Raw HTML. Rendering freeform HTML would mean building an HTML rendering engine, so HTML
  beyond the style tags above is kept as text.
- What the editor's line model cannot hold. A line is a paragraph with one stack of blocks
  (a quote around a list item around its text), so blocks nested any other way, such as
  a code block inside a list item, lose their nesting but keep their content. The editor
  also keeps every empty line, which markdown can only write as a blank line a renderer
  drops.

**CommonMark**: 483 of the spec's 652 examples

![CommonMark: 483 supported, 48 not yet, 121 not planned](docs/images/commonmark-support.svg)

- Image titles, reference images, and links or images in alt text (16)
- Code spans across lines (6)
- Nested links, and spaces in a link destination (6)
- Lines continuing a list item or quote (5)
- Escaped delimiters and symbols in emphasis (5)
- Smaller edge cases (10)

Not planned: raw HTML (72), blocks inside list items or block quotes (26), list items
holding more than one paragraph (10), blank lines kept as empty lines (10), and images
inside text or links (3).

**GitHub Flavored Markdown extensions**: 12 of 24 examples

![GFM extensions: 12 supported, 11 not yet, 1 not planned](docs/images/gfm-support.svg)

- Tables, task lists and strikethrough are supported
- Bare links (`www.example.com`, `user@example.com`) are not made links yet (11)
- Not planned: filtering raw HTML tags (1), since raw HTML is kept as text

#### Platforms

| Platform | Status |
| --- | --- |
| Desktop (JVM) | Supported |
| Android | Supported |
| iOS | Beta: typing, autocorrect, CJK composition, the edit menu, loupe and touch handles |
| WASM | Beta: typing, IME composition, the rich clipboard and drag and drop |

## Want to try it?

Text Editor:

`implementation("com.darkrockstudios:composetexteditor:3.0.3")`

Markdown addon, to use the editor as a markdown editor (`state.withMarkdown()`):

`implementation("com.darkrockstudios:composetexteditor-markdown:3.0.3")`

Spell Checking addon:

`implementation("com.darkrockstudios:composetexteditor-spellcheck:3.0.3")`

Find & Replace addon:

`implementation("com.darkrockstudios:composetexteditor-find:3.0.3")`

Or take the BOM and leave the versions off the modules, so they always match:

```kotlin
implementation(platform("com.darkrockstudios:composetexteditor-bom:3.0.3")
implementation("com.darkrockstudios:composetexteditor")
implementation("com.darkrockstudios:composetexteditor-markdown")
implementation("com.darkrockstudios:composetexteditor-spellcheck")
implementation("com.darkrockstudios:composetexteditor-find")
```

Upgrading from 2.x (markdown as its own module, decoration layers,
shortcuts matched on the keyboard layout): see [docs/MIGRATION.md](docs/MIGRATION.md).
How the editor is built is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and how it is
tested in [docs/TESTING.md](docs/TESTING.md).

On iOS, host the Compose view with `.ignoresSafeArea(.keyboard)` in SwiftUI, as the
Compose Multiplatform template does. The editor keeps its caret above the keyboard itself;
letting SwiftUI shrink the view as well moves the content twice.

On Android, declare `android:windowSoftInputMode="adjustResize"` on the activity, as
Compose apps should; otherwise Android also pans the whole window to the caret. The
editor keeps its caret in view whether the host pads it by the keyboard's inset
(`imePadding`) or lets the keyboard cover it. `TextEditorState.keyboardSettings` asks the
keyboard for its capitalisation, autocorrect, layout and action key; an editor for code
would turn capitals and autocorrect off. Android, iOS and the web honour it; the web
ignores autocorrect.

## Really?

I don't know. Maybe. It might not be a permanent solution.
`BasicTextField2`'s new state based approach and gap-buffer has reached maturity and includes a lot of what I would need
to replace this, but still cannot handle rich text rendering.
So until that is solved, this is the only solution that I am aware of that ticks every box.


[badge-android]: http://img.shields.io/badge/-android-6EDB8D.svg?style=flat

[badge-jvm]: http://img.shields.io/badge/-jvm-DB413D.svg?style=flat

[badge-js]: http://img.shields.io/badge/-js-F8DB5D.svg?style=flat

[badge-js-ir]: https://img.shields.io/badge/support-[IR]-AAC4E0.svg?style=flat

[badge-linux]: http://img.shields.io/badge/-linux-2D3F6C.svg?style=flat

[badge-windows]: http://img.shields.io/badge/-windows-4D76CD.svg?style=flat

[badge-wasm]: https://img.shields.io/badge/-wasm-624FE8.svg?style=flat

[badge-wasmi]: https://img.shields.io/badge/-wasi-626FFF.svg?style=flat

[badge-jsir]: https://img.shields.io/badge/-js(IR)-22D655.svg?style=flat

[badge-apple-silicon]: http://img.shields.io/badge/support-[AppleSilicon]-43BBFF.svg?style=flat

[badge-ios]: http://img.shields.io/badge/-ios-CDCDCD.svg?style=flat

[badge-ios-sim]: http://img.shields.io/badge/-iosSim-AFAFAF.svg?style=flat

[badge-mac-arm]: http://img.shields.io/badge/-macosArm-444444.svg?style=flat

[badge-mac-x86]: http://img.shields.io/badge/-macosX86-111111.svg?style=flat

[badge-watchos]: http://img.shields.io/badge/-watchos-C0C0C0.svg?style=flat

[badge-tvos]: http://img.shields.io/badge/-tvos-808080.svg?style=flat
