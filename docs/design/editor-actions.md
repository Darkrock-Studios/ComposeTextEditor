# Editor actions and edit behaviors

The design reference for how input reaches editor behavior: which shortcuts
exist, what they invoke, and how a feature attaches behavior to a primitive
edit without the primitive knowing the feature exists. It replaces the closed
`EditorCommand.Action` enum and the hard-wired line-block branches in
`TextEditorState`. Motivating issue: #37.

## The problem

Two symptoms, one cause.

*Actions are a closed set.* `EditorCommand.Action` is an `internal` enum with
fourteen members. `KeyBindings` maps chords onto it and
`TextEditorKeyCommandHandler` executes it, which is a clean split as far as it
goes, but a host cannot add a member. `MarkdownExtension` already exposes
`toggleBlockquote`, `toggleBulletList`, `toggleOrderedList`, `toggleCodeFence`
and `toggleHeader`, and the sample app drives all of them from toolbar buttons.
Not one of them can be bound to a key without forking the library. Because
there is also no way to *invoke* an action by name, every input surface
re-implements the ones that do exist: `ContextMenuActions.paste` and
`TextEditorKeyCommandHandler.handlePaste` are the same twenty-five lines of
clipboard, rich-span provenance and HTML-block logic, written twice.

*Behavior is welded into primitives.* `insertNewlineAtCursor` and
`backspaceAtCursor` call `detectLineBlock` and branch on the result, so a
line-block concern lives inside the two functions every input source shares.
That inverts the intended layering (core reaching into `richstyle`), and it
leaves no seam for any other feature that wants the same kind of hook.

The second symptom was already a bug. Because the behavior sat in the
primitives rather than at a point every input path agrees on, whether the user
got it depended on which method their IME happened to call:

| Path | Before | After |
| --- | --- | --- |
| Hardware key, `TextEditorKeyCommandHandler` | yes | yes |
| Android `sendKeyEvent(KEYCODE_DEL)`, re-dispatched to the key handler | yes | yes |
| Android `deleteSurroundingText(1, 0)` to `imeDeleteSurroundingText` | no | yes |
| `commitText("\n")` to `imeCommitText` to `insertStringAtCursor` | no | yes |
| `performEditorAction` to `imePerformNewline` | yes | yes |

So on a soft keyboard, backspace at the start of a bullet demoted or merged
depending on the IME's choice of `InputConnection` method, and Enter on an
empty bullet exited the list or did not depending on whether the IME committed
the newline as text. Every row is checked by `ImeLineBlockParityTest`, with the
hardware-key result as the reference the others are compared against.

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
chord, which is precisely why the current code ended up inside
`backspaceAtCursor` in the first place. The fix is not to move it somewhere
chord-shaped; it is to make that interception point explicit and registrable.

## Actions

### The vocabulary

`EditorCommand` becomes public. `Motion` stays an enum: motions are a genuinely
closed vocabulary, nothing about a caret movement is extensible, and keeping it
closed preserves the exhaustive `when` in the handler. `Action` becomes an open
class keyed by a string id:

```kotlin
sealed interface EditorCommand {
    enum class Motion : EditorCommand { Left, Right, /* … */ }

    class Action(val id: String, val isEdit: Boolean) : EditorCommand {
        companion object {
            val SelectAll = Action("editor.selectAll", isEdit = false)
            val Copy = Action("editor.copy", isEdit = false)
            val Paste = Action("editor.paste", isEdit = true)
            // … the current fourteen
        }
    }
}
```

Existing `KeyBindings` implementations are untouched by this: `Action.Copy`
still resolves through the companion. A host writes
`Action("myapp.toggleBold", isEdit = true)` and binds it from its own
`KeyBindings`. Ids are namespaced by convention (`editor.`, `markdown.`,
`myapp.`) and the registry is keyed by id string, not object identity, so a
duplicate id is a detectable collision rather than a silent second entry.

`isEdit` stays on the action because the disabled-editor gate needs it before
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

`isEnabled` exists for the context menu, which today asks `canCut()` /
`canCopy()` / `canPaste()` through bespoke methods. Once actions carry their own
enablement predicate the menu can be built from a list of action ids and stop
knowing what any of them mean.

The core registers its fourteen built-ins when the state is constructed.
`MarkdownExtension` registers `markdown.toggleBold`, `markdown.toggleBulletList`
and the rest from its `init`, which is the same place it already reaches into
`editorState`.

### Resolution and consumption

`TextEditorKeyCommandHandler.handleKeyEvent` becomes:

1. `keyBindings.commandFor(event)` or return false.
2. `Motion`: move the caret, extending on shift. Unchanged.
3. `Action`: return false if `isEdit && !enabled`. Look it up; **return false
   if unregistered** (rule 5). Otherwise `perform(context)` and return true.

Returning false for an unregistered action matters more than it looks: a host
that binds a chord and forgets to register the handler gets the character
typed, not a dead key.

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
like any other operation (the current line-block code already does this
deliberately, going through `toggleLineBlock` rather than mutating spans
directly).

The chain is consulted inside the existing public functions, which keep both
their names and their current behavior:

```kotlin
fun backspaceAtCursor() {
    if (editBehaviors.any { it.onBackspace(this) }) return
    backspaceAtCursorRaw()
}
```

