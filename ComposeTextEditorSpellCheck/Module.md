# Module Spell Check

Spell checking for the Compose Text Editor: red squiggle underlines on misspelled
words, tap-for-suggestions, and a pluggable checker backend. The editor's per-edit
change stream means only the words you actually touch get re-checked.

> **Try it live:
** [open the Spell Check demo on Wasm »](https://darkrock-studios.github.io/ComposeTextEditor/)

```kotlin
implementation("com.darkrockstudios:composetexteditor-spellcheck:2.0.0")
```

## Recipe

Provide an [EditorSpellChecker][com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker],
build a [SpellCheckState][com.darkrockstudios.texteditor.spellcheck.SpellCheckState] with
[rememberSpellCheckState][com.darkrockstudios.texteditor.spellcheck.rememberSpellCheckState],
and use [SpellCheckingTextEditor][com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor]
in place of `TextEditor`:

```kotlin
@Composable
fun SpellCheckedEditor(spellChecker: EditorSpellChecker) {
    val state = rememberSpellCheckState(
        spellChecker = spellChecker,
        enableSpellChecking = true,
        spellCheckMode = SpellCheckMode.Word,
    )

    SpellCheckingTextEditor(
        state = state,
        modifier = Modifier.fillMaxSize(),
    )
}
```

`SpellCheckingTextEditor` draws the squiggles and wires misspelled-word taps and
right-clicks to a suggestion menu for you; Shift+F10 or the Menu key opens the same menu
for the word at the caret. Toggle checking at runtime with
`state.setSpellCheckingEnabled(...)`, or fetch suggestions yourself via
`state.getSuggestions(word)`.

Numbers and single letters are not checked, nor two letters a period joins to another
letter, so abbreviations such as "U.S.A.", "e.g." and "Ph.D." draw no squiggles.

The menu offers "Ignore", which stops flagging the word for the session
(`state.ignoreWord(word)`, listed in `state.ignoredWords`). Pass `onAddToDictionary` to
also offer "Add to dictionary"; it receives the word to store in your dictionary, and the
word stops being flagged at once. Both match across case as a dictionary does: ignoring
"kotlinx" also clears "Kotlinx" and "KOTLINX", and a word capitalised only at its start is
taken for a sentence's first word. A word with other capitals, such as "NASA", clears only
as written, so ignoring it leaves "nasa" flagged. With `PlatformEditorSpellChecker`, that can be the
platform checker's own `addToDictionary`. Localize the menu with `spellCheckStrings`.

To add your own entries to that menu, pass `spellCheckMenuItems`: it receives the flagged
[SpellCheckItem][com.darkrockstudios.texteditor.spellcheck.SpellCheckItem] and returns
[ContextMenuItem][com.darkrockstudios.texteditor.contextmenu.ContextMenuItem]s rendered
after the built-in ones.

Everything else is passed to the editor it wraps, as on `TextEditor`: `onLinkClick` opens
a link on Ctrl+click (Cmd+click on macOS), and `onRichSpanClick` and `onRichSpanClickEvent`
hear clicks on your own spans. Clicks on squiggles are the spell checker's and are not
passed on. `readOnly`, `lineLimits`, `contentDescription` and `keyBindings` work as they do
on `TextEditor`; in a read-only editor the menu on a flagged word offers Ignore and Add to
dictionary but no corrections, and a diagnostic's menu shows its message without its fixes.

A checker serves one language. To switch, create a checker for the new language and pass
it to `rememberSpellCheckState`, which re-checks the document with it; the ignored words
carry over.

## Choosing a backend

[EditorSpellChecker][com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker] is
the platform-agnostic contract the editor talks to. This module ships two adapters:

- `SymSpellEditorSpellChecker` — backed by the
  [SymSpell](https://github.com/Wavesonics/SymSpellKt) library. Pure Kotlin, works on
  every target (including Wasm); you supply the dictionary.
- `PlatformEditorSpellChecker` — delegates to the operating system's native spell
  checker (desktop, Android, iOS).

Construct whichever fits the target and pass it in:

```kotlin
// SymSpell, anywhere:
val symSpell = SymSpell(SpellCheckSettings(topK = 5)).apply { /* load a dictionary */ }
val checker: EditorSpellChecker = SymSpellEditorSpellChecker(symSpell)

// Or the OS checker on desktop / Android / iOS:
val checker: EditorSpellChecker = PlatformEditorSpellChecker(platformSpellChecker)
```

## With Markdown

A spell-checked editor is a `TextEditorState` underneath, so the markdown addon
(`composetexteditor-markdown`) installs on it as on any other:

```kotlin
val state = rememberSpellCheckState(spellChecker = spellChecker)
val markdown = remember(state) { state.textState.withMarkdown() }

LaunchedEffect(markdown) { markdown.importMarkdown(source) }
```

# Package com.darkrockstudios.texteditor.spellcheck

The spell-checking
editor: [SpellCheckingTextEditor][com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor],
the [SpellCheckState][com.darkrockstudios.texteditor.spellcheck.SpellCheckState] holder
and its [rememberSpellCheckState][com.darkrockstudios.texteditor.spellcheck.rememberSpellCheckState]
factory, and the
[SpellCheckMode][com.darkrockstudios.texteditor.spellcheck.SpellCheckMode]
(word vs. sentence) selector.

# Package com.darkrockstudios.texteditor.spellcheck.api

The backend contract:
[EditorSpellChecker][com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker]
and its value types ([Correction][com.darkrockstudios.texteditor.spellcheck.api.Correction],
[Suggestion][com.darkrockstudios.texteditor.spellcheck.api.Suggestion]). Implement this
interface to plug in any spell-checking engine.

# Package com.darkrockstudios.texteditor.spellcheck.adapters

Ready-made [EditorSpellChecker][com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker]
implementations: a SymSpell-backed checker and an OS-backed platform checker.
