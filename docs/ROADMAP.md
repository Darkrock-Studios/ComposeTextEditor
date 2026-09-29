# Roadmap: from working to polished

A survey of what separates the editor from a fully polished, professional text
editing widget, and the order to close the gap in. Taken on 2026-09-28 against
`2817c4b` (Compose Multiplatform 1.12.1).

The core is sound. The "not there yet" feeling comes from three places:

1. The caret and selection behave unlike native editors in about a dozen small
   ways that every user hits constantly.
2. Platform depth is uneven. Desktop and Android are real implementations; iOS
   and web input are close to stubs.
3. Writer conveniences are absent, and there is no hook for a host to add them.

None of this needs a document object model.

Phases 1 to 3 and 5 to 7 are ordered by return on effort. Phase 4 (iOS and
web) is not a late phase: it is a parallel track that starts alongside
Phase 1, because both platforms are shipped today.

## How to read this

Each item has an id, a checkbox, and an evidence tag:

| Tag | Meaning |
| --- | --- |
| **R** | Reproduced by running a probe through the desktop e2e harness |
| **S** | Confirmed by reading the code directly |
| **C** | Found by a code-reading audit, not run |
| **U** | Reported by a user, not verified |

Each item also carries work tags:

| Tag | Meaning |
| --- | --- |
| **[Opus]** | Well specified; implement with Opus |
| **[Fable]** | Design judgment, cross-platform reach, or weak verification; design with Fable. Implementation can drop to Opus once the design is written down |
| **[Human]** | Needs a person with a device |
| **[Lane X]** | The parallel lane the item belongs to (see Workflow) |
| **[Mac work]** | Needs the Mac: iOS code cannot be compiled or run on Linux |

Nothing here was verified on a physical Android, iOS, macOS, or Windows
device. Treat **C** items on those platforms as leads to confirm first.

Paths are relative to
`ComposeTextEditor/src/commonMain/kotlin/com/darkrockstudios/texteditor/`
unless they start with a source set or module name.

## Non-goals

A word processor in the MS Word sense: tables, page layout, named style sheets,
footnotes, headers and footers. The editor stays a line-based, markdown
flavoured rich text editor. Users are not asking for those things anyway; the
closest requests are paragraph indent control, custom fonts, and comments on
text, all of which fit the line model.

## What is solid

- The edit pipeline, offset transforms, transactions, and incremental relayout.
- The Android `InputConnection`, hardened against misbehaving keyboards.
- Desktop dead keys, composition, and candidate window placement.
- Line-block smart editing, markdown round trip for supported syntax, HTML
  paste sanitising (foreign colour, size, and background are ignored).
- Undo coalescing by word, a 1000 entry history, public `canUndo`/`canRedo`.
- About 1240 tests, a seeded fuzzer, and cost regression tests.

## The reference rule

When a behaviour question comes up (where does the caret go, what does this
chord do), the answer is what the platform's native editor does. On desktop the
practical reference is `BasicTextField`: it runs in the same test harness and
already gets these conventions right. Every Phase 1 item below where the editor
and `BasicTextField` were both probed, `BasicTextField` gave the native answer.

Exceptions, where `BasicTextField` is not native (found by 0.2, and tolerated
by its `referenceQuirk`): with a selection, its Home and End measure from the
selection's start and end rather than the caret; Home on an empty last line
moves to the end of the line above; End on a paragraph's last row stops before
its trailing spaces; a word wider than the row is broken where it starts
instead of moving to the next row. Its arrow keys in right-to-left text are
logical, like the editor's (7.5).

## Workflow

### Branch and machines

- All roadmap work happens on one working branch, `native-parity`, pushed to
  `origin`. Both machines sync through it. `main` receives it by pull request
  at phase boundaries.
- **Linux** is the primary machine: shared code, desktop, Android, and web.
- **Mac** does everything tagged [Mac work], using its own agent session in
  its own clone.
- Only one machine holds unpushed commits at a time. Every session starts
  with `git pull --ff-only` and ends with a push.

### The chunk loop

A chunk is one roadmap item, or a tight cluster of items in the same lane,
small enough to review in one pass.

1. Pick the chunk. Check its tags: model, lane, Mac.
2. Write the failing test first. For behaviour with a native reference, that
   is a comparison against `BasicTextField` (0.1). A user report becomes a
   failing test before its fix.
3. Implement.
4. Run the tests: `./gradlew :ComposeTextEditor:desktopTest`, plus the addon
   module's suite when it was touched. Run `./gradlew check` before a push.
5. Run `/code-review` on the chunk.
6. Fix what the review found. Rerun the tests.
7. Tick the item's checkbox here, in the same commit as the work.
8. Checkpoint commit, naming the roadmap item. Then move on.

Never start the next chunk on top of unreviewed or uncommitted work.

Findings made along the way become new items in this document. They are not
fixed silently inside an unrelated chunk.

### Handing off to the Mac

Work proceeds on Linux until the next step needs the Mac. Then:

1. Finish the current chunk through its checkpoint commit.
2. Add what the Mac must do to the **Mac queue** at the end of this document:
   the item id, what to build or verify, and what a pass looks like.
3. Push.
4. On the Mac: pull, work the queue with the same chunk loop, record the
   results against each entry, remove entries that passed, push.
5. Back on Linux: pull and continue.

Any change that touches `iosMain`, or adds an `expect` declaration, is
unverified until it has compiled on the Mac. It goes in the queue even when
its item has no [Mac work] tag.

Commands on the Mac:

```bash
./gradlew :ComposeTextEditor:compileKotlinIosSimulatorArm64
```

```bash
./gradlew :ComposeTextEditor:iosSimulatorArm64Test
```

The sample app is the Xcode project in `sampleAppiOS/`. While the Mac is in
use, also give the macOS desktop items (2.4, 2.6, 2.7) a manual pass in the
desktop sample app; the automated tests exercise the Mac key bindings but
not a real Mac keyboard.

### Parallel lanes