The alternative was a new semantic layer (`performBackspace` running the chain,
`backspaceAtCursor` staying primitive). Rejected for this pass: it changes what
`backspaceAtCursor` does for existing callers, and there is no caller that wants
the primitive without the behavior. The raw form stays internal until something
needs it.

### Line blocks as the first behavior

Line blocks are not a markdown feature. They are a core capability that
markdown happens to serialize, so the behavior stays in the library and
`MarkdownExtension` remains a consumer. It moves out of `TextEditorState` and
into a `LineBlockEditBehavior` in `richstyle`, registered by default, carrying
the logic currently at `TextEditorState.kt:490-502` and `:530-552` verbatim.
The semantics documented under "Smart editing" in
[line-blocks.md](line-blocks.md) do not change.

Moving code that keeps running by default and behaves identically is worth
stating plainly, because it is fair to ask what was gained:

- The dependency narrows. `TextEditorState` drops `applyLineBlock` and
  `detectLineBlock` and names `LineBlockEditBehavior` instead. It does not stop
  importing `richstyle`: `normalizeLineBlocks` and the block span styles are
  still used by layout, measurement and content normalization, which have
  nothing to do with edit behaviors. An earlier draft of this document claimed
  the dependency inverts outright, which was wrong.
- Every input path gets the behavior, which fixes the divergence table above.
- A code-editor host can add auto-indent or bracket-closing without forking.
- It can be turned off, which previously required editing the library.

The newline case does not fit a plain "handled / not handled" return. Enter on
an empty item is a clean claim, but a split *inside* an item needs the block
captured before the split and re-applied to both halves after, in one revision,
because detection after the split is unreliable. Rather than add a second hook
and thread state between the two, `TextEditorState.insertNewlineRaw()` is
`internal` and the behavior performs the split itself inside its own
`withAtomicEdit`. The interface stays a single boolean.

That double apply is load-bearing rather than defensive: with the behavior
removed, splitting an item leaves the new half without its gutter marker, which
`EditBehaviorTest` pins.

## Making every input path agree

With the chain in place, the IME paths route to the semantic functions:

- `imeDeleteSurroundingText(before = 1, after = 0)` with no composing region
  and no selection is a backspace. Route it to `backspaceAtCursor()`. Every
  other shape stays a range delete.
- `imeCommitText("\n", …)` with no composing region is an Enter. Route it to
  the newline path.

This is the risky part of the whole design and should land last, on its own,
with tests. `deleteSurroundingText(1, 0)` is not unambiguously a keypress:
autocorrect and prediction engines use the same call to rewrite what the user
typed, and treating one of those as a backspace would demote a bullet in the
middle of a word correction. The guard conditions above (no composing region,
no selection) exclude the autocorrect case as far as static reading can tell,
but this needs verification on a device against at least Gboard and one
third-party keyboard, not just the `androidHostTest` suite.

As implemented, the guard is written on the computed character range rather
than the caller's arguments: exactly one character ending at the caret, no
composing region, no selection. Writing it that way lets
`deleteSurroundingTextInCodePoints` share it without a one-code-point delete of
an astral character reaching `backspaceAtCursor`, which deletes a single UTF-16
char and would split the surrogate pair.

### Device verification still owed

Everything below is covered only by desktop JVM tests against the shared
`ImeEditLogic`. The guards are a static reading of what an autocorrect rewrite
looks like versus what a relayed keypress looks like, and no soft keyboard was
run against them. Do not treat this as release-ready until it has been checked
on hardware against Gboard and at least one third-party keyboard (SwiftKey or
Samsung Keyboard are the usual second targets).

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
`deleteSurroundingRange` / `imeCommitText`, not to widen it. The pre-existing
behavior is the safe fallback: a plain range delete is always correct for the
text, it just misses the block semantics.

## Landing order

Each step compiles, passes, and is independently revertible. All six have
landed.

1. `EditorCommand` public; `Action` becomes an open class with companion
   constants. Pure mechanical change, no behavior difference.
2. `EditorActionRegistry` on `TextEditorState`. Built-in action bodies move out
   of `TextEditorKeyCommandHandler` into registered specs; the handler resolves
   through the registry.
3. `ContextMenuActions` collapses onto the registry. Deletes the duplicated
   cut/copy/paste bodies.
4. `EditBehavior` chain; `LineBlockEditBehavior` extracted and registered by
   default. `TextEditorState` loses its line-block editing imports.
5. IME backspace and newline routed through the semantic paths. Tests for each
   row of the divergence table.
6. Registration surfaced on `BasicTextEditor` / `TextEditor`, `LocalKeyBindings`
   made public, docs updated, sample app binds Ctrl+B to bold as the worked
   example.

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
  hard-coded `TAB_SIZE = 4`, now in `BuiltinEditorActions`. Because actions are
  open these are overridable, so a list-aware Tab or a code-editor indent is a
  host concern rather than a library change. Not addressed here.
- Behaviors are consulted for newline, backspace and forward delete only.
  Typed-character interception (auto-pairing quotes and brackets) is the
  obvious next hook and is deliberately out of scope until something needs it.
- `EditBehavior` is public but the transactional primitives it would want are
  not. An out-of-module behavior can claim an edit and can mutate through the
  public API, but cannot compose several mutations into one revision. Widen this
  when a host asks for it, rather than guessing at the shape now.
- The IME routing is unverified on real hardware. See "Device verification still
  owed" above; that list should be worked through before a release ships this.
