# Editor actions and edit behaviors

How input reaches editor behavior: which shortcuts exist, what they invoke,
and how a feature attaches behavior to a primitive edit without the primitive
knowing the feature exists. Covers the `EditorCommand` vocabulary, the
`EditorActionRegistry`, and the `EditBehavior` chain. Motivating issue: #37.

## Design rules

1. **Input translates, it never decides.** Key handling, pointer handling and
   the IME all resolve to the same named action or the same semantic edit.
   No input path may carry behavior another input path lacks.
2. **The chord and the behavior are separate registries.** `KeyBindings`
   answers "which action does this chord name". The action registry answers
   "what does that action do". Neither may absorb the other.
3. **Built-ins register through the public mechanism.** Line blocks, clipboard
   actions and everything else the library ships use the same registration API
   a host would. If a built-in needs a private door, the door is the design
   defect.
4. **Primitives stay permissive.** `insertNewlineAtCursor` and friends do one
   mechanical thing. Anything smart is a registered behavior consulted above
   them.
5. **Unclaimed input falls through.** An action id with no registered handler
   must not consume the key event, or an unbound chord swallows a keystroke
   that should have been typed.

## Why two mechanisms and not one

An action is invoked by name, from anywhere: a chord, a menu item, a toolbar
button, host code. A behavior intercepts a semantic edit that has no name and
no chord of its own.

It is tempting to collapse them, since "backspace demotes the bullet" looks
like an action bound to Backspace. It is not, because the IME never produces a
key event. A soft-keyboard backspace arrives as `deleteSurroundingText(1, 0)`,
an autocorrect replacement arrives as `commitText`, and neither carries a chord
to bind. The interception point has to be the semantic edit primitive, not the
chord. This is also why the line-block logic could never live in key handling:
a behavior attached to a chord reaches only the input paths that produce key
events, and the soft-keyboard paths silently lose it.

## Actions

### The vocabulary

`EditorCommand` is public. `Motion` is an enum: motions are a genuinely closed
vocabulary, nothing about a caret movement is extensible, and keeping it
closed preserves the exhaustive `when` in the handler. `Action` is an open
class keyed by a string id:

```kotlin
sealed interface EditorCommand {
    enum class Motion : EditorCommand { Left, Right, /* … */ }

    class Action(val id: String, val isEdit: Boolean) : EditorCommand {
        companion object {
            val SelectAll = Action("editor.selectAll", isEdit = false)
            val Copy = Action("editor.copy", isEdit = false)
            val Paste = Action("editor.paste", isEdit = true)
            // … the built-in set
        }
    }
}
```

A host writes `Action("myapp.insertDate", isEdit = true)` and binds it from
its own `KeyBindings`. Ids are namespaced by convention (`editor.`,
`markdown.`, `myapp.`) and the registry is keyed by id string, not object
identity, so a duplicate id is a detectable collision rather than a silent
second entry.

`isEdit` sits on the action because the disabled-editor gate needs it before
dispatch, without having to resolve a handler first.

### Platform tables

Three `KeyBindings` tables ship, and `platformKeyBindings()` picks one for the
host:

| Table | Used on | Differs in |
| --- | --- | --- |
| `CtrlKeyBindings` | Linux, Android, and any other Ctrl host | The base: Ctrl for shortcuts and jumps; going forward stops at ends (GTK, `EditText`) |
| `WindowsKeyBindings` | Windows desktop, browsers on Windows | Going forward runs on to the next start: Ctrl+Right and Ctrl+Delete to the next word's (`WordRight`, `DeleteWordForward`), Ctrl+Down to the next paragraph's. Word motion stops at line breaks both ways: Ctrl+Left and Ctrl+Backspace from a line start go to the previous line's end (`PreviousWordStart`, `DeleteToPreviousWordStart`) |
| `MacKeyBindings` | macOS, iPadOS, browsers on macOS | Cmd for shortcuts, Option for word and paragraph jumps; Option+Right and Option+Delete stop at the word end; Cocoa's Emacs-style Ctrl+A, E, F, B, N, P, D, H, K and Y |

