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
moves to the end of the line above; End stops before a row's trailing spaces,
and Up and Down stop before a row's trailing spaces when the goal x is over or
past them; it has no caret affinity, so a caret on a wrap offset is on the lower
row to it: Home from there stays put, End runs on to the lower row's end, and Up
and Down measure from that row; a word wider than the row is broken where it
starts instead of moving to the next row; a page move neither starts nor follows
a goal x, so it measures from the caret, and Up or Down after one measures from
where it landed; PageUp and PageDown stop on the first and last rows instead of
going on to the document start and end; with a selection, its Shift paragraph
jumps measure from the selection's start and end rather than the caret; its
goal x survives typed text and Enter, so Up or Down after typing measures from
the column the typing started at; its word motions stop after every punctuation
character, where GTK, `EditText` and Cocoa skip punctuation (they agree that an
emoji is a word of its own), and its Ctrl+Left and Ctrl+Backspace step back
one character at a time and stop at the first segment that began before the
step, so they pass over one-character segments (a space, a mark, a flag, a lone
ideograph) and stop one short of a word when the step lands inside it, where
GTK and Cocoa go to the previous word's start; and its paragraph direction is
the whole text's, from its first strong character, so a right-to-left paragraph
after a left-to-right one keeps left-to-right arrows, where native editors and
the editor resolve each paragraph on its own. Its
arrow keys inside a mixed paragraph are logical, like the editor's (7.33).

## Workflow

### Branch and machines

