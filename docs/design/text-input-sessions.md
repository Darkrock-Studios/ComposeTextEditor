# Text input sessions

How composed text input (the IME) is connected to the editor. Key chords and
pointer gestures are simple translation and are covered by the input section
of ARCHITECTURE.md; this doc covers the part with a real protocol in it: the
platform input-method connection, the Android `InputConnection` in
particular, and where the key pipeline entangles with it.

## The session lifecycle

The session is owned by `TextEditorInputModifierNode` and driven entirely by
focus. Gaining focus (while enabled) launches a session; losing focus, or
disabling the editor, cancels it. Nothing else in the system may start or
stop input sessions.

Launching does two things, one per direction of the IME contract:

1. Starts `ImeCursorSync`, the editor-to-IME direction (below).
2. Calls Compose's `establishTextInputSession` and hands it the platform's
   `TextEditorTextInputService.startInput`, the IME-to-editor direction.

`startInput` never returns normally; a session ends only by cancellation, so
there is exactly one live session per node and relaunching cancels its
predecessor. What `startInput` actually does is the per-platform fork:

- **Android**: starts an input method with a request that builds a real
  `InputConnection`; this is what opens the soft keyboard.
- **Desktop and iOS**: starts an input method with a Compose (skiko)
  `PlatformTextInputMethodRequest` that adapts the editor state and routes
  edit commands; used for composed input only.
- **WASM**: suspends forever. There is no IME path in the browser; typed
  characters arrive as `keydown`-derived key events.

## The contract has two directions

Commands flow in: `commitText`, `setComposingText`, `deleteSurroundingText`,
`setSelection`, and friends. State flows out: the IME keeps its own mirror of
the text around the cursor, and every command it sends is computed against
that mirror. If the mirror goes stale (the editor changed and the IME was not
told), the IME's next command edits text that is not there. Most IME bugs are
direction-two bugs; when diagnosing one, suspect the notification path before
the command path.

## The shared core: `ImeEditLogic`

Every mutating IME command has exactly one implementation, as commonMain
extension functions on `TextEditorState`. The platform adapters (the Android
`InputConnection` methods, the desktop and iOS `editText` scopes) translate
their platform's calls into these functions and decide nothing themselves.
The semantics they pin down:

- `commitText` replaces the composing region when there is one, otherwise
  replaces the selection or inserts at the cursor, then always ends
  composition (even when no text changed).
- `setComposingText` is the same replacement, but the inserted text becomes
  the new composing region (rendered underlined). This is the path dead-key
  and accent composition takes.
- The Android `newCursorPosition` contract is honored everywhere: positive
  is relative to the end of the inserted text, zero or negative to the start.
- `setSelection` collapses to a cursor when the range is empty; the cursor
  lands at `end` per platform convention.
- Composition replacement passes `inheritStyle`, so autocorrect replacing a
  bold word does not strip the bold.

Two guards worth knowing. The composing range is held on `TextEditorState`
and, like the selection, is discarded by any content mutation (see
edit-operation-offset-transforms.md); if a composing range somehow survives
an out-of-pipeline edit, it is validated against the current document and
treated as absent rather than trusted. And each of these functions is a
single ordinary mutation through the edit manager: nothing here coalesces
undo. Batching (below) suppresses notifications only.

## Android

Android is the deep end: the IME is a separate app holding a live
`InputConnection` into the editor, and the protocol has years of
vendor-specific folklore. The implementation deliberately mirrors
androidx foundation's `RecordingInputConnection` (the one behind
`BasicTextField`) so we inherit the contract IMEs are actually tested
against, rather than inventing our own.

### The connection

`startInput` registers a `PlatformTextInputMethodRequest` whose
`createInputConnection` populates `EditorInfo` (multiline text, autocorrect,
sentence caps, no fullscreen extract UI, initial selection in flat character
indices) and returns a `TextEditorInputConnection`. The connection's read
side (`getTextBeforeCursor`, `getSurroundingText`, `getExtractedText`)
answers from the state's flat-index conversions; its write side records
commands.

### Batch edits

IMEs wrap multi-command edits (autocorrect is typically a delete plus a
commit) in `beginBatchEdit`/`endBatchEdit`. Mutations are recorded as command
values and applied when the outermost batch ends. Every single command also
wraps itself in its own depth 0 to 1 to 0 batch, so batched and unbatched
mutations exit through one drain path.

The defensive details exist because real IMEs misbehave:

- Some (Huawei Celia, SwiftKey) send `endBatchEdit` without a matching
  begin. The depth floors at zero so a stray end cannot go negative and
  permanently block the drain.
- `closeConnection` resets the batch depth, so a dead connection's stale
  depth cannot suppress notifications for the next session.
- The platform batch flag is cleared *before* the drain applies the queued
  commands, so the state mutations run with `isInBatchEdit == false` and the
  observation path (next section) sees them.