Windows and Linux are the same desktop JVM target, so the choice is made at
runtime from `os.name` (desktop) or the browser's platform and user agent
(web), not by `expect`/`actual`. `WindowsKeyBindings` delegates everything it
does not change to `CtrlKeyBindings`, so the two cannot drift apart. A host
replaces the table through `LocalKeyBindings` or an editor's `keyBindings`.

### The registry

Lives on `TextEditorState`, because that is the one object the key handler, the
context menu and the IME already share. Clipboard and coroutine scope are not
available at registration time, so they arrive per invocation:

```kotlin
class EditorActionContext(
    val state: TextEditorState,
    val clipboard: Clipboard,
    val scope: CoroutineScope,
)

class EditorActionSpec(
    val action: EditorCommand.Action,
    val isEnabled: (EditorActionContext) -> Boolean = { true },
    val perform: (EditorActionContext) -> Unit,
)

class EditorActionRegistry {
    fun register(spec: EditorActionSpec)
    fun unregister(action: EditorCommand.Action)
    operator fun get(action: EditorCommand.Action): EditorActionSpec?
}
```

`isEnabled` exists for the context menu, which builds its items from a list of
action ids and asks each spec whether it currently applies, instead of knowing
what any of them mean.

The core registers its built-ins (`BuiltinEditorActions`) when the state is
constructed. Nothing else in the library registers an action: the block
toggles are plain functions on the state (`toggleBulletList` and the rest of
`state/TextEditorStateBlockExt.kt`), and a host that wants a chord for one
registers the action itself.

### Formatting toggles

`editor.toggleBold`, `editor.toggleItalic`, `editor.toggleUnderline`,
`editor.toggleStrikethrough` and `editor.toggleInlineCode` are built-ins. They
apply the styles of the state's `richTextStyles`, which the format addons
read as well, so one action serves a plain editor and a markdown one, and a
markdown editor exports what it applied.
Underline has no markdown form and toggles
`SpanStyle(textDecoration = TextDecoration.Underline)`.

All five follow `TextEditorState.toggleSpanStyle`, which a toolbar calls too:

- A selection carrying the style on every character loses it.
- Any other selection, including a partly styled one, gains it throughout.
- A collapsed caret toggles the style for the text typed next; the document is
  untouched.

Empty lines inside a selection do not count against "every character".
`hasStyleThroughout` answers the same question for a toolbar's active state, so
a button lights exactly when pressing it would remove the style. Styles match
by equality, as `addStyleSpan` and `removeStyleSpan` do.

| Action | Windows, Linux | macOS |
| --- | --- | --- |
| Bold | Ctrl+B | Cmd+B |
| Italic | Ctrl+I | Cmd+I |
| Underline | Ctrl+U | Cmd+U |
| Strikethrough | Ctrl+Shift+X | Cmd+Shift+X |
| Inline code | Ctrl+E | Cmd+E |
| Clear formatting | Ctrl+\ | Cmd+\ |
| Unlink | none | none |

Strikethrough follows Google Docs on macOS, Slack and Teams; the other common
choice, Shift+S, is Save As in most hosts. Inline code follows GitHub and
Notion. Clear formatting follows Google Docs; Word's Ctrl+Space switches the
input method on Windows, Linux and macOS. Unlink has no chord common enough to
claim.

`editor.clearFormatting` (`TextEditorState.clearFormatting`) takes every
character style off the selection except those structure puts there: a
heading's or code block's line style and, in a markdown editor, the body text
style and the link style where a link covers the text. At a collapsed caret it
sets the style of the text typed next to its line's plain style, like the
toggles leaving the document alone. `editor.unlink` (`TextEditorState.unlink`) takes off,
whole, every link the selection touches or the one the caret is in or at the
edge of, with its link style; its `isEnabled` is false away from a link. Each
is one undo step.

### Tab

`editor.indent` and `editor.outdent` sit on Tab and Shift+Tab in every table.
`TextEditorState.tabSettings` (`TabSettings`) configures them:

- `size`, four by default, is how many spaces one indent inserts and one
  outdent strips. Outdent strips a single leading tab character instead when
  the line starts with one.
- `insertTabCharacter` indents with a tab character instead of spaces.
- `movesFocus` makes Tab and Shift+Tab move focus, as in a form field. The key
  handler leaves them to the focus system before asking the bindings, so the
  indent actions stay available to other chords and to host code.

The default keeps Tab indenting, which a writing app wants; `BasicTextField`
inserts a tab character instead, which a host can choose. Either way the
keyboard can leave the editor: Tab with Ctrl or Cmd is bound in no table, so
Ctrl+Tab and Ctrl+Shift+Tab reach the focus system (the GTK, Cocoa and Swing
convention for a text view that takes Tab), and a Tab after Escape is left to it
too (CodeMirror's escape, for browsers that keep Ctrl+Tab). Escape arms Tab until
another key is pressed or focus changes. Alt+Tab still indents where the system
lets it through, as Option+Tab does in Cocoa.

Tab is list-aware. At a list item's start it nests the item one level, never
deeper than one below the item above (roadmap 5.6); inside the item's text it
inserts the indent text. A list's first top-level item has nothing to nest
under: Google Docs nests it anyway and Word indents the whole list, and the
line model can do neither, so it takes the indent text, which survives a
markdown round trip (7.45) and Shift+Tab takes back (7.58). A nested item
already at its limit is left alone, since Shift+Tab there un-nests it and would
leave the indent, and so is a blank first item, whose indent would keep Enter
from ending the list. Tab over several lines treats each line as Tab alone
does. Shift+Tab at the caret un-nests a nested item and otherwise strips the
line's leading spaces, list items included; over several lines it does both.

### The kill ring

`editor.deleteToLineStart`, `editor.deleteToLineEnd` and
`editor.deleteToParagraphEnd` are kills, as Cocoa's `deleteToBeginningOfLine:`,
`deleteToEndOfLine:` and `deleteToEndOfParagraph:` are: what they delete goes
in the editor's own kill buffer (`KillRing` on the state), never the clipboard.
A kill made with the text and the caret as the last kill left them, and no other
key command between, joins it, after it going forward and in front of it going
back, so Ctrl+K pressed down a run of lines kills them as one piece; a kill of a
selection starts afresh. `editor.yank` (Ctrl+Y on macOS) inserts it over any
selection, as one undo step, keeping its character styling but not its rich
spans (links, images, list markers), which only Cut and Paste carry. Like
Cocoa's default the buffer holds one entry, and loading a document empties it.
The yank is bound on macOS only; elsewhere Ctrl+Y is Redo.

### The context menu

The built-in menu lists, after any host items, Undo and Redo; Cut, Copy, Paste
and Paste as Plain Text; and Select All, each group behind a divider. An item
shows when its action is registered and allowed (a read-only editor or view has
no editing items, so it offers Copy and Select All) and is disabled while its
spec's `isEnabled` says it has nothing to act on, as native menus grey items out
rather than drop them. Paste stays enabled, since the clipboard cannot be read
synchronously. `ContextMenuStrings` holds every label; `TextEditor`,
`BasicTextEditor` and `RichTextView` take one, and `TextEditor` takes a
`TextEditorContextMenuState` too.

`editor.showContextMenu` opens it under the caret. It is bound to Shift+F10 and
the Menu key in `CtrlKeyBindings` (Windows, Linux and Android), and to nothing
on macOS, which has no such convention. On the web Compose does not name the
Menu key, and whether the browser leaves Shift+F10 to the page is unverified.
The composable showing a state registers how to open its menu, and the action's
`isEnabled` is false while none does. The menu takes Up, Down, Enter and Escape
once open. An addon with menu items of its own (spell check) can register over
the action to open its menu instead.

Pointer and touch-toolbar positions are in the text canvas's coordinates; the
composable converts them through the layout into the menu provider's, so the
content padding and any padding in the host's modifier are accounted for.

### Resolution and consumption

`TextEditorKeyCommandHandler.handleKeyEvent` resolves in three steps:

1. `keyBindings.commandFor(event)` or return false.
2. `Motion`: move the caret, extending on shift.
3. `Action`: look it up; **return false if unregistered** (rule 5). Then return
   false if the *registered spec's* `isEdit` and the editor is disabled.
   Otherwise `perform(context)` and return true.

The gate reads `isEdit` off the spec rather than off the `EditorCommand.Action`
the bindings returned, because `Action` identity is its id alone. A host writing
its own bindings can hand back `Action("editor.paste", isEdit = false)`, which
resolves the real built-in paste; taking the caller's word for it would let that
mutate a read-only editor.

Returning false for an unregistered action leaves the event to whatever would
have handled it otherwise, but only unmodified printable keys have a useful
fallback. `handleCharacterInput` refuses control characters and refuses any
Ctrl or Cmd chord, so an unregistered `editor.paste` makes Ctrl+V inert, and an
unregistered `editor.indent` lets Tab reach the platform's focus traversal and
move focus out of the editor entirely. A host that wants a chord to do nothing
should bind it to a registered no-op action rather than unregister the
built-in.

## Edit behaviors

### The chain

```kotlin
interface EditBehavior {
    fun onNewline(state: TextEditorState): Boolean = false
    fun onNewlineLanded(state: TextEditorState, range: TextEditorRange): Boolean = false
    fun onBackspace(state: TextEditorState): Boolean = false
    fun onDeleteForward(state: TextEditorState): Boolean = false
    fun onTextInput(state: TextEditorState, text: String, range: TextEditorRange): Boolean = false
    fun onPaste(state: TextEditorState, text: String, range: TextEditorRange): Boolean = false
}
```

Returning true means "I handled it, do nothing else". Behaviors are an ordered
list on `TextEditorState`; the first to claim the edit wins. A behavior that
mutates must route through the edit manager, so its work lands in undo history
like any other operation (`LineBlockEditBehavior` does this by going through
`toggleLineBlock` rather than mutating spans directly).

`onTextInput` is the typed-text hook. Like `onNewlineLanded` and `onPaste`,
and unlike the hooks asked before an edit, it runs *after* the edit: it is told
where committed text landed. It sees every
path: a key event's character (`insertTypedString`), an IME commit (the
whole word a soft keyboard or a candidate window commits, in place of what
it was composing, or a composition it finishes as it stands), a dictated
phrase through the accessibility `insertTextAtCursor`, and a host's own
`insertTypedString`. It never sees an IME's composing updates, which are not
committed text, and it never sees a paste, which is not typing. A paste goes to
`onPaste` instead, told where the pasted text landed once the paste (both paste
actions, so every platform's paste) has committed as its own undo step; an edit
there is a step of its own, as on the typed-text hook. A drop that is not a move
within the editor is offered the same way. A host that registers its own paste
action replaces that offer along with the paste, and makes it by calling
`pasteLanded` once its paste has committed. A lone typed line break is the Enter
key and goes to `onNewline` before it lands, never to `onTextInput`; once the
Enter's own step has put a line break in, `onNewlineLanded` is told where it
landed, and an edit there is a step of its own. The one exception is an IME
committing `"\n"` over its own composition, which is a replacement of the
composition and reaches none of these hooks (see `ImeLineBlockParityTest`).

It runs after rather than before because the default edit is not one thing a
behavior could reproduce: on the IME path it replaces the composition,
inherits its styling, and places the caret by the IME's `newCursorPosition`
contract. Letting it land first means a behavior reads the document around
`range` and edits on top, owns the caret from there, and the IME is asked to
resync when it moves the text or the caret. It also gives the undo shape
native editors have for free: the typed text is its own step, the behavior's
replacement the next, so one undo of an em dash gives back the two hyphens.
Several edits go in one `editGroup` to be one step. The chain is skipped for
edits a behavior makes while handling one, and a behavior that changes the
text ends the chain whether or not it claims, since the range it was told no
longer holds; one that only styles it (auto-link's link) leaves the chain
going, so auto-link and smart punctuation both act on one commit. Smart punctuation, markdown as you type, and auto-link are opt-in
behaviors on this hook; `TextInputBehaviorTest` shows the shape. The ones core
ships (`SmartPunctuation`, `AutoLink`) are described in [behaviors.md](behaviors.md).

The chain is consulted inside the public semantic functions, so every caller
gets it:

```kotlin
fun backspaceAtCursor() {
    if (editBehaviors.any { it.onBackspace(this) }) return
    backspaceAtCursorRaw()
}
```

A separate semantic layer (`performBackspace` running the chain over a
primitive `backspaceAtCursor`) was considered and rejected: no caller wants
the primitive without the behavior, so the raw form stays `internal` until
something needs it.

### Line blocks as the first behavior

Line blocks are not a markdown feature. They are a core capability that
markdown happens to serialize, so the behavior lives in the library
(`LineBlockEditBehavior` in `richstyle`) and is registered by default;
the markdown module remains a consumer. The semantics documented under
"Smart editing" in [line-blocks.md](line-blocks.md) are unchanged by where the
code sits.

What the seam buys over branching inside the primitives:

- The dependency narrows. `TextEditorState` names `LineBlockEditBehavior` but
  no longer calls `applyLineBlock` or `detectLineBlock` itself. It still
  imports `richstyle`: `normalizeLineBlocks` and the block span styles are
  used by layout, measurement and content normalization, which have nothing to
  do with edit behaviors.
- Every input path gets the behavior. See "Input-path parity" below.
- A code-editor host can add auto-indent or bracket-closing without forking.
- It can be turned off, which previously required editing the library.

The newline case does not fit a plain "handled / not handled" return. Enter on
an empty item is a clean claim, but a split *inside* an item needs the block
captured before the split and re-applied to both halves after, in one revision,
because detection after the split is unreliable. Rather than a second hook with
state threaded between the two, `TextEditorState.insertNewlineRaw()` is
`internal` and the behavior performs the split itself inside its own
`withAtomicEdit`. The interface stays a single boolean.

That double apply is load-bearing rather than defensive: with the behavior
removed, splitting an item leaves the new half without its gutter marker, which
`EditBehaviorTest` pins.

## Input-path parity

Five paths deliver a backspace or a newline, and all five must land on the
same semantic function so the chain sees them:

- Hardware key events, through `TextEditorKeyCommandHandler`.
- Android `sendKeyEvent(KEYCODE_DEL)`, re-dispatched to the key handler.
- Android `deleteSurroundingText(1, 0)`, routed to `backspaceAtCursor()`.
- `commitText("\n")`, routed to the newline path.
- `performEditorAction`, through `imePerformNewline`.

Each path is checked by `ImeLineBlockParityTest`, with the hardware-key result
as the reference the others are compared against. Before the IME routing
existed, whether a soft-keyboard backspace demoted a bullet depended on which
`InputConnection` method the IME happened to call.

The IME routing is the risky part of the design, because
`deleteSurroundingText(1, 0)` is not unambiguously a keypress: autocorrect and
prediction engines use the same call to rewrite what the user typed, and
treating one of those as a backspace would demote a bullet in the middle of a
word correction. The guards:

- Intent is read from the widths the caller asked for, not from the range that
  survives clamping: exactly one character on exactly one side, no composing
  region, no selection. The distinction matters at the edges of the document,
  where a request for several characters shrinks to one; reading the survivor
  would let an autocorrect rewrite at the top of the document pass as a
  backspace.
- The code-point variant additionally requires that its request resolved to at
  most one UTF-16 char, and either variant goes semantic only when the cluster
  beside the caret is one char: `backspaceAtCursor` and `deleteAtCursor` take
  a whole code point, emoji sequence, or cluster, more than the IME asked for.
- Both semantic routes also run when clamping leaves an empty range. A
  backspace at the very start of the document removes nothing but can still
  exit a line block, which is what the hardware key does; bailing on the empty
  range would leave the key dead on a soft keyboard for a block on the first
  line.

### Device verification still owed

The guards are a static reading of what an autocorrect rewrite looks like
versus what a relayed keypress looks like, covered only by desktop JVM tests
against the shared `ImeEditLogic`; no soft keyboard has been run against them.
Do not treat this as release-ready until it has been checked on hardware
against Gboard and at least one third-party keyboard (SwiftKey or Samsung
Keyboard are the usual second targets).

What to check, in a document with a bullet list:

1. *Backspace at the start of a bullet item* should demote the item, and a
   second press should merge it into the previous line. Check with the caret at
   column 0 of an item whose predecessor is a different block, and again where
   the predecessor is the same block (which must merge directly, no demote).
2. *Enter on an empty bullet item* should exit the list rather than adding
   another empty item.
3. *Autocorrect must not demote.* Type a word at the start of a bullet item,
   let the keyboard offer a correction, and accept it. The bullet must survive.
   This is the failure mode the guard exists to prevent and the one most likely
   to be wrong, because it depends on whether the keyboard keeps a composing
   region while it rewrites.
4. *Predictive replacement of a whole word* at the start of an item, same
   expectation.
5. *Swipe / glide typing* into and over an item boundary, which tends to use
   `commitText` with multi-character strings and larger `deleteSurroundingText`
   spans than the guard admits. Nothing should demote.
6. *Emoji and other astral characters* deleted with one backspace at the end of
   an item: the whole glyph goes, never half a surrogate pair.
7. *Voice input* committing text that contains newlines, which must not be read
   as an Enter.

If any of these misbehave, the fix is to narrow the guard in
`deleteSurroundingRange` / `imeCommitText`, not to widen it. A plain range
delete is always correct for the text, it just misses the block semantics.

## Extending the editor

Three seams, in the order you are likely to reach for them.

*Add a shortcut.* Register an action, then bind a chord to it. Delegate the
chords you do not claim or you lose every built-in:

```kotlin
val InsertDate = EditorCommand.Action("myapp.insertDate", isEdit = true)

state.actions.register(EditorActionSpec(InsertDate) { it.state.insertStringAtCursor(today()) })

val bindings = KeyBindings { event ->
    if (event.key == Key.D && event.isCtrlShortcut && event.isShiftPressed) InsertDate
    else platformKeyBindings().commandFor(event)
}

TextEditor(state = state, keyBindings = bindings)
```

Use `isCtrlShortcut` rather than `isCtrlPressed`: Windows synthesizes AltGr as
left-Ctrl plus right-Alt, so a bare Ctrl test steals the layout chords that type
a character. On macOS shortcuts belong on Cmd (`isMetaPressed`); a host chord
that should follow the platform checks `platformKeyBindings() === MacKeyBindings`.

*Replace a built-in.* Register over its id. `editor.paste` bound to a paste that
sanitizes the clipboard changes the chord, the context menu and anything else
that invokes it, because they all resolve through the same registry. Pasting has
two actions, `editor.paste` and `editor.pasteAsPlainText`: a host that reroutes
or disables pasting replaces or unregisters both.

*Intercept an edit.* Implement `EditBehavior` and add it to
`state.editBehaviors`. Use this, not an action, when the thing you are reacting
to has no chord: an IME commits a newline without ever producing a key event.
An out-of-module behavior builds on the public edit API
(`insertStringAtCursor`, `delete`, `replace`) and wraps a compound edit in
`state.editGroup { }`, which makes it one revision and one undo step; the raw
primitives stay `internal`.

## Known limitations and follow-ups

- A code-editor indent (to the next tab stop, or matching the line above) is a
  host's own `editor.indent`.
- Behaviors see typed text, pastes and drops, newline, backspace and forward
  delete. A typed composition the editor ends itself (a tap outside it, focus loss) offered to `onTextInput`, only one the
  IME commits or finishes (roadmap 5.9).
- The IME routing is unverified on real hardware. See "Device verification
  still owed" above; that list should be worked through before a release ships
  this.
