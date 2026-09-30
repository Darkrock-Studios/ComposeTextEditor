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
- ✅ Efficient rendering and editing of long-form text
- ✅ Rich text with custom spans
- ✅ Expose scroll state
- ✅ Spell checking
- ✅ Diagnostics from your own checker (grammar, style), underlined with a menu of fixes
- ☑️ CommonMark Spec (partial)
  - Inline styles (bold, italics, ect)
  - Block styles (code fence with its language tag, lists, images)
  - Underline (`<u>`), highlight (`==text==` or `<mark>`), colour and size (`<span style>`)

You can [Give it a try here](https://darkrock-studios.github.io/ComposeTextEditor/), or
browse
the [API reference & recipes](https://darkrock-studios.github.io/ComposeTextEditor/api/).

![sample_screenshot_00.png](sample_screenshot_00.png)

### Features:

- Rich text rendering and editable
  - Semi-efficient rendering for long form text (_only renders what is visible_)
  - Semi-efficient data structure for text storage & editing. (_but not nearly as efficient as the
    Gap Buffer BTF2 uses under the hood_)
- Cursor movement, clicking, keyboard short cuts, ect
- Text selection (_highlighting and edit ops_)
- copy/cut/paste
- Exposed scroll state, so we can render scroll bars (_BTF1 can't do this_)
- Doesn't copy and return full contents on each edit, so again better for longer form text. (_BTF2
  also works this way, but BTF2 doesn't support AnnotatedString for rich content_)
- Support custom Rich Span drawing (_this allows us to render the traditional Spell Check red
  squiggle_)
- Emits edit events: so if a single character is inserted, you can collect a Flow, and know exactly
  what change was made. This makes managing Spell Check much more efficient as you can just
  respell-check the single word that was changed, rather than everything. (_BTF2 now finally offers this!_)
- Find & Replace UI: Works exactly as you'd expect.
- Screen reader support: the editor and `RichTextView` publish their text, selection,
  links and clipboard actions as `BasicTextField` does.
- Word count, by the same word segmentation as word motion and spell check.
- `rememberSaveableTextEditorState`: the document, caret, selection and scroll survive
  configuration changes and process death (the undo history does not).
- Editor configuration: read-only with a caret, sizing to the text between minimum
  and maximum lines, single line, maximum length and input filters. No soft-wrap
  toggle yet.

#### Platforms

| Platform | Status |
| --- | --- |
| Desktop (JVM) | Supported |
| Android | Supported |
| iOS | Experimental: typing, autocorrect, and CJK composition work in the simulator; the edit menu and a device pass are pending |
| WASM | Experimental: typing, backspace, and IME composition run through the browser input session; the soft keyboard and real-browser IME passes are pending |

See the [roadmap](docs/ROADMAP.md) for what is planned.

### Work left to do:

- Copy/Paste of rich text always strips the formatting (_this is a Compose MP bug_)
- Right-to-Left text is probably broken
- Sentence level spell checking is not working as expected
- Full CommonMark Spec compliance (_nested lists_)

## Want to try it?

Text Editor:

`implementation("com.darkrockstudios:composetexteditor:2.0.0")`

Spell Checking addon:

`implementation("com.darkrockstudios:composetexteditor-spellcheck:2.0.0")`

Find & Replace addon:

`implementation("com.darkrockstudios:composetexteditor-find:2.0.0")`

On iOS, host the Compose view with `.ignoresSafeArea(.keyboard)` in SwiftUI, as the
Compose Multiplatform template does. The editor keeps its caret above the keyboard itself;
letting SwiftUI shrink the view as well moves the content twice.

On Android, declare `android:windowSoftInputMode="adjustResize"` on the activity, as
Compose apps should; otherwise Android also pans the whole window to the caret. The
editor keeps its caret in view whether the host pads it by the keyboard's inset
(`imePadding`) or lets the keyboard cover it. `TextEditorState.keyboardSettings` asks the
keyboard for its capitalisation, autocorrect, layout and action key; an editor for code
would turn capitals and autocorrect off. Android honours it so far.

## Really?

I don't know. Maybe. It might not a permanent solution.
`BasicTextField2`'s new state based approach and gap-buffer has reach maturity and includes a lot of what I would need
to replace this, but still cannot handle rich text rendering, still can't emit individual edit events.
So until those are solved, this is the only solution that I am aware of that ticks every box.


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
