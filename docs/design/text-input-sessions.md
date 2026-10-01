# Text input sessions

How composed text input (the IME) is connected to the editor. Key chords and
pointer gestures are simple translation and are covered by the input section
of ARCHITECTURE.md; this doc covers the part with a real protocol in it: the
platform input-method connection, the Android `InputConnection` in
particular, and where the key pipeline entangles with it.

## The session lifecycle

The session is owned by `TextEditorInputModifierNode` and driven by focus and
taps. Gaining focus (while enabled) launches a session; losing focus, or
disabling the editor, cancels it. Nothing else in the system may start or
stop input sessions.

Launching does two things, one per direction of the IME contract:

1. Starts `ImeCursorSync`, the editor-to-IME direction (below).
2. Calls Compose's `establishTextInputSession` and hands it the platform's
   `TextEditorTextInputService.startInput`, the IME-to-editor direction.

`startInput` never returns normally; a session ends only by cancellation, so
there is exactly one live session per node and relaunching cancels its
predecessor.

Focus events are not unique: Compose re-sends one, unchanged, for reasons
that have nothing to do with the user (a tap on the focused editor,
focus-property invalidation), so the node acts only on a change of focus.
A tap reaches it separately: the tap handler that requests focus also calls
`TextInputRequester.requestInput`, because tapping an editor that already has
focus changes no focus state. The node answers by showing the keyboard in the
live session, bringing back one the user dismissed, and starts a session only
if none is running. It never restarts a live one for a tap, since a restart
resets the keyboard mid-word and discards whatever it had in flight.

A session starts only when the user asks for input, because starting one
raises the soft keyboard: on gaining focus, or on a tap. Turning input back on
under focus (`enabled`, or `readOnly` switched off) restores the focus state and,
where the platform can keep the keyboard down (`startsInputQuietly`: Android and
desktop), starts a session with a request to hide the keyboard queued behind it,
which Android's input service coalesces with the session's own request to show,
so the keyboard never rises; a tap then shows it in that session. iOS and the web
wait for a tap, since their keyboard follows the session's first responder or
focused text area. Rebinding the editor to a
different state restarts a live session, since a session is bound to its
state.

What `startInput` actually does is the per-platform fork:

- **Android**: starts an input method with a request that builds a real
  `InputConnection`; this is what opens the soft keyboard.
- **Desktop, iOS, and WASM**: start an input method with the one skiko
  `PlatformTextInputMethodRequest` shared by the three
  (`SkikoTextEditorInputMethodRequest` in `skikoMain`), which adapts the
  editor state and routes every edit into `ImeEditLogic`. Each platform file
  contributes its `ImeOptions`; web also keeps DOM focus on its textarea
  (below).

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
`InputConnection` methods, the skiko request's `editText` scope and
`onEditCommand` list) translate their platform's calls into these functions
and decide nothing themselves.
The semantics they pin down:

- `commitText` replaces the composing region when there is one, otherwise
  replaces the selection or inserts at the cursor, then always ends
  composition (even when no text changed). Once the text has landed and the
  caret is placed, the `EditBehavior` chain is told where (`onTextInput`); a
  behavior that edits on top owns the caret and the IME is asked to resync.
  `setComposingText` never tells it: composing updates are not committed
  text. `finishComposingText` over a typed composition does, since it
  commits the composition as it stands.
- `setComposingText` is the same replacement, but the inserted text becomes
  the new composing region (rendered underlined). This is the path dead-key
  and accent composition takes.
- The Android `newCursorPosition` contract is honored everywhere: positive
  is relative to the end of the inserted text, zero or negative to the start.
- `setSelection` collapses to a cursor when the range is empty; the cursor
  lands at `end` per platform convention.
- Composition replacement passes `inheritStyle`, so autocorrect replacing a
  bold word does not strip the bold.

