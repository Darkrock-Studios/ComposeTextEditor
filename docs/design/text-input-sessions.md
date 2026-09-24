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
predecessor.

Focus events are not unique: Compose re-sends one when a focused editor is
tapped again. The node keeps a live session through those and only asks the
software keyboard controller to show, which brings back a keyboard the user
dismissed without restarting the IME (a restart resets the keyboard mid-word
and discards whatever it had in flight). It relaunches only when no session is
running, or when it is rebound to a different state or its `enabled` flag
flips while focused.

What `startInput` actually does is the per-platform fork:

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
vendor-specific folklore. The implementation follows the contract `EditText`,
Chromium, and androidx foundation's `StatelessInputConnection` (behind the
current `BasicTextField`) share, because that is what keyboards are tested
against. Keyboards that follow the protocol loosely are the ones that expose
any departure from it.

### The connection

`startInput` registers a `PlatformTextInputMethodRequest` whose
`createInputConnection` populates `EditorInfo` (multiline text, autocorrect,
sentence caps, no fullscreen extract UI, initial selection in flat character
indices) and returns a `TextEditorInputConnection` bound to the session's
view. The connection's read side (`getTextBeforeCursor`, `getSurroundingText`,
`getExtractedText`) answers from the state's flat-index conversions, measuring
from the selection's edges and clamping requested lengths before any
arithmetic (some IMEs ask for `Int.MAX_VALUE`); its write side applies
commands.

### Batch edits

IMEs wrap multi-command edits (autocorrect is typically a delete plus a
commit) in `beginBatchEdit`/`endBatchEdit`. Commands apply to the state
immediately, batch or no batch, as they do in `EditText`; a batch only holds
back notifications until the outermost one ends. Every single command also
runs in its own batch, so batched and unbatched commands end the same way.

Applying immediately is a robustness decision. Queuing commands until the
outermost batch ends (what androidx's legacy `RecordingInputConnection` did,
and this editor once copied) makes the editor the one place where a keyboard's
batching mistakes cost text: a keyboard that leaves a batch open types
nothing at all, and reads made mid-batch miss the keyboard's own edits, so it
rebuilds its composition from the wrong buffer.

The defensive details exist because real IMEs misbehave:

- Some (Huawei Celia, SwiftKey) send `endBatchEdit` without a matching
  begin. A connection ignores an end for a batch it never opened, so a stray
  end can neither go negative nor release a batch someone else holds.
- Batch depth is counted per connection. `closeConnection` releases exactly
  the levels its own IME left open, without notifying, so a dead connection
  cannot suppress notifications for its successor. A connection replaced by a
  restart closes after its successor opened, so it resets the monitor flags
  only if it is still the active connection.

### Notifying the IME: one flush, never mid-edit

Nothing in the edit path calls the `InputMethodManager`. `ImeCursorSync`
reports everything through a single `flush`, which compares the state as it
stands (selection, composing region, and the text when the IME monitors
extracted text) against what the keyboard was last told, and reports only the
difference. A flush runs at one of two points:

- when the outermost batch edit ends. Every IME command runs in one, so a
  keyboard hears about its own edit exactly once, after it is complete;
- posted to the main looper after any other change (keys, pointer, undo,
  programmatic edits), signalled by the state's cursor, selection, edit, and
  resync flows. The flows are triggers only: the flush reads the state once
  the change has finished, not the value a flow carried.

A flush while a batch is open does nothing; the batch's end flushes instead.

This is strict because an edit passes through states the keyboard must never
see. Replacing composing text clears the composing range before the new range
is set, so a report taken inside that edit tells the keyboard its composition
vanished, and composing keyboards believe it: Gboard's Japanese input finished
its composition on every keystroke, so kana could never be converted. A
delete-then-commit autocorrect reported half way looks like the user moved the
caret, which keyboards that track their expected selection treat as a reason
to reset. The earlier design reported from flow collectors, which did both,
and also dropped the final report whenever it landed while the next batch was
already open.

The comparison is what keeps IME-originated edits from echoing: a keyboard's
own command produces exactly the report it expects, once. Because the
composing region is part of the comparison, composing-only changes
(`setComposingRegion`, `finishComposingText`) are reported too.

A behavior that answers an IME request in a way no diff can express (exiting
a list on backspace leaves text and caret where they were) sets a resync flag
on the state. The next flush consumes it with `restartInput`, which makes the
keyboard discard its mirror, then reports afresh. The flag, not only the
flow, is what guarantees the restart goes out when the IME's batch ends.

Cursor anchor info (`updateCursorAnchorInfo`, used by floating toolbars,
stylus handwriting, and some candidate windows) is requested by the IME via
`requestCursorUpdates` and sent by the flush whenever the selection report
changes, from the caret's layout metrics plus the view's screen location. The
`View` `ImeCursorSync` reports through is captured by the `CaptureViewForIme`
composable into `platformExtensions` when the editor enters composition.

### One key pipeline

The IME can synthesize key events (`sendKeyEvent`), and hardware keyboards
on Android deliver events through the soft-keyboard intercept chain before
the normal key path. Both are routed into the same
`TextEditorKeyCommandHandler`:

- `sendKeyEvent` hands the event to
  `InputMethodManager.dispatchKeyEventFromInputMethod`, the post-IME dispatch
  `EditText` uses, so it reaches Compose's key dispatch the way a hardware
  key does after the IME stage. A string sent as an `ACTION_MULTIPLE` key
  event (the legacy way to send text as a key) is committed as text instead,
  since it has no key code to translate.
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
- IME commands apply immediately. Never queue an edit behind a batch: a
  keyboard's batching mistakes may delay a notification, never lose text.
- Never notify the `InputMethodManager` from an edit path. Reports go through
  `ImeCursorSync.flush`, at a batch end or posted; if the IME's mirror is
  stale, fix what the flush compares or when it runs, do not add a push.
- A batch edit suppresses notifications, nothing more. Undo coalescing is
  `TextEditHistory`'s business, and the two must not be conflated.
- Session start and stop belong to the modifier node's focus handling; no
  other code may establish or cancel input sessions.
- Which event types mean "typed character" is a platform fact and lives in
  `isCharacterInputCandidate`, not in handler logic.
- When an IME misbehaves (unbalanced batches, unexpected action codes),
  harden the connection defensively the way the existing floors do; never
  assume the protocol is followed.