Items in different lanes touch different files and can be worked by separate
agents at the same time. Items in the same lane are worked one after another.
Each parallel agent works in its own git worktree on a branch off
`native-parity`, runs the full chunk loop there, and merges back after its
review.

| Lane | Area | Main files | Items |
| --- | --- | --- | --- |
| A | Caret motion | `state/TextEditorCursorState.kt`, `state/TextEditorStateCursorExt.kt`, `state/WordSegmentationUtils.kt`, `input/TextEditorKeyCommandHandler.kt` | 1.1 to 1.7, 1.19, 2.3, 2.6, 7.5 |
| B | Pointer and touch | `textEditorPointerInputHandling.kt`, `state/TextEditorSelectionManager.kt`, `DrawSelectionHandles.kt` | 1.9, 1.12 to 1.16, 3.1, 3.2, 3.4 to 3.8, 3.13 |
| C | Drawing and geometry | `Draw*.kt`, `cursor/`, `scrollbar/`, `state/TextEditorScrollState.kt`, hit testing | 1.8, 1.10, 1.11, 1.17, 1.18, 3.3, 3.12, 4.14, 7.6, 7.7 |
| D | Bindings, actions, menu | `input/KeyBindings.kt`, `input/EditorCommand.kt`, `input/BuiltinEditorActions.kt`, `contextmenu/` | 2.1, 2.2, 2.4, 2.5, 2.7 to 2.10, 4.8, 5.8 |
| E | Input sessions on desktop, iOS, web | `desktopMain`, `iosMain`, `wasmJsMain` under `input/` | 4.2 to 4.7, 4.10 to 4.12, 4.19 |
| F | Android input | `androidMain` | 0.4, 3.9 to 3.11, 3.14, 4.16, 4.18, 4.20 |
| G | Edit pipeline and undo | `state/TextEditManager.kt`, `state/TextEditHistory.kt`, `state/EditBehavior.kt`, `input/ImeEditLogic.kt` | 1.20, 5.1 to 5.5, 6.1 to 6.6 |
| H | Clipboard and HTML | `clipboard/`, `html/` | 4.9, 4.13, 4.17, 6.7 to 6.12 |
| I | Markdown and block model | `markdown/`, `richstyle/` | 5.6, 7.14 to 7.16 |
| J | Find addon | `ComposeTextEditorFind/` | 7.17 to 7.19 |
| K | Spell check addon | `ComposeTextEditorSpellCheck/` | 7.20 to 7.22 |
| L | Tests and CI | test sources, `.github/workflows/` | 0.1 to 0.3, 0.5 to 0.8, 4.1, 4.15 |
| M | Accessibility and host API | semantics in `BasicTextEditor.kt`, `RichTextView.kt`, `state/rememberTextEditorState.kt` | 7.1 to 7.4, 7.13, 7.23 to 7.25 |
| N | Core layout and performance | `state/TextEditorState.kt` | 5.7, 7.8 to 7.12 |

Housekeeping items are [Opus] and fit any lane that is already in the file.

Limits on parallel work:

- `state/TextEditorState.kt` and `BasicTextEditor.kt` are shared by several
  lanes. Lane N runs alone, never beside lanes A, C, or G. Other overlaps are
  small; the agent that merges second resolves them.
- Lane A's 2.3 and 2.6 add motions that also touch lane D's binding files.
  Do them when lane D is idle.
- Lanes J, K, and L are the safest to run beside anything.

### Order and dependencies

| Do first | Before |
| --- | --- |
| 0.1 | Every caret item in lane A |
| 1.1 | The break iterator part of 1.5, and turning on the 0.3 invariants |
| 4.1 | Trusting any iOS change |
| 4.2 | 4.3, 4.5 to 4.7, 4.11, 4.12 |
| 5.1 | 5.2 to 5.4, which can then run in parallel as separate behaviours |
| 6.2 | 6.1 |

A first wave that can run in parallel:

| Lane | Items | Model | Machine |
| --- | --- | --- | --- |
| L | 0.1, 0.2, 0.3 | Opus | Linux |
| E | 4.2, then 4.3 | Fable | Linux, then the Mac queue |
| D | 2.1, 2.2, 2.4, 2.5, 2.8 | Opus | Linux |
| J, K | 7.17 to 7.19, 7.21, 7.22 | Opus | Linux |
| L | 4.1 | Opus | Mac |

Lane A starts as soon as 0.1 lands.

## Phase 0: test foundations

Build these first so each later fix lands with a check. The existing e2e
harness is good; what is missing is an independent oracle and platform breadth.
The probes that produced the **R** findings used the existing harness and found
five user-facing bugs in minutes, because the current tests assert what the
editor does rather than what it should do.

| Area | State today |
| --- | --- |
| Tests | About 1240, almost all headless desktop JVM |
| Android | 2 host test files, no on-device tests |
| iOS, wasm | None |
| CI | `check` on Ubuntu only |
| Rendering | One proof-of-concept pixel test |

- [x] **0.1 Differential tests against `BasicTextField`.** [Opus] [Lane L]
  Replay one key script through both widgets and compare text, caret, and
  selection. Covers navigation, selection, word stops, and Unicode. Plain text
  only. Confirmed workable: `BasicTextField(TextFieldState)` runs under
  `runSkikoComposeUiTest` and accepts the same synthetic key input.
  Done in `desktopTest/.../e2e/differential/BasicTextFieldParityTest.kt`. A
  case the editor fails today carries `divergesUntil = "<item>"`; it fails once
  the editor matches, so each fix deletes its item's markers.
- [x] **0.2 Differential fuzzing.** [Opus] [Lane L] Feed `FuzzScript` scripts
  to both widgets. Add navigation keys, shift-selection, emoji, ZWJ sequences,
  flags, combining marks, and right-to-left words to the vocabulary.
  Done in `e2e/differential/DifferentialFuzzTest.kt`. A divergence explained by
  an item in `OPEN_PARITY_ITEMS` (`utils/DifferentialFuzz.kt`) is tolerated;
  delete the item there when it lands.