Guards worth knowing. The composing range is held on `TextEditorState`
and, like the selection, is discarded by any content mutation (see
edit-operation-offset-transforms.md); if a composing range somehow survives
an out-of-pipeline edit, it is validated against the current document and
treated as absent rather than trusted. A pointer that puts the caret or a
selection outside the composition ends it, which is what keyboards do
themselves when told of the move; one that does not would type over the old
composing word, wherever it is. A caret placed inside the composition keeps
it, since some keyboards edit mid-composition. `deleteSurroundingText` counts
from the selection's edges and leaves the selection itself in place, as the
Android contract requires. Each of these functions is one undo step (a
commit over a selection groups its delete and insert), and `setComposingText`
is recorded as typing: a composition's updates and its commit fold into the
typing run they rewrite, so a composed word plus its commit undoes as one
step and joins the typing around it as a plain typed word does. A commit is
typing only when what it replaces was composed (`composingIsTyped`); a commit
over text the IME merely marked with `setComposingRegion` is the shape of an
autocorrect and stays its own step, so undo gives back what was typed. The
protocol cannot tell a keyboard correcting the word it is composing from a
CJK keyboard committing the candidate for what it is composing, nor a
keyboard that re-marks a word and rewrites it through `setComposingText`
from one letting the user keep typing that word, so both fold into the run:
undo removes the word rather than reverting the correction. Batching (below)
suppresses notifications only.

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
`createInputConnection` returns a `TextEditorInputConnection` bound to the
session's view and populates `EditorInfo`: the input type and action from the
host's `TextEditorState.keyboardSettings` (by default multi-line text with
autocorrect and sentence caps, Enter as a new line; single-line text with Done
for an editor limited to one line), no fullscreen extract UI,
the initial selection in flat character indices, the caps mode at the caret,
and, from API 30, the text around the caret. The action key a connection was
opened with calls the host's `onImeAction`, or the default the modifier node
supplies (Next and Previous move focus, Done hides the keyboard); the
unspecified and none actions are Enter. A settings change restarts input from
the next flush, as `EditText.setInputType` does; iOS and web do not read the
settings yet. The connection's read side (`getTextBeforeCursor`, `getSurroundingText`,
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
  cannot suppress notifications for its successor.
- Monitor requests (extracted text, cursor anchor) belong to one IME session,
  so opening a connection clears them: its keyboard has asked for nothing yet.
  A restart opens the successor before closing the old connection, so
  clearing on close alone would carry the old session's requests over.

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

A whole-document replacement (`setText`, `setDocument`) advances
`documentGeneration`, which is no edit the keyboard could follow: the next
flush sends `restartInput`, which makes the keyboard discard its mirror, then
reports afresh, as `EditText` restarts input on `setText`.

