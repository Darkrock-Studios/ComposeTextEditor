# Touch input, focus, and the soft keyboard

Working notes for PR #88 (`touch-input-fixes`). This documents what we are trying
to build, the constraints we have hit, three landed attempts with what each one
broke, and the fourth attempt that settled the design. It exists because the
problem ate three review rounds before the decision below was made.

## The goal

On Android, focus is what raises the soft keyboard, and the editor takes focus
through pointer input. The keyboard should rise exactly when the user asks to
type, and never cover something the user just opened. Concretely:

1. Panning or flinging the document must not raise the keyboard. This was the
   original complaint: touching the text to scroll popped the keyboard over the
   content on every drag.
2. A tap places the caret and raises the keyboard.
3. A tap that opens the spell-check suggestion popup must not raise the keyboard
   over the popup. Device-confirmed as the desired behaviour, including staying
   unfocused after a suggestion is applied.
4. A long press selects a word, and the selection must be typeable-over, which
   means long-press-select needs focus.
5. Fling must coast. Scrolling is handled by an ancestor `Modifier.scrollable`,
   and it must keep receiving the gesture untouched.
6. Everything not listed stays as it was: mouse focuses on press, right-click
   opens the menu and focuses, `RichTextView` selection and copy work, selection
   handle drags work.

A separate fix that came along for the ride and has survived every round: the
spell-check editor only opens a menu on tap when it actually has a correction to
offer, and taps on spans it does not own are delegated to the host's listener.

## Constraints discovered

These are the facts that killed the attempts. Any future design has to satisfy
all of them at once.

**Focus is the only lever.** There is no per-gesture "do not show the keyboard"
control in this codebase; whoever requests focus decides.

**The knowledge and the decision live in different modifiers.** What a tap did
(placed a caret, opened a popup, grabbed a handle) is known inside the gesture
handler on the text Canvas. Focus is requested by a separate handler on the
enclosing container, which also covers the empty space below a short document.
Several `pointerInput` handlers plus the ancestor `scrollable` all observe the
same events in parallel.

**A finger down is ambiguous until lift.** Tap, pan, and long press only become
distinguishable later, so focus cannot be decided at the down event. Mouse is
unambiguous at press.

**Pointer consumption is broadcast, not private.** Consuming a change to signal
one handler also signals every other handler. In particular the ancestor
`scrollable` treats a consumed release as its drag being taken away and cancels
the fling instead of finishing with velocity. Consumption cannot carry a private
bit between two of our own modifiers.

**Compose dispatches the Main pass child-first.** An ancestor scroll container
consumes moves after our Canvas-level handler has already seen them, so reading
`isConsumed` at the Canvas can never observe an ancestor's scroll consumption.

**`onRichSpanClick` returning true does not mean "do not focus".** Rich spans
include bullets, blockquotes, links, and spell-check underlines. Hosts (including
our own sample app) return true for every click type just to observe clicks.
Bullet and blockquote lines are covered by spans, so any design that suppresses
focus when the listener returns true makes list items and quotes unfocusable by
touch. This is the bug found on device today, and it was also review finding #1
in round two; the third attempt reintroduced it by keying its gate off the same
return value.

**Android reports external mice as `PointerType.Touch`.** Mouse-vs-finger must
be detected from `buttons`, not pointer type (see the `android_mouse_pointer_type`
memo). Any focus logic that branches on finger-vs-mouse inherits this.

**The two costs are asymmetric.** A keyboard that fails to appear costs the user
one extra tap. A keyboard that appears over a popup, or an editor that cannot be
focused at all, is broken. When in doubt, focus.

## Attempt log

### Attempt 1, commit `393f68c`: focus on lift, consumption as the span signal

Finger focuses on lift within touch slop; mouse focuses on press. A tap claimed
by a span (judged by `onRichSpanClick` returning true) consumed the release, and
the container's focus handler skipped consumed releases.

Device-confirmed wins that all later attempts kept: pan no longer raises the
keyboard, tapping a misspelled word shows suggestions without the keyboard.

Review round two found seven distinct defects, the big ones being:

- Keying "do not focus" off the listener return broke every editor whose host
  returns true liberally, including the sample app (spans unfocusable by touch).
- Long press still raised the keyboard over the selection handles, because the
  focus handler re-derived tap recognition and disagreed with the gesture
  handler about what a long press is.
- The slop check used local coordinates, so a pan that scrolls an ancestor
  container (which translates the editor with the finger) read as a tap.
- `event.changes.first()` misbehaves with a second pointer down.

### Attempt 2, commit `f365883`: the gesture handler owns focus

Moved the focus decision into `handleTextInteractions` via an `onRequestFocus`
callback, since that is where the gesture is classified. To tell the container
fallback to stand down, it consumed the release of every gesture it handled.
Added pointer-id tracking and an `isConsumed`-means-drag check for the ancestor
pan case. Made long press deliberately not focus.

Review round three found ten defects, all confirmed, and this attempt was
reverted whole:

- Consuming every release killed fling on every touch platform: the ancestor
  scrollable saw its drag consumed and reported DragCancelled instead of
  DragStopped with velocity.
- `RichTextView` wires no `onRequestFocus`, so a selectable view became
  permanently unfocusable: selection visible but Ctrl+C dead.
- Right-click never called the callback but still consumed, so right-click could
  no longer focus the editor.
- The pointer-id latch was never reset, so it only worked for the first gesture
  the node ever saw. Dead code.
- The `isConsumed` drag check ran on the Main pass before any ancestor could
  consume. Dead code.
- A press on a selection handle broke out of the loop before the consume, so the
  fallback focused anyway and raised the keyboard over the handles.