- [x] **0.3 Invariants in the fuzzer.** [Opus] [Lane L] After every op: no lone
  surrogate in the document, the caret never sits inside a grapheme cluster,
  Down moves exactly one visual row, Left then Right returns to the same
  position. Done in `e2e/torture/EditorInvariantFuzzTest.kt`. Each invariant
  names the items it needs (`utils/EditorInvariants.kt`) and stays off while
  any is in `OPEN_PARITY_ITEMS`; today only Left then Right is on. Set
  `FUZZ_INVARIANTS=all` to run every one.
- [ ] **0.4 Keyboard trace record and replay.** [Fable] [Lane F] A debug
  recorder on the Android `InputConnection` that logs every command and read. A
  user attaches the trace to a bug report; the trace replays in
  `androidHostTest`. Build a corpus per keyboard (Gboard, Gboard Japanese,
  Samsung, SwiftKey, AnySoftKeyboard). First case: hammer-editor#930.
- [ ] **0.5 Geometry assertions.** [Opus] [Lane L] Assert caret and selection
  rectangles from layout. Stable across machines, unlike pixels.
- [ ] **0.6 Golden screenshots.** [Opus] [Lane L] A small set of scenes with a
  bundled font on one CI machine: caret, selection across wrapped and empty
  lines, squiggles, list markers, composing underline.
- [ ] **0.7 CI breadth.** [Opus] [Lane L] [Mac work] Desktop suite on macOS and
  Windows runners. An Android emulator smoke job. Browser automation against
  the built wasm demo for real key and composition events. An iOS simulator
  smoke test. Mac part: the iOS simulator smoke test.
- [ ] **0.8 Real OS input, nightly.** [Opus] [Lane L] Drive the sample app on a
  virtual Linux display with a dead-key layout. Most expensive, so last.

## Phase 1: native feel

Highest return. Small, contained in cursor, selection, and pointer code, and
fixes what users feel every minute.

### Caret and keyboard

- [ ] **1.1 Grapheme-aware movement and deletion. R.** [Fable] [Lane A]
  [Mac work] Left and Right step one UTF-16 unit
  (`state/TextEditorCursorState.kt`, `moveLeft`/`moveRight`). Backspace and
  Delete remove one unit (`state/TextEditorState.kt`,
  `backspaceAtCursor`/`deleteAtCursor`). Backspace after an emoji leaves half a
  surrogate pair in the document, which is then saved. Vertical movement and
  IME code-point deletes can land mid-cluster too. Needs one shared
  grapheme-boundary utility, `expect`/`actual` over the platform break
  iterators, used by every movement, delete, and hit-test path. Mac part: the
  iOS `actual`.
- [ ] **1.2 Pixel-based vertical movement with a goal column. R.** [Opus]
  [Lane A] Up, Down, PageUp, and PageDown add a character count to the target
  row's start (`state/TextEditorStateCursorExt.kt`). With proportional fonts
  the caret jumps sideways, and when the target row is shorter in characters
  the caret overflows into a later row: the probe saw Down go from row 2 to row
  5. Use the caret's x and hit-test the target row; remember the goal x until a
  horizontal move or an edit.
- [ ] **1.3 Document edges. R.** [Opus] [Lane A] Up on the first row and Down
  on the last row do nothing. Native moves to document start and end.
- [ ] **1.4 Collapse the selection on an unshifted arrow. R.** [Opus] [Lane A]
  Left or Right with a selection moves one character from the caret
  (`input/TextEditorKeyCommandHandler.kt`, `moveCursor`). Native collapses to
  the selection's start or end without moving further.