A behavior that answers an IME request its own way (a claimed Backspace that
demotes a bullet, "--" become a dash) advances a resync generation on the
state. A restart clears the keyboard's suggestions and shift state, so it is
the last resort. `ImeExpectation` follows where the keyboard's own commands
since the last flush have left it expecting the selection and composing
region. If the flush finds the selection where the keyboard expects it,
nothing more is needed (a same-length substitution stays as `EditText` leaves
it). If it finds a selection the keyboard was not last told, the ordinary
report reaches it, and keyboards re-read the text around an unexpected
selection as they do after a tap. Only when the selection is where the
keyboard last heard it (or nothing has been reported yet) and not where its
commands left it expecting, or where those commands cannot be followed (a key
event, a delete counted in code points), does the flush restart: the
`InputMethodManager` drops a report that repeats the last one. A key event is
queued rather than applied, so the expectation stays unknown until the flush
after the key has been handled (a key the view holds behind another pending
input event can land after that flush; then a claim of it goes unnoticed). A resync the keyboard's commands did not
cause (a hardware key, a host's edit) leaves the keyboard expecting what it
was last told, so it restarts nothing. `invalidateInput` (API 34) would not be lighter
here, since Compose's connection wrapper does not pass `takeSnapshot`
through, which makes it fall back to a restart. Reading counters at flush
time, rather than waiting on a flow, is what guarantees the report or restart
goes out when the IME's batch ends.

Cursor anchor info (`updateCursorAnchorInfo`, used by floating toolbars,
stylus handwriting, and some candidate windows) is requested by the IME via
`requestCursorUpdates` and sent by the flush whenever the selection report
changes, and while it monitors, whenever the caret moves on screen without
the selection changing (a scroll, a relayout, the editor moving or resizing
in its window, the strip a keyboard covers): the flush compares the caret's
geometry
and the view's screen location too, and remembers what any report sent, an
immediate one included. While it monitors, each frame that draws the view
somewhere else on screen than the last anchor said resends the anchor alone,
as `TextView` checks its position on each frame: that catches a view that
moves with nothing in the editor changing (a window panned for the keyboard,
a scrolling parent). The marker is the caret
measured from the layout as it is sent (the last frame's drawn caret is one
move behind), in the view's coordinates (the canvas's position in the Compose
root, so content padding and scroll are in it), with flags saying whether its
top and bottom are inside the editor's clipped bounds less a strip the
keyboard covers; the matrix is the view's screen location. The skiko
request's caret rectangle is built from the same geometry
(`imeCaretInRoot`).
Reports go through the view the live connection is bound to, the one the
`InputMethodManager` is serving; between sessions they fall back to the view
the `CaptureViewForIme` composable captures into `platformExtensions`.

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
- A hardware keyboard's dead key reaches the key path too, as a character
  carrying `KeyCharacterMap.COMBINING_ACCENT`. `DeadKeyComposer` shows the
  accent as a composition (`imeSetComposingText`) and settles it on the next
  key: the composed pair committed, or the accent committed and the next
  character typed after it, as `EditText` does. Any other key (an arrow,
  Enter, Escape) commits it first. An IME text command lands over the
  composition, as it would over the accent `EditText` selects. Nothing else
  on the key path composes.