- Long-press-no-focus was the wrong call: a selection you cannot type over is
  useless, and tapping to summon the keyboard destroys the selection. Real
  Android text fields keep focus on long-press-select.

Lesson: centralising the decision was fine; the stand-down signal (broadcast
consumption) was not separable from the fling and fallback machinery.

### Attempt 3, commit `e8ebb1a`: revert, plus a private gate

Reverted attempt 2. Replaced consumption with `SpanTapGate`, a tiny object owned
by the composable and read by exactly two modifiers, reset at each gesture
start, set when a span claimed the tap. Also: a handle hit no longer counts as a
span claim, long press focuses again (with a test), and the spell-check
delegation fix was re-applied.

Fling confirmed working on device. But the gate is still keyed off
`onRichSpanClick` returning true, which is the same broken predicate from
attempt 1: on device today, tapping a blockquote or a bullet list item moves the
caret but raises no keyboard. Tapping a misspelled word correctly shows
suggestions without the keyboard, which proves the plumbing works and only the
predicate is wrong.

### Attempt 4, the settled design: ask what the tap did, not who answered

The requirement was never "a span handled this"; it is "this tap opened a popup
the keyboard would cover". The editor already knows whether a popup is showing:
`TextEditorContextMenuState.isVisible`, which both the spell-check suggestions
and the standard context menu go through, and which is set synchronously during
the tap dispatch (the suggestion menu shows a loading state immediately).

Shape: `SpanTapGate` is gone; `requestFocusOnPress` takes a
`popupIsShowing: () -> Boolean`; `BasicTextEditor` passes
`{ effectiveContextMenuState.isVisible }`. The Canvas handler signals nothing.
Focus on lift proceeds unless a popup is visible at that moment.

This also covers a case none of the previous attempts handled: a long press on
an existing selection opens the context menu, and the popup check keeps the
keyboard from rising over it, while a plain long-press-select still focuses.
Both are test-pinned.

The underlying principle, stated once so the next attempt does not have to
rediscover it: a tap has three independent outcomes with three different owners.
The text-editing default (caret placement, selection clearing) belongs to the
gesture handler and no span vetoes it. The span reaction (open a popup, navigate
a link, log the click) belongs to the listener chain, and the listener's Boolean
is only a chaining protocol between listeners: it is how the spell-check
wrapper decides whether to delegate to the host's listener, and it has no effect
on the editor itself. Focus belongs to the container's handler and is decided
only from editor-observable outcomes (is a popup showing), never from what any
listener returned. Per-span "interactive" flags were considered and rejected:
they rebuild the broken listener-return predicate one level down, and a claimed
tap still would not answer the question the keyboard actually poses.

The open questions from the draft, resolved:

- Hosts with their own popups: the supported pattern is to route them through
  the `contextMenuState` parameter, which is exactly what the spell-check
  wrapper does, making its popup visible to the focus predicate for free. A host
  whose dialog cannot go through the menu state gets a keyboard over it, at the
  cost of one extra tap; if that ever hurts in practice, the escape hatch that
  fits the design is a `suppressKeyboardWhen: () -> Boolean` parameter ORed into
  the predicate, still "what is showing", never "who handled it". Not built
  until someone asks.
- `RichTextView` passes `{ false }`: it is read-only, never raises a keyboard,
  and its focus exists only to route copy shortcuts, so no tap ever suppresses
  it (attempt 2 proved suppression there only breaks Ctrl+C).
- The timing holds by construction: the Main pass dispatches child-first, so the
  Canvas handler runs the tap dispatch that opens the menu before the
  container-level handler sees the same release and reads the lambda. Stated in
  a comment at the read site in `requestFocusOnPress`.

The `RichSpanClickListener` KDoc now states the Boolean's real contract
(listener chaining only, no editor behavior attached), replacing the old
"return true to consume the event" wording that three attempts took literally.

## What is true on the branch right now

- Focus on lift for finger, on press for mouse. Device-confirmed: no keyboard on
  pan, fling coasts.
- Spell-check menu policy: tap opens a menu only with a correction to offer,
  other spans delegate to the host listener.
- Long press selects and focuses (test-pinned).
- Focus is skipped only when the tap or long press left a popup showing
  (test-pinned from both sides: popup-opening tap does not focus, span-claimed
  tap with no popup does). Bullets and blockquotes focus by touch again.
- The markdown demo document is long enough to fling and puts blocks below the
  fold.

Deliberately not addressed, tracked as follow-ups:

- Dragging a selection handle cannot restore focus once it is lost (needs the
  same "gesture completed, restore focus" design as popup dismissal).
- The orphaned long-press job when a second finger lands mid-gesture. Pre-dates
  this branch; exists on `main`.

## Verification assets

- `TouchFocusE2eTest`: drives real touch and mouse gestures through the composed
  editor. Each behavioural claim has a test, and the suite is A/B verified: with
  the fixes reverted, exactly the tests pinning the fixes go red and the
  guard-rail tests stay green. Attempt 4's A/B against attempt 3: the
  claimed-tap-still-focuses and long-press-on-selection tests go red, the other
  eight stay green.
- Harness additions in `EditorUiTest`: `tapAt`, `tapAtCharacter`, `panFrom`,
  `longPressAtCharacter`, `autoFocus` and `contextMenuState` parameters. Gotchas
  recorded: the long-press timer is a coroutine on the editor's scope driven by
  the virtual test clock, so tests must use `mainClock.advanceTimeBy`, never
  `Thread.sleep`; and pointer input is injected at the tagged editor node, not
  `onRoot()`, because an open context menu popup is a second root and makes
  `onRoot()` ambiguous.
- What desktop tests cannot see: the keyboard itself, fling physics, and IME
  interaction. Every attempt so far has needed the Pixel Fold to find what the
  suite missed, so device passes stay part of the loop.
