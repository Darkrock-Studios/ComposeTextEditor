# Writer conveniences

The opt-in `EditBehavior`s core ships for prose: smart punctuation (roadmap
5.2). They live in the `behaviors` package, are off by default, and a host turns
one on by adding it to `TextEditorState.editBehaviors`. Each builds on the
typed-text hook, `EditBehavior.onTextInput`, described in
[editor-actions.md](editor-actions.md), "Edit behaviors".

## Shared rules

- **Every input path.** The hook is told about committed text from key events,
  an IME's commit or finished composition (desktop, iOS and web through the
  skiko request, Android through its `InputConnection`), the accessibility
  insert and a host's `insertTypedString`. An IME's composing updates are never
  offered, so a keyboard sees its own composition untouched until it commits.
  A committed word is processed character by character, as if typed, so a
  keyboard committing `it's` whole gets the same result as one typing it.
- **One undo gives back what was typed.** The behavior edits on top of the
  typed text, which was already its own step, so the first undo reverts only
  the substitution: `--` typed, an em dash shown, undo shows `--` again.
- **Never in code.** A character carrying the inline code style
  (`RichTextStyles.codeStyle`, or a retired configuration's) or on a code block
  line is never rewritten or read as part of a pattern.
- **Each rewrite is small.** A substitution replaces only the characters it
  changes, each taking the style of the character it replaces, and the caret
  stays where the input left it, mapped across the rewrite.
- **The IME is resynced** after an edit on top of its commit (4.25, 4.27), so
  its mirror of the text holds the substituted characters.

## Smart punctuation

```kotlin
state.editBehaviors += SmartPunctuation()
state.editBehaviors += SmartPunctuation(enDashes = false) // each switch on its own
```

| Switch | Typed | Becomes |
| --- | --- | --- |
| `doubleQuotes` | a straight double quote | an opening or closing curly double quote |
| `singleQuotes` | a straight single quote | an opening or closing curly single quote; an apostrophe is a closing one |
| `emDashes` | two hyphens | an em dash, as soon as the second is typed |
| `enDashes` | word, space, hyphen, space | the hyphen becomes an en dash when the space after it is typed |
| `ellipses` | three periods | an ellipsis |

Choices, with the native editors they follow:

- **Quotes open** at a line start, after whitespace, after an opening bracket
  (`(`, `[`, `{`, `<`), after a straight quote, and after the other kind's
  opening quote, so a quotation nested in another opens. Anywhere else a quote
  closes; a second double quote right after an opening one closes it (two typed
  in a row make an empty pair).
- **After a dash** a quote could go either way: `said--"stop"` opens, and
  interrupted dialogue (`"I was going to--"`) closes. It closes when the line
  already holds an opening quote of its kind with no closing one, and opens
  otherwise. Word always opens there, which is a well known annoyance in
  dialogue.
- **The apostrophe before a decade.** An opening single quote this just made,
  followed directly by a digit, becomes an apostrophe, so `'90s` comes out
  right. A quotation that starts with a digit loses its opening quote to this;
  that is far rarer than an elided century. Other leading elisions (`'em`,
  `'til`, `rock 'n' roll`) still get an opening quote, as in Word and macOS:
  telling them from a quotation needs the word that follows.
- **Dashes.** Two hyphens give an em dash whether or not spaces surround them,
  as macOS and iOS do; Word makes a spaced pair an en dash, which would need
  waiting for the space and rewriting the dash a second time. The en dash comes
  from Word's other rule instead: a single hyphen with a space on each side,
  after a letter or digit (`1990 - 2000`), becomes one when the space after it
  is typed. A hyphen after punctuation or at a line start (a list marker typed
  as text, `> - item`) is left alone.
- **Three hyphens stay hyphens.** A hyphen typed straight after an em dash this
  just made turns the dash back into hyphens, so `---` (a markdown rule, a
  separator line) survives, and longer runs are never touched. An em dash typed
  or pasted as one, or made before any other edit, is never rewritten.
- **An ellipsis** forms on the third period; a fourth is a plain period after it.

### iOS

iOS keyboards apply smart punctuation themselves when Settings > General >
Keyboard > Smart Punctuation is on, which it is by default. Nothing breaks when
both run, since the behavior rewrites only straight characters and the dash and
quote it just made, but the two disagree in places (the spaced hyphen, three
hyphens), so leave `SmartPunctuation` off on iOS unless the keyboard's is off.
Whether the keyboard's converted characters reach the editor is the Mac queue's
5.2 row.