- `performContextMenuAction` (the IME's select-all/copy/paste/cut buttons)
  synthesizes the matching Ctrl chords and dispatches them to the view
  directly, so they resolve through the same handler and registry. Unlike
  `sendKeyEvent` they apply before the call returns, as in `EditText`: an IME
  reads the selection right after asking for select-all. And
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

## iOS

iOS starts the same shared request. Compose's `UIKitTextInputService` binds a
`UITextInput` view to it and applies everything the keyboard does through
`editText`: typing is `commitText`, marked text is `setComposingText`,
autocorrect is `deleteSurroundingTextInCodePoints` plus a commit, dictation
is a run of composing updates ending in a commit. All of it lands in
`ImeEditLogic`, so what the desktop suite pins down about composition and
surrounding deletes holds on iOS by construction. The iOS file contributes
the keyboard traits only: default keyboard, sentence capitalisation,
autocorrect on, multiline.

What iOS cannot get from the shared request is a Compose `TextLayoutResult`,
because the editor lays out its own lines. The request answers null there, as
the interface allows, so features that read per-character geometry from it
(the spacebar trackpad's floating caret, marked-text rectangles) are
unavailable until the editor can offer an equivalent (roadmap 4.6). The caret
and editor rectangles are supplied.

This backend has been compiled and exercised only through the desktop suite's
coverage of the shared code; the device pass is queued for the Mac.

## State out on the skiko platforms

The request exposes a live adapter (length, charAt, subSequence served from
the requested range only, so IME queries stay cheap on large documents) that
the framework reads on demand, plus the caret rectangle from the cursor's
layout metrics to position the candidate window or backing input.

The frameworks observe it through `snapshotFlow`: desktop watches the
selection and composition (and ends the platform composition when the caret
leaves it), iOS watches text, selection, and composition, web watches the
`TextFieldValue`. The cursor, selection, and composing range are snapshot
state and trigger those flows on their own; the document content is not, by
design (it is a volatile snapshot readable from any thread). So the request's
text reads fold in the state's `textRevision`, snapshot state that advances with
every text change, in the same apply as the caret move. Without it an
edit that moves nothing observable (a forward delete) would never reach the
platform's mirror.

A resync request (`requestImeResync`) reaches the skiko
platforms through the same session: it watches the generation and hands each
advance to the platform's `SkikoImeResync`. What a platform needs differs.
Desktop's AWT input method asks the request for text as it needs it and keeps
no copy, so desktop does nothing. Web keeps a real copy in its textarea,
and Compose lets a key's default action edit that copy while mirroring the
editor back only when the editor's value changes, so a key the editor answered
without that edit (Enter leaving a list) leaves the browser's own line break
there; web rewrites the textarea from the request's value. iOS does nothing
for now: Compose's iOS connection tells UIKit nothing about a change made
during the keyboard's own edit, and the one tool the session has, restarting
the input method, resets the keyboard (roadmap 4.29).

The caret rectangle is measured from the layout when a platform asks for it,
so an observer re-running at a caret move gets the new position before the
next frame draws the caret. Observers re-run on a caret move only (and on a
resize, through the viewport size); a scroll alone does not move the
rectangle until the caret moves.

## WASM

Web starts the same shared request. Compose's web session (`WebTextInputSession`
over `WebTextInputService`) creates a hidden `<textarea>` next to the canvas,
focuses it (which is what raises the soft keyboard on a phone), mirrors the
request's `value()` into it after every edit, and positions it at the caret
rectangle so the browser's IME candidate window and the phone's keyboard land
near the text. It is the older half of the skiko API: edits come back as an
`EditCommand` list through `onEditCommand`, which the shared request
translates into `ImeEditLogic` calls.

What the browser delivers, and when (`DomInputStrategy` and
`NativeInputEventsProcessor` in Compose's webMain):

- A typed character is a `beforeinput` of type `insertText`, turned into a
  `CommitTextCommand`. Dead keys and CJK input are `insertCompositionText`
  (`SetComposingTextCommand`) followed by `compositionend`
  (`CommitTextCommand`). Mobile autocorrect is `insertReplacementText` or a
  `deleteContentBackward` over a range plus an insert, delivered as a
  `SetSelectionCommand` and a commit, which is why the shared request applies
  a batch in order.
- `keydown` on the textarea is forwarded to Compose's key dispatch only when
  the key carries no character (`isTypedEvent` is false: arrows, Backspace,
  Enter, Tab, Ctrl and Meta chords). Those reach the key handler like any
  other key event. A typed character's `keydown` is dropped by the textarea,
  never forwarded.
- Events are batched and replayed on the next animation frame, in timestamp
  order, so a Backspace `keydown` that Compose consumed suppresses the
  textarea's own `deleteContentBackward`.
- Only the Backspace key does: on macOS the textarea is a Cocoa text view
  with the Emacs-style Ctrl bindings, so Ctrl+H's own `deleteContentBackward`
  became a second backspace. The session prevents the default of a Ctrl
  chord's `keydown` there (not inside a composition, nor with Cmd or Option),
  leaving the chord to the editor's bindings.

Which path owns plain typing therefore follows DOM focus, and the browser gives
a keystroke to one element only. With the textarea focused, typing is
`commitText` and the character-input predicate never sees it. A mouse press on
the canvas moves DOM focus there even when Compose focus stays on the editor: a
right-click (which skips `requestInput`, so a menu is not covered by a phone
keyboard), a toolbar button that takes no focus, a context menu item. Canvas key
events cannot tell some typed characters from named keys, so while its session
is live the web input service listens for `focusin` on the viewport's shadow
root and hands DOM focus from the canvas straight back to the textarea. The
listener sits on the shadow root because a focus move inside a shadow tree is
not reported outside it, and it is removed as the session is cancelled. The
textarea prevents a Tab's default itself and leaves the key to Compose's focus
system; when that moves focus off the editor the session ends and Compose
removes the focused textarea, which would drop DOM focus to the page body, so a
task later the session puts it back on the canvas, unless the last press was
outside the viewport or something else has taken focus. A touch is left alone:
a tap Compose did not consume blurs the textarea to hide the soft keyboard, and
refocusing would raise it again.

The canvas keeps DOM focus only while the editor has no session (a focused
editor disabled and enabled again waits for a tap on the web) or after such a touch,
and a keystroke then
arrives as a canvas `keydown` carrying the character, which the predicate
(`KeyDown`) accepts. The same keystroke cannot reach both elements, but two
shapes the textarea forwards would insert on their own and the predicate
refuses them: a named key (F2, Insert, a dead key), whose Compose event
carries the key code as its code point and would type a letter, and a Ctrl
chord, because Windows browsers report AltGr as Ctrl+Alt and the textarea
commits that character itself. Ctrl is never a typing modifier in a browser,
so refusing it loses nothing. The predicate has no view of the DOM event, so
';' and '=' (whose codes equal their characters, and a German dead key sits
on '=') are refused too, which costs only the no-session canvas path.

A browser answers Ctrl/Cmd+C, X and V in the textarea with a `copy`, `cut` or
`paste` event while the key is down; Compose forwards the key to the editor's
bindings a frame later. `ClipboardEventsEffect` (in `clipboard/`) uses the event
to move the data, since only then may the page use the clipboard without a
permission prompt, and prevents the textarea's own plain-text copy or paste; the
Copy, Cut and Paste actions the key then runs do the editing and take the data
from there rather than from `navigator.clipboard`. Without a session (a disabled
or read-only editor, a `RichTextView`), or after a touch leaves it there, the
canvas holds DOM focus, and Compose takes a key there before the browser fires
any clipboard event, so a copy chord pressed on a Compose canvas asks for a
`copy` event with `execCommand('copy')` first, in the capture phase, and a cut
chord in an editor taking input asks for a `cut` event with `execCommand('cut')`.

Compose sets `autocapitalize="off"` on every backing field whatever the
`ImeOptions` say, so the web session sets it back to `sentences`, as the
Android and iOS sessions ask of their keyboards.

`ImeCursorSync` stays a no-op on web; the session's `snapshotFlow` over
`value()` is the state-out direction, fed by the shared revision described
above. Composition on desktop browsers and the soft keyboard on mobile
browsers follow from the session existing; both need a manual pass in real
browsers (roadmap 4.4, 4.15).

## Rules for new code

- A new IME mutation is implemented once in `ImeEditLogic` and called from
  every platform adapter. Adapters translate; they never decide semantics.
  The skiko command list carries three commands no `InputConnection` has
  (backspace, move cursor, delete all); their translations live beside the
  shared request in `skikoMain`, still one implementation for the three
  platforms that can receive them.
- Desktop, iOS, and web share `skikoMain`. Anything that differs between
  them is a value the platform file passes in (today: `ImeOptions`), not a
  second copy of the request.
- IME commands apply immediately. Never queue an edit behind a batch: a
  keyboard's batching mistakes may delay a notification, never lose text.
- Never notify the `InputMethodManager` from an edit path. Reports go through
  `ImeCursorSync.flush`, at a batch end or posted; if the IME's mirror is
  stale, fix what the flush compares or when it runs, do not add a push.
- A batch edit suppresses notifications, nothing more. Undo coalescing is
  `TextEditHistory`'s business, and the two must not be conflated.
- Session start and stop belong to the modifier node; other code asks for
  input through `TextInputRequester` and never establishes or cancels input
  sessions itself.
- Which event types mean "typed character" is a platform fact and lives in
  `isCharacterInputCandidate`, not in handler logic.
- When an IME misbehaves (unbalanced batches, unexpected action codes),
  harden the connection defensively the way the existing floors do; never
  assume the protocol is followed.