### Notifying the IME: no manual notify path

This is the design decision the whole Android side hangs on: nothing in the
edit path calls `InputMethodManager` notify methods. `ImeCursorSync` collects
the state's flows and derives every notification:

- Cursor and selection changes (combined, deduped against the last values
  actually sent) drive `updateSelection`. Deduping matters because
  IME-originated edits flow through here too: without it, every command the
  IME sends would echo back an update it already expects.
- Applied edits on `editOperations` drive `updateExtractedText` when the IME
  has requested extracted-text monitor mode; edits always emit here even when
  the selection did not move (autocorrect to a same-length word).
- Both are suppressed while a batch edit is in progress, so the IME sees one
  consistent update per logical edit, not the intermediate states.

Because notifications derive from state rather than from call sites,
IME-originated edits notify exactly like keyboard, pointer, undo, or
programmatic edits. That closes the loop that keeps the IME's mirror correct,
and it is what makes `setSelection` safe to treat as absolute indices.

Cursor anchor info (`updateCursorAnchorInfo`, used by floating toolbars,
stylus handwriting, and some candidate windows) follows the same pattern:
requested by the IME via `requestCursorUpdates`, then pushed on cursor moves
from the caret's layout metrics plus the view's screen location. The Android
`View` all of this needs is captured by the `CaptureViewForIme` composable
into `platformExtensions` when the editor enters composition.

### One key pipeline

The IME can synthesize key events (`sendKeyEvent`), and hardware keyboards
on Android deliver events through the soft-keyboard intercept chain before
the normal key path. Both are routed into the same
`TextEditorKeyCommandHandler`:

- `sendKeyEvent` forwards to `View.dispatchKeyEvent`, which re-enters
  Compose's key dispatch from the top.
- The modifier node mirrors its shortcut handling in
  `onPreInterceptKeyBeforeSoftKeyboard`, because with an active IME a
  Bluetooth keyboard's Ctrl+C would otherwise be consumed before
  `onPreKeyEvent` ever fired. It intercepts only chords the handler claims;
  typed characters fall through to the IME, which delivers them as
  `commitText`.
- `performContextMenuAction` (the IME's select-all/copy/paste/cut buttons)
  synthesizes the matching Ctrl chords through the same dispatch, and
  `performEditorAction` maps the unspecified/none actions to newline, since
  some IMEs send Enter that way instead of committing `"\n"`.

The result: navigation, shortcuts, and printable characters resolve in one
handler regardless of whether they originated from hardware or from the IME.

## Desktop

Desktop establishes a real input-method session too: Compose attaches AWT
`InputMethodRequests` to the window and routes `InputMethodEvent`s into the
request's `editText` callback, which calls straight into `ImeEditLogic`.
That is what makes dead keys, the macOS press-and-hold accent popup, CJK
input, and the Windows emoji picker work.

Plain typing does not take that path: AWT delivers it as `KEY_TYPED`, which
reaches `handleCharacterInput` as a key event. The two paths coexist and AWT
delivers any given keystroke through exactly one of them. The
character-input predicate accepts only `Unknown`-type events on desktop
because AWT also fires a `KEY_PRESSED` for the same keystroke; accepting
both would double-insert every printable key.

State out is simpler than Android: the request exposes a live adapter
(length, charAt, subSequence served from the requested range only, so IME
queries stay cheap on large documents) that the framework reads on demand,
plus the caret rectangle from the cursor's layout metrics to position the
candidate window.

## iOS

iOS uses the same request shape as desktop: the live state adapter for
reads, `onEditCommand` translating the common commands (commit, delete
surrounding, set selection, backspace). Its `editText` scope currently
applies changes by diffing and replacing the whole document, which is
correct but coarse. This is the least-exercised backend; treat it as a
starting point, not a reference.

## WASM

No input-method session exists: `startInput` suspends until cancelled, and
all typing arrives as browser `keydown`-derived key events (the predicate
accepts `KeyDown`; the browser never emits an `Unknown`-type event).
Composed input (IME typing in a browser) is a known gap.

## Rules for new code

- A new IME mutation is implemented once in `ImeEditLogic` and called from
  every platform adapter. Adapters translate; they never decide semantics.
- Never notify the `InputMethodManager` from an edit path. The state flows
  are the notification path; if the IME's mirror is stale, fix the
  observation, do not add a manual push.
- A batch edit suppresses notifications, nothing more. Undo coalescing is
  `TextEditHistory`'s business, and the two must not be conflated.
- Session start and stop belong to the modifier node's focus handling; no
  other code may establish or cancel input sessions.
- Which event types mean "typed character" is a platform fact and lives in
  `isCharacterInputCandidate`, not in handler logic.
- When an IME misbehaves (unbalanced batches, unexpected action codes),
  harden the connection defensively the way the existing floors do; never
  assume the protocol is followed.
