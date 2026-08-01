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

`SpellCheckingTextEditor` draws the squiggles and wires misspelled-word taps to a
suggestion menu for you. Toggle checking at runtime with
`state.setSpellCheckingEnabled(...)`, or fetch suggestions yourself via
`state.getSuggestions(word)`.

## Wrong-language protection

A checker loaded with the wrong dictionary flags *every* word: the document
turns into a wall of red squiggles and every lookup is wasted. The
[SpellCheckGuard][com.darkrockstudios.texteditor.spellcheck.SpellCheckGuard]
watches for that. When too large a share of the whole document comes back
misspelled (or more than `maxMisspellings` pile up) the state drops every
squiggle and stops checking instead of flagging everything. The ratio is
judged against the full document's word count, so a foreign-language passage
inside an otherwise fine document never trips it, and a full check still
bails as soon as the outcome is decided, so a big document isn't dragged
through a checker that can't read it.

```kotlin
val state = rememberSpellCheckState(
    spellChecker = spellChecker,
    guard = SpellCheckGuard(maxMisspellings = 500, maxFlaggedRatio = 0.7f),
)

state.suspension?.let { reason ->
    Text(
        when (reason) {
            is SpellCheckSuspension.LikelyWrongLanguage ->
                "Spell check paused: wrong dictionary language?"
            is SpellCheckSuspension.TooManyMisspellings ->
                "Spell check paused: too many misspellings."
        }
    )
}
```

`suspension` is Compose state, so a banner like the one above recomposes on
its own. A suspension is sticky: automatic checking stays off until a full
re-check completes with plausible results. Replacing the checker (passing a
new `spellChecker` to `rememberSpellCheckState` re-checks automatically),
calling `resumeSpellChecking()` or `runFullSpellCheck()`, and toggling
checking back on with `setSpellCheckingEnabled(true)` all trigger that
re-check; if the checker is still flagging everything, the suspension simply
stays in place. Pass `SpellCheckGuard.Disabled` to opt out entirely.

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

To combine spell checking with Markdown import/export, wrap the state with
[SpellCheckState.withMarkdown][com.darkrockstudios.texteditor.spellcheck.markdown.withMarkdown]
and load content through `importMarkdown` so block elements are parsed:

```kotlin
val state = rememberSpellCheckState(spellChecker = spellChecker)
val markdown = remember(state) { state.withMarkdown() }

LaunchedEffect(markdown) { markdown.importMarkdown(source) }
```

# Package com.darkrockstudios.texteditor.spellcheck

The spell-checking
editor: [SpellCheckingTextEditor][com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor],
the [SpellCheckState][com.darkrockstudios.texteditor.spellcheck.SpellCheckState] holder
and its [rememberSpellCheckState][com.darkrockstudios.texteditor.spellcheck.rememberSpellCheckState]
factory, the
[SpellCheckMode][com.darkrockstudios.texteditor.spellcheck.SpellCheckMode]
(word vs. sentence) selector, and the
[SpellCheckGuard][com.darkrockstudios.texteditor.spellcheck.SpellCheckGuard]
that suspends checking when a checker starts flagging everything.

# Package com.darkrockstudios.texteditor.spellcheck.api

The backend contract:
[EditorSpellChecker][com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker]
and its value types ([Correction][com.darkrockstudios.texteditor.spellcheck.api.Correction],
[Suggestion][com.darkrockstudios.texteditor.spellcheck.api.Suggestion]). Implement this
interface to plug in any spell-checking engine.

# Package com.darkrockstudios.texteditor.spellcheck.adapters

Ready-made [EditorSpellChecker][com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker]
implementations: a SymSpell-backed checker and an OS-backed platform checker.

# Package com.darkrockstudios.texteditor.spellcheck.markdown

[SpellCheckState.withMarkdown][com.darkrockstudios.texteditor.spellcheck.markdown.withMarkdown]
— adds Markdown import/export to a spell-checked editor.
