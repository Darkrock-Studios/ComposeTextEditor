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

A host writes `Action("myapp.toggleBold", isEdit = true)` and binds it from
its own `KeyBindings`. Ids are namespaced by convention (`editor.`,
`markdown.`, `myapp.`) and the registry is keyed by id string, not object
identity, so a duplicate id is a detectable collision rather than a silent
second entry.

`isEdit` sits on the action because the disabled-editor gate needs it before
dispatch, without having to resolve a handler first.

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
constructed. Nothing else in the library registers an action:
`MarkdownExtension` still exposes its toggles as plain functions, and a host
that wants a markdown chord registers the action itself, as
`sampleApp/BoldShortcut.kt` does. Having the extension register
`markdown.toggleBold` and friends from its `init` is the obvious follow-up.

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
    fun onBackspace(state: TextEditorState): Boolean = false
    fun onDeleteForward(state: TextEditorState): Boolean = false
}
```

Returning true means "I handled it, do nothing else". Behaviors are an ordered
list on `TextEditorState`; the first to claim the edit wins. A behavior that
mutates must route through the edit manager, so its work lands in undo history
like any other operation (`LineBlockEditBehavior` does this by going through
`toggleLineBlock` rather than mutating spans directly).

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
`MarkdownExtension` remains a consumer. The semantics documented under
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
  most one UTF-16 char, so a one-code-point delete of an astral character never
  reaches `backspaceAtCursor`, which deletes a single char and would split the
  surrogate pair.
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
val ToggleBold = EditorCommand.Action("myapp.toggleBold", isEdit = true)

state.actions.register(EditorActionSpec(ToggleBold) { it.state.toggleBold() })

val bindings = KeyBindings { event ->
    if (event.key == Key.B && event.isCtrlShortcut) ToggleBold
    else platformKeyBindings().commandFor(event)
}

TextEditor(state = state, keyBindings = bindings)
```

Use `isCtrlShortcut` rather than `isCtrlPressed`: Windows synthesizes AltGr as
left-Ctrl plus right-Alt, so a bare Ctrl test steals the layout chords that type
a character. `sampleApp/BoldShortcut.kt` is this worked out, including picking
the modifier per platform.

*Replace a built-in.* Register over its id. `editor.paste` bound to a paste that
strips formatting changes the chord, the context menu and anything else that
invokes it, because they all resolve through the same registry.

*Intercept an edit.* Implement `EditBehavior` and add it to
`state.editBehaviors`. Use this, not an action, when the thing you are reacting
to has no chord: an IME commits a newline without ever producing a key event.
Note that `withAtomicEdit` and the raw primitives are `internal`, so an
out-of-module behavior builds on the public edit API (`insertStringAtCursor`,
`delete`, `replace`) and cannot wrap a primitive in its own transaction.

## Known limitations and follow-ups

- `Action.Indent` / `Action.Outdent` insert and strip literal spaces against a
  hard-coded `TAB_SIZE = 4` in `BuiltinEditorActions`. Because actions are
  open these are overridable, so a list-aware Tab or a code-editor indent is a
  host concern rather than a library change.
- Behaviors are consulted for newline, backspace and forward delete only.
  Typed-character interception (auto-pairing quotes and brackets) is the
  obvious next hook and is deliberately out of scope until something needs it.
- `EditBehavior` is public but the transactional primitives it would want are
  not. An out-of-module behavior can claim an edit and can mutate through the
  public API, but cannot compose several mutations into one revision. Widen
  this when a host asks for it, rather than guessing at the shape now.
- The IME routing is unverified on real hardware. See "Device verification
  still owed" above; that list should be worked through before a release ships
  this.