- All roadmap work happens on one working branch, `native-parity`, pushed to
  `origin`. Both machines sync through it. The work can be shaped into a
  stack of pull requests as it goes, but none is opened, and nothing merges
  into `main`, until the roadmap is done.
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
5. Run `/code-review` on the chunk, naming its diff (for example "only the
   uncommitted changes, `git diff HEAD`"). Without one it compares against
   `main`, and a worktree branch has no upstream, so it reviews the whole
   working branch instead of the chunk.
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
use, also give the macOS desktop items (2.3, 2.4, 2.6, 2.7) a manual pass in the
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
| A | Caret motion | `state/TextEditorCursorState.kt`, `state/TextEditorStateCursorExt.kt`, `state/WordSegmentationUtils.kt`, `input/TextEditorKeyCommandHandler.kt` | 1.1 to 1.7, 1.19, 2.3, 2.6, 7.5, 7.33 |
| B | Pointer and touch | `textEditorPointerInputHandling.kt`, `state/TextEditorSelectionManager.kt`, `DrawSelectionHandles.kt` | 1.9, 1.12 to 1.16, 1.21 to 1.24, 3.1, 3.2, 3.4 to 3.8, 3.13, 3.15, 4.23, 6.16 |
| C | Drawing and geometry | `Draw*.kt`, `cursor/`, `scrollbar/`, `state/TextEditorScrollState.kt`, hit testing | 1.8, 1.10, 1.11, 1.17, 1.18, 3.3, 3.12, 3.16, 4.14, 7.6, 7.7, 7.41 |
| D | Bindings, actions, menu | `input/KeyBindings.kt`, `input/EditorCommand.kt`, `input/BuiltinEditorActions.kt`, `contextmenu/` | 2.1, 2.2, 2.4, 2.5, 2.7 to 2.12, 4.8, 5.8 |
| E | Input sessions on desktop, iOS, web | `desktopMain`, `iosMain`, `wasmJsMain` under `input/` | 4.2 to 4.7, 4.10 to 4.12, 4.19, 4.21, 4.22, 4.24 to 4.26, 4.28, 4.29, 4.32, 4.33, 7.37 |
| F | Android input | `androidMain` | 0.4, 3.9 to 3.11, 3.14, 3.17, 4.16, 4.18, 4.20, 4.27, 4.30, 4.31, 7.40 |
| G | Edit pipeline and undo | `state/TextEditManager.kt`, `state/TextEditHistory.kt`, `state/EditBehavior.kt`, `input/ImeEditLogic.kt` | 1.20, 5.1 to 5.5, 5.9, 6.1 to 6.6, 6.14, 6.15, 6.17, 6.22, 6.23 |
| H | Clipboard and HTML | `clipboard/`, `html/`, `dragdrop/` | 4.9, 4.13, 4.17, 6.7 to 6.13, 6.18 to 6.21, 7.39 |
| I | Markdown and block model | `markdown/`, `richstyle/` | 5.6, 7.14 to 7.16, 7.43 |
| J | Find addon | `ComposeTextEditorFind/` | 7.17 to 7.19, 7.26, 7.29, 7.42 |
| K | Spell check addon | `ComposeTextEditorSpellCheck/` | 7.20 to 7.22, 7.28, 7.30, 7.31, 7.34, 7.35, 7.38, 7.44 |
| L | Tests and CI | test sources, `.github/workflows/` | 0.1 to 0.3, 0.5 to 0.9, 4.1, 4.15 |
| M | Accessibility and host API | semantics in `BasicTextEditor.kt`, `RichTextView.kt`, `state/rememberTextEditorState.kt` | 7.1 to 7.4, 7.13, 7.23 to 7.25, 7.32, 7.36 |
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
| 4.25, 4.26, 4.27 | Shipping 5.2 to 5.4 with a behavior that edits on the IME path (4.27 done) |
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
  any is in `OPEN_PARITY_ITEMS`; today Left then Right, and Down moves one row,
  are on. Set `FUZZ_INVARIANTS=all` to run every one.
- [ ] **0.4 Keyboard trace record and replay.** [Fable] [Lane F] A debug
  recorder on the Android `InputConnection` that logs every command and read. A
  user attaches the trace to a bug report; the trace replays in
  `androidHostTest`. Build a corpus per keyboard (Gboard, Gboard Japanese,
  Samsung, SwiftKey, AnySoftKeyboard). First case: hammer-editor#930.
- [ ] **0.5 Geometry assertions.** [Opus] [Lane L] Assert caret and selection
  rectangles from layout. Stable across machines, unlike pixels.
  Started in lane C: `utils/DrawRecorder.kt` runs a draw function on a canvas
  that records each rectangle and line with its colour.
- [ ] **0.9 A bundled test font. R.** [Opus] [Lane L] The e2e harness lays
  text out in the machine's default sans-serif font, so any test that depends on
  wrapping or text width can pass locally and fail on the CI runner. Two did
  (`TouchGesturesTest`, `LineDragAutoScrollE2eTest`), reproduced by making DejaVu
  the only font. Pin a bundled font in `editorUiTest` and `DifferentialHarness`.
- [ ] **0.6 Golden screenshots.** [Opus] [Lane L] A small set of scenes with a
  bundled font on one CI machine: caret, selection across wrapped and empty
  lines, squiggles, list markers, composing underline.
- [ ] **0.7 CI breadth.** [Opus] [Lane L] [Mac work] Desktop suite on macOS and
  Windows runners. An Android emulator smoke job. Browser automation against
  the built wasm demo for real key and composition events. An iOS simulator
  smoke test. Mac part: the iOS simulator smoke test.
  Found on the Mac 2026-09-30 at `ca6aac5`, before a macOS runner exists:
  `./gradlew check` fails there with or without that day's changes. Eight
  `ComposeTextEditorFind` tests (`FindBarNavigationTest`, `FindCloseTest`)
  never open the bar, likely because they press Ctrl+F where the host's
  bindings are Cmd; `LineLimitsE2eTest`'s minimum-lines case measures 64 px
  for an expected 66, a font-metric difference. Pin the bindings and derive
  the height from the font before the macOS job goes in.
  Fixed on the Mac: the Find UI tests press the find chords with a `primary`
  modifier, Cmd where `usesMacChords` holds and Ctrl elsewhere, so each
  desktop OS runs its own convention (the AltGr test stays Ctrl+Alt). The
  line-limits test took its row unit from the empty first line, and an empty
  line measures without a font: 16.49 px on macOS against 16.0 for every row
  with text, `" "` included, which is the unit the editor uses; the test now
  measures `" "` as the editor does. `./gradlew check` is green on macOS.
  Side note for 7.13: an empty document's one row is that half pixel taller
  than a row of text, so typing the first character shrinks it slightly.
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
  Linux part done: `state/TextBreaks.kt` declares `graphemeCursor`,
  `wordCursor` and `isEmojiCodePoint`; `skikoMain` backs them with skia's ICU
  (`org.jetbrains.skia.BreakIterator`, the iterator `BasicTextField` uses, so
  desktop, iOS and wasm share one `actual`) and `androidMain` with
  `java.text.BreakIterator`, ICU-backed on a device and real on the host JVM. Left, Right, Delete, vertical moves, End on
  a wrapped row and hit testing step by cluster; Backspace removes the previous
  code point, or the whole cluster when it is an emoji sequence, the rule
  `BasicTextField` and `EditText` share (a combining mark comes off its base
  on its own). The `NoLoneSurrogate` and `CaretOnGraphemeBoundary` invariants
  are on. IME `deleteSurroundingText` deletes stay as the keyboard counts them.
  The Mac part (compile the shared `actual` for iOS) is in the queue; the
  checkbox waits on it.
- [x] **1.2 Pixel-based vertical movement with a goal column. R.** [Opus]
  [Lane A] Up, Down, PageUp, and PageDown add a character count to the target
  row's start (`state/TextEditorStateCursorExt.kt`). With proportional fonts
  the caret jumps sideways, and when the target row is shorter in characters
  the caret overflows into a later row: the probe saw Down go from row 2 to row
  5. Use the caret's x and hit-test the target row; remember the goal x until a
  horizontal move or an edit.
  The goal x lives on `TextEditorCursorState` and ends with any other caret
  move or any document change. A page move past the first or last row keeps
  it through its jump to the document end (1.7), so the move back returns to
  the column; Up and Down at the ends (1.3) measure afresh from the caret, as
  `BasicTextField` does in both cases. A goal x past a wrapped row's end sits
  at the row's end since 1.6.
- [x] **1.3 Document edges. R.** [Opus] [Lane A] Up on the first row and Down
  on the last row do nothing. Native moves to document start and end.
- [x] **1.4 Collapse the selection on an unshifted arrow. R.** [Opus] [Lane A]
  Left or Right with a selection moves one character from the caret
  (`input/TextEditorKeyCommandHandler.kt`, `moveCursor`). Native collapses to
  the selection's start or end without moving further.
  Word motions and Up/Down still move from the caret, as `BasicTextField`'s do.
- [ ] **1.5 Word motion and word selection. C, U.** [Opus] [Lane A]
  - Line end is not a boundary: Ctrl+Right from the last word of a line skips
    the first word of the next, and Ctrl+Delete deletes it
    (hammer-editor#852). Since 1.19 only Windows' next-word-start motion
    (`WordRight`, `DeleteWordForward`) does this; the word end stops at the
    line end.
  - Only the straight apostrophe is a word character
    (`state/WordSegmentationUtils.kt`), so "don’t" splits. This also makes
    spell check flag contractions in typeset prose.
  - `isWordChar` is per-char `isLetterOrDigit`: combining marks and surrogate
    halves split words; a CJK run is one word.
  - Double-click on whitespace or punctuation selects the neighbour or leaves
    an empty, non-null selection.
  - Prefer the platform word break iterator, shared by keyboard, mouse, and
    spell check. This part builds on the 1.1 utility: [Fable] [Mac work].
  Linux part done: `wordRuns` (`state/TextBreaks.kt`) segments a line with the
  platform's ICU word iterator (`wordCursor`, the second `expect` of 1.1) and
  classifies each segment: lexical (holds a letter or digit), emoji, or other
  (spaces, punctuation, symbols). Word motion and double-click stop at lexical
  and emoji segments and skip the rest, as GTK, `EditText` and Cocoa do; spell
  check gets the lexical ones. "don’t" and "don't" are one word, a combining
  mark stays with its base, CJK breaks by dictionary word, and ICU breaks
  letters at a period ("U.S.A." is U, S, A). Windows' `WordRight` and
  `DeleteWordForward` stop at the line end and on an empty line. The spell
  check addon looks "don’t" up with a straight apostrophe. Double-click on
  punctuation or whitespace selects the word ending there, or nothing; the
  empty non-null selection is lane B's 1.21. Windows' Ctrl+Left still crosses
  a line break in one step (2.12). The Mac part (compile the shared `actual`
  for iOS) is in the queue; the checkbox waits on it.
- [x] **1.6 End on a wrapped row, and caret affinity. R, C.** [Fable] [Lane A]
  End goes to `nextWrapStart - 1`. That is right when the row ends in a space,
  one character short when the wrap falls mid-word or in CJK, and past the
  first space when the row ends in several.
  `CharLineOffset` has no affinity, so a position at a wrap boundary always
  draws on the later row.
  Done: affinity lives on the caret, not on positions.
  `TextEditorCursorState.affinity` (`CaretAffinity.Upstream` or `Downstream`)
  says which row a caret at a wrap offset draws on; every `updatePosition`
  resets it to downstream, and End on a wrapped row, and a vertical move whose
  goal x reaches past a row's end, place the caret on the wrap offset upstream.
  `TextEditorState.cursorRowIndex()` is the one read of the caret's row for
  the motions (Up, Down, Home, End, page moves, delete to the row end); the
  scroll manager, which has no state reference, resolves the same row through
  `getWrapForDrawing(position, affinity)`; and
  `LineWrap.caretX` draws an upstream caret at its row's right edge (left in a
  right-to-left row). Positions elsewhere (selections, spans, hit tests) stay
  affinity-free, so the blast radius is the caret's own readers. Left, Right
  and a click still land downstream: a click past a wrapped row's end puts the
  caret on the next row's start (1.24).
- [x] **1.7 PageUp and PageDown. C.** [Opus] [Lane A] Driven by scroll position
  rather than the caret's row; PageDown never reaches the document end. The
  scroll margin is a hard-coded 10 px (`state/TextEditorScrollManager.kt`).
  Done: a page move shifts the caret's row by the viewport height, keeps the
  goal x, and scrolls with the caret; past the first or last row it goes to the
  document start or end, as GTK and browser text areas do. No margin: the view
  scrolls just far enough to show the caret's row, like every native editor; a
  host wanting room adds content padding.
- [x] **1.8 Caret drawing. C.** [Opus] [Lane C] Width is a raw `2f` px, not dp
  and not configurable (`cursor/DrawCursorUi.kt`). Blink does not reset on
  forward delete. The caret is still drawn while a selection exists.
  Done: `TextEditorStyle.cursorWidth`, 2.dp like `BasicTextField`, drawn with
  its left edge on the glyph boundary and kept inside the canvas. The blink
  restarts on every caret move and every edit. The caret is hidden while text is
  selected, on every platform; its metrics still reach the IME
  (`drawing/CaretDrawingTest.kt`, which records drawing through
  `utils/DrawRecorder.kt` rather than reading pixels).

### Mouse

- [x] **1.9 Right-click keeps the selection. S.** [Opus] [Lane B] Any mouse
  button counts as a click (`textEditorPointerInputHandling.kt`,
  `detectMouseClicksImperatively`) and the click handler clears the selection,
  so the context menu opens with Cut and Copy hidden. Only the primary button
  clicks now. A right-click inside the selection keeps it; one outside moves
  the caret there first, as Chrome, VS Code, and GTK do. `BasicTextField`
  leaves the caret where it was on any right-click, so this departs from it.
  A read-only `RichTextView` keeps its selection on any right-click.
- [x] **1.10 Clicks above the first line. S.** [Opus] [Lane C]
  `getOffsetAtPosition` (`state/TextEditorState.kt`) falls through to "end of
  last line" for any y above the content. Clicking in the top padding, or
  dragging a selection above the top, sends the caret to the document end.
  Done: a click above the first row hits the first row and one below the last
  row hits the last row, x hit-tested either way, as `BasicTextField` does
  (`e2e/ClickOutsideTextE2eTest.kt`). A drag past the first or last row, in the
  viewport or out of it, selects to the document's start or end, following
  1.14 (`DragAutoScroll`); `BasicTextField` keeps x there too. A click or tap
  outside the text (above, below, or in the side padding) clicks no span.
- [x] **1.11 Padding. C.** [Opus] [Lane C] The placeholder draws at `Offset(0,
  0)`, ignoring top padding (`DrawPlaceholderText.kt`). Horizontal padding sits
  outside pointer input, leaving dead click zones.
  Done: the placeholder starts at the first line. `BasicTextEditor` and
  `RichTextView` apply their padding inside the canvas, below its pointer
  input; the handlers subtract the padding's top-left from every position
  (`contentOrigin`), so a press in the padding reaches the nearest row edge,
  focuses, and scrolls (`e2e/ContentPaddingE2eTest.kt`).
- [x] **1.12 Multi-click drag. C.** [Opus] [Lane B] Double-click then drag
  should extend by word, triple-click then drag by line. Today multi-click
  resolves on release and replaces the drag. Word selection should appear on
  press. Shift plus double-click ignores shift. Thresholds are hard-coded 300
  ms and 20 px instead of `viewConfiguration`. One mouse handler now owns
  caret placement, click counting (by event time, so tests drive it with the
  virtual clock), and the drag, all matching `BasicTextField`. Words come from
  `findWordSegmentAt`, the same function double-click and long press use, so
  1.5's break iterator reaches all three.
- [x] **1.13 Pointer icons. C.** [Opus] [Lane B] No I-beam over the editor
  (`RichTextView` has one). No hand over links. The editor and a selectable
  `RichTextView` show the I-beam; a hand shows over a link a click would open
  (1.15), so in an editor only while Ctrl or Cmd is held. The icon changes on
  the next mouse move, not on the key press itself.
- [x] **1.14 Drag auto-scroll. C.** [Opus] [Lane B] Scrolls only on pointer
  move events. Holding still outside the viewport stops the scroll.
  `DragAutoScroll` now scrolls every frame while the pointer is above or below
  the viewport, at 10 px per second for each pixel outside, and extends the
  selection to the edge row as the text moves. It never jumps to the row
  under the pointer any more. With the first or last row in view, a drag past
  that edge selects to the document's start or end.
- [x] **1.15 Links. C.** [Opus] [Lane B] Span clicks are reported on press with
  no modifier state, so a host that opens links on click also fires when the
  user places the caret or starts a drag. Report on release, pass modifiers,
  and offer a built-in Ctrl/Cmd+click convention. A left-click or tap now
  reports on release, only when it lifts on the span it landed on without a
  drag; the second and third press of a multi-click do not report; a
  right-click still reports on press, with its menu. `RichSpanClickListener`
  is unchanged. The modifiers come through a new, parallel
  `onRichSpanClickEvent: (RichSpanClick) -> Boolean` on `BasicTextEditor` and
  `TextEditor`. The convention is a new `onLinkClick: (url) -> Unit`: in an
  editor Ctrl+click opens a `LinkSpanStyle` (Cmd+click under `MacKeyBindings`),
  and in `RichTextView` a plain click or tap does. Span hit testing now uses
  the character under the pointer, not the nearest caret position.
- [x] **1.16 Middle-click paste on Linux. C.** [Opus] [Lane B] Not handled;
  middle click moves the caret. Only on Android, where `awaitFirstDown` answers
  every mouse button; on skiko it answers only the primary one. The click
  handler now takes the primary button only, so a middle click leaves the caret
  and selection alone everywhere. Compose has no primary selection API; the
  paste itself is 4.23.

- [x] **1.21 Empty selections. C.** [Opus] [Lane B] A drag that ends where it
  began leaves a non-null selection with start equal to end. The delete-by-motion
  actions (word, line, and paragraph deletes) then delete nothing and still
  consume the key. Normalise an empty selection to none. In the code an empty
  update was ignored rather than stored, which kept the last non-empty
  selection: a drag back to its start left a character selected. An empty
  update now clears the selection and its touch mode. A long press on blank
  space places the caret there and selects nothing.
- [x] **1.22 Right-click does not focus on desktop. R.** [Opus] [Lane B]
  `requestFocusOnPress` (`BasicTextEditor.kt`) waits for `awaitFirstDown`,
  which on skiko ignores every mouse button but the primary one, so a
  right-click on an unfocused editor opens the menu without focusing it.
  `docs/design/touch-focus.md` expects it to focus. Watch for the press event
  directly instead. It now reads the press event itself, as the mouse handler
  does, so any button focuses; a secondary press does not itself ask for the
  soft keyboard, though on Android focusing an unfocused editor starts the
  input session and may raise it.
- [x] **1.23 Word and line drags past the viewport. C.** [Opus] [Lane B]
  Auto-scroll (1.14) keeps a character drag's caret on a wholly visible row,
  but a word or line drag puts the caret at the far end of the unit under the
  pointer. In a long wrapped paragraph that end is off screen, so the
  editor's scroll-to-caret animation restarts every frame against the
  auto-scroll and the scroll lurches. Reproduced for a line drag (a
  triple-click drag held below the viewport scrolled 66, 83, 25, 83 px per
  200 ms); a word drag does it only for a word wider than the row, which
  layout breaks across rows. While its ticker runs `DragAutoScroll` now owns
  the scroll: it stops any scroll animation and sets
  `TextEditorScrollManager.cursorScrollSuppressed`, which
  `ensureCursorVisible` honours, and when the pointer comes back inside or
  lifts it clears the flag and reveals the caret.

### Selection drawing

- [ ] **1.24 Pointer affinity. C.** [Opus] [Lane B] A click or drag past the
  end of a wrapped row lands on its wrap offset, which `getOffsetAtPosition`
  returns as a bare position, so the caret draws at the start of the next row
  (1.6 gave only the keyboard an upstream caret). Return the affinity from the
  hit test and place the caret with it.
- [x] **1.17 Empty lines and newlines. C.** [Opus] [Lane C] Empty lines inside
  a selection draw nothing (`DrawSelectionUi.kt`). Native shows a sliver for
  the newline.
  Done: each selected line break adds a sliver one space wide (the base text
  style's) after its line's text, trailing spaces included, which are now
  highlighted too; a soft wrap adds none. Only rows in view are drawn, each
  its full height, block rows included (`drawing/SelectionDrawingTest.kt`).
  The sliver always goes right; right-to-left lines are 7.6's.
- [x] **1.18 Unfocused state. C.** [Opus] [Lane C] No unfocused selection
  colour; selection and touch handles stay drawn unchanged after focus loss.
  Done: `TextEditorStyle.unfocusedSelectionColor`, by default a neutral grey
  from `rememberTextEditorStyle` or, constructed directly, the selection colour
  at half its alpha, is drawn while the editor lacks focus. The context menu
  keeps focus, so it does not dim the selection. Touch selection handles are
  neither drawn nor grabbed without focus (the caret handle already hid,
  from 3.5) (`drawing/UnfocusedSelectionTest.kt`). Focus here is
  `TextEditorState.hasFocus`, set by the input node, since `isFocused` also
  needs the editor enabled; a read-only editor or `RichTextView` with focus
  keeps its colour and handles. Only Compose focus counts: switching to another
  window does not dim the selection, as it does natively on macOS. A handle
  drag under way when focus goes keeps going.

### Found by the differential tests

- [x] **1.19 Word ends. R.** [Opus] [Lane A] Ctrl+Right and Ctrl+Shift+Right
  stop at the start of the next word; `BasicTextField` stops at the end of the
  current one, and its Ctrl+Delete deletes to that end. Windows editors stop at
  the next word start, so the answer may be per platform (see 2.6 for macOS).
  Decide, then clear the `divergesUntil = "1.19"` cases.
  Decided per platform, through the binding tables rather than `expect`/`actual`
  (Windows and Linux share the desktop target): `Motion.WordEnd` and
  `Action.DeleteToWordEnd` sit beside `WordRight` and `DeleteWordForward`.
  `CtrlKeyBindings` (Linux, Android, other Ctrl hosts) and `MacKeyBindings`
  (Option+Right, Option+Delete) use the word end, like GTK, `EditText` and
  Cocoa; `WindowsKeyBindings` keeps the next word start. Desktop picks Windows
  from `os.name` and the web from the browser's platform and user agent.
- [x] **1.20 Joining lines deletes an empty line. R.** [Opus] [Lane G] In a
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
- [x] **2.3 Paragraph motion.** [Opus] [Lane A] Ctrl+Up/Down on Windows and
  Linux, Option+Up/Down on macOS.
  Up goes to the paragraph's start, or the previous one's when already there,
  everywhere. Down stops at the paragraph's end on Linux (GTK and
  `BasicTextField`), Android (`EditText`) and macOS (Cocoa's Option+Down), and
  goes on to the next paragraph's start on Windows (Word, WordPad), through a
  new `WindowsKeyBindings` table. Shift extends from the caret; without Shift a
  selection is left from its start going up and its end going down, as
  `BasicTextField`, `EditText` and Cocoa do.
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
- [x] **2.6 macOS conventions.** [Opus] [Lane A] Option+Right stops at the end
  of the current word, not the start of the next (done with 1.19, as is
  Option+Delete deleting to the word end). The Emacs-style Ctrl bindings
  (A, E, F, B, N, P, D, H, K) that every Cocoa text view has. K landed with
  2.4. Ctrl+Y (yank) needs a kill ring that K fills, which does not exist.
  Done: A and E go to the paragraph's start and end past any wrap, as Cocoa's
  `moveToBeginningOfParagraph:` and `moveToEndOfParagraph:` do; F, B, N and P
  are Right, Left, Down and Up (N and P keep the goal x); D and H delete
  forward and backward. Shift extends the motions. Ctrl+Y waits for 2.11.
- [ ] **2.7 Layout-aware shortcuts. U.** [Fable] [Lane D] A BEPO user reports
  shortcuts follow physical QWERTY positions on desktop (hammer-editor#945).
  Confirm, then match on the produced character where the platform provides it.
- [x] **2.8 Enter with modifiers. S.** [Opus] [Lane D] Every Enter chord
  inserts a newline. Leave Ctrl/Cmd+Enter unbound so hosts can claim it.
  Enter and Shift+Enter break the line; any chord with Ctrl, Cmd or Alt is
  unbound. A deliberate departure from `BasicTextField`, which also breaks the
  line on Ctrl+Enter (Windows, Linux) and Option+Enter (macOS), and from Cocoa,
  which breaks it on Ctrl+Return and Option+Return.
- [x] **2.9 Tab. C.** [Opus] [Lane D] Always inserts four spaces and is always
  consumed, so there is no keyboard way out of the editor. Make Tab and
  Shift+Tab list-aware, make the tab size and the insert-tab behaviour
  configurable.
  Done: `TextEditorState.tabSettings` (`TabSettings`: `size`,
  `insertTabCharacter`, `movesFocus`), defaulting to today's four spaces.
  Ctrl+Tab and Ctrl+Shift+Tab are unbound everywhere and move focus (GTK,
  Cocoa, Swing), as does Tab straight after Escape (CodeMirror), and
  `movesFocus` gives Tab and Shift+Tab to the focus system outright. Nested
  lists do not exist (5.6), so list-aware means Tab adds no leading spaces to a
  list item: at an item's start it does nothing, and over several lines it
  skips the items. See `docs/design/editor-actions.md`, "Tab".
- [x] **2.10 Context menu. C.** [Opus] [Lane D] No Menu key or Shift+F10. No
  Undo or Redo. Unavailable items are hidden rather than disabled. The position
  is shifted by the start content padding. `TextEditor` does not expose
  `contextMenuStrings` or `contextMenuState`; `RichTextView` hard-codes
  English. No Paste as plain text item (2.2 added the action).
  Since 1.11 the canvas's pointer input covers the padding, so a raw pointer
  position is already in the menu provider's coordinates; the handlers
  translate it to text coordinates before `onContextMenuRequest`, which is the
  shift.
  Done: `Action.ShowContextMenu` on Shift+F10 and the Menu key off macOS opens
  the menu under the caret. Undo, Redo and Paste as Plain Text are items, and
  an item whose action has nothing to do is disabled; one not registered or
  not allowed (editing in a read-only editor) is hidden. `ContextMenuStrings`
  gained `undo`, `redo` and `pasteAsPlainText` with English defaults;
  `TextEditor` takes `contextMenuStrings` and `contextMenuState`, and
  `RichTextView` `contextMenuStrings`. The composables convert the canvas
  positions the handlers and the touch toolbar give through the layout
  (`ContextMenuPlacement`), so content padding and a host modifier's padding
  both count and the pointer handling is unchanged; the menu is placed with
  `absoluteOffset`, so a right-to-left layout does not mirror it. The menu was
  already keyboard-navigable (Material's skiko dropdown). See
  `docs/design/editor-actions.md`, "The context menu".
- [x] **2.11 Kill ring.** [Opus] [Lane D] Ctrl+K on macOS deletes to the
  paragraph end but keeps nothing. Cocoa saves killed text to a kill ring,
  consecutive kills append to it, and Ctrl+Y yanks it back. Separate from the
  clipboard.
  Done: Ctrl+K, Cmd+Backspace and Cmd+Fn+Delete (the three Cocoa kill
  commands) keep what they delete in a one-entry, per-editor `KillRing`, a
  kill straight after another joins it (after it forward, before it
  backward), and `Action.Yank` on Ctrl+Y inserts it over any selection as one
  undo step, with its character styling. See
  `docs/design/editor-actions.md`, "The kill ring".
- [x] **2.12 Windows Ctrl+Left stops at the previous line's end.** [Opus]
  [Lane D] Since 1.5 Windows' Ctrl+Right stops at the line end before the next
  line's first word, but `WordLeft` is one motion for every platform, so
  Ctrl+Left and Ctrl+Backspace from a line start still reach the previous
  line's last word in one step. Windows edit controls stop at the previous
  line's end first; GTK and Cocoa do not. Needs a Windows-only motion beside
  `WordRight` in the binding tables.
  Done: `Motion.PreviousWordStart` and `Action.DeleteToPreviousWordStart` on
  `WindowsKeyBindings`' Ctrl+Left and Ctrl+Backspace, the mirror of
  `WordRight`: the start of the word on this line, else the line start, and
  from a line start the previous line's end. `KeyBindings.wordBackward` names
  it for the mirrored arrows of a right-to-left paragraph.

## Phase 3: touch polish (Android first)

- [x] **3.1 Crossing handles. C.** [Opus] [Lane B] The drag uses
  `selection.start`/`end` as the fixed edge while the range is reordered, so
  the anchor is lost once the handles cross. A user describes handles that
  "jump all over" (hammer-editor#956). The other end is now fixed for the
  whole drag, so a handle crosses cleanly; landing exactly on the other end
  keeps the last selection rather than emptying it. The two handles' hit
  areas overlap on a short selection, and the start handle used to win even
  under the end handle; the nearer one wins now. A finger can grab a handle
  only when handles are drawn (a touch selection). `startSelection` is
  deprecated: an empty selection is none, so it can only clear.
- [x] **3.2 Handle grab offset. C.** [Opus] [Lane B] A fixed 162 px upward
  offset is applied instead of the grab delta, so the edge jumps on grab. The
  edge now moves by exactly the finger's travel from where it grabbed.
- [x] **3.3 Density. C.** [Opus] [Lane C] Handle sizes, the 80 px hit radius,
  stroke widths, and the composing underline are raw px; handle colour is
  hard-coded (`DrawSelectionHandles.kt`).
  Done: in dp, sized to match the old pixels on a 2.625x phone: a 20 dp knob
  19 dp below the row on a 2 dp stem, a 30 dp selection handle hit radius, the
  caret handle's still 1.5 times the knob's radius, and a 1 dp composing
  underline drawn on whole pixels. Hit tests use the pointer input's own
  density. `TextEditorStyle.handleColor` sets the colour, by default the
  theme's `primary` from `rememberTextEditorStyle`, otherwise the old blue
  (`drawing/HandleDensityTest.kt`).
- [x] **3.4 Auto-scroll while dragging a handle. C.** [Opus] [Lane B] Absent.
  A handle drag uses the mouse drag's `DragAutoScroll` (1.14), measured at the
  dragged end rather than the finger.
- [x] **3.5 Caret handle. C.** [Opus] [Lane B] Handles are drawn only with a
  selection. A tap in a non-empty editor now puts a handle under the caret,
  and dragging it moves the caret (auto-scrolling like the others). It hides
  when the caret moves any other way, the document changes, something is
  selected, focus leaves, or after 4 s idle, as Android's insertion handle
  does. Its hit area is the drawn handle and a small margin, not the 80 px
  of the selection handles, because it hangs over the lines below the caret.
- [x] **3.6 Magnifier. C.** [Opus] [Lane B] Absent. Compose Multiplatform
  1.12.1 has `Modifier.magnifier` only in androidMain (it is the androidx
  one, a no-op below API 28); commonMain and skiko have only foundation's
  internal text-field magnifiers. The editor now shows it on Android while a
  selection or caret handle is dragged, centred on the dragged end's row and
  level with the finger. `textMagnifier` is an `expect` whose skiko `actual`
  (desktop, iOS, web) adds nothing; see 3.15 and the Mac queue.
- [x] **3.7 Gestures. C.** [Fable] [Lane B] No double-tap word select, no
  long-press then drag. The long-press timeout is a hard-coded 500 ms. A
  second tap within the platform's double-tap timeout of the first tap's lift,
  and within Android's 100 dp double-tap slop of it, now selects the word
  under it with handles, and dragging on from a double tap or a long press
  extends by word from the first word, with the magnifier and auto-scroll a
  handle drag has; the moves are consumed so the ancestor scrollable does not
  pan. The timeout is `viewConfiguration.longPressTimeoutMillis`. A drag that
  selected focuses on release although it travelled past touch slop: the focus
  handler compares `TextEditorSelectionManager.touchSelectionGeneration` across
  the gesture, which every finger selection advances. No triple tap: Android's
  text fields have none.
- [x] **3.8 Reaching Paste by touch. C.** [Fable] [Lane B] The menu opens only
  on a second long-press over an existing selection, and long-press on an empty
  line does nothing. Paste is unreachable in an empty editor and at a bare
  caret. Use the platform text toolbar. `TouchToolbar` (`TouchToolbar.kt`) now
  shows `LocalTextToolbar` with Cut, Copy, Paste and Select all as the editor
  can do them: once a long press or double tap lifts (over the word, or over
  the caret a long press on empty space or an empty editor placed), on a tap
  of the caret handle, on a tap or drop of a selection handle, and on a long
  press on the selection. It hides when the caret or selection moves under
  it, on focus loss, and on any mouse press, and follows the text on a scroll;
  its Select all keeps it up with handles on the new selection. Android and iOS have a toolbar
  (`hasNativeTextToolbar`, an `expect`); desktop's is inert and the web's is
  drawn only inside foundation's own text fields, so there the editor's
  context menu stands in, but only at a bare caret and for a long press on the
  selection: it is modal and would eat the tap after every selection.
  Right-click keeps the context menu everywhere. A selectable `RichTextView`
  gets the same with Copy and Select all.
- [x] **3.9 Caret under the soft keyboard. C, U.** [Opus] [Lane F] A viewport
  resize relayouts but does not re-run `ensureCursorVisible`, and there is no
  `BringIntoViewRequester` (hammer-editor#932). Done: a window that draws the
  keyboard over the editor (edge-to-edge) was covered by 4.24's keyboard
  cover, which is common code. A window that shrinks the editor instead
  (`adjustResize`, or a host's `imePadding`) now keeps a focused editor's caret
  in view across the resize when it was in view before, snapping each frame
  so it keeps up with the keyboard's animation; a caret scrolled away stays
  where it is, and a scroll already taking the caret into view when the
  keyboard starts is taken over (`ViewportResizeE2eTest`). The sample app
  (edge-to-edge, padding itself by the keyboard's inset) declared no
  `windowSoftInputMode`, so Android also panned the window to the caret and
  the editor floated above a gap; it now declares `adjustResize`, which
  stops the pan, and no longer pads by the navigation bar twice. The README
  tells hosts the same. Checked on an emulator (API 36, Gboard): a tap on the
  last visible row raised the keyboard and the row stayed in view above it,
  with the toolbar in place.
- [x] **3.10 Cursor anchor info. C.** [Opus] [Lane F] Translated by the view's
  screen position but built from canvas-local metrics, so it is off by the
  editor's offset inside the view
  (`androidMain/.../state/PlatformTextEditorExtensions.android.kt`).
  Done: the marker is the caret in the view's coordinates, content padding and
  scroll included (`imeCaretInRoot`, `input/ImeCaret.kt`; `ImeCaretTest`), and
  its flags say whether its top and bottom lie inside the editor's visible
  bounds (clipped by its ancestors, less a strip the keyboard covers), as
  `TextView` reports them. While the IME monitors the anchor, a scroll,
  relayout, move or resize that moves the caret on screen resends it, and so
  does any flush that finds the view moved on screen (`ImeCursorSyncTest`).
  The skiko request's caret rectangle is built from the same geometry. Checked on an emulator (API 36, Gboard) through
  `dumpsys input_method`: a tap at y 700 reported a marker from 663 to 712 with
  the visible flag, and a scroll that took the caret row above the editor
  resent it at 245 to 294, flagged invisible, with the selection unchanged.
- [x] **3.11 Keyboard options. C.** [Opus] [Lane F] `inputType` and
  `imeOptions` are hard-coded; hosts cannot configure capitalisation,
  autocorrect, or keyboard type. `initialCapsMode` and initial surrounding text
  are not set. `commitContent` returns false. No autofill, no stylus
  handwriting. Done: `TextEditorState.keyboardSettings` (`KeyboardSettings`,
  commonMain `input/KeyboardSettings.kt`) sets capitalisation, autocorrect,
  the keyboard's layout, and the action key, in Compose's own types, and
  `TextEditorState.onImeAction` handles that key. Android maps the settings
  to `EditorInfo` as Compose does for a multi-line `BasicTextField` (Enter
  keeps `IME_FLAG_NO_ENTER_ACTION` unless an action is asked for), answers
  the action its connection was opened with through the handler, or without
  one as Compose's text fields do (Next and Previous move focus, Done hides
  the keyboard), and restarts input when the settings change. `initialCapsMode` and, from API 30, the initial
  surrounding text are set (`KeyboardSettingsTest`). The Code Editor demo
  turns capitals and autocorrect off; checked on an emulator (API 36, Gboard)
  through `dumpsys input_method`: its editor reported `inputType=0x20001`
  (text, multi-line) and the rich text demo's `0x2c001` (with sentence caps
  and autocorrect). iOS and web adoption is 4.32; the rest moved to 3.17.
- [x] **3.12 Scrolling. C.** [Opus] [Lane C] No overscroll effect. The mobile
  scroll indicator is non-interactive, always visible, with a fixed 15% thumb.
  A 32 px buffer is always added to max scroll, so a one-line document scrolls.
  Done: the editor feeds and draws the platform's overscroll
  (`rememberOverscrollEffect`: a stretch on Android, none on desktop). The
  Android and iOS indicator (`scrollbar/ScrollIndicator.kt`, shared) has a
  thumb as long as the share of the document in view and fades half a second
  after scrolling stops; it stays display-only, as on both platforms. The
  buffer is gone: the furthest scroll puts the last row and the bottom padding
  at the viewport's bottom, as `BasicTextField` does, and a document that fits
  with its padding does not scroll; bottom content padding is the room below
  the last line when a host wants it. `TextEditorScrollState` reports
  `canScrollForward` and `canScrollBackward`, by Compose's meaning (forward
  consumes a positive delta, which this state turns toward the start), so a
  wheel at an end reaches a parent scroll container (`scrollmanager/ScrollRangeE2eTest.kt`,
  `ScrollIndicatorTest.kt`). Touch handles on the last row now sit below the
  viewport's edge with nothing to scroll them into view; the buffer never
  fully cleared them either (see 3.3).
  iOS checked in the simulator 2026-09-29: the thumb is proportional, shows
  while scrolling and fades after, and pulling past the top rubber-bands and
  settles back.
- [x] **3.13 Known open issues** [Fable] [Lane B] from
  `docs/design/touch-focus.md`: a handle drag cannot restore focus; an orphaned
  long-press job with a second finger. The handle case is closed by 1.18: the
  handles go with focus, so there is none to drag on an unfocused editor, and
  a finger where one stood is a tap, which focuses (test-pinned). A drag still
  advances the touch selection generation 3.7 added (test-pinned), so a drop
  asks for the soft keyboard back and focuses even under a popup. The
  long-press job cannot outlive its gesture (a `finally`
  cancels it), and a second finger on the editor now cancels it and the tap
  (the caret handle's and a link's too), and keeps the focus handler from
  focusing, as Android's gesture detector treats a second pointer. A finger
  landing outside the editor's node never reaches its handlers.
- [ ] **3.14 Italics invisible on Android. U.** [Fable] [Lane F] Saved and
  exported correctly but not drawn (hammer-editor#956). Not reproduced.
- [ ] **3.15 Magnifier on iOS and mobile web. C.** [Fable] [Lane B]
  [Mac work] Compose has no magnifier outside Android (3.6). iOS text views
  show a loupe while the caret or a handle is dragged; matching it means
  drawing our own: an enlarged copy of the canvas around
  `TextEditorSelectionManager.magnifierCenter` in a popup above the finger,
  fed from the `skikoMain` `textMagnifier`. Mobile browsers show none for
  canvas content. Desktop needs none: a mouse does not hide the text.
- [ ] **3.16 The keyboard cover is measured against the last frame's canvas.
  C.** [Opus] [Lane C] `BasicTextEditor` measures the cover (4.24) when the
  keyboard's inset changes, from the canvas's bounds as last laid out. Under
  a host's `imePadding` the inset grows a frame before the padding shrinks
  the editor, so each frame of the keyboard's animation briefly reads a
  covered strip, starts an animated `ensureCursorVisible`, and the next
  layout resets the cover to 0. Since 3.9 the resize takes that scroll over,
  but a snap measured while the stale strip stands leaves the caret row that
  many pixels higher than it needs to be. Measure the cover after layout
  only, or ignore an inset change the next layout will absorb.
- [ ] **3.17 Rich content, autofill, and stylus handwriting on Android. C.**
  [Fable] [Lane F] From 3.11: `commitContent` returns false, so a keyboard's
  GIFs and stickers are refused; the editor offers nothing to autofill; and
  there is no stylus handwriting (`View.setAutoHandwritingEnabled` and
  `EditorInfo.setStylusHandwritingEnabled`, API 33 and 35). Each needs a host
  hook or a design decision about what the editor does with the content.

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
- [x] **4.2 One shared input request for desktop, iOS, and web.** [Fable]
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
  `input/SkikoInputMethodRequestTest` cover it and wasm compiles. Mac part
  done 2026-09-29 at `024affd`: iOS compiles with no `iosMain` change beyond
  the `ImeOptions`, and the simulator pass under 4.5 is clean.
- [x] **4.3 Start a real input session on web.** [Fable] [Lane E] Replace the
  suspend-forever stub with `startInputMethod` using the shared request, then
  settle which path owns plain typing so keys are not inserted twice (today
  typed characters arrive as `keydown`; see `isCharacterInputCandidate`).
  Done: `wasmJsMain` starts the shared session. Ownership follows DOM focus,
  and the browser gives a keystroke to one element: with Compose's hidden
  textarea focused, typing is `commitText` and the textarea forwards only
  character-less keys; with the canvas focused, the `keydown` carries the
  character and the predicate inserts it. Verified in Chromium against
  `./gradlew :sampleApp:wasmJsBrowserDevelopmentRun` (localhost:8080): typing,
  Backspace (one deletion per press), Enter, Home, arrows, insertion at the
  caret, click then type, and composition driven by synthetic
  `insertCompositionText` and `compositionend` events on the textarea (k
  becomes か, committed once). The textarea lives in the viewport's shadow
  root; find it with `host.shadowRoot.querySelector('.compose-backing-field')`.
  A real IME, Firefox and Safari, and the mobile soft keyboard are 4.4.
- [ ] **4.4 Verify on devices.** [Human] [Lane E] [Mac work] iOS simulator and
  a physical iPhone; desktop and mobile browsers. Record what works in the
  manual QA plan.

### iOS

- [x] **4.5 Input correctness. S.** [Opus] [Lane E] [Mac work] Resolved by 4.2;
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
  All four are addressed on Linux by the shared request (4.2). Simulator pass
  2026-09-29 (iPhone 17 Pro Max, iOS 26.0), against the baseline below: soft
  keys type once each; backspace removes one character per press; "teh" then
  space becomes "The " (the correction replaces the word); Japanese Romaji
  "nihongo" shows underlined にほんご with kanji candidates, and picking 日本語
  replaces the kana once; Return at the end of a bullet item continues the
  list, and bold elsewhere on the line survives. Dictation and a physical
  device are left to 4.4.
- [x] **4.6 Layout geometry. C.** [Opus] [Lane E] [Mac work] `textLayoutResult`
  and every rect return null, so spacebar trackpad mode and IME positioning
  cannot work. The rects come with 4.2 (done on Linux); `textLayoutResult`
  stays null because the editor lays out its own lines, so the spacebar
  trackpad's floating caret needs an editor-side equivalent of
  `getOffsetForPosition` before it can work. That is the remaining work here.
  Also: the rectangles read the snapshot-backed viewport size, so a resize
  re-runs iOS's geometry observer, but a move without a resize does not,
  since `canvasLayoutCoordinates` is a plain field.
  Done. On the input connection the editor uses, Compose's iOS view answers
  UIKit's caret, selection and hit-test queries with fixed dummies (Compose
  draws its own caret), so `textLayoutResult` matters only to the spacebar
  trackpad (`beginFloatingCursor`/`updateFloatingCursor`) and to
  `verticalPositionFromPosition`. `input/DocumentTextLayout.kt` (skikoMain)
  builds a whole-document layout when UIKit first asks, as plain text in the
  base style at the viewport's width, kept until the text, width or style
  changes and kept out of the measurer's cache; only iOS asks for it
  (`exposeTextLayout`), so desktop and web pay nothing. The canvas's position
  is now snapshot state (`canvasPositionInRoot`), so the geometry observer
  follows moves too. `DocumentTextLayoutE2eTest` and a
  `SkikoInputMethodRequestTest` case; in the simulator, long-pressing the space
  bar and dragging moves the caret. Follow-ups: the layout is unstyled, so its
  rows differ from the drawn ones where headings, code spans or block heights
  change the wrapping, and a drag can cross more rows than it seems to (build it
  from the editor's own line layouts instead); and `verticalPositionFromPosition`
  now answers, so check that a hardware Up/Down on iOS moves once (Mac queue).
- [x] **4.7 Keyboard options. C.** [Opus] [Lane E] [Mac work] Capitalisation is
  not set. Set to sentences with autocorrect on in 4.2. Confirmed in the
  simulator: the keyboard opens shifted at the start of a line.
- [x] **4.24 Caret under the soft keyboard, and a shifted screen. R.** [Opus]
  [Lane E] [Mac work] Seen in the 4.5 pass. Tapping a line that the keyboard
  will cover leaves the caret hidden behind the keyboard until the first edit,
  which does scroll it into view; the iOS twin of 3.9. Separately, when the
  keyboard first opens, the whole screen is often pushed up by about the
  toolbar's height, hiding the toolbar and leaving an empty band above the
  keyboard; it sometimes corrects itself when the keyboard changes. Seen on
  three of five keyboard opens across both days, with and without the 4.2
  rects, so the rects alone did not fix it. Suspect Compose's keyboard
  avoidance reacting to `focusedRectInRoot` before the editor has scrolled or
  resized.
  Done. Three causes. The sample's SwiftUI host let SwiftUI shrink the Compose
  view for the keyboard while Compose also offset its content by the keyboard
  inset it measured before the shrink, so the keyboard was compensated twice:
  the band and the hidden toolbar. `ContentView` now has
  `.ignoresSafeArea(.keyboard)`, as the Compose Multiplatform template does,
  and the README says so for hosts. Compose's iOS keyboard avoidance
  (`OffsetToFocusedRect`) keeps the focused node's focus rect above the
  keyboard, and that was the whole editor, so a tall editor still pushed the
  screen up; `CaretFocusRect` now reports the caret row, pulled above the
  keyboard where the editor scrolls it, so the window moves only when the
  editor cannot show the caret itself. And the editor did not know the keyboard
  covered it: `state/KeyboardCover.kt` measures the covered bottom of the
  canvas from `WindowInsets.ime`, the caret scrolls above it when it grows, and
  the scroll range grows by it so the last line can clear the keyboard, as a
  native text view's content inset does. Nothing changes where the host
  already keeps the editor above the keyboard, or for an unfocused editor.
  Known limit: the keyboard is measured from the root's bottom, so an editor in
  a dialog or popup that does not reach the window's bottom is mis-measured. `KeyboardCoverE2eTest`, `KeyboardCoverTest` and
  three `TextEditorScrollManagerTest` cases; checked in the simulator: the
  toolbar stays, a tapped line under the keyboard comes into view at once, and
  typing Returns keeps the caret at the keyboard's top. Android reports
  `WindowInsets.ime` only to edge-to-edge windows, so this also covers 3.9
  there when the host has no `imePadding`; a window that resizes is still 3.9.
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
  Still present after 4.2; fixed by 4.24.
- After any hardware key event the simulator hides the soft keyboard until the
  device is rebooted. Keep that in mind when testing both paths in one run.

### Web

- [ ] **4.11 Soft keyboard on mobile web. C.** [Opus] [Lane E] Compose creates
  its backing DOM input inside `startInputMethod`, which is never called, so no
  keyboard appears. Resolved by 4.3: the textarea now exists and is focused
  on a tap, which is what raises the keyboard. Tick after a pass on Android
  Chrome and iOS Safari (4.4); a headless browser cannot show one.
  Gap: Compose sets `autocapitalize="off"` on every backing field, so phone
  keyboards never capitalise a sentence. The web session sets it to
  `sentences`, matching the Android and iOS sessions, but only once the
  field exists, after Compose has focused it; whether a keyboard already up
  honours the change is for the phone pass, and if not the fix belongs in
  Compose (its `DomInputStrategy` ignores `ImeOptions.capitalization`,
  which the session now passes). Verified in Chromium
  with the pane's Pixel 8 emulation (Android user agent, touch points): a
  synthetic touch tap on the unfocused Blank editor focuses the textarea
  (`inputmode="text"`, `enterkeyhint="enter"`, `autocorrect="on"`,
  `autocapitalize="sentences"`) placed at the caret; a Gboard-shaped
  sequence (`keydown` 229 "Unidentified", `insertCompositionText` "T", "Te",
  "Teh", corrected to "The", `compositionend`, `insertText` " ", then "cat"
  composed and a `deleteContentBackward`) leaves "The ca" with each step
  applied once; an `insertReplacementText` over "ca" gives "The cat". Still
  needs a phone: the keyboard rising and staying up, suggestions, a tap on
  the canvas that Compose does not consume hiding the keyboard, and whether
  the keyboard covers the caret.
- [ ] **4.12 Composition on desktop web. C.** [Opus] [Lane E] Dead keys and CJK
  input. Also resolved by 4.3, and the event shape a browser IME sends is
  verified with synthetic events. Tick after a pass with a real IME (fcitx or
  ibus on Linux, the macOS Japanese keyboard) in Chrome, Firefox, and Safari.
  Rechecked with synthetic events in Chromium against the dev server: a dead
  key (`keydown` "Dead", `insertCompositionText` "´", then "é",
  `compositionend`) gives one "é" with no stray "Þ" or "´"; a Romaji
  composition ("n", "に", "にh", "にほ", Backspace to "に", on to "にほん",
  converted to "日本") commits "日本" once, and an Enter `keydown` during the
  composition adds no line; a composition after arrowing into the middle of
  a line lands at the caret. No code change was needed. Synthetic events
  leave the textarea's own text alone, which real input does not, so a real
  IME in Chrome, Firefox and Safari is still what ticks this.
- [x] **4.13 Clipboard. C.** [Opus] [Lane H] Plain text only through
  `navigator.clipboard`, failures swallowed silently (shared with 6.7). Done: a
  browser answers Ctrl/Cmd+C, X and V in the backing textarea with a `copy`,
  `cut` or `paste` event while the key is down, and Compose hands the key to the
  editor's bindings a frame later. `ClipboardEventsEffect`
  (`wasmJsMain/.../clipboard/ClipboardEvents.wasmJs.kt`) uses the event, the one
  moment the page may use the clipboard with no permission prompt, to move the
  data: copy and cut write the selection's `text/html` and `text/plain` into it,
  paste keeps its flavors for the Paste action, and the textarea's own plain
  copy or paste is prevented; the actions still do the editing, so a key press
  edits once. Only an event aimed at this viewport's input while the editor has
  focus is taken. The context menu and host calls use `navigator.clipboard`:
  `write` with a `ClipboardItem` holding both flavors and `read` preferring the
  markup where the browser has them, else `writeText` and `readText`; a paste
  reads once, since some browsers ask on every read, and a refused `read` is not
  retried. Each refusal is logged with `console.warn` and the browser's reason,
  as Compose's own web clipboard does. Checked in Chromium with synthetic chords
  (keydown then clipboard event: a bold run copied out as `<strong>` and pasted
  back bold, once, with no `navigator.clipboard` call) and a refused read; real
  browsers with clipboard permission granted are still to check (QA 2.11). The
  event and the action are paired by time (one second): a host that binds the
  chords to other actions leaves the event's data unclaimed, and a context-menu
  copy inside that second is dropped. Cut's data loss is 6.19.
- [x] **4.14 Scrollbar. C.** [Opus] [Lane C] The implementation is commented
  out.
  Done: desktop and web share Compose's own `VerticalScrollbar`
  (`skikoMain/.../scrollbar/EditorVerticalScrollbar.skiko.kt`) in a gutter at
  the end edge, through an adapter over `TextEditorScrollState`: the thumb
  drags, a press on the track pages (repeating while held), a wheel over it
  scrolls the editor, and it is hidden when the document fits. A host's
  `LocalScrollbarStyle` styles it; without one its colours come from the
  Material theme's `onSurface`. It replaces the desktop's hand-drawn 16 dp bar
  (the gutter is now the style's 8 dp, so text wraps 8 dp wider) and the web's
  commented-out one (`scrollbar/EditorScrollbarE2eTest.kt`).
- [ ] **4.15 Browser tests.** [Opus] [Lane L] Automation against the built demo
  (0.7), with composition events.
- [x] **4.21 Whole-document mirror per edit. S.** [Opus] [Lane E] Compose's web
  session copies `request.value().text` into the backing `<textarea>` after
  every edit, and iOS snapshots `state.text` the same way, so each keystroke
  builds the whole document as a `String` and hands it to the platform. Same
  as `BasicTextField` on those platforms, so acceptable today; measure on a
  long document (7.8) before deciding whether the request should serve a
  window around the caret instead.
  Web measured 2026-09-29 on a 200,000-character document (2,000 lines of
  99 characters): not worth a window there. iOS is left to the Mac queue.
  - Web, Chromium, sample app dev server (unoptimised wasm, so the editor's
    own share is larger than in production): main-thread time per
    keystroke, summed over every animation frame, timer and message task in
    the 3 s after a real key press, caret blink (about 5 ms per idle 3 s)
    not subtracted. With the mirror: 13.8, 16.4, 14.1 and 11.6 ms. With
    `value()` stubbed to a constant (no textarea write, no string handed to
    JS): 13.5, 12.4, 12.7, 12.5 and 12.7 ms. A 2,000-character document
    with the mirror: 14.2, 8.1 and 6.4 ms. So the mirror adds about 1 ms a
    keystroke at 200k characters, with a wider spread (a few samples; a
    rare copy or GC spike cannot be ruled out). The textarea write with a
    forced layout took 0.1 to 2.4 ms; loading the 200k document into it
    once took 17 ms. The stub still builds the document string when
    semantics reads it (7.9), so this is the mirror's cost beyond that.
  - The Kotlin side on the desktop JVM (iOS runs the same code on
    Kotlin/Native, which can be several times slower): building the
    document string takes 214 µs per revision at 200k characters (5 µs at
    2k), memoized and shared with semantics (7.9); `value()` over it takes
    1 µs; comparing successive values is under 1 µs when an edit changes
    the length, and a scan to the first difference when it does not (typing
    over a one-character selection).
  - iOS, 2026-09-30 at `f3b8d8f`, iPhone 17 Pro Max simulator (Debug
    framework, on an Apple silicon Mac, so a phone is slower), soft
    keyboard, twelve keys each time, timed with temporary logging. The
    session's `state.text` read (the mirror): 0 ms at 200k characters,
    memoized. The keyboard's `editText` block (the edit itself): 0.7 ms
    median at 2k, 9.4 ms at 200k, the same at the end and near the start of
    the document. Frames over 20 ms while typing: none at 2k; at 200k about
    six a keystroke, up to 137 ms. At 200k, even idle, each caret-blink
    frame takes 33 ms (two vsyncs), where 2k never exceeds one. So on iOS
    too the mirror is not the cost and needs no window; typing at 200k is
    slow for the reasons in 7.8 and 7.11, which carry these numbers.
    Instruments' Time Profiler would not attach from the command line
    (`xctrace record --attach` stalled with nothing recorded), so the split
    inside a frame is unmeasured.

Exit criteria: typing, composition, and clipboard work in current Chrome,
Firefox, and Safari on desktop; the soft keyboard works on Android Chrome and
iOS Safari; browser tests run in CI.

- [x] **4.22 DOM focus left on the canvas. S.** [Opus] [Lane E] While the
  editor holds Compose focus, the browser's focus can stay on the canvas rather
  than the input session's textarea; a right-click, for one, skips
  `onRequestInput` (`requestFocusOnPress` in `BasicTextEditor.kt`). Typing then
  arrives as canvas key events, which cannot tell a typed character from a named
  key whose code Compose substitutes (see `PlatformCharacterInput.wasmJs.kt`).
  So ';' and '=' are dropped, and AltGr characters are refused, because Windows
  reports AltGr as Ctrl+Alt and a focused textarea commits that keystroke
  itself. Refocus the textarea whenever the editor holds focus.
  Done: while its session is live, the web input service listens for
  `focusin` on the viewport's shadow root and moves DOM focus from the canvas
  back to the textarea (a focus move inside a shadow tree never reaches a
  document listener). A touch is left alone, so a tap Compose does not consume
  still hides the phone keyboard. The canvas path now serves only a focused
  editor with no session (disabled and enabled again, until a tap) and
  keystrokes after such a touch; the predicate cannot
  see the DOM event, so it keeps refusing ';', '=' and Ctrl there. Verified
  in Chromium against the dev server: after a right-click in the text,
  Escape, and after a click on a toolbar button, DOM focus is back on the
  textarea and real ';' and '=' key presses insert at the caret; focus still
  moves to and from the Find demo's search field; after a synthetic touch
  `pointerdown` the canvas keeps focus. A phone pass is 4.4. Note for browser
  automation: CDP `insertText` without a `keydown` lets Compose's textarea
  `selectionchange` listener move the caret one ahead of each insert, so
  drive typing with key events.

### Android and desktop

- [ ] **4.16 Japanese input. U.** [Fable] [Lane F] Reported broken with Fcitx5
  and Mozc on Linux and with Gboard Japanese on Android (hammer-editor#930).
  ComposeTextEditor PR 102 reworked IME edits afterwards; nobody has confirmed
  the result.
- [ ] **4.17 Wayland paste. U.** [Fable] [Lane H] External paste fails on
  Wayland with KDE; internal paste works (hammer-editor#921).
- [ ] **4.18 ANR on Galaxy S21 Ultra. U.** [Fable] [Lane F] hammer-editor#545,
  stale.
- [x] **4.19 Desktop candidate window. C.** [Opus] [Lane E] `lastCursorMetrics`
  updates only when the caret is drawn, so it can lag during blink-off.
  1.8 made the draw record the metrics whatever the blink, but the skiko
  request's caret rectangle still read them, so a platform asking right after
  a caret move (AWT's `getTextLocation` after a composing update, web's
  textarea placement, iOS) got the previous frame's position. Done: the
  request measures the caret from the layout when asked
  (`SkikoInputMethodRequestTest`); null before the first layout. Observers
  still re-run on caret moves only, not on a scroll, left for 4.6 and 4.24 on
  the Mac. A real IME pass is 4.4. Android's cursor anchor info read `lastCursorMetrics` the same
  way until 4.30.
- [x] **4.20 Hardware keyboard dead keys on Android. S.** [Opus] [Lane F]
  `handleCharacterInput` inserts `utf16CodePoint` directly, with no handling of
  combining accents. Done: `DeadKeyComposer` (`input/DeadKeys.kt`) shows a dead
  key's accent as a composition, as the desktop input methods do, and the next
  character replaces it with the pair composed through
  `KeyCharacterMap.getDeadChar`, or, when they do not compose, commits the
  accent and types after it, as `EditText` does. Any other key commits the
  accent before it acts. `deadChar` is an `expect` whose skiko `actual` never
  composes (see the Mac queue). Tested in `DeadKeyTest` and `DeadKeyAndroidTest`;
  checked on an emulator through the virtual keyboard's Alt+E acute. A
  physical keyboard's layout is left for a person (QA plan, "Android
  keyboards").
- [ ] **4.23 Primary selection on Linux. S.** [Fable] [Lane B] Middle-click
  paste of the X11 primary selection. Compose's `Clipboard` covers only the
  system clipboard, but AWT exposes the primary selection as
  `Toolkit.getSystemSelection()` (null on Windows and macOS). A full version
  needs: a platform hook (an `expect` with a desktop `actual` over AWT and
  no-ops elsewhere, so [Mac work] to compile iOS); owning the primary
  selection whenever the user selects, offering the selected text lazily
  rather than copying on every drag step; and a middle click that moves the
  caret to the pointer and inserts the primary selection's plain text there,
  through the normal paste path so undo and line blocks behave. AWT under
  native Wayland has no primary selection (see 4.17), so it only works under
  X11 or XWayland.
- [x] **4.25 The skiko request ignores IME resync requests. S.** [Opus]
  [Lane E] `TextEditorState.requestImeResync` advances a generation that only
  the Android cursor sync consumes (`ImeCursorSync.android.kt`); the shared
  skiko request (desktop, iOS, web) never restarts its session or tells the
  platform IME. After an `EditBehavior` claims a newline or edits on top of a
  committed word (5.1), the IME's mirror of the text is stale, and its next
  `deleteSurroundingText` or `setComposingRegion` addresses the wrong
  characters. Consume the generation in `startSkikoInputSession`. Needed
  before 5.2 to 5.4 ship a behavior that edits on the IME path.
  Done: `startSkikoInputSession` watches the generation (now snapshot state)
  and hands each advance to the platform's `SkikoImeResync`. Desktop passes
  `None`: the AWT input method asks the request for text as it needs it and
  keeps no copy. Web passes `Rewrite`: Compose leaves a key's default action
  to the backing textarea and mirrors the editor back only when the value
  changes, so Enter leaving a list left the browser's line break in the
  textarea, where the next ranged `beforeinput` would read its offsets; the
  rewrite puts the editor's value and selection back. Verified in Chromium:
  Enter on an empty bullet in the Markdown demo left a 6243-character
  textarea with the caret at 753; the rewrite restored 6242 and 752. iOS
  keeps the default `None` for now, split out as 4.29. `RestartInput` exists
  for it and is tested. iOS compiled and rechecked in the simulator
  2026-09-30 with 4.19: typing, autocorrect, backspace, a list Return and
  Japanese candidates behave as in the 4.5 pass.
- [ ] **4.26 A behavior's edit mid IME batch. C.** [Fable] [Lane E] A
  behavior edits on top of an IME commit (5.1) at once, but a batch (an
  Android `beginBatchEdit`, a web `onEditCommand` list) may hold further
  commands the IME computed against its own mirror, and the resync only
  lands after the batch. A `setComposingRegion` or `deleteSurroundingText`
  after the commit then addresses the wrong characters. Defer the hook to
  the batch's end, or drop the batch's remaining offsets once a behavior
  has edited.
- [x] **4.29 iOS ignores IME resync requests. C.** [Opus] [Lane E] [Mac work]
  Compose's iOS connection absorbs a value change made during the keyboard's
  own edit (`TextInputConnection.edit` stores the post-edit value with
  `postponeSelectionUpdate` and tells UIKit nothing), so an `EditBehavior`
  that answers or edits on top of a keyboard command (Return leaving a list,
  Backspace demoting a bullet, 5.2's substitutions) never reaches UIKit, and
  the keyboard's autocorrect and capitalisation context keeps what it typed.
  The shared session offers `SkikoImeResync.RestartInput`, which restarts the
  input connection (the keyboard resets and may flicker, and a resync fires
  on every list Return), so it is not switched on blind. On the simulator:
  leave a list with Return and demote a bullet with Backspace, then type a
  word that autocorrects; compare with `RestartInput` passed in
  `TextEditorTextInputService.ios.kt`. Needed before 5.2 ships on iOS.
  Done 2026-09-30 at `7421c8e`, iPhone 17 Pro Max simulator, soft keyboard,
  Markdown demo: iOS keeps `None`. With `None`, Return at the end of a
  bullet continues the list, Return on the empty bullet leaves it, and "teh"
  then space on the plain line that follows becomes "The ", capitalised and
  corrected, so the keyboard's context survived the edit it was not told
  about. With `RestartInput` the same steps give the same text and keyboard
  state, and the log showed one input restart per claimed Return; the
  restart buys nothing visible here and costs a keyboard reset on every list
  Return. It works because UIKit reads the text live through `UITextInput`
  (`textInRange`, the selection) rather than from a copy, and Compose stores
  the post-edit value before the keyboard's next query. Not compared: the
  bullet demote, since the soft keyboard's Backspace never reaches the
  behavior on iOS (4.33), and 5.2's substitutions, which do not exist yet.
  Recheck both when they do. The demote, rechecked after 4.33: `None` holds.
- [x] **4.33 Soft-keyboard Backspace at a bullet's start joins lines on iOS.
  R.** [Opus] [Lane E] [Mac work] Found in the 4.29 pass. At the start of a
  bullet item the iOS keyboard's Backspace joins the item onto the line
  above (or onto the previous item) instead of demoting it, which the
  hardware key and the web do (`LineBlockEditBehavior.onBackspace`,
  `SkikoInputMethodRequestTest`'s demote test). Logged in the simulator:
  UIKit first sets the selection over the line break before the caret
  (`imeSetSelection(722, 723)`), then deletes the selection, which Compose
  sends as `commitText("")`; `deleteSurroundingTextInCodePoints` never
  arrives, so `deleteSurroundingRange` and its route to `backspaceAtCursor`
  are skipped. Treat a commit of nothing over a one-character selection
  that the keyboard set immediately before, starting just before the
  previous caret, as a backspace, or catch the line-break selection in
  `imeSetSelection`. Then recheck 4.29's resync with the demote.
  Done. Wider than the bullet: the log showed every soft-keyboard backspace on
  iOS arrives this way, mid-line too (`setSelection(750, 751)`, then
  `commitText("")`), so no iOS backspace reached `backspaceAtCursor`, its
  behaviors, or its undo run. UIKit selects the composed character before the
  caret, which is why a decomposed é went whole in 1.1. The skiko editing
  scope now remembers a selection the keyboard takes back from a collapsed
  caret and runs the commit of nothing that empties it next as a backspace
  (`KeyboardBackspace`, `imeBackspaceOver`); any other edit in between forgets
  it, and a selection that existed before is still deleted as a selection.
  Only a selection of exactly the one grapheme cluster before the caret (or
  the line break at a line start) counts, the unit UIKit takes for one press;
  a wider one, as a trackpad or Shift selection makes, is the user's and is
  deleted as a selection.
  `backspaceAtCursor(from)` deletes the keyboard's range when no behavior
  claims the edit, so iOS keeps removing the cluster its native editor would.
  `SkikoInputMethodRequestTest` covers the demote, a behavior mid-line, the
  keyboard's range, a line join, undo matching the hardware key, and the
  selections that are not a backspace. Simulator: Backspace at a bullet's
  start demotes it and a second joins the lines; mid-line it removes one
  character; "teh" then space afterwards still becomes "the", so 4.29's `None`
  holds with the demote too.
- [x] **4.30 Android's insertion marker lags a caret move. C.** [Opus]
  [Lane F] `PlatformTextEditorExtensions.android.kt` builds
  `CursorAnchorInfo.setInsertionMarkerLocation` from `lastCursorMetrics`,
  which the caret's draw writes. The flush that sends it is posted after the
  change, usually before the next frame, so a stylus handwriting or floating
  toolbar reading the marker gets the previous caret position. Measure with
  `calculateCursorPosition()` as the skiko request does since 4.19.
  Done: the marker is measured when the info is sent (`measureCursorMetrics`,
  `input/ImeCaret.kt`; `ImeCaretTest`), which the skiko request shares. A
  scroll that follows the caret lands after the send; resending then is 3.10.
  A stylus or floating keyboard pass is a person's (QA plan, "Android
  keyboards").
- [ ] **4.32 Keyboard settings on iOS and the web. C.** [Opus] [Lane E]
  [Mac work] 3.11 added `TextEditorState.keyboardSettings`, which only Android
  honours. `TextEditorTextInputService.ios.kt` passes fixed `ImeOptions` and
  web passes `ImeOptions.Default`; both should build them from the settings
  (`singleLine = false` and the four fields map one to one), route the
  request's `onImeAction` to `TextEditorState.performImeAction`, and
  restart the session when the settings change, as Android does. Web also
  forces `autocapitalize` to `sentences` (4.11), which should follow the
  settings' capitalisation.
- [ ] **4.31 Android's cursor anchor misses a view that moves alone. C.**
  [Opus] [Lane F] The anchor is resent when the caret moves in the view
  (3.10), but a view that moves on screen with nothing in the editor changing
  (a window panned by `adjustPan`, a `ComposeView` inside a scrolling Android
  parent, a freeform window dragged) goes unreported until the next caret
  move, so floating candidates stay where the view was. The same holds for a
  change in the strip a keyboard covers alone (Gboard switched to floating),
  which the marker's visibility flags depend on. `TextView` checks its screen
  location in an `OnPreDrawListener` while the IME monitors; watch the view
  the same way, only while monitoring.
- [x] **4.27 Android resyncs by restarting input. C.** [Opus] [Lane F]
  `requestImeResync` becomes `restartInput`, which clears the keyboard's
  suggestions and shift state. That suits a whole-document replace, not a
  smart-punctuation substitution beside the caret (5.2), which native
  `EditText` reports through `updateSelection` and a text-changed notice.
  Add a lighter resync for a local edit. Done: `ImeExpectation` follows where
  the keyboard's own commands left it expecting the selection. A resync
  restarts only when the selection is where the keyboard last heard it and
  not where its commands left it expecting, or where they cannot be followed
  (a key event, a delete counted in code points): the `InputMethodManager`
  drops a repeated report. Otherwise the ordinary report is enough
  (`ImeCursorSyncTest`, `ImeExpectationTest`).
  `invalidateInput` is no lighter under Compose: its connection wrapper does
  not forward `takeSnapshot`. Checked on an emulator (API 36, Gboard) through
  the `dumpsys input_method` start-input history: Enter at the end of a
  bullet continued the list with no restart and the keyboard shifted for the
  new item; Enter on the empty bullet and Backspace demoting a bullet each
  restarted once, as the rule says. So 5.2's "--" to a dash still restarts
  when the dash leaves the caret where the keyboard last heard it; a
  substitution of the same length, such as curly quotes, does not.
- [ ] **4.28 A Tab left to the focus system on iOS and the web. C.** [Opus]
  [Lane E] Since 2.9 the editor leaves some Tabs unconsumed: Ctrl+Tab, a Tab
  after Escape, and every Tab under `TabSettings.movesFocus`. On desktop the
  Compose focus system moves focus. On iOS an unconsumed Tab may reach UIKit's
  `insertText("\t")` and type a tab character when Compose has nowhere to move
  focus; on the web the input session's hidden text area may take the browser's
  default Tab and move DOM focus off the canvas. Confirm on both (the iOS half
  is in the Mac queue) and consume or drop the Tab in the session if so.

## Phase 5: writer conveniences

- [x] **5.1 A typed-text hook.** [Fable] [Lane G] `EditBehavior` covers
  newline, backspace, and forward delete only (`state/EditBehavior.kt`). Add a
  hook that sees committed text from every input path, keys and IME alike.
  Everything below in this phase builds on it, as opt-in behaviours. Done:
  `EditBehavior.onTextInput(state, text, range)` is told where committed
  text landed, after the default edit, by `insertTypedString` (keys),
  `imeCommitText` and `imeFinishComposing` over a typed composition (the
  shared skiko request and the Android connection; composing updates are
  never offered), and the accessibility insert. A
  behaviour edits on top, so a substitution undoes back to what was typed.
  A paste is not offered; 5.4's pasted-URL half needs its own seam.
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
  Tab and Shift+Tab at a list item's start are the chords to nest and un-nest
  it; since 2.9 Tab does nothing there (`handleIndent` in
  `input/BuiltinEditorActions.kt`).
- [ ] **5.7 Paragraph formatting.** [Fable] [Lane N] Paragraph spacing does not
  exist; rows stack with no gap. No per-paragraph alignment, indent, or line
  height. Global `textIndent`, `lineHeight`, and `textAlign` already work
  through `textStyle` (relevant to hammer-editor#927).
- [x] **5.8 Clear formatting and unlink** [Opus] [Lane D] actions.
  Done: `Action.ClearFormatting` on Ctrl+\ and Cmd+\ (Google Docs; Word's
  Ctrl+Space switches the input method) and `Action.Unlink`, unbound, through
  `TextEditorState.clearFormatting` and `unlink`, each one undo step. Clear
  formatting keeps heading and code block styles and, in a markdown editor, the
  body style and the link style on links; at a caret it resets the typing
  style. Unlink removes whole links the selection
  touches or the caret is in. See `docs/design/editor-actions.md`,
  "Formatting toggles".
- [ ] **5.9 A composition the editor ends is not offered. C.** [Fable]
  [Lane G] 5.1 offers a typed composition when the IME commits or finishes
  it, but the editor also ends one itself, with a bare `clearComposingRange`:
  a tap or drag outside it (`endCompositionIfPointerLeft`), focus loss, and
  the Android connection closing. The IME's later `finishComposingText` then
  finds nothing, so a behavior never sees the last word typed before a tap.
  Offering it there means a behavior may edit while the pointer is placing
  the caret, so decide who owns the caret first. Touches lane B's pointer
  handling and lane F's connection; do it when both are idle.

## Phase 6: undo and clipboard fidelity

### Undo

- [x] **6.1 One user action, one undo step. C.** [Fable] [Lane G] These take
  several today: typing or Enter over a selection (two), a rich paste of N list
  items (N+1), find's replace-all (one per match), `setLink` (two), IME
  composition updates (several per word, because only Insert and Delete
  coalesce and composition is a Replace). Done on 6.2: each runs in an
  `editGroup`, and the IME text commands are recorded as typing so a
  composition's updates and commit fold into the run they rewrite.
- [x] **6.2 A public grouping API.** [Fable] [Lane G] `withAtomicEdit` is
  internal and does not group history. Done: `TextEditorState.editGroup { }`
  runs its block as one revision and one undo step; every transaction now
  stages its recorded operations and lands them as one history entry at
  commit. `canUndo`/`canRedo` refresh at every commit rather than at layout.
- [x] **6.3 Style undo. C.** [Fable] [Lane G] A blind inverse over the same
  range, so undoing bold on a partly bold selection strips the bold that was
  already there (`state/TextEditManager.kt`). The formatting chords (2.1)
  apply over a partly styled selection, so this is one Ctrl+B and one undo
  away. Done: the entry records each touched line's character styles
  (`OperationMetadata.spanStylesBefore`) and undo applies an exact inverse,
  one operation per sub-range the style operation actually changed.
- [ ] **6.4 Restore the selection,** [Opus] [Lane G] not only the caret.
- [ ] **6.5 Pasted HTML blocks are unrecorded. C.** [Opus] [Lane G] Redo should
  restore the text without its blocks. Inferred; no test covers it.
- [ ] **6.6 Time-based coalescing breaks,** [Opus] [Lane G] and a configurable
  history cap.
- [ ] **6.14 An IME composition inherits the style it touches. R.** [Opus]
  [Lane G] A composition replace runs with `inheritStyle`, which takes every
  span merely touching the replaced range (`TextEditManager`, the resolve of
  inherited styles). Bold text, bold toggled off at the caret, then a composed
  word: its first update is plain, its second re-bolds it, so on a composing
  keyboard non-bold text cannot follow bold text. Inherit from the replaced
  characters themselves, and from the caret's typing style when there are
  none.
- [ ] **6.15 A style operation drops the line's paragraph style. C.** [Opus]
  [Lane G] `SpanManager.applySingleLineSpanStyle` and
  `removeSingleLineSpanStyle` rebuild the line from its text and character
  styles only, so Ctrl+B on a list or quote line loses the indent
  `ParagraphStyle` the block baked in, and nothing restores it (normalization
  only repairs placeholder lines). Carry `paragraphStyles` through; 6.3's undo
  then restores the line exactly.
- [ ] **6.22 A replace of nothing moves a block marker. C.** [Opus] [Lane G]
  `RichSpanManager.handleReplace` has no `stickyAtStart` case, so a `Replace`
  over an empty range at a line's start shifts that line's list, quote or
  heading marker off column 0, where an `Insert` at the same place keeps it.
  Found in 7.3, whose `setText` sends an insertion as an `Insert` to avoid it;
  `TextEditorState.replace` with a collapsed range still hits it.
- [ ] **6.23 Line endings are normalised per entry point. C.** [Opus] [Lane G]
  6.8 normalises in `insertStringAtCursor`, `replace`, `setText`, the IME and
  paste; an operation built directly and handed to `applyOperation` (the
  semantics `setText`'s insert, found in 7.3's rebase) skips all of them.
  Normalise once where `Insert` and `Replace` are applied.

### Clipboard

- [x] **6.7 Rich clipboard on Android, iOS, and web. C.** [Opus] [Lane H] Plain
  text only in both directions; bold and italic are lost even editor to editor.
  Desktop already writes HTML. The iOS half is 4.9. Done for Android and web:
  Android copies with `ClipData.newHtmlText` (the selection's markup beside its
  text) and the copy id in the description's extras, so `supportsCopyProvenance`
  is true there; a paste prefers each item's HTML, gives its block structure to
  the paste, and reads the clip once, since Android 12 and later tell the user
  each time an app reads another's clip (`androidHostTest/.../clipboard/AndroidRichClipboardTest.kt`).
  A copy too large for the binder with its markup falls back to the text alone,
  and this editor's own copy pastes the characters it copied when its markup
  would re-parse to others. Copy ids now start at random, as they leave the
  process. The web carries markup both ways since 4.13. Editor to editor round trips keep
  what the HTML path carries (bold, italic, code, strike, underline, headings,
  lists, quotes, fences, links), and the body size through 6.18. iOS stays plain
  until 4.9 (Mac queue).
- [x] **6.8 Line endings. C.** [Opus] [Lane H] No `\r` handling anywhere; a
  CRLF paste leaves stray carriage returns in lines. Done: `\r\n` and a lone
  `\r` become `\n` on every entry path (`setText`, `insertStringAtCursor`,
  `replace`, typed strings, IME commits and compositions, the semantics insert,
  paste, markdown and HTML parsing), spans kept on their characters
  (`clipboard/LineEndingsTest.kt`). Copy writes `\n` and leaves native endings to
  the platform clipboard layer, as native editors get them: AWT's Windows flavor
  map converts its text flavors to CRLF and back, and macOS, Linux, Android and iOS
  use LF. `setDocument` takes lines as given.
- [x] **6.9 Links in HTML. C.** [Opus] [Lane H] No `href` handling on paste or
  copy. Done: `<a href>` becomes a `LinkSpanStyle` over its text, with the
  configured link style, on paste (spliced lines included) and in
  `HtmlExtension.importHtml`; copy and `exportAsHtml` write `<a href>` for each
  link, leaving out the tags the link style alone would add. A link across a
  `<br>` becomes one per line. `html/HtmlLinks.kt` `sanitizeLinkUrl` keeps
  relative URLs and the http, https, mailto, tel and ftp schemes and refuses the
  rest (`javascript:`, `data:`, `vbscript:`, `file:`), after dropping the tabs,
  line breaks and edge controls browsers ignore, in both directions; a refused
  link keeps its text (`html/HtmlLinkTest.kt`). Other sources are 6.16.
- [ ] **6.16 Link destinations are sanitised only in HTML. S.** [Opus] [Lane B]
  Markdown import, `setLink` and the in-editor span buffer keep `javascript:` and
  `data:` destinations, and Ctrl/Cmd+click hands them to `onLinkClick` or the
  `UriHandler` (`textEditorPointerInputHandling.kt`). Refuse them where a link is
  opened, with `html/HtmlLinks.kt`'s `sanitizeLinkUrl`, so every source is covered.
- [x] **6.10 Non-breaking spaces** [Opus] [Lane H] become plain spaces on HTML
  paste. Done: a no-break space between two characters of one text node lands
  as U+00A0 ("10&nbsp;km"), and copy writes it as `&nbsp;` (plain text keeps
  U+00A0). Sources also write `&nbsp;` to keep an ordinary space from
  collapsing. Safari and Word mark those (`Apple-converted-space`,
  `mso-spacerun`), so markup carrying either mark keeps every unmarked one;
  otherwise one at a text node's edge or beside an ordinary space (Chrome's
  `&nbsp; ` pairs, Google Docs at a span's start) is an ordinary space. Only
  HTML's own whitespace collapses, so U+202F, U+2007 and U+3000 are content. The
  serializer writes runs of spaces, tabs, line-edge spaces and no-break spaces
  the parser would misread under `white-space:pre-wrap` rather than as `&nbsp;`,
  so editor round trips are exact, headings included
  (`html/NonBreakingSpaceTest.kt`). Inline code is dropped only inside a `<pre>`
  element, not under that CSS, so monospace text under it now pastes as code.
- [x] **6.11 Large paste is quadratic. C.** [Opus] [Lane H] One full line-list
  copy per pasted line. Done: multi-line insert, replace and delete splice the
  line list once through `TextEditorState.replaceLines`; `splitAnnotatedString`
  gives each span only the lines it covers instead of checking every span on
  every line; and an Insert's offset transform and the rich-span pass for a
  Replace work out where the new text ends once per operation, not once per span
  they move. Other multi-line edits still copy per line: 6.17.
- [ ] **6.17 Multi-line style and block edits copy the line list per line. S.**
  [Opus] [Lane G] `TextEditorState.setLine` copies the whole line list, and the
  multi-line style path in `TextEditManager.applyStyleOperation` and
  `applyLineBlockState` call it once per line, so Ctrl+B or a list toggle over a
  long selection (and their undo and redo) is O(lines x document). Stage the
  line list once per transaction, or collect the lines and write them with
  `replaceLines` as 6.11 did for paste.
  `state/LargePasteCostTest.kt` counts the lines written
  (`TextEditorState.linesWritten`) and shapes for a 400-line paste at the caret,
  over a selection, and through undo and redo.
- [x] **6.18 A styled paste from markup drops the body text size. R.** [Opus]
  [Lane H] Pasted text that carries spans keeps only its own, so HTML from
  another application (desktop) or from the editor itself (web, which has no
  in-process flavor) lands without the markdown body style's size and renders
  smaller than the text around it; plain text inherits it (QA 2.5). Seen in the
  web demo copying "plain **bold** end" to a new line. Layer the configuration's
  `defaultTextStyle` beneath styled pasted text when the editor has a markdown
  configuration, as the markdown parser does. Done: a styled paste takes the
  styles that size text where it lands (the body style, a heading's, a host's
  own size) beneath its own spans (`clipboard/PasteStyle.kt`), so it matches the
  text around it and its own sizes still win; `importHtml` puts the body style
  under its text as markdown import does (`clipboard/RichPasteBodyStyleTest.kt`).
- [ ] **6.19 Cut deletes before the clipboard write can fail. S.** [Opus]
  [Lane H] `cutSelection` deletes the selection and then writes the clipboard in
  a coroutine. On the web the context menu's Cut writes through
  `navigator.clipboard`, which an insecure page, a lapsed user gesture or a
  refused permission turns into a logged warning, and the cut text is gone
  except through undo. Write first and delete on success, or refuse Cut where
  the write cannot happen.
- [x] **6.12 Drag and drop** [Opus] [Lane H] of the selection, and drops of
  external text. Done on desktop: a mouse press inside the selection is held;
  moving past the slop starts a platform drag of it (Compose's
  `DragAndDropSourceModifierNode`), and coming up in place puts the caret there.
  Only a press over a selected character is held, not one beside the selected
  lines. The drag offers what a copy offers (HTML and plain text; AWT serializes
  object flavors even within the process, so the exact `AnnotatedString` does not
  survive a drop) and a random drag id, as a move or, with the platform's
  modifier, a copy; a move another application takes removes the text here, if
  it is still there. The editor accepts drops of text
  (`DragAndDropTargetModifierNode`), draws a drop caret while one hovers, and
  inserts and selects the dropped text as one undo step, restoring its blocks
  from the markup as a paste does; a drop carrying its own drag id moves the
  selection unless it lands inside it (`dragdrop/`, `dragdrop/*Test.kt`). A
  read-only editor lets its text be dragged out as a copy only. Where no drag
  can start, the press selects as before. Android, iOS and web are 6.20; rich
  spans on a moved range are 6.21.
- [ ] **6.21 A dragged move drops the text's rich spans. S.** [Opus] [Lane H]
  `dropText` deletes the source range and inserts the dragged text, so rich
  spans the HTML cannot carry (highlights, comments, a host's own) are lost
  where cut and paste keeps them through `copyRichSpans` and `pasteRichSpans`.
  Carry them the same way, keyed by the drag id.
- [ ] **6.20 Drag and drop on Android, iOS and web. S.** [Opus] [Lane H]
  `dragdrop/PlatformTextDrag` has desktop actuals only. Android: build the
  transfer from `ClipData.newHtmlText` with `View.DRAG_FLAG_GLOBAL`, read drops
  from `toAndroidDragEvent().clipData` and its `x`/`y`, and start a drag from a
  long press inside the selection (lane B's touch handling). iOS and web: check
  what Compose Multiplatform's `DragAndDropEvent` exposes there (web has a
  `WebDragAndDropManager`) and fill in the same four functions.
- [x] **6.13 Plain paste reads the HTML flavor.** [Opus] [Lane H] On desktop,
  `Action.PasteAsPlainText` takes `ClipboardHelper.getText(...).text`, so a
  foreign paste that offers HTML yields the text of the parsed markup rather
  than the source's own `text/plain` flavor. Add a plain read to
  `ClipboardHelper` (an `expect` member, so it joins the Mac queue). Done:
  `ClipboardHelper.getPlainText` reads the plain flavor (desktop's string flavor,
  Android's item text, `UIPasteboard.string`, `navigator.clipboard.readText`),
  and on desktop falls back to the parsed markup's text only when there is no
  plain flavor (`e2e/PlainPasteE2eTest.kt`). The iOS actual compiled and
  pasted in the simulator 2026-09-30: iOS's edit-menu Paste reads through it,
  and text on the general pasteboard pasted as is, 200k characters included.

## Phase 7: reach

### Accessibility

- [x] **7.1** [Opus] [Lane M] `RichTextView` has no semantics at all.
  Done, as a text view publishes itself (`BasicText`, a selectable
  `TextView`): its text, with links as `LinkAnnotation.Url` opening through
  `onLinkClick`, and its whole-document text layout. A selectable view adds the
  selection range, `setSelection`, copy while there is a selection, and a long
  press that opens its menu, a click that focuses it, and stays focusable; it
  offers nothing that edits. The semantics sit on the node that carries the
  host's `modifier` and the focus, as in the editor, so a host's own semantics
  (a content description) join the same node.
- [x] **7.2** [Opus] [Lane M] A disabled editor still exposes `setText` and
  `insertTextAtCursor`, and is still focusable, contrary to its KDoc.
  Done: a disabled editor reports `disabled()` and `isEditable = false`, and
  offers no `setText` or `insertTextAtCursor` (`EditorSemantics.kt`). It stays
  focusable, deliberately: its text can be selected, and focus is what routes
  the copy and select-all shortcuts to it. The KDoc now says so. Also fixed:
  the published text went stale after an edit that left the caret in place
  (a forward delete), since the document is not snapshot state; the semantics
  block now reads `lineOffsets`, which every edit republishes (since 7.25, the
  state's `revision`).
- [x] **7.3** [Opus] [Lane M] The semantics `setText` calls `state.setText`,
  wiping rich spans and undo history.
  Done: it replaces only the part that differs (the text between the common
  prefix and suffix, never splitting a surrogate pair) as one undo step on top
  of the history, never recorded as typing. Styles and rich spans outside that
  part survive (a pure insertion is an insert, so a line's block marker stays
  at its start), the new part takes the style typing there would, the caret
  ends after it, and the new part is then offered to the edit behaviors as
  `insertTextAtCursor`'s text is (`replaceAllAsEdit` in `EditorSemantics.kt`).
  The incoming text's own formatting is applied only inside the changed part.
- [x] **7.4** [Opus] [Lane M] Missing: `getTextLayoutResult`, copy, cut, and
  paste actions, content description, and any structure (headings, links,
  lists).
  Done, against `BasicTextField`'s semantics: copy while there is a
  selection, cut while enabled with one, paste while enabled, all through the
  action registry; `onLongClick` focuses and opens the context menu;
  `textCompositionRange`; a `contentDescription` parameter on both editor
  composables, since a host's `Modifier.semantics` lands on a container around
  the editable node, not on it; links published as `LinkAnnotation.Url` in the
  text, which TalkBack lists and opens through the host's `onLinkClick` (only
  when there is one, so no link is offered that cannot open).
  `getTextLayoutResult` measures the whole document on request (cached per
  revision, width and style) the way the editor measures a line, each line its
  own paragraph, so line navigation matches the editor's rows; its geometry
  leaves out the content
  padding, the scroll offset and block span heights (7.36). `onImeAction` is
  offered only for an action key the host chose (3.11's
  `KeyboardSettings.imeAction`); the default is Enter, where
  `BasicTextField`'s default action is a no-op. Headings and lists
  stay unexposed: an editable node is one text, and native editors
  (`EditText`, `UITextView`) do not expose them either.
- [ ] **7.36** [Opus] [Lane M] The semantics text layout (7.4, 7.1) is measured
  apart from the editor's rows, so its character bounds are offset by the start
  and top content padding (in `RichTextView` too) and the editor's scroll
  offset, and rows below an image or rule
  sit higher than drawn. Screen readers that draw a highlight from character
  bounds (Select to Speak, braille cursors) place it wrong on a scrolled or
  padded editor. Compose offers no way to translate a `TextLayoutResult`; a fix
  needs the semantics node's inner coordinates to follow the content origin.
  It is also shaped whole on the first request after each edit, so a screen
  reader that asks for caret bounds after every keystroke (NVDA through the
  Java Access Bridge) pays a whole-document shape per keystroke on a long
  document.
- [ ] **7.37** [Opus] [Lane E] Turning input back on while the editor keeps
  focus (`enabled` or, since 7.13, `readOnly` switched off) marks it focused
  but starts no input session until the next tap, by design, so the soft
  keyboard does not rise unasked. Desktop dead keys and IME composition, and
  Android and iOS IME text, do nothing until then. Start a session that shows
  no keyboard, or document the host's `requestFocus` after the toggle.
- [x] **7.38** [Opus] [Lane K] `SpellCheckingTextEditor` has no `readOnly`
  or `lineLimits` (7.13), and its corrections are menu items that call
  `correctSpelling` directly, past `ContextMenuActions`' editable gate.
  Forward both, and offer no corrections while read-only.
- [ ] **7.39** [Opus] [Lane H] On the web a disabled or read-only editor (7.13)
  has no input session, so no backing text area receives the browser's `copy`
  event and `ClipboardEventsEffect` answers nothing: Ctrl+C falls back to
  `navigator.clipboard`, plain text only, and fails where the page may not
  write the clipboard.
- [ ] **7.40** [Opus] [Lane F] A single-line editor (7.13) still asks the
  soft keyboard for multi-line text (Android's `TYPE_TEXT_FLAG_MULTI_LINE`;
  iOS the same), so with the default `KeyboardSettings.imeAction` the keyboard
  shows a return key that now does nothing. A single line should ask for
  single-line text and default its action key to Done, and a hardware Enter
  should run `onImeAction` as the keyboard's action key does.
- [ ] **7.41** [Fable] [Lane C] No soft-wrap toggle: every line wraps at the
  viewport width, so a code editor cannot keep a line whole and scroll
  sideways, and `EditorLineLimits.SingleLine` (7.13) wraps and grows where
  `BasicTextField`'s single line scrolls. Split out of 7.13 because it is not
  contained: layout would measure lines with an unbounded width (the tight
  `lineConstraints` in `updateBookKeeping`), and a horizontal scroll offset
  would have to join the vertical one everywhere that one is applied today
  (about 14 files: `DrawEditorText`, `DrawSelectionUi`, `DrawRichSpanUi`,
  `DrawPlaceholderText`, the caret and its `CursorExt`, hit testing in
  `textEditorPointerInputHandling`, `DragAutoScroll`, `TouchToolbar`, the
  handles and magnifier, the skiko input method's rectangles), with a
  horizontal `ensureCursorVisible`, a horizontal scrollbar on desktop and
  web, and horizontal `scrollable` input. Then `SingleLine` becomes one row
  that scrolls sideways.

### Right-to-left and bidirectional text

- [x] **7.5** [Fable] [Lane A] Arrow keys are logical, so visually inverted in
  right-to-left text. `BasicTextField` is logical too, so it is no reference
  here. Collapsing a selection with Left or Right (1.4) goes to its logical start
  or end; `BasicTextField` swaps the two in a right-to-left paragraph.
  Done, by paragraph direction, which is also what `BasicTextField` does once
  its style resolves a direction from the content: in a right-to-left paragraph
  the arrow keys mirror (`TextEditorKeyCommandHandler`): Left and Right swap,
  Ctrl+Left performs the platform's forward word motion
  (`KeyBindings.wordForward`: the word end, or the next word start on Windows)
  and Ctrl+Right the word start, Cmd+Left and Cmd+Right swap line start and
  end, and the 1.4 collapse follows the mirrored key. Home, End, the Emacs
  chords, the deletes and the vertical moves stay logical. Direction is the
  paragraph's `getParagraphDirection`, which with Compose's default
  `TextDirection.Unspecified` is the app's layout direction: an all-Hebrew
  paragraph in a left-to-right app is left-to-right based and stays logical,
  exactly as `BasicTextField` is; a host that sets `TextDirection.Content` on
  the editor's text style gets the mirroring (7.32). Mixed runs inside a
  paragraph stay logical (7.33).
- [x] **7.32** [Fable] [Lane M] The editor's text style leaves `textDirection`
  unspecified, so the paragraph direction is the app's layout direction: a
  right-to-left paragraph in a left-to-right app is laid out left-to-right
  based (aligned left, caret motion logical), and an English paragraph in a
  right-to-left app is right-to-left based, with the 7.5 mirror on its arrows,
  unless the host sets `TextDirection.Content`. `BasicTextField` behaves the
  same, but native editors resolve each paragraph from its first strong
  character. Decide whether the editor should default to `Content`, or
  document the host's job. Decided: keep following the app's layout
  direction, as `BasicTextField` does; the `textStyle` KDoc on
  `TextEditorStyle` tells hosts to set `TextDirection.Content` for
  per-paragraph direction.
- [ ] **7.33** [Fable] [Lane A] Arrow keys inside a mixed paragraph (a Hebrew
  word in English text, or the reverse) move logically, so the caret jumps
  visually at the run boundaries. macOS and Windows move visually through the
  runs, with the caret carrying a direction at each boundary; `BasicTextField`
  is logical here too. Needs `getBidiRunDirection` and a run-aware step, and a
  visual caret position at run boundaries.
- [ ] **7.6** [Fable] [Lane C] Selection draws one rect per row from x(start)
  to x(end); wrong in right-to-left, and mixed text needs several rects.
- [ ] **7.7** [Fable] [Lane C] Underline boxes (spell check, composing, links)
  assume no bidi.

Hit testing and caret x already delegate to Compose and should be correct.

### Performance on long documents

Shaping is one line per keystroke. These still scale with document length:

- [ ] **7.8** [Fable] [Lane N] Per keystroke: the line list is copied, every
  `LineWrap` is rebuilt, and every rich span is re-anchored. Measured on the
  iOS simulator (4.21): one keyboard edit takes 9.4 ms at 200k characters
  against 0.7 ms at 2k, wherever the caret is.
- [ ] **7.9** [Opus] [Lane N] `getAllText()` rebuilds the whole document per
  revision when read by semantics, the Android IME, and the desktop adapter.
  214 µs per revision at 200k characters on the desktop JVM (4.21).
- [ ] **7.10** [Opus] [Lane N] Any viewport change, height-only included,
  reshapes the entire document. This is every soft keyboard open and close.
- [ ] **7.11** [Opus] [Lane N] Linear scans per frame or event: visible-line
  lookup in drawing, unculled selection drawing, `getWrappedLineIndex`,
  `getOffsetAtPosition` on each drag move. On the iOS simulator at 200k
  characters (4.21), an idle caret-blink frame takes 33 ms, two vsyncs,
  where a 2k document stays under one; typing frames reach 137 ms.
- [ ] **7.12** [Opus] [Lane N] `moveRight` and `moveToNextWord` sum all line
  lengths though `getTextLength()` is constant time.

### Editor configuration

- [x] **7.13** [Opus] [Lane M] Missing: read-only with a caret, single-line
  mode, min and max lines, auto-grow (the editor forces `fillMaxSize`), max
  length, an input filter, a soft-wrap toggle with horizontal scrolling.
  The soft keyboard options are `TextEditorState.keyboardSettings` since
  3.11, honoured on Android; iOS and web are 4.32.
  Progress, in chunks:
  - Read-only with a caret: done. `readOnly` on both editor composables: the
    caret shows and blinks, and moves and selects from the keyboard, pointer
    and screen readers; no edit reaches the document by any path (keys,
    menu, touch toolbar, semantics, IME), no input session starts, so no
    soft keyboard; copy stays; a tap shows the caret handle. Semantics say
    editable false, not disabled. `TextEditorState.hasFocus` is now public
    (focus whether or not the editor takes input), and the focus border
    follows it. Found: 7.37, 7.38, 7.39.
  - Sizing: done. `lineLimits: EditorLineLimits` on both editor composables:
    `Fill`, the default and the old behaviour, or `MultiLine(minLines,
    maxLines)`, as tall as its laid-out rows between the limits (a row being
    one line of the text style), plus the vertical content padding, within
    the host's constraints, then scrolling. A host's `fillMaxSize` still
    wins, as a fixed height does. The rows follow the width, which only
    layout settles, so the height follows one pass later on a width change;
    each row added changes the viewport height, which reshapes the whole
    document (7.10). Without `maxLines` in a parent that does not bound the
    height, it takes the whole document, capped at the largest height layout
    represents. A scroll animation now clamps each frame to the scroll range,
    which shrinks as the editor grows.
  - Maximum length, input filter, single line: done.
    `TextEditorState.inputFilter: EditorInputFilter?` screens every edit
    that adds text in `TextEditManager.applyOperation`, the one choke point:
    typing, IME, Enter, paste, drop, semantics, and the host's editing
    functions, but not undo, redo or document loads; deletions always pass.
    A filter returns the text to insert, changed, or null to refuse, and a
    change or refusal asks the IME to resync. `EditorInputFilter.maxLength`
    cuts to what fits, as Android's `LengthFilter` does (never through a
    surrogate pair), rather than refusing as `BasicTextField`'s does, and
    publishes `maxTextLength` to accessibility services; filters chain with
    `then`. `EditorLineLimits.SingleLine` adds `EditorInputFilter.SingleLine`
    (Enter refused, line breaks in text become spaces) and sizes like
    `MultiLine()`; it wraps and grows, since there is no sideways scrolling.
    Enter is consumed, not handed to the host or the IME action (3.11).
    Entry points whose caret, composition or styling depend on what lands
    (typing over a selection, Enter, the IME's commit and composition, paste,
    the semantics insert and set text) screen first over the whole range
    they replace, then apply under `alreadyScreened`; a refused edit leaves
    the selection it would have replaced, a paste the filter changed lands
    plain, and the behaviors are told what landed. A change that does not
    lengthen the document passes the maximum even over it. Found: 7.40.
  - Soft wrap off with horizontal scrolling: not contained, so split out as
    7.41. The item is done apart from it and the keyboard options (3.11).

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
- [x] **7.26** [Opus] [Lane J] Regex replace inserts the replacement
  literally; `$1` and named groups are not expanded.
  Done: with regex on, the replacement uses Kotlin's `Regex.replace` syntax
  (`$0`, `$1`, `${name}`, backslash escapes), with each match's groups read before
  any edit. A reference to a group the pattern lacks, where `Regex.replace` would
  throw, is inserted as written: the groups are only known once a match exists,
  and the result is visible and one undo away (`FindRegexReplaceTest`).
- [x] **7.27** [Opus] [Lane C] Decorations take part in span hit testing
  (`findSpanAtPosition`), ranked above line markers. While find in selection
  is on, its scope decoration answers clicks on list, blockquote, and code
  fence markers inside it. A decoration needs a way to opt out.
  Done: `RichSpanStyle.isHitTestable`, default `true`, and false spans are
  skipped by `findSpanAtPosition`; the find scope opts out, and so do the
  find match highlights, which took marker clicks the same way. Decorations stay
  hit-testable by default because spell check (`SpellCheckStyle`, the
  diagnostics style) answers clicks with its suggestions
  (`spans/SpanHitTestTest.kt`, find's `FindScopeHitTestTest.kt`).

- [x] **7.29 Find in selection loses its scope. C.** [Opus] [Lane J]
  `FindState.search` records `selectionBeforeSearch` unless the selection equals
  a current match, and a query with no results empties the match list while the
  selection still sits on the last match. Select a paragraph, type "cat", then
  "catx", then back to "cat": the recorded selection is now the "cat" match, so
  turning on find in selection scopes to that one word. Compare against the
  last range the find session selected instead.
  Done: `FindState` remembers the selection object it last left (a match, or none
  after `clearSearch`) and compares by identity, so clearing the query keeps the
  scope too, and reselecting the match's range yourself counts as your own
  (`FindInSelectionTest`).
- [ ] **7.42** [Opus] [Lane J] `FindState.selectionBeforeSearch` is a plain range
  that does not follow edits. After Replace moves on to the next match, turning on
  find in selection scopes to the old offsets, which can point at other text or
  past the end of a line. Keep it as a tracked decoration span, like the scope,
  or drop it on any edit.

- [ ] **7.43 Line breaks inserted into a line block. R.** [Opus] [Lane I]
  Text with a line break inserted into a list item or other line block leaves
  the new line as body text, where Enter at the same spot continues the block
  (and Word and Google Docs keep both halves in the list). Seen with find's
  regex replace of ", " by `\n` in "- a, b", which exports "- a" then "b".
  Check programmatic `replace` and plain paste too; fix it in the edit
  pipeline, not in the find addon.

### Spell check addon

- [ ] **7.20** [Fable] [Lane K] Sentence mode: sentences run across line
  boundaries, offsets shift on indented lines, and each partial check rescans
  the whole document. Tested only against fakes.
- [x] **7.21** [Opus] [Lane K] No ignore list or language API in
  `EditorSpellChecker`; add to dictionary exists only as a host menu extension
  (hammer-editor#861).
- [x] **7.22** [Opus] [Lane K] Hard-coded English strings ("Loading...", "No
  suggestions").

- [x] **7.28** [Opus] [Lane K] Ignore and Add to dictionary match the exact
  string, so ignoring "kotlinx" leaves "Kotlinx" at a sentence start flagged.
  Match case-insensitively, or at least across a capitalised first letter.
- [x] **7.30** [Opus] [Lane K] `SpellCheckingTextEditor` does not forward
  `onLinkClick` or `onRichSpanClickEvent` (1.15) to the editor it wraps, so
  spell-checked editors have no link convention and no modifier state.
- [x] **7.31** [Opus] [Lane K] Since 1.5 words come from ICU, which breaks
  letters at a period: "U.S.A." reaches the checker as U, S and A, and the s
  of "U.S.'s" on its own. Skip one-letter segments, or rejoin an abbreviation
  before the lookup, so typeset abbreviations stop drawing squiggles.
- [x] **7.34** [Opus] [Lane K] A tap on a flagged word or a diagnostic opens
  `SpellCheckingTextEditor`'s menu at the span click's offset, which is in the
  text canvas's coordinates, so the menu sits the start padding (16 dp by
  default) left of the word. A right-click is re-anchored by the editor since
  2.10; a tap is not. Convert it through the layout, as `ContextMenuPlacement`
  does.
- [x] **7.35** [Opus] [Lane K] Shift+F10 and the Menu key (2.10) open the
  standard menu even with the caret in a flagged word, so a keyboard user never
  reaches its suggestions, Ignore or Add to dictionary. Register over
  `editor.showContextMenu` in `SpellCheckingTextEditor` to open the spell check
  menu for the span at the caret, and the standard one elsewhere. Done through a
  hook instead, `TextEditorContextMenuState.onOpenedAtCaret`, which the editor
  calls once the keyboard's menu is open.
- [ ] **7.44** [Opus] [Lane K] `correctSpelling`, `applySentenceCorrection` and
  `applyFix` drop the flag before the replacement, which `TextEditorState.inputFilter`
  or a single-line limit (7.13) may cut short or refuse: the word is left unflagged and
  uncorrected, or partly replaced, until the next re-check. Keep the flag when the
  filter changes the replacement.

### Host API

- [x] **7.23** [Opus] [Lane M] No `Saver`, so state is lost where
  `rememberSaveable` would keep it.
  Done: `rememberSaveableTextEditorState(initialText, richSpanStyleSaver)`. The
  state cannot be built outside composition (7.24), so there is no standalone
  `Saver` for hosts; its saver is internal and captures the composition's scope
  and measurer. It saves nested lists of strings and numbers (Bundle-safe, and
  tested through Java serialization): the lines; character styles' plain values
  (colour, size, weight, style, decoration, background, letter spacing,
  baseline shift, feature settings, a generic font family); the built-in rich
  spans (lists, quotes, fences, rules, headings, links); each line's paragraph
  styles in order, a block's by name so it stays equal to the one the block
  strips, and stacked blocks nest as before; other paragraph styles' indent,
  line height, alignment, direction, line breaking and hyphenation; the caret,
  the selection, and the line at the top of the viewport, scrolled back to on
  first layout, since a pixel offset means nothing after a rotation. Not
  saved: loaded fonts, brushes, shadows and other values that are not plain;
  undo history (a restored editor starts without, as after `setDocument`;
  serializing operations and their span metadata is large and would crowd the
  Bundle), decorations (their owners recompute them), and other rich span
  styles unless the host's `richSpanStyleSaver` keeps them (the sample keeps
  images this way). Markdown was not used: it loses underline, colour and size
  (7.16) and needs the extension attached.
- [ ] **7.24** [Fable] [Lane M] The state needs a `TextMeasurer` and a scope,
  so it cannot be created outside composition.
- [x] **7.25** [Opus] [Lane M] No word count, no programmatic focus beyond
  `autoFocus`, `cursorDataFlow` has no initial value.
  Done: `TextEditorState.wordCount` counts the word segments holding a letter
  or digit in the ICU word breaks that word motion and spell check use, so
  "don't" is one word, "self-aware" two, emoji and punctuation none, and CJK
  counts dictionary words (a word processor counts by spaces instead). It is
  observable in composition and kept per line: a recount segments only the
  lines that changed, found by identity from both ends. `wordCount(range)`
  counts the words a range touches. Focus from code is the Compose convention:
  a `FocusRequester` on the editor's `modifier` (both composables), now
  documented and tested; no state method, since focus belongs to the
  composable. `cursorDataFlow` emits the current `CursorData` on collection,
  and `cursorData` reads it directly. The document gained an internal
  snapshot-state `revision`, advanced by every published revision, which the
  word count and the semantics read to recompute.

## Housekeeping

- [ ] The README's "Work left to do" is stale: desktop copy and paste now
  preserves formatting.
- [ ] `TextEditorScrollManager.scrollToCursor()` is public and bypasses
  `cursorScrollSuppressed` (1.23); only `ensureCursorVisible` honours it.
- [ ] `TextEditorScrollManager.scrollToPosition(offset, animated = false)`
  animates anyway unless `top` is set: the path that scrolls just far enough
  always calls `animateScrollTo` (found in 3.9).
- [ ] `imeSetComposingRegion` (`input/ImeEditLogic.kt`) clears the composition
  when an IME passes the bounds reversed; `BaseInputConnection` orders them.
  Found in 4.27, whose expectation follows the contract.
- [ ] Stray `println` calls in `state/TextEditorState.kt` and
  `SpellCheckState.kt`.
- [ ] The sample app's toolbar Link button attaches its own
  `sample.LinkSpanStyle`, not the library's, so those links get no hand icon
  and do not open on Ctrl+click (1.15). Markdown-parsed links do.
- [x] `docs/design/text-input-sessions.md` describes iOS as routing through
  the shared IME logic; it does not yet (4.2). True since 4.2's Linux part.
- [ ] `getOffsetAtCharacter` returns a negative char for negative input.
- [x] `rememberTextEditorStyle` leaves `backgroundColor` out of its `remember`
  keys, so a new background colour is ignored until another key changes.
- [ ] The document content is not snapshot state, so the skiko input session
  (4.2) collects `editOperations` and `documentGeneration` to bump a
  snapshot-backed revision its text reads fold in. The bump lands one
  dispatch after the caret move, so a keystroke evaluates the platform's
  `snapshotFlow` twice, and each evaluation builds the whole text. A
  revision advanced from `TextEditorState.onCommit` would land in the same
  apply batch as the caret, give every snapshot observer the same signal,
  and need no collectors; do it when `state/TextEditorState.kt` is next open
  (lane N). Half done in 7.25: `TextEditorState.revision` is that counter,
  advanced wherever `content` is published; the skiko session (lane E) still
  collects its own.

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
| 1.1 | `./gradlew :ComposeTextEditor:compileKotlinIosSimulatorArm64`. `skikoMain/.../state/TextBreaks.skiko.kt` is the iOS `actual` for the break cursors (`org.jetbrains.skia.BreakIterator`, `org.jetbrains.skia.icu.CharProperties`); if it does not compile, the fix is in that file. Then in the iOS sample app: type an emoji, a family ZWJ sequence, a flag and a keycap, and backspace through each; type "e" then a combining acute (or Vietnamese "ế") and backspace once; arrow Left and Right across them; type Japanese and step through it | Backspace removes each emoji sequence whole and only the accent off its base; Left and Right never stop inside a sequence; no half character ever shows |  Partial, 2026-09-30 at `78bf021`, iPhone 17 Pro Max simulator, iOS 26.0. Compiles. Soft-keyboard backspace removes 😀, 👨‍👩‍👧, 🇯🇵, 1️⃣, precomposed ế and each kanji in one press each, and no half character ever shows. e + U+0301 goes whole in one press, not accent first; Safari's native address field in the same simulator does the same, so this matches iOS but not the rule written in 1.1. Compose sends that press as `deleteSurroundingTextInCodePoints(1, 0)`. Left and Right not run: they need a hardware keyboard, which the simulator tools here cannot drive. Left for a person |
| 1.5 | The same iOS compile as 1.1 covers `wordCursor`. In the iOS sample app: Option+Left and Option+Right with a hardware keyboard through "don’t stop", "日本語を勉強します" and "a 😀 b"; double-tap "don’t" and an emoji | Option arrows stop at word ends and starts only, keeping "don’t" whole, stepping Japanese by dictionary word and stopping at the emoji; a double-tap selects the whole contraction or the whole emoji |  Partial, same run. Double-tap selects "don’t" whole and 😀 whole. **Fails:** double-tap on 勉 in 日本語を勉強します selects only 強, so iOS word breaks split kanji per character. Desktop, on the same skia `actual`, segments 勉強 (`TextEditorWordSegmentationTest`), so skia's ICU on iOS seems to lack the CJK dictionary; `NSString` word enumeration or `CFStringTokenizer` would have it. Option+Left and Option+Right not run: hardware keyboard, left for a person |
| 2.6 | In Safari and Chrome on macOS, open the wasm demo and press Ctrl+A, E, F, B, N, P, D, H and K in a paragraph. The page's hidden text area has the same Cocoa Emacs bindings, so a chord could act twice | Each chord moves or deletes once, as in the desktop sample app | Not run: needs a person at a real keyboard. Browser automation injects key events below the Cocoa text system, so it cannot reproduce a chord acting twice |
| 4.6 | With a hardware keyboard in the iOS sample app (a person: the simulator tools here cannot press arrow keys), press Up and Down through a wrapped paragraph and a heading | Each press moves the caret one drawn row, once. `verticalPositionFromPosition` now answers from the unstyled layout, so a double move or a move by an undrawn row would come from UIKit handling the arrow through `UITextInput` as well | |
| 2.9 | In the iOS sample app with a hardware keyboard: press Tab, Ctrl+Tab, then Escape followed by Tab; then set `state.tabSettings = TabSettings(movesFocus = true)` on the demo editor and press Tab | Tab indents by four spaces; Ctrl+Tab, Escape then Tab, and Tab under `movesFocus` either move focus to another control or do nothing, and never type a tab character (4.28) || Not run: hardware keyboard, left for a person |
| 3.8 | `./gradlew :ComposeTextEditor:compileKotlinIosSimulatorArm64`. 3.8 added `internal expect fun hasNativeTextToolbar()` (commonMain `TouchToolbar.kt`) with `iosMain/.../TouchToolbar.ios.kt` answering true. Then in the simulator: long-press a word, double-tap a word, long-press empty space, tap the caret handle, and drag a selection handle | Compiles. UIKit's edit menu appears over the selection or caret with Cut, Copy, Paste and Select all as applicable (Paste and Select all alone at a bare caret), hides while a handle is dragged and returns when it drops, and goes when the caret moves or the text is scrolled. If no menu appears, the input connection has no toolbar: fall back to `false` in `TouchToolbar.ios.kt` so the context menu stands in |  Partial, same run. Compiles. The UIKit menu works over a selection: double-tap or long-press a word shows Cut, Copy, Paste, Select All, and each works. **Fails:** long-press in an empty document calls `show()` with a zero-width caret rect and only Paste, and UIKit shows nothing (the toolbar reports Hidden right after `showMenu`); a tap on the caret handle never calls `show()`. Native reference: a tap in Safari's focused empty field shows Paste. Also: a long-press past a line's end selects the line's last word instead of placing the caret; with a selection ending at the document end, a long-press below the text counts as on the selection. When the screen was shifted by 4.24 the selection menu did not appear either. Did not fall back to `false`, since the menu works for selections |
| 4.13 | `./gradlew :ComposeTextEditor:compileKotlinIosSimulatorArm64`. `clipboard/ClipboardEvents.kt` adds `internal expect fun ClipboardEventsEffect`; the iOS actual (`iosMain/.../clipboard/ClipboardEvents.ios.kt`) is a no-op. Then the web demo in Safari on macOS: Cmd+C a bold word, Cmd+V it back, and paste a bulleted list from another page; also the context menu's Paste | Compiles. Safari pastes the bold word bold and the list as a list; the context menu's Paste either pastes or logs a `ComposeTextEditor:` warning in the console, never fails silently || Compile part passed 2026-09-30 at `f3b8d8f`. The Safari part is not run: it needs Safari on macOS with a person at the keyboard, since the clipboard events only fire for real key presses and driving Safari needs its Remote Automation setting turned on |
| 4.20, 4.30, 3.10 | `./gradlew :ComposeTextEditor:compileKotlinIosSimulatorArm64`. 4.20 added `internal expect fun deadChar` (commonMain `input/DeadKeys.kt`) with its `actual` in `skikoMain/.../input/DeadKeys.skiko.kt`, which composes nothing. 4.30 moved the skiko request's caret measure into `measureCursorMetrics` (commonMain `input/ImeCaret.kt`), called from `focusedRectInRoot`; 3.10 builds that rectangle from `imeCaretInRoot` in the same file | Compiles. Nothing to run for 4.20: iOS never delivers a dead key as a key event; the caret rectangle behaves as in the 4.19 row | |
| 4.9, 6.7 | Rich clipboard on iOS, the half 6.7 left. In `iosMain/.../clipboard/ClipboardHelper.ios.kt`, write the selection as `public.html` (UTF-8 `NSData` of the `html` argument, or `text.toHtml(configuration)`) beside `public.utf8-plain-text` in one `UIPasteboard.generalPasteboard` item; read `public.html` first (`dataForPasteboardType`, parsed with `toAnnotatedStringFromHtml`), falling back to `string`, and return that markup from `ClipboardHtml.ios.kt` so pasted lists keep their blocks. Compile with `./gradlew :ComposeTextEditor:compileKotlinIosSimulatorArm64`, then in the simulator copy a bold word and a bulleted list between two sample editors, and from Notes and Safari | Bold, lists and links survive editor to editor and from Notes and Safari; pasting into Notes keeps bold | |
| 6.12 | `./gradlew :ComposeTextEditor:compileKotlinIosSimulatorArm64`. `dragdrop/PlatformTextDrag.kt` adds four `internal expect` functions; the iOS actuals (`iosMain/.../dragdrop/PlatformTextDrag.ios.kt`) answer null and false. Then on the Mac's desktop sample app: select a word, drag it within the editor, then with Option held, then into TextEdit, and drag text from TextEdit into the editor | Compiles. The word moves (Option copies), arrives in TextEdit styled and leaves the editor, and TextEdit's text drops in at the drop caret | |