- [ ] **1.5 Word motion and word selection. C, U.** [Opus] [Lane A]
  - Line end is not a boundary: Ctrl+Right from the last word of a line skips
    the first word of the next, and Ctrl+Delete deletes it
    (hammer-editor#852).
  - Only the straight apostrophe is a word character
    (`state/WordSegmentationUtils.kt`), so "don’t" splits. This also makes
    spell check flag contractions in typeset prose.
  - `isWordChar` is per-char `isLetterOrDigit`: combining marks and surrogate
    halves split words; a CJK run is one word.
  - Double-click on whitespace or punctuation selects the neighbour or leaves
    an empty, non-null selection.
  - Prefer the platform word break iterator, shared by keyboard, mouse, and
    spell check. This part builds on the 1.1 utility: [Fable] [Mac work].
- [ ] **1.6 End on a wrapped row, and caret affinity. R, C.** [Fable] [Lane A]
  End goes to `nextWrapStart - 1`. That is right when the row ends in a space,
  one character short when the wrap falls mid-word or in CJK, and past the
  first space when the row ends in several.
  `CharLineOffset` has no affinity, so a position at a wrap boundary always
  draws on the later row.
- [ ] **1.7 PageUp and PageDown. C.** [Opus] [Lane A] Driven by scroll position
  rather than the caret's row; PageDown never reaches the document end. The
  scroll margin is a hard-coded 10 px (`state/TextEditorScrollManager.kt`).
- [ ] **1.8 Caret drawing. C.** [Opus] [Lane C] Width is a raw `2f` px, not dp
  and not configurable (`cursor/DrawCursorUi.kt`). Blink does not reset on
  forward delete. The caret is still drawn while a selection exists.

### Mouse

- [ ] **1.9 Right-click keeps the selection. S.** [Opus] [Lane B] Any mouse
  button counts as a click (`textEditorPointerInputHandling.kt`,
  `detectMouseClicksImperatively`) and the click handler clears the selection,
  so the context menu opens with Cut and Copy hidden.
- [ ] **1.10 Clicks above the first line. S.** [Opus] [Lane C]
  `getOffsetAtPosition` (`state/TextEditorState.kt`) falls through to "end of
  last line" for any y above the content. Clicking in the top padding, or
  dragging a selection above the top, sends the caret to the document end.
- [ ] **1.11 Padding. C.** [Opus] [Lane C] The placeholder draws at `Offset(0,
  0)`, ignoring top padding (`DrawPlaceholderText.kt`). Horizontal padding sits
  outside pointer input, leaving dead click zones.
- [ ] **1.12 Multi-click drag. C.** [Opus] [Lane B] Double-click then drag
  should extend by word, triple-click then drag by line. Today multi-click
  resolves on release and replaces the drag. Word selection should appear on
  press. Shift plus double-click ignores shift. Thresholds are hard-coded 300
  ms and 20 px instead of `viewConfiguration`.
- [ ] **1.13 Pointer icons. C.** [Opus] [Lane B] No I-beam over the editor
  (`RichTextView` has one). No hand over links.
- [ ] **1.14 Drag auto-scroll. C.** [Opus] [Lane B] Scrolls only on pointer
  move events. Holding still outside the viewport stops the scroll.
- [ ] **1.15 Links. C.** [Opus] [Lane B] Span clicks are reported on press with
  no modifier state, so a host that opens links on click also fires when the
  user places the caret or starts a drag. Report on release, pass modifiers,
  and offer a built-in Ctrl/Cmd+click convention.
- [ ] **1.16 Middle-click paste on Linux. C.** [Opus] [Lane B] Not handled;
  middle click moves the caret.

### Selection drawing

- [ ] **1.17 Empty lines and newlines. C.** [Opus] [Lane C] Empty lines inside
  a selection draw nothing (`DrawSelectionUi.kt`). Native shows a sliver for
  the newline.
- [ ] **1.18 Unfocused state. C.** [Opus] [Lane C] No unfocused selection
  colour; selection and touch handles stay drawn unchanged after focus loss.

### Found by the differential tests

- [ ] **1.19 Word ends. R.** [Opus] [Lane A] Ctrl+Right and Ctrl+Shift+Right
  stop at the start of the next word; `BasicTextField` stops at the end of the
  current one, and its Ctrl+Delete deletes to that end. Windows editors stop at
  the next word start, so the answer may be per platform (see 2.6 for macOS).
  Decide, then clear the `divergesUntil = "1.19"` cases.
- [ ] **1.20 Joining lines deletes an empty line. R.** [Opus] [Lane G] In a
  three-line document with an empty line, joining the other two lines also
  deletes the empty one: Backspace before `c` in `\nb\nc` gives `bc`, and
  before `b` in `a\nb\n` gives `ab`. Typing over a selection that spans a line
  break loses it the same way. Reproduces on a bare `TextEditorState`; four-line
  documents are not affected.

## Phase 2: command completeness

- [x] **2.1 Formatting actions and chords.** [Opus] [Lane D] Built-in toggle
  actions for bold, italic, underline, strikethrough, and inline code, bound to
  the platform chords (ComposeTextEditor#22). Today `toggleBold` lives in the
  sample app (`sampleApp/.../BoldShortcut.kt`). Define mixed-selection
  behaviour once in the library. `Action.ToggleBold` and friends on Ctrl/Cmd+B,
  I, U, Shift+X (strikethrough) and E (inline code), all through
  `TextEditorState.toggleSpanStyle`; see `docs/design/editor-actions.md`.
- [x] **2.2 Paste as plain text.** [Opus] [Lane D] Ctrl/Cmd+Shift+V is
  currently a rich paste because `Key.V` ignores Shift (hammer-editor#929).
  `Action.PasteAsPlainText`, on Ctrl+Shift+V, Cmd+Shift+V, and Cocoa's
  Cmd+Option+Shift+V. The text takes the styling at its destination.
- [ ] **2.3 Paragraph motion.** [Opus] [Lane A] Ctrl+Up/Down on Windows and
  Linux, Option+Up/Down on macOS.
- [x] **2.4 Delete to line end.** [Opus] [Lane D] Cmd+Delete and Ctrl+K on
  macOS. Following Cocoa: Cmd+Backspace stays `deleteToBeginningOfLine:`;
  Cmd+Fn+Delete is `Action.DeleteToLineEnd` (`deleteToEndOfLine:`, to the end
  of the visual row, nothing at its end); Ctrl+K is
  `Action.DeleteToParagraphEnd` (`deleteToEndOfParagraph:`, past any wrap, and
  at the paragraph's end it deletes the line break). No kill ring, so Ctrl+Y
  does not yank. Not bound on Windows and Linux.
- [x] **2.5 Legacy chords.** [Opus] [Lane D] Ctrl+Insert, Shift+Insert,
  Shift+Delete, and the dedicated Cut, Copy, and Paste keys. The CUA chords
  are on `CtrlKeyBindings` only; the dedicated keys are on both. Shift+Delete
  with no selection is a no-op, as a cut of nothing is.
- [ ] **2.6 macOS conventions.** [Opus] [Lane A] Option+Right stops at the end
  of the current word, not the start of the next. The Emacs-style Ctrl bindings
  (A, E, F, B, N, P, D, H, K) that every Cocoa text view has. K landed with
  2.4. Ctrl+Y (yank) needs a kill ring that K fills, which does not exist.
- [ ] **2.7 Layout-aware shortcuts. U.** [Fable] [Lane D] A BEPO user reports
  shortcuts follow physical QWERTY positions on desktop (hammer-editor#945).
  Confirm, then match on the produced character where the platform provides it.
- [x] **2.8 Enter with modifiers. S.** [Opus] [Lane D] Every Enter chord
  inserts a newline. Leave Ctrl/Cmd+Enter unbound so hosts can claim it.
  Enter and Shift+Enter break the line; any chord with Ctrl, Cmd or Alt is
  unbound. A deliberate departure from `BasicTextField`, which also breaks the
  line on Ctrl+Enter (Windows, Linux) and Option+Enter (macOS), and from Cocoa,
  which breaks it on Ctrl+Return and Option+Return.
- [ ] **2.9 Tab. C.** [Opus] [Lane D] Always inserts four spaces and is always
  consumed, so there is no keyboard way out of the editor. Make Tab and
  Shift+Tab list-aware, make the tab size and the insert-tab behaviour
  configurable.
- [ ] **2.10 Context menu. C.** [Opus] [Lane D] No Menu key or Shift+F10. No
  Undo or Redo. Unavailable items are hidden rather than disabled. The position
  is shifted by the start content padding. `TextEditor` does not expose
  `contextMenuStrings` or `contextMenuState`; `RichTextView` hard-codes
  English. No Paste as plain text item (2.2 added the action).

## Phase 3: touch polish (Android first)

- [ ] **3.1 Crossing handles. C.** [Opus] [Lane B] The drag uses
  `selection.start`/`end` as the fixed edge while the range is reordered, so
  the anchor is lost once the handles cross. A user describes handles that
  "jump all over" (hammer-editor#956).
- [ ] **3.2 Handle grab offset. C.** [Opus] [Lane B] A fixed 162 px upward
  offset is applied instead of the grab delta, so the edge jumps on grab.
- [ ] **3.3 Density. C.** [Opus] [Lane C] Handle sizes, the 80 px hit radius,
  stroke widths, and the composing underline are raw px; handle colour is
  hard-coded (`DrawSelectionHandles.kt`).
- [ ] **3.4 Auto-scroll while dragging a handle. C.** [Opus] [Lane B] Absent.
- [ ] **3.5 Caret handle. C.** [Opus] [Lane B] Handles are drawn only with a
  selection.
- [ ] **3.6 Magnifier. C.** [Opus] [Lane B] Absent.
- [ ] **3.7 Gestures. C.** [Fable] [Lane B] No double-tap word select, no
  long-press then drag. The long-press timeout is a hard-coded 500 ms.
- [ ] **3.8 Reaching Paste by touch. C.** [Fable] [Lane B] The menu opens only
  on a second long-press over an existing selection, and long-press on an empty
  line does nothing. Paste is unreachable in an empty editor and at a bare
  caret. Use the platform text toolbar.
- [ ] **3.9 Caret under the soft keyboard. C, U.** [Opus] [Lane F] A viewport
  resize relayouts but does not re-run `ensureCursorVisible`, and there is no
  `BringIntoViewRequester` (hammer-editor#932).
- [ ] **3.10 Cursor anchor info. C.** [Opus] [Lane F] Translated by the view's
  screen position but built from canvas-local metrics, so it is off by the
  editor's offset inside the view
  (`androidMain/.../state/PlatformTextEditorExtensions.android.kt`).
- [ ] **3.11 Keyboard options. C.** [Opus] [Lane F] `inputType` and
  `imeOptions` are hard-coded; hosts cannot configure capitalisation,
  autocorrect, or keyboard type. `initialCapsMode` and initial surrounding text
  are not set. `commitContent` returns false. No autofill, no stylus
  handwriting.
- [ ] **3.12 Scrolling. C.** [Opus] [Lane C] No overscroll effect. The mobile
  scroll indicator is non-interactive, always visible, with a fixed 15% thumb.
  A 32 px buffer is always added to max scroll, so a one-line document scrolls.
- [ ] **3.13 Known open issues** [Fable] [Lane B] from
  `docs/design/touch-focus.md`: a handle drag cannot restore focus; an orphaned
  long-press job with a second finger.
- [ ] **3.14 Italics invisible on Android. U.** [Fable] [Lane F] Saved and
  exported correctly but not drawn (hammer-editor#956). Not reproduced.

## Phase 4: platform parity (parallel track)

iOS and web are labelled experimental in the README. That label describes
today, not the goal: both are improved as part of this work, starting
alongside Phase 1, and the label comes off each platform when its exit
criteria below are met.

Constraints that shape the order:

- Kotlin/Native iOS targets do not compile on Linux, and CI runs on Ubuntu
  only, so nothing compiles iOS code today unless someone builds on a Mac.
  iOS changes written on Linux are unverified until 4.1 lands.
- Web builds on any host and can be driven in a real browser, so it is the
  platform where progress can be verified first.
- Desktop, iOS, and web all implement the same skiko
  `PlatformTextInputMethodRequest`. Desktop's is complete and routes every
  edit through `ImeEditLogic`; iOS and web each went their own way.

### First steps, in order

- [x] **4.1 Compile and test iOS in CI.** [Opus] [Lane L] [Mac work] A macOS
  runner that builds the iOS targets and the iOS sample app. Without it every
  iOS change is a guess. Done: the `ios` job in `ci-build.yml`. There are no
  iOS test sources yet, so `iosSimulatorArm64Test` is skipped until some exist.
- [ ] **4.2 One shared input request for desktop, iOS, and web.** [Fable]
  [Lane E] [Mac work] Move the request, the state adapter, and the editing
  scope from `desktopMain/.../input/TextEditorTextInputService.desktop.kt` into
  a source set shared by the three skiko platforms. iOS and web then get
  composition, surrounding-text deletes, selection, and caret geometry from
  code that the desktop suite already tests. Platform files keep only what
  truly differs (keyboard options on iOS). Mac part: compile the iOS half and
  confirm typing, backspace, and composition in the simulator.
  Linux part done: `skikoMain` holds `SkikoTextEditorInputMethodRequest`
  (request, state adapter, editing scope, `EditCommand` translation) and the
  three platform files pass in `ImeOptions` only; the desktop suite plus
  `input/SkikoInputMethodRequestTest` cover it and wasm compiles. The Mac part
  is in the queue; the checkbox waits on it.
- [ ] **4.3 Start a real input session on web.** [Fable] [Lane E] Replace the
  suspend-forever stub with `startInputMethod` using the shared request, then
  settle which path owns plain typing so keys are not inserted twice (today
  typed characters arrive as `keydown`; see `isCharacterInputCandidate`).
- [ ] **4.4 Verify on devices.** [Human] [Lane E] [Mac work] iOS simulator and
  a physical iPhone; desktop and mobile browsers. Record what works in the
  manual QA plan.

### iOS

- [ ] **4.5 Input correctness. S.** [Opus] [Lane E] [Mac work] Resolved by 4.2;
  listed so each can be checked off on a device.
  `iosMain/.../input/TextEditorTextInputService.ios.kt` does not use the shared
  logic, contrary to `docs/design/text-input-sessions.md`.
  - `deleteSurroundingTextInCodePoints` is an empty stub, which is the path
    soft-keyboard backspace takes.
  - `setComposingText` is treated as commit and `setComposingRegion` is a
    no-op, so marked text, autocorrect, and dictation insert duplicates
    (hammer-editor#791).
  - `applyTextFieldValue` replaces the whole document with a plain string on
    every edit, dropping character styles.
  - Commits skip the edit-behavior chain, so list continuation does not run.
  All four are addressed on Linux by the shared request (4.2); each waits on
  the device pass in the Mac queue.
- [ ] **4.6 Layout geometry. C.** [Opus] [Lane E] [Mac work] `textLayoutResult`
  and every rect return null, so spacebar trackpad mode and IME positioning
  cannot work. The rects come with 4.2 (done on Linux); `textLayoutResult`
  stays null because the editor lays out its own lines, so the spacebar
  trackpad's floating caret needs an editor-side equivalent of
  `getOffsetForPosition` before it can work. That is the remaining work here.
- [ ] **4.7 Keyboard options. C.** [Opus] [Lane E] [Mac work] Capitalisation is
  not set. Set to sentences with autocorrect on in 4.2; confirm the keyboard
  shows it in the Mac queue pass.
- [ ] **4.8 Native edit menu. C.** [Fable] [Lane D] [Mac work] A Material
  dropdown is used instead of the platform text toolbar.
- [ ] **4.9 Rich clipboard. C.** [Opus] [Lane H] [Mac work] Plain text only
  (shared with 6.7).
- [ ] **4.10 Hardware keyboard shortcuts.** [Human] [Lane E] [Mac work] Mac
  bindings are wired; never verified at runtime.

Exit criteria for dropping "experimental": typing, backspace, autocorrect,
dictation, and CJK composition work on a device; selection handles and the
edit menu work by touch; iOS is compiled and smoke-tested in CI.

#### Simulator baseline before 4.2

Recorded 2026-09-28 at `227f2ac`, Xcode 26.5, iPhone 17 Pro Max simulator on
iOS 26.0, Markdown Editor (Blank) demo. This is the before picture for 4.2 and
4.5 to 4.7.

| Input | Result |
| --- | --- |
| Soft keyboard letters | Work. QuickType suggestions update per word. |
| Soft keyboard backspace | Works (arrives as `BackspaceCommand`). |
| Hardware keyboard letters, capitals, punctuation | Work. The first injected character of a session once lost its capital; not reproduced. |
| Autocorrect: "teh" then space | **Broken.** "hel teh" became "hel tehthe": the correction is appended instead of replacing the word, and the space is lost. Same class as hammer-editor#791. |
| Japanese Romaji: "k" then "a" | **Broken.** Shows "kか", not a marked "か". Composing text is committed, so the candidate bar never offers conversions. |
| Auto-capitalisation | Absent. The keyboard opens in lower case at document start (4.7). |
| Dictation | Not testable in the simulator. |

Also seen:

- The caret draws one line below the placeholder text in an empty editor
  (1.11).
- Intermittent: with the soft keyboard up, the whole screen was pushed up by
  about the toolbar's height, hiding it and leaving a gap above the keyboard.
  Seen on one of three runs. The null `focusedRectInRoot` (4.6) is the likely
  cause; recheck after 4.2.
- After any hardware key event the simulator hides the soft keyboard until the
  device is rebooted. Keep that in mind when testing both paths in one run.

### Web

- [ ] **4.11 Soft keyboard on mobile web. C.** [Opus] [Lane E] Compose creates
  its backing DOM input inside `startInputMethod`, which is never called, so no
  keyboard appears. Resolved by 4.3.
- [ ] **4.12 Composition on desktop web. C.** [Opus] [Lane E] Dead keys and CJK
  input. Also resolved by 4.3.
- [ ] **4.13 Clipboard. C.** [Opus] [Lane H] Plain text only through
  `navigator.clipboard`, failures swallowed silently (shared with 6.7).
- [ ] **4.14 Scrollbar. C.** [Opus] [Lane C] The implementation is commented
  out.
- [ ] **4.15 Browser tests.** [Opus] [Lane L] Automation against the built demo
  (0.7), with composition events.
- [ ] **4.21 Whole-document mirror per edit. S.** [Opus] [Lane E] Compose's web
  session copies `request.value().text` into the backing `<textarea>` after
  every edit, and iOS snapshots `state.text` the same way, so each keystroke
  builds the whole document as a `String` and hands it to the platform. Same
  as `BasicTextField` on those platforms, so acceptable today; measure on a
  long document (7.8) before deciding whether the request should serve a
  window around the caret instead.

Exit criteria: typing, composition, and clipboard work in current Chrome,
Firefox, and Safari on desktop; the soft keyboard works on Android Chrome and
iOS Safari; browser tests run in CI.

### Android and desktop

- [ ] **4.16 Japanese input. U.** [Fable] [Lane F] Reported broken with Fcitx5
  and Mozc on Linux and with Gboard Japanese on Android (hammer-editor#930).
  ComposeTextEditor PR 102 reworked IME edits afterwards; nobody has confirmed
  the result.
- [ ] **4.17 Wayland paste. U.** [Fable] [Lane H] External paste fails on
  Wayland with KDE; internal paste works (hammer-editor#921).
- [ ] **4.18 ANR on Galaxy S21 Ultra. U.** [Fable] [Lane F] hammer-editor#545,
  stale.
- [ ] **4.19 Desktop candidate window. C.** [Opus] [Lane E] `lastCursorMetrics`
  updates only when the caret is drawn, so it can lag during blink-off.
- [ ] **4.20 Hardware keyboard dead keys on Android. S.** [Opus] [Lane F]
  `handleCharacterInput` inserts `utf16CodePoint` directly, with no handling of
  combining accents.

## Phase 5: writer conveniences

- [ ] **5.1 A typed-text hook.** [Fable] [Lane G] `EditBehavior` covers
  newline, backspace, and forward delete only (`state/EditBehavior.kt`). Add a
  hook that sees committed text from every input path, keys and IME alike.
  Everything below in this phase builds on it, as opt-in behaviours.
- [ ] **5.2 Smart punctuation.** [Opus] [Lane G] Curly quotes, dashes from
  double hyphens, ellipsis. One undo step reverts the substitution, as in
  native editors.
- [ ] **5.3 Markdown as you type.** [Opus] [Lane G] "- ", "1. ", "# ", "> " at
  line start; inline `**bold**` and friends.
- [ ] **5.4 Auto-link** [Opus] [Lane G] typed and pasted URLs.
- [ ] **5.5 Enter after a heading. S.** [Opus] [Lane G] `LineBlockEditBehavior`
  continues any line block, headings included, so the line after a chapter
  title is another heading. It should be body text.
- [ ] **5.6 Nested lists.** [Fable] [Lane I] Unsupported in the block model and
  the markdown parser (`docs/design/line-blocks.md`, known limitations).
- [ ] **5.7 Paragraph formatting.** [Fable] [Lane N] Paragraph spacing does not
  exist; rows stack with no gap. No per-paragraph alignment, indent, or line
  height. Global `textIndent`, `lineHeight`, and `textAlign` already work
  through `textStyle` (relevant to hammer-editor#927).
- [ ] **5.8 Clear formatting and unlink** [Opus] [Lane D] actions.

## Phase 6: undo and clipboard fidelity

### Undo

- [ ] **6.1 One user action, one undo step. C.** [Fable] [Lane G] These take
  several today: typing or Enter over a selection (two), a rich paste of N list
  items (N+1), find's replace-all (one per match), `setLink` (two), IME
  composition updates (several per word, because only Insert and Delete
  coalesce and composition is a Replace).
- [ ] **6.2 A public grouping API.** [Fable] [Lane G] `withAtomicEdit` is
  internal and does not group history.
- [ ] **6.3 Style undo. C.** [Fable] [Lane G] A blind inverse over the same
  range, so undoing bold on a partly bold selection strips the bold that was
  already there (`state/TextEditManager.kt`). The formatting chords (2.1)
  apply over a partly styled selection, so this is one Ctrl+B and one undo
  away.
- [ ] **6.4 Restore the selection,** [Opus] [Lane G] not only the caret.
- [ ] **6.5 Pasted HTML blocks are unrecorded. C.** [Opus] [Lane G] Redo should
  restore the text without its blocks. Inferred; no test covers it.
- [ ] **6.6 Time-based coalescing breaks,** [Opus] [Lane G] and a configurable
  history cap.

### Clipboard

- [ ] **6.7 Rich clipboard on Android, iOS, and web. C.** [Opus] [Lane H] Plain
  text only in both directions; bold and italic are lost even editor to editor.
  Desktop already writes HTML. The iOS half is 4.9.
- [ ] **6.8 Line endings. C.** [Opus] [Lane H] No `\r` handling anywhere; a
  CRLF paste leaves stray carriage returns in lines.
- [ ] **6.9 Links in HTML. C.** [Opus] [Lane H] No `href` handling on paste or
  copy.
- [ ] **6.10 Non-breaking spaces** [Opus] [Lane H] become plain spaces on HTML
  paste.
- [ ] **6.11 Large paste is quadratic. C.** [Opus] [Lane H] One full line-list
  copy per pasted line.
- [ ] **6.12 Drag and drop** [Opus] [Lane H] of the selection, and drops of
  external text.
- [ ] **6.13 Plain paste reads the HTML flavor.** [Opus] [Lane H] On desktop,
  `Action.PasteAsPlainText` takes `ClipboardHelper.getText(...).text`, so a
  foreign paste that offers HTML yields the text of the parsed markup rather
  than the source's own `text/plain` flavor. Add a plain read to
  `ClipboardHelper` (an `expect` member, so it joins the Mac queue).

## Phase 7: reach

### Accessibility

- [ ] **7.1** [Opus] [Lane M] `RichTextView` has no semantics at all.
- [ ] **7.2** [Opus] [Lane M] A disabled editor still exposes `setText` and
  `insertTextAtCursor`, and is still focusable, contrary to its KDoc.
- [ ] **7.3** [Opus] [Lane M] The semantics `setText` calls `state.setText`,
  wiping rich spans and undo history.
- [ ] **7.4** [Opus] [Lane M] Missing: `getTextLayoutResult`, copy, cut, and
  paste actions, content description, and any structure (headings, links,
  lists).

### Right-to-left and bidirectional text

- [ ] **7.5** [Fable] [Lane A] Arrow keys are logical, so visually inverted in
  right-to-left text. `BasicTextField` is logical too, so it is no reference
  here.
- [ ] **7.6** [Fable] [Lane C] Selection draws one rect per row from x(start)
  to x(end); wrong in right-to-left, and mixed text needs several rects.
- [ ] **7.7** [Fable] [Lane C] Underline boxes (spell check, composing, links)
  assume no bidi.

Hit testing and caret x already delegate to Compose and should be correct.

### Performance on long documents

Shaping is one line per keystroke. These still scale with document length:

- [ ] **7.8** [Fable] [Lane N] Per keystroke: the line list is copied, every
  `LineWrap` is rebuilt, and every rich span is re-anchored.
- [ ] **7.9** [Opus] [Lane N] `getAllText()` rebuilds the whole document per
  revision when read by semantics, the Android IME, and the desktop adapter.
- [ ] **7.10** [Opus] [Lane N] Any viewport change, height-only included,
  reshapes the entire document. This is every soft keyboard open and close.
- [ ] **7.11** [Opus] [Lane N] Linear scans per frame or event: visible-line
  lookup in drawing, unculled selection drawing, `getWrappedLineIndex`,
  `getOffsetAtPosition` on each drag move.
- [ ] **7.12** [Opus] [Lane N] `moveRight` and `moveToNextWord` sum all line
  lengths though `getTextLength()` is constant time.

### Editor configuration

- [ ] **7.13** [Opus] [Lane M] Missing: read-only with a caret, single-line
  mode, min and max lines, auto-grow (the editor forces `fillMaxSize`), max
  length, an input filter, a soft-wrap toggle with horizontal scrolling.
  Also the soft keyboard options: capitalisation, autocorrect, and keyboard
  type are fixed per platform (Android's `EditorInfo`, the iOS `ImeOptions`
  in 4.2), so a host editing code cannot turn sentence caps off.

### Markdown export

- [ ] **7.14** [Fable] [Lane I] Every special character in prose is escaped, so
  ordinary prose comes out backslash-heavy, and unsupported syntax kept as
  literal text on import (tables, task lists) is exported escaped.
- [ ] **7.15** [Fable] [Lane I] Paragraphs are exported with single newlines;
  other CommonMark renderers merge adjacent paragraphs.
- [ ] **7.16** [Opus] [Lane I] No markdown form for underline, highlight,
  colour, or size, so they are lost. Code fence language tags are dropped.

### Find and replace addon

- [x] **7.17** [Opus] [Lane J] Missing: whole word, regex, find in selection,
  F3 and Ctrl+G, prefill from the selection. Case sensitivity exists in
  `FindState` but `FindBar` has no control for it.
- [x] **7.18** [Opus] [Lane J] Replace-all drops the replaced text's styling,
  and overlapping matches are applied against already modified text.
- [x] **7.19** [Opus] [Lane J] Esc and Ctrl+F close without clearing
  highlights. The shortcut tests `isCtrlPressed`, so AltGr+F is stolen on
  Windows layouts; use `isCtrlShortcut`.
- [ ] **7.26** [Opus] [Lane J] Regex replace inserts the replacement
  literally; `$1` and named groups are not expanded.
- [ ] **7.27** [Opus] [Lane C] Decorations take part in span hit testing
  (`findSpanAtPosition`), ranked above line markers. While find in selection
  is on, its scope decoration answers clicks on list, blockquote, and code
  fence markers inside it. A decoration needs a way to opt out.

### Spell check addon

- [ ] **7.20** [Fable] [Lane K] Sentence mode: sentences run across line
  boundaries, offsets shift on indented lines, and each partial check rescans
  the whole document. Tested only against fakes.
- [x] **7.21** [Opus] [Lane K] No ignore list or language API in
  `EditorSpellChecker`; add to dictionary exists only as a host menu extension
  (hammer-editor#861).
- [x] **7.22** [Opus] [Lane K] Hard-coded English strings ("Loading...", "No
  suggestions").

### Host API

- [ ] **7.23** [Opus] [Lane M] No `Saver`, so state is lost where
  `rememberSaveable` would keep it.
- [ ] **7.24** [Fable] [Lane M] The state needs a `TextMeasurer` and a scope,
  so it cannot be created outside composition.
- [ ] **7.25** [Opus] [Lane M] No word count, no programmatic focus beyond
  `autoFocus`, `cursorDataFlow` has no initial value.

## Housekeeping

- [ ] The README's "Work left to do" is stale: desktop copy and paste now
  preserves formatting.
- [ ] Stray `println` calls in `state/TextEditorState.kt` and
  `SpellCheckState.kt`.
- [x] `docs/design/text-input-sessions.md` describes iOS as routing through
  the shared IME logic; it does not yet (4.2). True since 4.2's Linux part.
- [ ] `getOffsetAtCharacter` returns a negative char for negative input.
- [ ] The document content is not snapshot state, so the skiko input session
  (4.2) collects `editOperations` and `documentGeneration` to bump a
  snapshot-backed revision its text reads fold in. A revision advanced from
  `TextEditorState.onCommit` would give every snapshot observer the same
  signal with no collectors; do it when `state/TextEditorState.kt` is next
  open (lane N).

## User reports mapped to this roadmap

Issues are in `Darkrock-Studios/hammer-editor` unless marked CTE
(`Darkrock-Studios/ComposeTextEditor`).

| Issue | Report | Item |
| --- | --- | --- |
| #930 | Japanese IME does not work | 4.16, 0.4 |
| #791 | iOS dictation inserts only the first letter | 4.2, 4.5 |
| #945 | Shortcuts bound to QWERTY positions | 2.7 |
| #852 | Ctrl+Right skips across paragraph ends | 1.5 |
| CTE #22 | Ctrl+B and Ctrl+I | 2.1 |
| #921 | Cannot paste from outside the app on Wayland | 4.17 |
| #929 | Paste without formatting | 2.2 |
| #956 | Italics invisible on Android; handles jump | 3.14, 3.1 |
| #932 | Last line hidden while typing on Android | 3.9 |
| #927 | First-line indent hard-coded | 5.7 (host side) |
| #861 | Add to dictionary | 7.21 |
| #545 | ANR editing notes | 4.18 |

## Mac queue

Pending work for the Mac session. The Linux side appends; the Mac side
records results and removes entries that passed.

| Item | What to do | A pass looks like | Result |
| --- | --- | --- | --- |
| 4.2 | `./gradlew :ComposeTextEditor:compileKotlinIosSimulatorArm64`, then `./gradlew :ComposeTextEditor:iosSimulatorArm64Test`. The iOS file (`iosMain/.../input/TextEditorTextInputService.ios.kt`) now only passes `ImeOptions` into `skikoMain`'s `startSkikoInputSession`; if it does not compile, the fix is in that file or in `skikoMain/.../input/`, never a copy of the desktop code | Both tasks green with no change to the desktop or wasm sources | |
| 4.2 | Build `sampleAppiOS`, run it in the simulator, and repeat the baseline recording: type a sentence, backspace through it, accept an autocorrect suggestion, and compose Japanese (Settings > General > Keyboard, add Japanese Kana, type "nihongo" and pick a candidate). Compare against "Simulator baseline before 4.2" in the iOS section | Typed characters appear once each and backspace removes one character at a time (4.5); an accepted autocorrect replaces the word rather than appending it (4.5, hammer-editor#791); kana show underlined while composing and the chosen candidate replaces them once (4.5); the keyboard opens with a shifted first letter (4.7). If the baseline already passed any of these, note it as a regression check only | |
