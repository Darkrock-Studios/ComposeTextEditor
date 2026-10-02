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

An item closed without a fix is ticked with a "Won't fix:" line giving the reason.

Nothing here was verified on a physical Android, iOS, macOS, or Windows
device. Treat **C** items on those platforms as leads to confirm first.

Paths are relative to
`ComposeTextEditor/src/commonMain/kotlin/com/darkrockstudios/texteditor/`
unless they start with a source set or module name; the `markdown/` package
and its tests live in `ComposeTextEditorMarkdown/` since 7.52.

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
  paste sanitising (a source's text colour and body size and any background
  are ignored; a hued colour, and a size relative to the body, are kept, 7.46).
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
arrow keys inside a mixed paragraph are logical, where native editors and the
editor move visually (7.33).

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
| B | Pointer and touch | `textEditorPointerInputHandling.kt`, `state/TextEditorSelectionManager.kt`, `DrawSelectionHandles.kt` | 1.9, 1.12 to 1.16, 1.21 to 1.24, 3.1, 3.2, 3.4 to 3.8, 3.13, 3.15, 3.18 to 3.21, 4.23, 6.16 |
| C | Drawing and geometry | `Draw*.kt`, `cursor/`, `scrollbar/`, `state/TextEditorScrollState.kt`, hit testing | 1.8, 1.10, 1.11, 1.17, 1.18, 1.25, 3.3, 3.12, 3.16, 4.14, 7.6, 7.7, 7.27, 7.41, 7.78 |
| D | Bindings, actions, menu | `input/KeyBindings.kt`, `input/EditorCommand.kt`, `input/BuiltinEditorActions.kt`, `contextmenu/` | 2.1, 2.2, 2.4, 2.5, 2.7 to 2.12, 4.8, 5.8, 7.58 |
| E | Input sessions on desktop, iOS, web | `desktopMain`, `iosMain`, `wasmJsMain` under `input/` | 4.2 to 4.7, 4.10 to 4.12, 4.19, 4.21, 4.22, 4.24 to 4.26, 4.28, 4.29, 4.32, 4.33, 4.35, 4.37 to 4.40, 7.37 |
| F | Android input | `androidMain` | 0.4, 0.12, 3.9 to 3.11, 3.14, 3.17, 3.22, 4.16, 4.18, 4.20, 4.27, 4.30, 4.31, 4.34, 4.36, 4.41, 7.40 |
| G | Edit pipeline and undo | `state/TextEditManager.kt`, `state/TextEditHistory.kt`, `state/EditBehavior.kt`, `input/ImeEditLogic.kt` | 1.20, 5.1 to 5.5, 5.9 to 5.11, 5.13 to 5.18, 6.1 to 6.6, 6.14, 6.15, 6.17, 6.22, 6.23, 6.28, 6.29, 6.33 to 6.35, 6.40, 6.45, 6.49, 7.54, 7.55 |
| H | Clipboard and HTML | `clipboard/`, `html/`, `dragdrop/` | 4.9, 4.13, 4.17, 6.7 to 6.13, 6.18 to 6.21, 5.12, 6.24 to 6.27, 6.30 to 6.32, 6.36 to 6.39, 6.41 to 6.44, 6.46 to 6.48, 7.39, 7.46, 7.47, 7.49, 7.53, 7.63 |
| I | Markdown and block model | `ComposeTextEditorMarkdown/`, `richstyle/`, `state/TextEditorStateBlockExt.kt` | 5.6, 7.14 to 7.16, 7.43, 7.45, 7.52, 7.64, 7.67, 7.70 to 7.72, 7.79, 7.80, 7.83, 7.85 |
| J | Find addon | `ComposeTextEditorFind/` | 7.17 to 7.19, 7.26, 7.29, 7.42, 7.68, 7.69, 7.88 |
| K | Spell check addon | `ComposeTextEditorSpellCheck/` | 7.20 to 7.22, 7.28, 7.30, 7.31, 7.34, 7.35, 7.38, 7.44, 7.50, 7.56, 7.61, 7.74, 7.76, 7.77, 7.81, 7.84, 7.88 |
| L | Tests and CI | test sources, `.github/workflows/` | 0.1 to 0.3, 0.5 to 0.11, 4.1, 4.15, 7.62, 7.65 |
| M | Accessibility and host API | semantics in `BasicTextEditor.kt`, `RichTextView.kt`, `state/rememberTextEditorState.kt` | 7.1 to 7.4, 7.13, 7.23 to 7.25, 7.32, 7.36, 7.51, 7.57, 7.59, 7.60, 7.66, 7.73, 7.75, 7.82, 7.86 |
| N | Core layout and performance | `state/TextEditorState.kt` | 5.7, 7.8 to 7.12, 7.48 |

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
- [x] **0.4 Keyboard trace record and replay.** [Opus] [Lane F] A debug
  recorder on the Android `InputConnection` that logs every command and read. A
  user attaches the trace to a bug report; the trace replays in
  `androidHostTest`. Build a corpus per keyboard (Gboard, Gboard Japanese,
  Samsung, SwiftKey, AnySoftKeyboard). First case: hammer-editor#930.
  Done: a host sets a `KeyboardTraceRecorder` on the state
  (`TextEditorState.keyboardTrace`, Android only) and `trace()` returns the
  text. The keyboard is handed a `TracingInputConnection` around the editor's
  own, which passes calls straight through while nothing records. A trace
  holds each command and read with its result, what the editor reported back
  (selection, restart, extracted text), changes made outside the keyboard (a
  key event's effect, a tap, a host edit or new document), and the editor's
  state when it was taken; the format is in `docs/TESTING.md`.
  `KeyboardTraceReplayer` replays one in a host test and lists where the editor
  parts from it, and `KeyboardTraceCorpusTest` replays every file in
  `androidHostTest/resources/keyboard-traces/` (`KeyboardTraceTest`). The
  first entry, `gboard-japanese-930.trace`, is written by hand from how Gboard's
  Japanese keyboard drives a connection (a growing romaji reading, conversion,
  confirm, an emptied composition, Enter as a key) and passes: the composition
  is reported after each batch and never as gone. [Human] Every corpus entry
  still needs a recording from a device: Gboard Japanese to replace the
  hand-written one (and to confirm 4.16), then Gboard, Samsung, SwiftKey and
  AnySoftKeyboard (0.12).
- [ ] **0.12 Record the keyboard trace corpus. U.** [Opus] [Human] [Lane F]
  0.4's recorder has nothing recorded from a real keyboard yet. With a host
  that exposes it (Hammer needs a debug setting that starts a recorder and
  shares `trace()`; the sample app has none), type a paragraph with each of
  Gboard, Gboard Japanese (romaji and 12-key kana, with conversion), Samsung
  Keyboard, SwiftKey and AnySoftKeyboard on a device, including a correction
  tapped from the suggestion strip, a delete through a word, and a caret moved
  by tapping. Replace `gboard-japanese-930.trace` with the Japanese recording,
  add the others to `androidHostTest/resources/keyboard-traces/`, and turn any
  divergence into an item.
- [x] **0.5 Geometry assertions.** [Opus] [Lane L] Assert caret and selection
  rectangles from layout. Stable across machines, unlike pixels.
  Started in lane C: `utils/DrawRecorder.kt` runs a draw function on a canvas
  that records each rectangle and line with its colour.
  Done: `utils/Geometry.kt` has `drawnCaret`, `drawnSelection` and
  `drawnHandleCenters`, compared with Compose's own layout of the same text
  (`independentLayout`). `drawing/GeometryTest.kt` covers wrapped rows, the
  caret at a wrap, empty lines, the line-break sliver, right-to-left
  paragraphs, paragraph spacing (5.7) and the touch handles, and checks that
  the harness's `handleCenter` grabs the drawn knob. Two right-to-left cases
  were marked `failsUntil("7.6")` until 7.6 fixed them.
- [x] **0.9 A bundled test font. R.** [Opus] [Lane L] The e2e harness lays
  text out in the machine's default sans-serif font, so any test that depends on
  wrapping or text width can pass locally and fail on the CI runner. Two did
  (`TouchGesturesTest`, `LineDragAutoScrollE2eTest`), reproduced by making DejaVu
  the only font. Pin a bundled font in `editorUiTest` and `DifferentialHarness`.
  Done: `TestFontFamily` (`testUtils/testFont/`), a subset of Noto Sans Regular
  under the OFL, is the default in `editorUiTest`, both sides of
  `differentialUiTest`, `findUiTest` and `spellCheckUiTest`
  (`e2e/TestFontTest.kt` and one test per addon fail if a harness falls back to
  the system font). The three desktop suites pass with the machine's fonts and
  with fontconfig restricted to DejaVu; `docs/TESTING.md` has the recipe.
- [x] **0.10 The core test JVM's heap. R.** [Opus] [Lane L] `:ComposeTextEditor:desktopTest`
  runs in one JVM with Gradle's default 512 MB heap, and the suite sits near it:
  adding one class of five 500-line editors on the mocked counting measurer
  (MockK records every call) ran later classes out of memory (seen in 7.51, whose
  test was cut to 200 lines). Set `maxHeapSize`, or fork every so many classes.
  Done: the root build gives every `desktopTest` a 1 GB heap, and
  `countingMeasurer` no longer lets MockK keep what it records: MockK keeps
  every mock, child mocks included, and every call on one with its arguments
  and a stack trace, for the life of the JVM, and the editor calls the
  measurer's mocks per line. Its `measure` is not recorded, and making a
  measurer drops every mock's recorded calls, answers and exclusions kept.
  Measured with GC
  logs over the three suites, with the bundled font: the core suite's live
  heap is 60 to 70 MB through the UI tests, then climbs in the `state` cost
  tests on the mocked measurer, where a heap histogram showed about 118,000
  retained MockK invocations. Before, at 512 MB: the heap full at 510 MB, 46
  full collections that freed almost nothing, 4.7 s of pauses. A 2 GB heap
  alone: 548 MB live at the peak, no full collection. Both changes: at most
  200 MB after a collection, falling to 80 MB once the old regions are
  collected, no full collection, 0.2 s of pauses. The addon suites stay under
  30 MB. Forking was not needed.
- [x] **0.11 A cost test fails when it is the first to use MockK. R.** [Opus]
  [Lane L] `countingMeasurer` (0.10) clears MockK's recorded calls through
  `MockKDsl.internalClearAllMocks`, which skips the start-up every public MockK
  call makes, so a narrow run such as `--tests 'state.SegmentationCostTest'`
  failed every test with `lateinit property implementation has not been
  initialized`; the full suite passed only because an earlier class made a mock.
  Done: the clear runs inside `MockK.useImpl`.
- [x] **0.6 Golden screenshots.** [Opus] [Lane L] A small set of scenes with a
  bundled font on one CI machine: caret, selection across wrapped and empty
  lines, squiggles, list markers, composing underline.
  Done: `golden/GoldenScreenshotTest.kt`, those scenes plus nested list
  markers and paragraph spacing, against PNGs in
  `ComposeTextEditor/src/desktopTest/goldens/` with a small tolerance; a
  failure writes the actual, expected and diff images, which the Ubuntu CI job
  uploads. Linux only (glyph rasterisation differs by OS even with the font
  pinned); `-PupdateGoldens` rewrites them (`docs/TESTING.md`). Passes here
  with the machine's fonts and with only DejaVu; the first CI run is the check
  that another Linux machine renders the same.
- [x] **0.7 CI breadth.** [Opus] [Lane L] [Mac work] Desktop suite on macOS and
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
  macOS part done: the `desktop-macos` job in `ci-build.yml` runs the three
  desktop suites on `macos-26`, beside the Ubuntu `check` and the `ios` job.
  Written, pending their first CI run (`docs/TESTING.md`, "CI"): a
  `desktop-windows` job runs the three desktop suites on `windows-latest`;
  `android-emulator` runs `androidApp`'s `EditorTypingSmokeTest` (injected key
  events, and a composition and commit through the editor's
  `InputConnection`) on an API 35 emulator through
  `reactivecircus/android-emulator-runner`, and passes here on a local API 36
  emulator; `browser` builds the wasm demo and runs the Playwright suite in
  `browserTests/` in Chromium (real key presses, read back from the editor's
  accessibility node), which passes here. The emulator test found 7.60.
  The iOS smoke test, done on the Mac: a `SampleAppiOSUITests` XCUITest
  target in `sampleAppiOS`, in the shared `SampleAppiOS` scheme, opens the
  blank editor, taps it, types "Hello" and reads it back through the editor's
  accessibility value; with typing broken it fails ("the editor reads"
  nothing). The `ios` job now builds the sample app through `xcodebuild test`
  on the newest runtime's first iPhone simulator, with a 60-minute timeout.
  Passes locally on the iPhone 17 Pro Max simulator, iOS 26.0.
- [x] **0.8 Real OS input, nightly.** [Opus] [Lane L] Drive the sample app on a
  virtual Linux display with a dead-key layout. Most expensive, so last.
  Written, pending its first run: `.github/workflows/os-input-nightly.yml`
  starts Xvfb and openbox with the US International layout, and
  `testUtils/osInput/drive.sh` opens `osinput/OsInputProbe.kt`
  (`:ComposeTextEditor:runOsInputProbe`, a window with one focused editor that
  writes its text to a file, since nothing outside the process can read the
  sample app's editor) and presses keys with `xdotool`: plain typing,
  Enter and Backspace, dead acute, grave, circumflex, tilde and diaeresis, a
  capital, a dead key before a space, dead keys inside words, and AltGr. It
  runs nightly from the default branch and on pushes to `native-parity` that
  touch it. Nothing here could run it: no Xvfb or xdotool on this machine.
  Done: first runs on 2026-10-01. Runs 36786371170 and 36797047104 failed while
  the job was brought up; 36811528763 and 36822999186 passed every case (plain
  typing, Enter and Backspace, the five dead keys, a capital, a dead key before
  a space, dead keys inside words, AltGr), from the `os-input-results`
  artifact. The schedule only fires from the default branch, so nightly runs
  start once this work reaches `main`.

## Phase 1: native feel

Highest return. Small, contained in cursor, selection, and pointer code, and
fixes what users feel every minute.

### Caret and keyboard

- [x] **1.1 Grapheme-aware movement and deletion. R.** [Opus] [Lane A]
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
  On iOS the shared `actual` compiles, and Left and Right step whole clusters
  with a hardware keyboard. The soft keyboard's Backspace deletes e + U+0301
  whole, as iOS's own fields do; each platform keeps its own rule there.
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
- [x] **1.5 Word motion and word selection. C, U.** [Opus] [Lane A]
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
  Mac part done. On the iOS simulator with a hardware keyboard, Option+Left
  and Option+Right stop at word starts and ends, keep "don’t" whole and stop
  at the emoji in "a 😀 b", and a double-tap selects "don’t" or 😀 whole. But
  skia's ICU on iOS has no CJK dictionary, so 日本語を勉強します broke one kanji
  at a time. Fixed: `wordCursor` is no longer skia's on iOS for a line in a
  dictionary script (CJK, Thai, Lao, Khmer, Myanmar): such a line takes iOS's
  own breaks from `CFStringTokenizer` (`iosMain/.../state/WordBreaks.ios.kt`),
  and other lines keep skia's, since the tokenizer is about ten times slower
  over a scan of every line. Desktop and web keep skia's. iOS segments 日本語 as
  日本 and 語, as `NSString`'s word enumeration does in any locale, where desktop
  ICU keeps it whole; a double-tap on 勉 now selects 勉強
  (`iosTest/.../WordBreaksIosTest.kt`). Left as they are: a line mixing a
  dictionary script with Latin text takes iOS's breaks for its Latin words
  too, and a document mostly in a dictionary script still pays the tokenizer
  per line (about 0.3 to 0.5 ms) when word count or spell check scans it all.
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
  affinity-free, so the blast radius is the caret's own readers. Left and
  Right still land downstream; a pointer past a wrapped row's end is 1.24's.
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

- [x] **1.24 Pointer affinity. C.** [Opus] [Lane B] A click or drag past the
  end of a wrapped row lands on its wrap offset, which `getOffsetAtPosition`
  returns as a bare position, so the caret draws at the start of the next row
  (1.6 gave only the keyboard an upstream caret). Return the affinity from the
  hit test and place the caret with it.
  Done: it was worse than written: the hit landed on the row's last glyph's
  start, one short of the row's end (before its trailing space). A wrapped row
  is now hit as a vertical move to it is (`caretAtX`), so
  `TextEditorState.pointerHitAt` answers the wrap offset upstream past its end
  (`PointerHit`); `getOffsetAtPosition` is its position. A click, tap,
  right-click, drag, shift-click and caret handle drag place the caret with it,
  so it draws at the row's end and Home, End, Up and Down measure from that
  row. What wants a character rather than a caret (a double-click's word, a
  long press, a right-click's selection test, the span under a click) takes the
  hit's `character`, the row's last. A selection's end at a wrap offset stands
  on the row it ends on, and its start on the row it starts on, as the
  highlight does: its touch handles, the touch toolbar, and a caret at that end
  (`selection/PointerAffinityTest.kt`). A paragraph's last row still hits
  through the layout directly, so past the end of a row ending in a run of the
  other direction a click lands inside that run, where a vertical move goes to
  the row's end. The drop caret of a drag and drop is 6.24 (done).
- [x] **1.17 Empty lines and newlines. C.** [Opus] [Lane C] Empty lines inside
  a selection draw nothing (`DrawSelectionUi.kt`). Native shows a sliver for
  the newline.
  Done: each selected line break adds a sliver one space wide (the base text
  style's) after its line's text, trailing spaces included, which are now
  highlighted too; a soft wrap adds none. Only rows in view are drawn, each
  its full height, block rows included (`drawing/SelectionDrawingTest.kt`).
  In a right-to-left paragraph the sliver goes left (7.6).
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
- [x] **1.25 Composing underline on wrapped rows. R.** [Opus] [Lane C] The
  IME composing underline on any row but a paragraph's first is drawn a row
  too low per row above it: its y added the row's layout bottom, measured
  from the paragraph's top, to the row's own top. Found reading the code in
  7.7, then reproduced in the harness.
  Done: measured from the paragraph's top, where the text is drawn from
  (`drawing/ComposingUnderlineTest.kt`, rows 1, 2 and 9, scrolled).

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
  Checked by hand in Safari and Chrome on macOS (2026-10-01): each chord acts
  once in the wasm demo. Chrome first deleted twice on Ctrl+H: the page's
  textarea is a Cocoa text view with the same bindings, and Compose turns its
  `deleteContentBackward` into a backspace unless the key was Backspace. The
  web session now prevents the textarea's default for Ctrl chords on macOS
  (not inside a composition, nor with Cmd or Option);
  `browserTests/tests/emacs.spec.ts`.
- [x] **2.7 Layout-aware shortcuts. U.** [Opus] [Lane D] A BEPO user reports
  shortcuts follow physical QWERTY positions on desktop (hammer-editor#945).
  Confirm, then match on the produced character where the platform provides it.
  Confirmed by reading: Compose desktop's `Key` is AWT's key code, which
  XToolkit takes from the first layout installed ("will not depend on actual
  locale", `XKeysym.getLegacyJavaKeycodeOnly`), so with US listed before BÉPO
  every letter chord sits on its QWERTY key; AWT's extended key code is the
  active layout's. Done: a public `KeyEvent.layoutKey`, which the built-in
  bindings and the find addon's chords match on, and which a host's own
  bindings should too (`docs/MIGRATION.md`). It is an `expect` whose iOS
  actual needs a compile on the Mac (Mac queue). On desktop Linux it answers
  the extended key code when that is a Latin letter. A letter key the active
  layout gives a dead key or an accented Latin letter (BÉPO's circumflex on
  QWERTY's Y, its 'à' on Z) becomes that key, so Ctrl+Y and Ctrl+Z do not land
  on two keys. One it gives anything else keeps its reported key: a letter or
  mark of another script (Cyrillic, Greek, Thai), so shortcuts stay on the
  first layout's letters there, as GTK's do, and punctuation, which Greek and
  Hebrew put on letter keys too. That leaves BÉPO's '.' (QWERTY's V) a second
  Ctrl+V, the price of keeping those layouts' chords.
  AltGr is Ctrl+Alt on Windows and never a shortcut. The produced character is
  not used: Ctrl turns it into a control character on X11 and Windows. Windows
  and macOS keep `key` until checked on a real keyboard (Mac queue for macOS;
  Windows key codes already follow the layout's letters). Tested with
  synthetic AWT events (`input/LayoutKeyTest.kt`); not run on a real layout,
  since switching this machine's layout was off limits. The web has the same
  problem with every layout: 4.38.
  Checked by hand on macOS against TextEdit (2026-10-01), with the U.S.,
  Dvorak and "Dvorak - QWERTY ⌘" sources: Cmd+Z, X and B and Ctrl+A and F.
  U.S. and Dvorak matched as they were: AWT's key code on macOS is the letter
  the active layout types. Under "Dvorak - QWERTY ⌘" the Cmd chords sat on the
  Dvorak letters (Cmd on the key labelled B cut) where TextEdit's are on the
  QWERTY ones: the key code ignores the layout's Cmd switch, and the extended
  key code is what the Cmd chord types (0 with Ctrl, whose chords follow the
  Dvorak letters in TextEdit too). macOS now takes the extended key code as
  X11 does, and punctuation there as well, so the key labelled / (Dvorak's z)
  is no second Cmd+Z (`KeyCodeSource.CommandlessLayout`). Checked again by
  hand under all three sources, with Cmd+Option+Shift+V, Cmd+Shift+Z and
  Cmd+Shift+X on U.S.
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
  Checked on the iOS simulator with a hardware keyboard: Tab typed its four
  spaces and then a tab character, as UIKit types its own tab for the key the
  editor had handled, the same double as 4.6's arrows. Fixed: the held key
  record (`input/HeldKey.kt`, was `HeldCaretKey`; the iOS session option is now
  `echoesKeys`) names the echo each key gets, a selection change for a caret
  key and a "\t" for a plain Tab, so the iOS session drops UIKit's tab for
  the press and runs the indent again for each repeat (`KeyEchoE2eTest`). Then
  Tab indents by four spaces once, a held Tab repeats the indent, Ctrl+Tab
  types nothing, and Escape then Tab and Tab under `movesFocus` move focus off
  the editor without typing a tab.
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
  Seen on iOS since: a long press on an editor that is not focused selects the
  word but shows no menu; a second long press, once focused, shows it.
  Checked by hand in the iOS simulator (2026-10-01), after 3.18: the menu
  hides while a selection handle is dragged and returns when it drops, and
  goes or moves with the text on a scroll. Found: the menu opens beneath the
  selection and covers the handles (Mac queue).
  With 3.19's bars the menu stands clear of both handles, still beneath the
  selection where a native text view's opens above it.
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
- [ ] **3.14 Italics invisible on Android. U.** [Opus] [Human] [Lane F] Saved and
  exported correctly but not drawn (hammer-editor#956). Not reproduced.
- [x] **3.15 Magnifier on iOS and mobile web. C.** [Opus] [Lane B]
  [Mac work] Compose has no magnifier outside Android (3.6). iOS text views
  show a loupe while the caret or a handle is dragged; matching it means
  drawing our own: an enlarged copy of the canvas around
  `TextEditorSelectionManager.magnifierCenter` in a popup above the finger,
  fed from the `skikoMain` `textMagnifier`. Mobile browsers show none for
  canvas content. Desktop needs none: a mouse does not hide the text.
  Done, drawn in the canvas rather than a popup: the `skikoMain`
  `textMagnifier` records the canvas into a graphics layer while a handle or a
  long press is dragged and draws a capsule loupe (120 by 44 dp, 1.25 times)
  a little above `magnifierCenter`, or below it where there is no room above,
  kept inside the editor's bounds, on the editor's background or a white or
  near-black backdrop the text reads on (`TextEditorStyle.loupeBackdrop`). With
  no drag nothing is recorded and the layer is let go. It covers desktop too,
  where a touch screen hides the text a finger drags over as on a phone.
  `DrawnMagnifierTest`; on the iOS simulator a long-press drag shows the
  enlarged text above the finger. The loupe cannot leave the editor's bounds,
  unlike iOS's, which floats over anything.
- [x] **3.16 The keyboard cover is measured against the last frame's canvas.
  C.** [Opus] [Lane C] `BasicTextEditor` measures the cover (4.24) when the
  keyboard's inset changes, from the canvas's bounds as last laid out. Under
  a host's `imePadding` the inset grows a frame before the padding shrinks
  the editor, so each frame of the keyboard's animation briefly reads a
  covered strip, starts an animated `ensureCursorVisible`, and the next
  layout resets the cover to 0. Since 3.9 the resize takes that scroll over,
  but a snap measured while the stale strip stands leaves the caret row that
  many pixels higher than it needs to be. Measure the cover after layout
  only, or ignore an inset change the next layout will absorb.
  Done: reproduced on desktop with a stand-in inset under `windowInsetsPadding`
  (a keyboard rising 40 px a frame left the caret row 40 px above the shrunk
  viewport's bottom). The cover is now measured in the placement block of a
  layout node on the canvas (`measuresKeyboardCover`), which reads the inset
  and the focus there, so a change re-places the canvas after its ancestors
  have laid out for it, lookahead passes excepted; `onGloballyPositioned`
  still measures a canvas that moves (`e2e/KeyboardInsetE2eTest.kt`, with the
  inset stood in through the internal `LocalImeInsets`). An emulator pass is
  the QA plan's.
  On the iOS simulator this moved the whole window up by most of the keyboard's
  height, which 4.24 had stopped. iOS keeps the focus rect above the keyboard
  by offsetting the window, and asks for it in a measure that runs each frame
  of the slide before the canvas is placed, so the caret row pulled above the
  last placement's cover trailed the keyboard by a frame; the window moved a
  little each frame, and the cover, measured on the moved canvas, then read
  less, so the split stuck. Fixed: `caretFocusRect` also pulls the row above
  the keyboard as it is now (`TextEditorState.currentKeyboardHeight`, set by
  the cover's node), falling back to the last placement's cover where that
  leaves no room. `KeyboardInsetE2eTest` asks for the rect mid-slide; in the
  simulator the tapped line comes above the keyboard with the window still,
  Returns keep the caret at the keyboard's top, and the keyboard goes and
  comes back without the text jumping.
- [x] **3.17 Rich content, autofill, and stylus handwriting on Android. C.**
  [Opus] [Lane F] From 3.11: `commitContent` returns false, so a keyboard's
  GIFs and stickers are refused; the editor offers nothing to autofill; and
  there is no stylus handwriting (`View.setAutoHandwritingEnabled` and
  `EditorInfo.setStylusHandwritingEnabled`, API 33 and 35). Each needs a host
  hook or a design decision about what the editor does with the content.
  Design, after Compose foundation 1.12's `BasicTextField`:
  - Autofill: Compose's autofill manager enters a node only when its
    semantics carry `onFillData`, and puts one in the structure it hands the
    service only with a content type or data type. The editor publishes none,
    so it is already out of autofill. `ContentDataType.None` would put it in
    that structure, with the whole document as its value, on every request
    its window makes, so the editor declares nothing, pinned by a test. No
    opt-in: filling replaces the document, and a form field is
    `BasicTextField`'s job.
  - Keyboard content: `TextEditorState.keyboardContentReceiver` (androidMain):
    the MIME types to advertise and a callback given the keyboard's
    `InputContentInfo` (URI, description, link, grant), returning whether the
    host took it. Unset, `EditorInfo` advertises no types and `commitContent`
    refuses, as before. Set, the connection advertises its types; on a commit
    flagged for a read grant the editor asks for the grant first (as Compose
    and `InputConnectionCompat` do; a failed grant refuses), and releases it
    when the host refuses. Setting or clearing it restarts input. Inserting
    is the host's: an image block needs the host's `ImageProvider`.
  - Stylus handwriting, API 34+ (Compose's floor; the call exists from 33):
    an Android modifier on the editor, outside the touch code, watches the
    initial pass for a stylus stroke that passes the handwriting slop before
    the long press timeout, with Compose's bounds expansion and hover icon,
    while the editor is editable, its keyboard is not for a password and the
    IME offers handwriting. An unfocused editor takes focus with the caret
    where the stroke began (as `EditText` does), the stroke is consumed, and
    a replaying trigger calls `startStylusHandwriting` a frame into the input
    session, as Compose's does. The IME commits what is written through the
    connection, so behaviors, undo and the IME report see typing.
    `EditorInfo.setStylusHandwritingEnabled` (API 35) matches.
  - Handwriting gestures, API 34+: each gesture area is taken from the screen
    into the editor's content and through each paragraph's
    `getRangeForRect` with the gesture's granularity, as Compose maps it, and
    the edit runs as the keyboard's own `setSelection` and `commitText`
    would, in one batch. A gesture that finds no text commits its fallback
    text.
  Done, per part:
  - Autofill: nothing declared, as designed; `EditorSemanticsTest` pins that
    the editor publishes no content type, data type, fillable data or fill
    action. On the API 36 emulator with Google's autofill service, focusing
    and typing in the editor left `dumpsys autofill` at "No sessions".
  - Keyboard content: `TextEditorState.keyboardContentReceiver` and
    `KeyboardContentReceiver` (androidMain, `input/KeyboardContent.android.kt`).
    `KeyboardContentTest` covers the advertised types, routing, the grant
    (taken, kept, given back on refusal, a failed one refusing) and the
    restart. The sample's rich text demos insert keyboard images as image
    blocks (`KeyboardImages.android.kt`); on the emulator the editor's
    `EditorInfo` reported `contentMimeTypes=[image/*]`. A commit from a real
    keyboard was not driven (needs a device, below).
  - Stylus handwriting: `Modifier.stylusHandwriting` (new `expect`, Mac
    queue), Android's in `input/StylusHandwriting.android.kt`, skiko's a
    no-op. Unlike Compose it also asks `isStylusHandwritingAvailable`, so with
    a keyboard that cannot write a stylus still scrolls and selects, and the
    hover icon is left off a password editor. `StylusHandwritingTest`,
    `HandwritingCaretTest`. On the emulator (API 36, Gboard), strokes injected
    with `input stylus motionevent` started Gboard's handwriting on an
    unfocused editor with the caret where the stroke began, a written "L"
    arrived through `commitText`, a second one at the caret, and one undo
    took it back; `EditorInfo` reported `isStylusHandwritingEnabled=true`.
  - Handwriting gestures: select, select range, delete, delete range (a
    word takes one side's spaces, as Compose's), insert, join or split, and
    remove space, which deletes each run of spaces alone so the text between
    keeps its styles. Skiko leaves `getRangeForRect` unimplemented, so the
    editor maps an area itself (`input/HandwritingGestureLayout.kt`: grapheme
    or word segments whose bounds the inclusion strategy takes), the same on
    every platform and tested on desktop (`HandwritingGestureLayoutTest`);
    `HandwritingGestureEditTest` covers the edits. On the emulator a Gboard
    scribble deleted the word under it, a straight stroke begun on text put
    Gboard's `_` where it began, and undo restored the word. Limits: no previews
    (3.22), and join or split does not refuse a bidi run's edge as Compose
    does.
  [Human] On a Samsung tablet with an S Pen and a Pixel with a USI stylus:
  write in an unfocused editor (focus, caret at the stroke, text arrives),
  then in a focused one; scribble out a word, circle one to select it, draw
  a join and a split, and a caret-insert; undo each; check a finger still
  scrolls and a stylus tap places the caret. With a keyboard that cannot
  write (SwiftKey), a stylus drag selects as before. Gboard: insert a GIF and
  a sticker in the sample's Markdown editor (each becomes an image block)
  and in the Code Editor demo, which sets no receiver (Gboard offers none).

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

- [x] **3.18 The iOS edit menu misses the caret cases. R.** [Opus] [Lane B]
  [Mac work] From the 3.8 simulator run (Mac queue): over a selection the
  UIKit menu works, but a long-press in an empty document calls `show()` with
  a zero-width caret rect and only Paste, and UIKit shows nothing; a tap on
  the caret handle never calls `show()`. Safari's focused empty field shows
  Paste on a tap. Also a long-press past a line's end selects its last word
  instead of placing the caret, and the selection menu did not appear while
  the screen was shifted by 4.24.
  Done. The empty document was not the cause: a long press on an editor that
  was not yet focused asked for the menu before the input session it starts
  ran, and iOS hosts the menu on that session's view. `TouchToolbar` now waits
  for the session (`TextEditorState.inputSessionRunning`, set as the
  platform's session starts) and shows a frame after it, dropping the wait
  after a second or when the caret moves. The caret handle's tap did call
  `show()`, but the editor's container then asked for the keyboard on the same
  release, which on iOS dismisses an edit menu just shown; every gesture that
  ends with the menu now shows it a frame after the release
  (`showOnRelease`), dropped if the caret or selection moved in that frame.
  A long press with no character under the finger, as past a row's end,
  places the caret instead of selecting the row's last word. The shifted
  screen no longer happens since 3.16. `TouchToolbarTest`, `TouchGesturesTest`;
  on the iOS simulator a long press on an unfocused empty editor shows Paste
  and Select All, a tap on the caret handle shows the menu, and a long press
  past a line's end puts the caret there with the menu.
- [x] **3.19 Selection handles look like the platform's. S.** [Opus] [Lane B]
  [Mac work] `DrawSelectionHandles.kt` draws every handle the same way on every
  platform: a 20 dp circle 19 dp below the row, joined to the row's top by a
  2 dp stem. Native handles look different:
  - **Android** (and `BasicTextField` everywhere, through Compose's
    `SelectionHandle`): a teardrop about 22 dp, a disc with one square corner
    at the anchor. The start handle hangs below and left of the selection's
    start with its corner at top right; the end handle mirrors it; the caret
    handle points straight up under the caret. No stem.
  - **iOS:** the selection's start and end are 2 pt bars the row's height,
    with a dot (about 10 pt) above the start bar and below the end bar. No
    caret handle is drawn; the caret is moved by dragging it with the loupe.
  - **Desktop touch and the web:** follow Android's, as `BasicTextField` does.
  Draw each platform's shape from one `expect` (or a style hook a host can
  replace), and move `handleCenter`, the hit areas, the touch toolbar's and
  magnifier's anchors and the harness's `handleCenter` grab to the new
  geometry. Check against `BasicTextField` side by side, with a golden
  screenshot per shape. Requested by the owner: the current handles look
  wrong next to native ones.
  Done: `DrawSelectionHandles.kt` holds two looks, each giving a handle's
  drawing, touch target, grab point and drawn bounds. `TeardropHandles` copies
  Compose's Android `SelectionHandle` and `CursorHandle`: a 25 dp disc with a
  square corner on the end, hanging from the row's bottom, and a 25 dp tall
  teardrop under the caret; each takes a finger in a 40 dp box below the row
  on its disc's side, `BasicTextField`'s `MinTouchTargetSizeForHandles` (from
  `TextView`, not 48 dp), so a tap on the line under the caret within 20 dp of
  it is the handle's, as natively. `BarHandles` copies Compose's iOS 17+
  handle: a 2 dp bar the row's height, a 16.7 dp dot above the start and below
  the end, with its shadow; no caret knob, the caret itself takes a finger
  (24 dp wide on its row) while the caret handle is up, and taps there still
  count toward a double tap, which selects the word. A bar takes a finger
  where Compose's iOS handle does: the dot and bar, 5 dp wider than the dot.
  In a right-to-left paragraph each handle hangs on the other side, as
  Compose's `isLeftSelectionHandle` has it. The touch
  toolbar's rect reaches over the drawn handles, as `TextView` adds its handle
  height (the editor's own fallback menu still opens at the caret's row). An `expect val platformSelectionHandleShape` picks Bar on iOS
  and Teardrop elsewhere; `TextEditorStyle.handleShape` lets a host pick either
  (`SelectionHandleShape`). On desktop, Compose's own `BasicTextField` draws
  iOS-like lollipops (`SelectionHandles.skiko.kt`) and no caret handle, so
  desktop touch and the web follow Android's, as asked, rather than it. Tests:
  `drawing/SelectionHandleShapeTest`, the handle cases in `GeometryTest`,
  `HandleDensityTest`, `TouchCaretHandleTest`; goldens `handles-teardrop`,
  `handles-teardrop-caret`, `handles-bar`. On the API 36 emulator, beside a
  `BasicTextField` and an `EditText` showing the same text, the selection and
  caret handles match `BasicTextField`'s in size, shape and place (`EditText`'s
  drawables are a little smaller). The iOS compile and a simulator
  pass are in the Mac queue. Found: 3.20, 3.21.
  Checked on the Mac (2026-10-01, `e6001af2`): compiles and the iOS tests
  pass. By hand on the iPhone 17 Pro Max simulator (iOS 26): a double tap
  shows bars the row's height with a dot above the start and below the end;
  each handle drags from its dot or its bar; a placed caret drags from
  itself with no knob under it; a double tap selects the word. Not compared
  side by side with Notes, which the simulator lacks.
- [x] **3.20 Touch handles past the editor's edge can be grabbed. S.** [Opus]
  [Lane B] Compose draws its handles in popups, so a handle hanging past the
  editor's edge is drawn and grabbed outside it: a teardrop below a selection
  on the last visible row, or a bar's dot above one on the first. The editor
  draws its handles on its own canvas: past its edges they are drawn only
  where no ancestor clips, and the pointer input, bounded by the editor's
  node, never sees a finger there. Draw the
  handles, or at least take their touches, in a popup or an overlay that can
  extend past the editor, or scroll the row up when a selection is made on
  it. Found in 3.19.
  Done: each handle that draws is a `Popup` at its end (`TouchHandlePopups.kt`),
  placed from the canvas and covering its drawing and its touch target, as
  `BasicTextField`'s `HandlePopup` is, so a teardrop under the last visible row
  or a bar's dot over the first draws past the editor and any clipping
  ancestor, and takes a finger there. A handle whose end (its x and row bottom,
  Compose's handle position) is outside the canvas's visible bounds is hidden,
  as Compose's are. The popup runs the editor's own handle drag, following the
  finger by its moves, since the popup moves with the handle; the grab point,
  the magnifier, the toolbar and auto-scroll are unchanged, except that a
  handle grabbed past an edge scrolls that way only once the finger goes
  further out than it started. The iOS caret, which draws nothing, keeps its
  target on the canvas. A popup a finger holds stays until the finger lifts,
  so a drag that crosses the other end and then scrolls it away goes on, and
  only one handle takes a finger at a time. The test harness sends key input
  to the window's root, since the handles add roots. Tests:
  `HandlesPastEdgeTest`. On the API 36 emulator, selecting the last visible
  word with the keyboard up puts both teardrops below the editor, over the
  keyboard's strip; dragging the end one moves the end with no touch reaching
  the keyboard, dragging it down scrolls, and the start handle hides once its
  row scrolls away. Known limits: a mouse press on a handle drags it rather
  than reaching the editor, so a right-click there opens no menu; a second
  finger on the text while a handle is held starts a gesture of its own; a
  handle popup shown again stacks above the editor's own menu if that is open.
  Checked on the Mac (2026-10-01, `a5e48cad`): compiles and the iOS tests
  pass. By hand on the iPhone 17 Pro Max simulator (iOS 26): the dots draw
  past the editor's top and bottom edges and drag their ends, and the bars
  hide and come back with their rows on a scroll.
- [x] **3.21 Touch handles at a bidi run's edge. S.** [Opus] [Lane B]
  Compose places a selection handle at the edge of the bidi run the selected
  character is in (`getHorizontalPosition(offset, isStart, ...)`) and hangs it
  by that run's direction. The editor places it at the caret's x for the
  offset, by the paragraph's direction, and hangs it by the paragraph's
  direction. Selecting the Hebrew word in "abc אבג def" puts the start handle
  on the Hebrew word's left edge, where "abc " ends, rather than on its right
  edge, where its first letter is. Place and hang each end as Compose does,
  and check the selection highlight agrees. Found in 3.19.
  Won't fix: only touch selection across mixed-direction text, and the highlight is already right; rare for Hammer's users.
- [x] **3.22 Handwriting gestures show no preview on Android. S.** [Opus]
  [Lane F] Gboard previews a select or delete gesture while the stylus is
  still down (`previewHandwritingGesture`, API 34); `BasicTextField`
  highlights the range it would select or delete. The editor offers no
  previews (3.17), so a scribble shows nothing until the stroke ends. Draw a
  highlight for the previewed range (selection colour for select, a delete
  tint for delete), clear it on the cancellation signal or the next edit,
  and add the four previewable gestures to
  `supportedHandwritingGesturePreviews`. Found in 3.17.
  Done, after Compose foundation 1.12: `EditorInfo` offers previews of
  select, select range, delete and delete range; the connection's
  `previewHandwritingGesture` maps the area as the gesture's perform does
  (a delete's word is not widened, as in Compose) into
  `TextEditorState.handwritingPreview` (`input/HandwritingPreview.kt`), which
  the editor draws through `DrawSelection` after the selection: in the
  focused selection colour for a select, in the text colour at a fifth of
  its alpha for a delete, over the selection where they meet, as Compose
  draws both. A preview ends when its cancellation signal fires (posted to
  the main thread; a late one leaves a newer preview, and one already
  cancelled shows none), when any gesture is performed, when the connection
  closes, and, as Compose's, at the next change to the text (a bold toggle
  included, a rich span such as a spell check mark not), the selection or
  the caret, ended where the state makes the change; an area over no text
  shows none. Tests:
  `HandwritingPreviewTest` (drawing and ending, desktop),
  `HandwritingPreviewGestureTest` (offer, routing, cancel, perform, close).
  On the API 36 emulator with Gboard, a stylus scribble over "quick" held
  still drew the selection highlight over the word while the stylus was
  down, and on lift Gboard performed a select (or a delete, which removed
  the word and its highlight); in one run whose handwriting session Gboard
  had ended early, the delete tint showed and stayed until the next edit, as
  Compose's would, since no cancellation came. An `EditText` in the same
  window (a throwaway comparison screen) made Gboard end each handwriting
  session within milliseconds; without it, sessions lasted the stroke.
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
  from the editor's own line layouts instead). Checked with a hardware
  keyboard on the Mac: every caret key moved twice on iOS. Compose hands each
  hardware key to both the editor and UIKit, and UIKit moved the caret again
  from where the editor left it; Left, Right and the Option word moves always
  had, and Up and Down did once this item let `verticalPositionFromPosition`
  answer. UIKit also repeats a held key itself, with no further key event.
  Fixed: the key handler records the caret key it moved for
  (`input/HeldCaretKey.kt`, `TextEditorState.heldCaretKey`), and the iOS session
  (`echoesCaretKeys`) drops UIKit's echo of the press and runs the editor's move
  again for each repeat. Releasing the key, another key, a modifier change, a
  platform edit or a focus change ends the hold. `CaretKeyEchoE2eTest`; in the
  simulator, arrows, Option arrows and held keys each move once per step, Up
  and Down by drawn rows through a heading.
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
  Found on the iOS simulator (2026-10-01): with the keyboard up, a fling away
  from the caret stopped and the view scrolled back to the caret. The cover is
  measured again as the view settles after the fling, a few pixels larger
  each time (879, 899, 905 px), and any growth scrolled the caret above it.
  Now only a caret in view, or being scrolled to, as the cover grows is kept
  above it; one the reader scrolled away from stays away until a key or an
  edit brings it back (`KeyboardCoverE2eTest`). An editor focused without a
  tap while its caret is out of view no longer scrolls to it as the keyboard
  rises.
  The growth itself was the rubber band: the overscroll effect lifts the
  canvas at the end of a fling and lets it back, and the cover, measured on
  the canvas, shrank by the lift (26 px there) and the scroll range with it,
  so the scroll was left short of the end and the last line sat under the
  keyboard's edge. The cover is measured on the canvas's frame now, the
  editor's box outside the overscroll (`TextEditorState.canvasFrameCoordinates`,
  `KeyboardInsetE2eTest`); on the simulator the last line rests a content
  padding above the keyboard after a hard fling.
- [x] **4.8 Native edit menu. C.** [Opus] [Lane D] [Mac work] A Material
  dropdown is used instead of the platform text toolbar.
  Done. Touch has used the platform toolbar since 3.8 (with 3.18's fixes on
  iOS). The rest was the pointer: on iOS a right-click, from a mouse or a
  trackpad, now opens the edit menu at the pointer, as a native text view
  does, through `TouchToolbar.show(pointer)`, which waits for the input
  session like a long press's menu and keeps with the text under the pointer
  when it scrolls (`pointerMenuIsTextToolbar`, overridable through
  `LocalPointerMenuIsTextToolbar`). A span handler that opened the editor's
  menu with items of its own, as spell check does with its suggestions, keeps
  that menu. Android keeps the context menu for a mouse, as its text fields
  do, and desktop and the web keep it too, as Compose's own text fields do.
  `TouchToolbarTest`. A selectable `RichTextView` still opens the Material
  menu for a right-click on iOS. The iPad check is in the Mac queue.
  Checked by hand on the iPad Pro 11-inch (M5) simulator, iPadOS 26
  (2026-10-01), with the pointer sent to the device and Control-click: the
  system edit menu opens at the pointer over a word, also in an editor not
  yet focused, and a misspelt word in the spell-check demo opens the editor's
  menu with its suggestions. Run in the standalone Simulator: an embedded
  simulator panel can take the right-click itself.
- [x] **4.9 Rich clipboard. C.** [Opus] [Lane H] [Mac work] Plain text only
  (shared with 6.7).
  Done: a copy puts one `UIPasteboard` item holding the selection's
  `public.html` beside its `public.utf8-plain-text`, and the copy id in a
  private type (`com.darkrockstudios.texteditor.copy-id`), so iOS now has copy
  provenance as Android does and `supportsCopyProvenance` is true. A paste
  prefers the markup, gives it to the block paste (`readClipboardHtml`), and
  reads the pasteboard once; this editor's own copy uses its markup only where
  it re-parses to the characters copied; several items paste one per line.
  iOS derives `public.rtf` from the markup itself, which is what apps like
  Notes read. The first iOS tests (`iosTest/.../ClipboardHelperIosTest.kt`,
  11 cases, run by the `ios` CI job) cover it against a private pasteboard: a
  test binary is not an app, and the general pasteboard ignores it. Simulator,
  2026-09-30: Safari to editor keeps bold, a link and a bulleted list; editor
  to Safari's editable area and editor to editor keep the same. Notes is not
  in the simulator, so Notes both ways is left to 4.4 on a device.
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

- [ ] **4.11 Soft keyboard on mobile web. C.** [Opus] [Human] [Lane E]
  Compose creates its backing DOM input inside `startInputMethod`, which is
  never called, so no keyboard appears. Resolved by 4.3: the textarea now
  exists and is focused on a tap, which is what raises the keyboard. Tick
  after a pass on Android Chrome and iOS Safari (4.4); a headless browser
  cannot show one.
  Gap: Compose sets `autocapitalize="off"` on every backing field, so phone
  keyboards never capitalise a sentence. The web session sets it to
  `sentences`, matching the Android and iOS sessions, from inside the
  field's first `focus()` call since 4.40, so a keyboard rising for it should
  see it; that a phone does is for the phone pass, and if not the fix belongs
  in Compose (its `DomInputStrategy` ignores `ImeOptions.capitalization`,
  which the session passes). Verified in Chromium
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
  Checklist for a person, on an Android phone in Chrome with Gboard and on an
  iPhone in Safari:
  1. Build with `./gradlew :sampleApp:wasmJsBrowserDistribution`; in
     `sampleApp/build/dist/wasmJs/productionExecutable` run
     `python3 -m http.server 8765 --bind 0.0.0.0` (Python 3.10 or later, for
     the `.wasm` type); open `http://<computer's LAN address>:8765` on the
     phone and choose Markdown Editor (Blank).
  2. Tap the editor. Pass: the keyboard rises with Shift on, and stays up.
  3. Type "teh cat. it is" and let autocorrect fix "teh". Pass: "The cat. It
     is", the fix applied once, a capital after the full stop.
  4. Tap a suggestion in the strip for the last word. Pass: it replaces the
     word once.
  5. Press Backspace three times. Pass: three characters go, one per press.
  6. Press Enter until the caret is near the bottom of the screen. Pass: the
     caret stays visible above the keyboard.
  7. Tap the page below the editor's text, outside the editor, then the
     editor again. Pass: the first tap hides the keyboard, and the second
     raises it with the caret where tapped.
  Record each phone's result here and in the QA plan (3.8, step 7).
- [ ] **4.12 Composition on desktop web. C.** [Opus] [Human] [Lane E] Dead
  keys and CJK input. Also resolved by 4.3, and the event shape a browser
  IME sends is verified with synthetic events. Tick after a pass with a real
  IME (fcitx or ibus on Linux, the macOS Japanese keyboard) in Chrome,
  Firefox, and Safari.
  Rechecked with synthetic events in Chromium against the dev server: a dead
  key (`keydown` "Dead", `insertCompositionText` "´", then "é",
  `compositionend`) gives one "é" with no stray "Þ" or "´"; a Romaji
  composition ("n", "に", "にh", "にほ", Backspace to "に", on to "にほん",
  converted to "日本") commits "日本" once, and an Enter `keydown` during the
  composition adds no line; a composition after arrowing into the middle of
  a line lands at the caret. No code change was needed. Synthetic events
  leave the textarea's own text alone, which real input does not, so a real
  IME in Chrome, Firefox and Safari is still what ticks this.
  Checklist for a person, in Chrome and Firefox on Linux or Windows and in
  Chrome, Firefox and Safari on macOS:
  1. Run `./gradlew :sampleApp:wasmJsBrowserDevelopmentRun`, open the page it
     prints, choose Markdown Editor (Blank) and click in the editor.
  2. Switch to a Japanese input method: fcitx5 or ibus with Mozc on Linux,
     Microsoft IME on Windows, Japanese (Romaji) on macOS.
  3. Type "nihongo", press Space to convert, Enter to commit. Pass: the
     composing text is underlined, the candidate window sits at the caret,
     and "日本語" is committed once with no new line.
  4. Type "nihon", press Backspace twice, then commit. Pass: only what is
     left of the composition is committed, once.
  5. Back on a Latin layout, type "ab", press Left, switch to Japanese and
     type "ka", then commit. Pass: "aかb". A "か" at the line end is 4.35.
  6. Select a word and compose "ka" over it. Pass: the word is replaced by
     the commit.
  7. With a dead-key layout (US International on Linux or Windows; Option+E
     on the macOS US layout), type the acute dead key then "e". Pass: one
     "é", no stray "´".
  Record each browser's result here and in the QA plan (3.8, step 6).
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
  Checked by hand in Safari on macOS (2026-10-01): a bold word copied and
  pasted back stays bold, a bulleted list from another page pastes as a list,
  and the context menu's Paste shows Safari's own Paste prompt, then pastes.
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
- [x] **4.15 Browser tests.** [Opus] [Lane L] Automation against the built demo
  (0.7), with composition events.
  Done: `browserTests/` (Playwright, Chromium), run by the `browser` CI job,
  which passes there (run 36882012015 at `c838d224`: 9 passed, the two 4.35
  cases skipped); it passes here with Playwright's own Chromium download.
  Key presses (typing, Enter, Backspace, arrows, Shift selection, ';' and
  '=') and compositions through Chromium's input method over the
  DevTools protocol, which fires the real `composition*` and `beforeinput`
  events: a dead key, a Japanese composition converted and committed once, a
  cancelled composition, typing after a commit. The editor's text is read
  from its node in the accessibility tree Compose mirrors into the page.
  Found 4.35, marked `test.fixme`. A real IME in each browser (4.12) is still
  a person's check.
- [x] **4.35 A browser composition can land at a stale offset. R.** [Opus]
  [Lane E] With Chromium's own input method events (4.15), a composition
  started after moving the caret by key can land away from the caret: in
  "ab" after Left, "か" landed at the line end in three runs of five (one in
  six once the test waits for the field's caret to follow), and after Home
  (caret 0) at offset 1 in four of five. After Left the textarea shows the
  composition inserted at the caret, then about 15 ms later rewritten with it
  at the line end, so the session maps it with a stale selection. Under load
  (five pages at once) a composition is also sometimes ended early: a second
  `compositionstart` follows, the commit arrives as `insertText`, and a
  cancelled composition leaves its text; a rewrite of the field during the
  composition would do that. The 4.12 checks used synthetic events, which
  leave the field alone, so they could not see either. Failing cases:
  `browserTests/tests/composition.spec.ts`, "a composition lands at the caret
  in the middle of a line" and "a composition after Home lands at the line
  start", both `test.fixme`.
  Deferred: Hammer has no web target.
  Won't fix: web only, and Hammer has no web target.
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
- [x] **4.38 Web shortcuts follow QWERTY positions. S.** [Opus] [Lane E]
  Compose web builds `Key` from the DOM event's `code`, the physical key named
  by its US QWERTY letter, so on AZERTY, QWERTZ, BÉPO or Dvorak every letter
  chord (Ctrl+Z, Ctrl+B) sits on the QWERTY key in the browser. The event's
  `key` is the active layout's character even with Ctrl held, and Compose puts
  it in the key event's code point. Give `layoutKey`'s web actual
  (`LayoutKey.wasmJs.kt`, which answers `key` today) the Latin letter of the
  code point on a key down, as 2.7 did with AWT's extended key code. Check dead
  keys (`key` is "Dead") and macOS Option chords, and test with synthetic DOM
  key events whose `key` and `code` differ. Found in 2.7.
  Done: the web `layoutKey` answers `layoutKeyFromCodePoint`
  (`skikoMain/.../input/DomLayoutKey.kt`, so the desktop suite tests it), on
  key down and key up alike, since the DOM gives `key` on both. A Latin letter
  is that letter's key on whichever key it sits (AZERTY, QWERTZ, Dvorak's and
  BÉPO's letters on punctuation keys). A letter key typing another script's
  letter or mark keeps its QWERTY name, so Cyrillic, Greek, Hebrew and Thai
  keep their shortcuts. One typing anything else, an accented Latin letter or
  punctuation, becomes that key, so on a Latin layout no letter chord lands on
  two keys (BÉPO's 'à' and Dvorak's ';' on QWERTY's Z are no second Ctrl+Z).
  Desktop keeps punctuation on its letter key (2.7), where the clash needs two
  layouts installed; on the web it would hit every Dvorak and BÉPO user, and
  the cost is Greek's and Hebrew's Ctrl+Q and Ctrl+W, which the browser
  claims anyway. A named key ("Dead", "F1", "End") is told apart by Compose
  giving its key code as the code point (F1 is 'p', Numpad 1 without Num
  Lock is 'a' in the DOM's codes), shared with the character-input predicate.
  With Alt held (macOS Option, Windows AltGr as Ctrl+Alt) the character is
  that layer's ('ƒ' for Option+F), so only a Latin letter moves the key.
  Known gaps, in the KDoc: a dead key on a letter key keeps its QWERTY letter
  (4.39); the code point follows Shift, so Turkish Q's 'ı' key is its own key
  unshifted and I with Shift; Linux reports AltGr without Alt, so its layer
  counts as the base one. `input/DomLayoutKeyTest.kt` (with the web's key
  codes); verified in Chromium against the dev server with synthetic events
  on the session's textarea (Ctrl with `code` KeyW and `key` "z" undoes, KeyZ
  with "w", "à" or ";" and KeyV or KeyE with "." do nothing, KeyZ with "y"
  redoes, with "я" undoes, Slash with "y" redoes);
  `browserTests/tests/layout.spec.ts` repeats this, pending its first CI run.
  Checked by hand in Safari and Chrome on macOS (2026-10-01), with the U.S.
  and ABC-AZERTY layouts: Cmd+Z undoes and Cmd+Shift+Z redoes on the key that
  types z (QWERTY's W under AZERTY), Cmd+B bolds, Ctrl+A on the key that types
  a and Ctrl+F move as in TextEdit, and Cmd+Option+Shift+V pastes plain.
- [x] **4.39 A dead key on a letter key keeps its QWERTY letter on the web. S.**
  [Opus] [Lane E] Compose web gives a `key` of "Dead" the key code as its
  code point, as it does a capital typed on its own key, and keeps the DOM
  event to itself (`InternalKeyEvent`), so `layoutKeyFromCodePoint` cannot
  tell them apart: BÉPO's dead circumflex on QWERTY's Y is a second Ctrl+Y,
  where desktop Linux answers the dead key (2.7). The session's keyboard
  events reach Compose a frame after the DOM event, so a listener of ours
  cannot pair them either. Fix upstream (expose the DOM `key`, or a code
  point of 0 for a named key), or pair the events by their order. Found in
  4.38.
  Won't fix: the fix belongs in Compose web, and the gap is narrow (a dead key
  on a letter key, held with Ctrl, on a non-QWERTY layout, in a browser).
- [x] **4.40 Ask for sentence capitals before the phone keyboard rises. S.**
  [Opus] [Lane E] From 4.11's gap: the web session set the backing field's
  `autocapitalize` once it found the field, a frame or more after Compose had
  created it with "off" and focused it, which is when a phone keyboard reads
  it. Done: as each run starts, in the dispatch that has Compose create the
  field, the session listens for `focusin` on the viewport's shadow root (a
  focus move inside a shadow tree is reported only there; the root holding
  the focused canvas, else every open root) and sets the attribute on the
  first backing field focused, inside that `focus()` call. The listener goes
  on that first hit, when the session finds the field, or as the run is
  cancelled, so it cannot reach the next text field's. Finding the field
  still sets the attribute too, for a field in a root it missed. Verified in
  Chromium against the dev server with `HTMLElement.prototype.focus` wrapped
  to read the attribute as each field's first call returns: "off" before,
  "sentences" after; the Find demo's search field, a `BasicTextField`
  focused from the editor, keeps "off". `browserTests/tests/keyboard.spec.ts`
  checks both, pending its first CI run. Whether a phone keyboard then
  capitalises is 4.11's phone pass.

Exit criteria: typing, composition, and clipboard work in current Chrome,
Firefox, and Safari on desktop; the soft keyboard works on Android Chrome and
iOS Safari; browser tests run in CI (met: the `browser` job, 4.15).

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

- [ ] **4.16 Japanese input. U.** [Opus] [Human] [Lane F] Reported broken with Fcitx5
  and Mozc on Linux and with Gboard Japanese on Android (hammer-editor#930).
  ComposeTextEditor PR 102 reworked IME edits afterwards; nobody has confirmed
  the result.
- [ ] **4.17 Wayland paste. U.** [Opus] [Human] [Lane H] External paste fails on
  Wayland with KDE; internal paste works (hammer-editor#921).
  Investigated 2026-10-01, not reproduced: this machine runs GNOME on Wayland,
  where the owner already found paste working. Desktop paste reads Compose's
  `Clipboard`, whose desktop implementation is AWT's
  `Toolkit.getSystemClipboard()`; there is no other clipboard API to fall back
  to, and a second AWT request for the text flavor alone goes through the
  same format list and conversion, so it would only repeat a failed read.
  Hammer bundles Temurin 21 (its Flatpak manifest and release workflow), so
  AWT is always `XToolkit` and the window is an XWayland one, whatever the
  report's commenter says about a native Wayland window (only the JetBrains
  Runtime's `WLToolkit` makes one). An in-process paste never leaves the JVM,
  since AWT hands back its own contents, which is why internal paste works; an
  external one goes through KWin's Wayland to X11 clipboard bridge. That bridge
  had a bug that matches the report: after a while it stops updating the X11
  clipboard ("target STRING not available") until the owner changes, fixed in
  Plasma 6.2 (KDE bug 490577). Nothing in the editor's read is at fault on that
  path, so no code change. Found on the way: 6.36, which is Windows only and
  leaves this path as it was, and 6.37. To verify, on KDE Plasma
  under Wayland: note the Plasma version (`plasmashell --version`) and run the
  desktop sample app (`./gradlew :sampleApp:run`). Copy a sentence in a
  Wayland-native application (Kate, or Firefox) and press Ctrl+V in the
  editor; then copy in an XWayland application (`xterm`, or the sample app
  itself) and paste into Kate; then, after ten minutes of use, copy again in
  Kate and paste in the editor. Each paste should land. If one fails, run
  `xclip -o -selection clipboard` (an X11 client, like the app) right after
  the copy: if that prints nothing either, the bridge is at fault, not the
  editor, and a Plasma before 6.2 is the likely cause. If `xclip` prints the
  text and the editor still pastes nothing, report that with the `java
  -version` of the runtime Hammer ships.
- [ ] **4.18 ANR on Galaxy S21 Ultra. U.** [Opus] [Human] [Lane F] hammer-editor#545,
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
- [x] **4.23 Primary selection on Linux. S.** [Opus] [Lane B] Middle-click
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
  Done: `clipboard/PrimarySelection.kt` has `internal expect fun
  platformPrimarySelection()`, read through `LocalPrimarySelection`; the desktop
  actual wraps `Toolkit.getSystemSelection()`, the others answer null (Mac
  queue). An editor or selectable `RichTextView` offers its selection whenever
  the selection changes to a new one (not on an edit, nor when a state is shown
  again with its selection): it claims the selection once and offers the
  document snapshot and range, so a drag claims once and the text is built only
  when something pastes it. A cleared selection leaves the last one on offer,
  as Qt does (GTK drops it); an editor leaving composition keeps it as text,
  letting go of the document. A middle press in an editable editor places the
  caret as a click does, reads the primary selection off the UI thread, and
  pastes its text as Paste as plain text does, so one undo step, line blocks,
  the input filter and `pasteLanded`; a read-only editor ignores it, as
  before. Like a drop, it is not an editor action, so a host that replaces the
  Paste actions does not replace it. The test suites set
  `-Dcomposetexteditor.primarySelection=false` so a test's selection never
  reaches the desktop's (`PrimarySelectionTest`, over an in-memory AWT
  clipboard).
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
- [x] **4.26 A behavior's edit mid IME batch. C.** [Fable] [Lane E] A
  behavior edits on top of an IME commit (5.1) at once, but a batch (an
  Android `beginBatchEdit`, a web `onEditCommand` list) may hold further
  commands the IME computed against its own mirror, and the resync only
  lands after the batch. A `setComposingRegion` or `deleteSurroundingText`
  after the commit then addresses the wrong characters. Defer the hook to
  the batch's end, or drop the batch's remaining offsets once a behavior
  has edited.
  Reproduced on all three paths with smart punctuation on: an Android batch
  of `commitText("--")` then `deleteSurroundingText(2, 0)` emptied the line
  (the dash the behavior made was one character, so the delete took the
  character before it too), and a skiko `editText` block and a web command
  list did the same. Done by deferring the landed hooks (`onTextInput`,
  `onNewlineLanded`, `onPaste`) to the end of the outermost batch:
  `TextEditorState` counts IME batches (`beginImeBatch`/`endImeBatch`, which
  Android's batch edit, the skiko `editText` block and the web command list
  all use), queues what landed in one with its text, moves each queued range
  across the batch's later edits as the rich spans move (text put in right at
  a range's end follows it), and at the end offers each range that still holds
  the text that landed there, in order; one a later command rewrote or removed
  is not offered, since it is no longer what the user typed. Dropping the
  batch's remaining offsets was rejected: the keyboard's mirror cannot be
  known, so no remap is right, and dropping commands loses text. The
  behaviors run before Android leaves the batch, so the one flush after it
  reports their edits and the resync (4.27) restarts input then; on the skiko
  platforms the resync (4.25) lands after the block as before. The pre-edit
  hooks (`onNewline`, `onBackspace`, `onDeleteForward`) still decide their edit
  at once, as they must. A composition the batch opened after the text landed
  (`commitText("--")` then `setComposingText("x")`) is put back after the
  behaviors' edits, moved with them, since every edit clears it and the
  keyboard goes on composing. A keyboard that never closes its batch keeps its
  text, gets no substitutions, and its landed input is dropped unoffered when
  the connection closes with no batch left open.
  Hooks run outside a batch (hardware keys, a host's `insertTypedString` or
  `pasteLanded`) are offered at once as before.
  Checked on the Mac (2026-10-01, `a5e48cad`): compiles and the iOS tests
  pass. On the iPhone 17 Pro Max simulator (iOS 26) with the soft keyboard and
  the sample's `SmartPunctuation` switches on, read from the edit operations
  the sample prints: `A--` is three inserts and one replace of `--` with the
  dash, and undo gives `A--` back in one step; each `"` and the `'` of `It's`
  is replaced as it is typed, and `...` becomes the ellipsis; nothing is
  doubled or lost and the suggestions carry on. That is with iOS's own Smart
  Punctuation (Settings, General, Keyboard) off. With it on, the default, the
  keyboard substitutes first: the second dash arrives as one replacement of
  the whole token (`A-` deleted, `A` and the dash inserted), which is typing
  to the editor, so `SmartPunctuation` never sees `--` and one undo takes the
  whole typed run, as a native text view's Undo Typing does. Compose's
  `ImeOptions` has no trait to turn the keyboard's off.
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
- [x] **4.32 Keyboard settings on iOS and the web. C.** [Opus] [Lane E]
  [Mac work] 3.11 added `TextEditorState.keyboardSettings`, which only Android
  honours. `TextEditorTextInputService.ios.kt` passes fixed `ImeOptions` and
  web passes `ImeOptions.Default`; both should build them from the settings
  (`singleLine = false` and the four fields map one to one), route the
  request's `onImeAction` to `TextEditorState.performImeAction`, and
  restart the session when the settings change, as Android does. Web also
  forces `autocapitalize` to `sentences` (4.11), which should follow the
  settings' capitalisation. Since 7.40 a single-line editor
  (`TextEditorState.isSingleLine`) asks for `singleLine = true` and its action
  key from `TextEditorState.effectiveImeAction()` (Done by default); a
  hardware Enter already presses it on every platform.
  Done. `TextEditorState.skikoImeOptions()` builds the options from the
  settings, the single-line flag and the action key; iOS and the web pass it
  to `startSkikoInputSession`, which reads it as snapshot state and starts the
  input method again when it changes (`collectLatest`), calling a per-run hook
  the web uses to adopt each new text area and set its `autocapitalize` from
  the capitalisation. The request's `onImeAction` runs `performImeAction` for
  any action that does not start a line; iOS calls it for Return in a single
  line or under an action key, and Compose's web text area never calls it, so
  a web Enter stays with the key handler. Desktop keeps `ImeOptions.Default`.
  A single line on the web now gets an `<input>` rather than a `<textarea>`,
  as Compose maps it. `SkikoKeyboardSettingsTest`; on the iOS simulator,
  turning Single line on with the keyboard up turns Return into Done, and
  Done closes the keyboard without a line break. Left: on iOS a settings
  change raises a keyboard the user had dismissed, as the restart makes the
  input view first responder again; the web half wants a phone pass with 4.11.
- [x] **4.31 Android's cursor anchor misses a view that moves alone. C.**
  [Opus] [Lane F] The anchor is resent when the caret moves in the view
  (3.10), but a view that moves on screen with nothing in the editor changing
  (a window panned by `adjustPan`, a `ComposeView` inside a scrolling Android
  parent, a freeform window dragged) goes unreported until the next caret
  move, so floating candidates stay where the view was. The same holds for a
  change in the strip a keyboard covers alone (Gboard switched to floating),
  which the marker's visibility flags depend on. `TextView` checks its screen
  location in an `OnPreDrawListener` while the IME monitors; watch the view
  the same way, only while monitoring.
  Done: while the IME monitors, `ImeCursorSync` checks the view's screen
  location as each frame its window draws (`ViewDrawWatch`, an
  `OnDrawListener` held while the view is attached, on the live connection's
  view; at the draw rather than before it, since a panned window takes its
  offset in the draw), and resends the anchor there when it differs from the
  last one sent. The covered strip
  (`TextEditorScrollManager.obscuredBottomPx`) is now snapshot state, so a
  change in it alone re-measures the anchor as a scroll does
  (`ImeCursorSyncTest`). A freeform window dragged by the system redraws
  nothing, so it still waits for the next change, as it does for `TextView`.
  A floating keyboard or stylus pass is a person's (QA plan, "Android
  keyboards"). The marker's visibility flags inside a scrolling parent are
  4.34.
- [x] **4.34 The cursor anchor ignores clipping by the views around the
  editor. C.** [Opus] [Lane F] `imeCaretInRoot` decides the marker's
  visibility flags from the canvas's `boundsInRoot`, which knows only
  Compose's own clipping. A `ComposeView` inside an Android `ScrollView` whose
  parent has scrolled the caret's row out of sight still reports the marker
  visible, so floating candidates point at a caret no one can see. Clip by
  the view's `getGlobalVisibleRect` as well, as `TextView` does through
  `isPositionVisible`.
  Done: the anchor clips the caret by the view's `getLocalVisibleRect` too
  (`imeCaretInRoot`'s `rootVisible`, `ImeCaretTest`). Checked on an emulator
  (API 36) with a temporary instrumented test: a `ComposeView` 2400 px tall in
  a `ScrollView`, the caret on its first row, the IME monitoring. Scrolled
  3000 px down, the anchor resent at the draw (the view's screen position
  changed) with both flags invisible, and scrolled back with both visible;
  without the clip both stayed visible. A parent that clips the view without
  moving it on screen (a resize) waits for the next change, as `TextView`'s
  position check does. Under a scaled ancestor the rect is in screen units,
  as the anchor's translate-only matrix already is. The test also found 4.36.
- [x] **4.36 The keyboard cover assumes the Compose root ends at the window's
  bottom. R.** [Opus] [Lane F] `updateKeyboardCover` measures the keyboard
  from the bottom of the root (`keyboardCover`'s `rootHeight`), which is the
  window's only when the root fills it. A `ComposeView` 600 px tall inside an
  Android `ScrollView`, with the keyboard up, measured its whole canvas as
  covered (found in 4.34's emulator check), so the anchor marked a caret on
  screen invisible and the editor scrolls the caret toward a strip it thinks
  is clear. Its doc names dialogs and popups; an embedded `ComposeView` is
  the same. Measure from the root's position in the window on Android (the
  view's `getLocationInWindow` and the window's height), or from the
  platform's own inset of the view.
  Done: on Android the keyboard rises from the bottom of the window the view
  is in (the root view's height less the view's top in the window), which the
  view lends the state (`TextEditorState.windowBottomInRoot`); other platforms
  keep the root's bottom (`KeyboardInsetE2eTest`, `WindowBottomInRootTest`).
  Checked on an emulator (API 36, Gboard) with a temporary instrumented test:
  a `ComposeView` 600 px tall at the top of a `ScrollView`, the keyboard 1008
  px tall in a 2856 px window, was covered 600 px before and 0 after; placed
  lower, it was covered 252 px, as the overlap is, and scrolling the parent
  up 1000 px measured 0 again (the root's move reaches `onGloballyPositioned`).
  A scaled ancestor is not converted, as the IME anchor's is not. iOS is 4.37.
- [x] **4.37 The keyboard cover on iOS assumes the root reaches the window's
  bottom. C.** [Opus] [Lane E] [Mac work] 4.36 measures from the window's
  bottom on Android only; elsewhere `windowBottomInRoot` is null and the
  cover is measured from the root's bottom, so a `ComposeUIViewController`
  embedded in a UIKit view that ends above the screen's bottom would read the
  keyboard as covering more than it does. Confirm in the iOS sample with the
  controller in a shorter container, and if so lend the state the window's
  bottom from the root view, as Android's `CaptureViewForIme` does.
  Not so on iOS: Compose's `WindowInsets.ime` there is the keyboard's overlap
  with the Compose view itself (`ComposeSceneKeyboardOffsetManager` takes the
  view's distance from the screen's bottom off the keyboard's height), so the
  root's bottom is the right edge to measure from. Checked on the simulator
  with the sample's controller in a SwiftUI stack 200 points short of the
  screen's bottom: the caret's row ends at the keyboard's top, with no gap.
  No change.
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
- [x] **4.28 A Tab left to the focus system on iOS and the web. C.** [Opus]
  [Lane E] Since 2.9 the editor leaves some Tabs unconsumed: Ctrl+Tab, a Tab
  after Escape, and every Tab under `TabSettings.movesFocus`. On desktop the
  Compose focus system moves focus. On iOS an unconsumed Tab may reach UIKit's
  `insertText("\t")` and type a tab character when Compose has nowhere to move
  focus; on the web the input session's hidden text area may take the browser's
  default Tab and move DOM focus off the canvas. Confirm on both (the iOS half
  is in the Mac queue) and consume or drop the Tab in the session if so.
  Web done: Compose's text area prevents a Tab's default itself and hands the
  key to the focus system, so no tab is typed and the browser does not move
  focus; on the canvas (no session) Compose prevents it when it moves focus.
  The loss was elsewhere: when focus moved off the editor, the session ended
  and Compose removed the focused text area, dropping DOM focus to the page
  body, where no key reached Compose until a click. The web session now puts
  DOM focus back on its canvas a task after it ends, when its text area held
  it, the last press was not outside the viewport, and nothing else has taken
  it (`stopRefocusing`). A session whose root lookup fails (two viewports)
  does not.
  Compose's own `BasicTextField` (the find bar's) still drops focus the same
  way. Checked in Chromium against the dev server: Escape
  then Tab left no tab in the text and DOM focus on the canvas, Shift+Tab from
  there brought focus back to the editor with a new session, and a Tab in a
  read-only editor was prevented and handled by Compose. The iOS half stays in
  the Mac queue (2.9's row).
  iOS done, in 2.9's run on the simulator with a hardware keyboard and every
  edit logged: Ctrl+Tab, Escape then Tab, and Tab under `movesFocus` typed no
  tab character, and the last two moved focus off the editor to the demo's
  switches. With nowhere for focus to go, Compose's focus search clears focus
  rather than keeping it (seen on desktop, from the same common code), so the
  session ends and UIKit's tab reaches no editor. The handled Tab's own echo
  was the bug found there, fixed in 2.9.
- [ ] **4.41 A stale connection's close ends its successor's composition. S.**
  [Fable] [Human] [Lane F] `TextEditorInputConnection.closeConnection` finishes the
  state's composition (5.9) whichever connection it is, while
  `connectionClosed` guards on `activeConnection`. Android closes the old
  connection after `restartInput` has opened and started the new one, posted
  to the main thread, so a close arriving after the new keyboard has begun a
  word finishes that word mid-composition: a behavior may rewrite it and the
  keyboard's next `setComposingText` inserts rather than replaces. Before
  5.9 the same close dropped the composing range with the same duplication.
  A restart's own close is the one that ends the old composition today (the
  `ImeCursorSync.flush` comment relies on it), so a guard must end it at the
  restart instead, or at `connectionOpened`; decide against a keyboard trace
  of a restart (4.30's recorder) before changing it.

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
- [x] **5.2 Smart punctuation.** [Opus] [Lane G] Curly quotes, dashes from
  double hyphens, ellipsis. One undo step reverts the substitution, as in
  native editors: typing `--` gives an em dash and undo gives back `--`.
  Decided: an opt-in behaviour in core, off by default, built on the 5.1
  hook. Natively only iOS applies smart punctuation, in the keyboard (UIKit's
  smart quotes and dashes); macOS does it in `NSTextView`, which Compose does
  not use, and Android, Windows, Linux, and the web leave it to the app. So
  on iOS the behaviour stays off unless the host turns the keyboard's own
  off, or the text would be converted twice (see the Mac queue).
  Done: `behaviors/SmartPunctuation`, a data class with a switch per
  substitution (`doubleQuotes`, `singleQuotes`, `emDashes`, `enDashes`,
  `ellipses`). Quotes open at a line start and after whitespace, an opening
  bracket or the other kind's opening quote, and close elsewhere; after a dash
  they close when the line has a quotation of their kind open (interrupted
  dialogue), else open; an opening single quote before a digit becomes an
  apostrophe (`'90s`). Two hyphens make an em dash at once, spaced or not
  (macOS); a spaced hyphen after a letter or digit makes an en dash when the
  space after it is typed (Word); a third hyphen puts `---` back. A committed
  word is processed as if typed character by character; inline code and code
  blocks are never touched; one undo gives back the straight characters (`SmartPunctuationTest`, `SmartPunctuationE2eTest`,
  `WriterBehaviorsImeTest`, `WriterBehaviorsInputConnectionTest`). Design in
  `docs/design/behaviors.md`. The sample's rich text demo has a switch for each.
  Checked on the iOS simulator with Smart Punctuation on (the default): typing
  `it's` and `a--b` arrives as `it’s` and `a—b`, and quotes arrive curled, all
  converted by iOS before the input session hands them over. So 5.2 stays off
  on iOS by default. With the keyboard's Smart Punctuation off and the demo's
  switches on, typed on the soft keyboard: `"` after a space opens as `“`, `'s`
  closes as `’s`, `a--` becomes `a—`, "teh" and space still autocorrect to
  "the" after a substitution (4.29's `None` holds), and one undo after `a--`
  gives back `a--`. (The simulator tool's `text` typing sends `'` for `"`, so
  a quote check through it reads single quotes.)
- [x] **5.3 Markdown as you type.** [Opus] [Lane G] "- ", "1. ", "# ", "> " at
  line start; inline `**bold**` and friends. Decided: not in core. Markdown
  is a storage detail for a WYSIWYG host like Hammer, which does not want
  it. It belongs in `ComposeTextEditorMarkdown` (7.52), as an opt-in
  `EditBehavior` on the 5.1 hook that the markdown demo installs, with
  examples there.
  Done: `markdown/MarkdownShortcuts(blocks, inline)`, installed with
  `editBehaviors.add(0, MarkdownShortcuts())`. At a line's start `- `, `* `,
  `+ `, a number and `. ` or `) `, one to six `#` and `> ` make the block when
  the space is typed (a marker the line's blocks refuse stays text), and
  three backticks with a language then Enter make a code block in it. A
  closing `**`/`__`, `*`/`_`, `` ` ``, `~~` or `==` makes its style over the
  text since an opener of the same run length that starts a word, the
  markers removed, and text typed after it is unstyled. One undo gives back
  what was typed; nothing converts in code (`MarkdownShortcutsTest`; design in
  `docs/design/behaviors.md`). The sample's markdown demos have a switch.
- [x] **5.4 Auto-link** [Opus] [Lane G] typed and pasted URLs. Decided: opt-in,
  off by default. Paste does not go through the 5.1 hook, so the pasted half
  needs its own seam.
  Done: `behaviors/AutoLink(typed, pasted)`, installed with
  `editBehaviors.add(0, AutoLink())` so it runs ahead of the line block
  behavior on Enter. A typed URL links when whitespace, Enter, or a closing
  bracket the URL did not open follows it; a paste links every URL it holds.
  URLs start with `http://`, `https://`, `ftp://` or `mailto:`, or `www.`
  (linked as `https://`), and email addresses link as `mailto:`; bare domains
  do not. Trailing punctuation and unbalanced closing brackets are left out
  (Word, Google Docs, GitHub). `sanitizeLinkUrl` gates the destination and
  `setLink` makes the link, its own undo step after the text, so one undo
  takes the link off and keeps the text; Enter's link shares the Enter's step
  (5.11). Code and existing links are left alone; a paste is judged out to the
  runs it joins. The paste seam is `EditBehavior.onPaste(state, text, range)`,
  offered by both paste actions in `input/BuiltinEditorActions.kt` through
  `TextEditorState.pasteLanded` once the paste has committed. A behavior that
  only styles the landed text (a link) no longer ends the hook's chain; one
  that changes the text still does, so auto-link and smart punctuation act on
  one commit (`AutoLinkTest`, `AutoLinkE2eTest`,
  `WriterBehaviorsImeTest`, `WriterBehaviorsInputConnectionTest`). Design in
  `docs/design/behaviors.md`. The sample's rich text demo has a switch for
  typed and for pasted URLs.
- [x] **5.5 Enter after a heading. S.** [Opus] [Lane G] `LineBlockEditBehavior`
  continues any line block, headings included, so the line after a chapter
  title is another heading. It should be body text. Done: Enter at a heading's
  end, empty heading or not, leaves the heading and opens a body line (Word's
  and Google Docs' next-paragraph style); a split inside a heading keeps both
  halves headings; a quote around the heading continues. Text typed at the start
  of a line takes the text style its own line's block bakes in (a heading's
  size), not the line above's. Enter also continues every block on the line, not
  only the first (a quoted list item stays a list item; Enter on an empty one
  leaves the list and stays quoted), and records the markers it sets as a
  `LineBlock` operation in the same undo step, so a redo of Enter in a list
  brings the new item's bullet back, which it did not
  (`blocks/HeadingEnterTest.kt`).
- [x] **5.6 Nested lists.** [Fable] [Lane I] Unsupported in the block model and
  the markdown parser (`docs/design/line-blocks.md`, known limitations).
  Tab and Shift+Tab at a list item's start are the chords to nest and un-nest
  it; since 2.9 Tab does nothing there (`handleIndent` in
  `input/BuiltinEditorActions.kt`). Done. Model and markdown: a list line's
  level lives in its span style (`BulletListSpanStyle.of(level)`,
  `OrderedListSpanStyle.of(level)`, the bare names level 0), one list block
  per line, indent and marker glyph per level, numbering per level; import
  resolves levels from indentation by CommonMark's content offsets and export
  writes them back, an orphaned deeper item at the level its predecessor
  allows. Editing, as Google Docs, Word, Notion and Apple Notes have it: Tab
  at an item's start nests it (never deeper than one below the item above,
  a selection as one block), Shift+Tab un-nests it with the items under it,
  Enter continues the level, Enter on an empty nested item un-nests and on an
  empty top-level item ends the list, Backspace at a nested item's start
  un-nests, a toggle keeps the level when switching kinds and lifts a cleared
  parent's children; each is one undo step (`richstyle/ListNesting.kt`,
  `TextEditorState.nestListItems` and `unnestListItems`). Tab inside an item's text
  still inserts, per 2.9. HTML nests since 7.47. Design in
  `docs/design/line-blocks.md`, "Nested lists".
- [x] **5.7 Paragraph formatting.** [Fable] [Lane N] Paragraph spacing does not
  exist; rows stack with no gap. No per-paragraph alignment, indent, or line
  height. Global `textIndent`, `lineHeight`, and `textAlign` already work
  through `textStyle` (relevant to hammer-editor#927). Done
  (`docs/design/incremental-relayout.md`, section 11): a paragraph's format
  is a line-anchored content span, `ParagraphFormatSpanStyle` (space before
  and after in dp; alignment; an indent and a first-line indent in sp or em,
  added to a block's own; a line height), set with
  `TextEditorState.setParagraphFormat` and read with `paragraphFormat`, one
  undo step; `TextEditorStyle.paragraphSpacing` is the space after every
  paragraph without one. The spacing lies between a paragraph's last row and
  the next row, outside every row: the caret, the selection and hit testing
  stay on rows, and a point in a gap goes to the row above it. Alignment,
  indents and line height are shaped in, over the block's indent, with the
  stored line untouched. Enter at a paragraph's start or end carries its
  format, as word processors do (`RichSpanStyle.boundToParagraph`). The saveable state keeps the format; markdown cannot
  and loses it (a markdown round trip drops every paragraph format); HTML
  carries it as inline styles (7.49). `ParagraphFormatTest`.
- [x] **5.8 Clear formatting and unlink** [Opus] [Lane D] actions.
  Done: `Action.ClearFormatting` on Ctrl+\ and Cmd+\ (Google Docs; Word's
  Ctrl+Space switches the input method) and `Action.Unlink`, unbound, through
  `TextEditorState.clearFormatting` and `unlink`, each one undo step. Clear
  formatting keeps heading and code block styles and, in a markdown editor, the
  body style and the link style on links; at a caret it resets the typing
  style. Unlink removes whole links the selection
  touches or the caret is in. See `docs/design/editor-actions.md`,
  "Formatting toggles".
- [x] **5.9 A composition the editor ends is not offered. C.** [Fable]
  [Lane G] 5.1 offers a typed composition when the IME commits or finishes
  it, but the editor also ends one itself, with a bare `clearComposingRange`:
  a tap or drag outside it (`endCompositionIfPointerLeft`), focus loss, and
  the Android connection closing. The IME's later `finishComposingText` then
  finds nothing, so a behavior never sees the last word typed before a tap.
  Offering it there means a behavior may edit while the pointer is placing
  the caret, so decide who owns the caret first. Touches lane B's pointer
  handling and lane F's connection; do it when both are idle.
  Done: `TextEditorState.finishComposition` ends a composition keeping its
  text and offers a typed one to `onTextInput`, as the keyboard's
  `finishComposingText` does; a pointer leaving the composition, focus loss
  and the Android connection closing all go through it. The pointer owns the
  caret: a tap, press, drag or handle drag finishes the composition before
  its placement is read, so a behavior's edit is laid out first and the
  caret or selection then goes where the pointer is on the substituted text,
  which also keeps a double-click's or drag's selection, since a behavior's
  edit would clear one made before it. A caret placed inside the composition
  keeps it, as before. A selection already standing (a handle grabbed, focus
  lost) is put back after the behaviors' edits, mapped, unless an edit reached
  into it. Focus loss while an Android batch is open (disabling the editor
  counts as focus loss) leaves the composition to the connection's close,
  since what is offered mid-batch is dropped with the batch when the keyboard
  never ends it. The behavior's edit stays its own undo step and asks the IME
  to resync. Paste and drop finish one too (6.47).
- [x] **5.10 Text typed at a link's end joins the link. R.** [Opus] [Lane G]
  Typing right after a link (`setLink`, a pasted or auto-made one) takes its
  link style and grows its `LinkSpanStyle` over the new text, and after Enter
  at the link's end the next line's text takes the link style without being
  a link (probed in a scratch test: the style and `linkAt` both carry on).
  Word, Google Docs and TextEdit end a link at its last character: text typed
  after it is plain and outside it. Leave the link and its style out of the
  typing style at a link's end, on the same line and across a line break, and
  stop the span growing; a caret inside a link keeps both. Shows with 5.4: a
  pasted URL ends at the caret, so text typed straight after the paste joins
  the link, and after Enter following a typed URL the next line looks linked.
  The span's growth is the re-anchoring in `RichSpanManager`, lane G's.
  Done: an insert at a link's end leaves its `LinkSpanStyle` where it was, and
  the typing style past a link's end, on its line or after Enter, or before a
  link with nothing ahead of it, leaves out the link style, the current one or
  one an earlier configuration gave it; a caret inside a link keeps both. Undo
  of a delete at a link's end gives the link back whole, and a rich paste of a
  link against a link to the same place joins it rather than leaving two
  (`state/LinkEndTypingTest.kt`, `e2e/AutoLinkE2eTest.kt`).
- [x] **5.11 An auto-link made by Enter undoes with the line break. S.** [Opus]
  [Lane G] `AutoLink` links the URL before the caret in `onNewline`, which runs
  inside `insertTypedNewline`'s undo group, so one undo takes back the line
  break and the link together, keeping the URL; after a space it takes back
  only the link, as Word does after either. A hook told once a typed line break
  has landed (after the group, as `onTextInput` is) would give Enter the same
  shape, and would free the behavior from running ahead of
  `LineBlockEditBehavior`.
  Done: `EditBehavior.onNewlineLanded(state, range)` is told where a line break
  `onNewline` was offered has landed (Enter, an IME's lone line break, a host's
  `insertNewlineAtCursor`, the line block behavior's own split), after the
  Enter's own step, as typed text is offered; an Enter that makes no plain line
  break is not offered. `AutoLink` links there instead of in `onNewline`, so one undo
  takes the link off and keeps the line break, and it links on a list item's or
  quote's Enter wherever it sits in the chain (`behaviors/AutoLinkTest.kt`).
- [x] **5.12 A drop is not offered to `onPaste`. S.** [Opus] [Lane H] Text
  dropped into the editor (`dragdrop/TextDragAndDrop.kt`, `dropText`) lands
  without telling the behaviors, so `AutoLink(pasted = true)` leaves a dropped
  URL plain, where Word and Google Docs link it. Offer a drop through
  `TextEditorState.pasteLanded` once it has committed, as the paste actions do.
  A host replacing the paste actions has no way to offer its paste either,
  since `pasteLanded` is internal; make it public if a host asks. Done:
  `dropText` offers the dropped text where it landed once its edit group has
  committed, so a dropped URL links and one undo takes only the link off. A
  move within the editor is not offered, since it is not new text (a URL the
  user unlinked stays plain), and neither is a refused drop. `TextEditorState.pasteLanded` is public, for a host that performs its
  own paste to call once that paste has committed; a range past the document's
  lines throws (`dragdrop/DropOfferedAsPasteTest.kt`).

- [x] **5.13 Composing over a link's word drops the link. S.** [Opus] [Lane G]
  An input method that puts its composing region back over the word before the
  caret (Gboard after a tap) and then sets new text there sends a replace over
  the word. When the word is the whole link, `RichSpanManager`'s replace takes
  the link as text replaced within it and drops it; over part of one it cuts the
  link short, while the inherited style keeps the link's look on the new text.
  A replace inside a link (or of all of it) on one line should keep the link
  over what lands there, as typing inside it does. Found in 5.10's review.
  Done: a link a one-line replace touches is placed by the characters the
  replace changes, the shared start and end with the text it replaces staying
  as they were (`RichSpanManager.linkAfterReplace`, `sharedEnds`): a change
  inside the link joins it, one at its end or before its start stays out, as
  typing does since 5.10. An `inheritStyle` replace aligns its styles the same
  way, and characters it adds where it replaced a link's characters take the
  link's look. So a composition over the word keeps the link however far it
  runs past the link's end, one key at a time, and letters it adds there stay
  out, whether or not the input method recomposes the word; a correction or
  find replace of a linked word keeps it whole. New text in the link that does
  not look linked (plain text pasted over it), a change across the link's
  edge (5.14) and a link across lines get the general handling
  (`state/LinkComposingTest.kt`). Found: 5.14, 5.15.
- [x] **5.14 A replace reaching past a link's end leaves its look outside it.
  R.** [Opus] [Lane G] An `inheritStyle` replace from inside a link to past its
  end ("nk he" of "see link here" with "xx") cuts the link at the replace's
  start, but each new character inherits the styles of the replaced one at its
  position, the link's look included, so "xx" looks linked and is not. Either
  the link should take in what lands from inside it, or the look should stay
  off; `removeLinkLookOutsideLinks` does the second for a paste. Found in 5.13.
  Done: the look stays off, as text typed at a link's end does. A one-line
  replace whose change reaches across a link's edge takes the characters it
  changes out of the link (`RichSpanManager.linkAfterReplace`): the link keeps
  its characters before the change, or after it, the ones the replace leaves as
  they were included. Those changed characters take no link look, which they
  keep only when one link holds all the characters they replace
  (`TextEditManager.resolveInheritedStyle`). So "nk he" of "see link here"
  replaced by "xx" leaves "li" linked and "xx" plain, and "ink h" replaced by
  "inxx" leaves "lin" linked (`state/LinkComposingTest.kt`). A replace across
  lines, or of a link across lines, keeps the general handling. Found: 5.16,
  5.17.
- [x] **5.15 A link pasted onto another link overlaps it. C.** [Opus] [Lane G]
  A rich paste of a copied link into another link, inside it or over all of
  its word, keeps the link it lands in over the pasted text (an insert inside
  a link joins it, and since 5.13 a replace of its word does when the text
  looks linked, as the copy does) and then adds the copied link over the same
  text (`addPreservedRichSpans` only merges a link of the same destination),
  so two links with different destinations cover it and `linkAt` and the
  serializers see either. A pasted link should take its text out of the link
  it lands in. Found in 5.13's review.
  Done: reproduced, inside the link and over its word, from this editor's copy
  and from markup. A link placed over text a link to elsewhere covers takes that
  text out of it, the other link keeping its parts before and after
  (`takeOutOfOtherLinks`): a pasted or dropped link from the span buffer
  (`addPreservedRichSpans`), a link from pasted markup, and `setLink`. A
  markup link that a link to the same place already covers, the buffer's
  (compared through `sanitizeLinkUrl`, as the copy wrote it) or the one the
  paste landed in, is not added again (`clipboard/PastedLinkInLinkTest.kt`).
- [x] **5.16 Redo of a composition over a link's end loses the link. S.**
  [Opus] [Lane G] Composing over the linked "link" of "see link here" as "lin"
  and then "linx" lands "lin" linked and the "x" out of it, one key at a time
  (5.13). The history merges the run into one replace of "link" by "linx",
  whose change ("k" to "x") lies inside the link but whose "x" was baked
  without the link's look, so on redo `linkAfterReplace` hands it to the
  general handling, which drops the link the replace covers. Undo and redo
  leave no link. Found in 5.14's review.
  Done: a change at a link's first or last characters, not both, whose new
  text does not look linked takes them out of the link, as one typed there
  does, and when the replace's text looks linked anywhere, a character the
  link holds counts as left as it was only when its new one looks linked
  (`sharedEnds` takes a comparison). So the merged "link" to "linx", and
  "lin" then "links" (whose shared "k" lost the look), redo to "lin" linked,
  and a replace of " li" by a plain " x" and a linked "i" leaves "ink"
  (`state/LinkComposingTest.kt`). Plain text over a whole link still drops it.
- [x] **5.17 A replace across the edge of a link spanning lines leaves its
  look outside it. S.** [Opus] [Lane G] `linkAfterReplace` places only a link
  on one line; a link across lines gets the general handling, which cuts it at
  the replace's start or end, while the characters the replace leaves as they
  were at its start or end keep their link look. With a link from (0,4) to
  (1,3), replacing "e li" at (0,2) by "abli" starts the link after "li", which
  still looks linked. Found in 5.14's review.
  Done: `linkAfterReplace` places a link across lines too when the replace is
  on its first or last line, where only its own start or end is an edge: the
  example leaves "link" linked from "li", a replace across its end keeps the
  letters it leaves as they were, and a composition merged over its end keeps
  it on redo. A replace on a line between keeps the general handling, which
  keeps the link whole (`state/LinkComposingTest.kt`). Found 5.18.
- [x] **5.18 A plain replace inside a link joins it without its look. S.**
  [Opus] [Lane G] A replace that does not inherit styles (`replace` with
  `inheritStyle = false`, the host's) of letters inside a link, say "nk" of
  "link" by "NK", leaves the link over "liNK" (the general handling bridges
  it) while "NK" does not look linked. Plain text over a link's first or last
  letters leaves the link instead, and over its whole word drops it. Either
  the look should follow, or the link should leave the plain letters,
  splitting around them. Found in 5.17's review.
  Done: the look follows, as typing there and native editors have it: plain text a
  replace puts strictly inside a link, on its line or a later line of one spanning
  lines, takes the look of the link's character before it (after it at a line's
  start), baked into the replace so undo and redo carry it. A replace reaching a
  link's first or last characters still leaves the link, and one covering the whole
  link drops it, even when it changes only letters inside (`state/LinkComposingTest.kt`).

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
- [x] **6.4 Restore the selection,** [Opus] [Lane G] not only the caret. Done,
  as `BasicTextField`'s undo does: each step records what was selected when its
  transaction began and when it committed (`HistoryEntry.selectionBefore` and
  `selectionAfter`); undo selects the first again, with the caret where it was,
  and redo the second. So undoing typing over a selection, a deleted selection or
  a paste over one selects the replaced text, undo and redo of a style keep its
  selection, and a step made with nothing selected leaves nothing selected. A
  typing run keeps the selection it began from, and an edit that replaces a
  selection starts a step of its own (`state/UndoSelectionTest.kt`,
  `e2e/UndoRedoE2eTest.kt`). A touch selection comes back without its handles.
- [x] **6.5 Pasted HTML blocks are unrecorded. C.** [Opus] [Lane G] Redo should
  restore the text without its blocks. Inferred; no test covers it. Confirmed:
  redo of an HTML paste gave back its text with no list, rule, link or paragraph
  format. Done: the blocks, links and formats a paste or a drop of foreign HTML
  places are recorded in its undo step (`TextEditManager.recordLineChanges`: the
  lines' content and blocks as a LineBlock step, each other span that came or went
  as a RichSpan step), so redo replays them with the text
  (`e2e/HtmlPasteUndoE2eTest.kt`).
- [x] **6.6 Time-based coalescing breaks,** [Opus] [Lane G] and a configurable
  history cap. Done: `TextEditorState.undoSettings` (`UndoSettings`) holds
  `maxSteps` (default 1000, as before; lowering it drops the oldest steps) and
  `typingPause` (default 2 seconds; `Duration.INFINITE` turns it off). A typed
  or deleted character after a pause that long starts a new step; an input
  method's rewrites of the word it composes are never split, and no run
  continues across an undo or a redo. `BasicTextField` instead ends a run 5
  seconds after it began, typing or not, and keeps 100 steps
  (`state/UndoSettingsTest.kt`).
- [x] **6.14 An IME composition inherits the style it touches. R.** [Opus]
  [Lane G] A composition replace runs with `inheritStyle`, which takes every
  span merely touching the replaced range (`TextEditManager`, the resolve of
  inherited styles). Bold text, bold toggled off at the caret, then a composed
  word: its first update is plain, its second re-bolds it, so on a composing
  keyboard non-bold text cannot follow bold text. Inherit from the replaced
  characters themselves, and from the caret's typing style when there are
  none. Done: each character of an `inheritStyle` replace takes the styles of
  the replaced character at its position, on one line as across lines; the
  characters past them, and a replace of nothing, take what an insert at the
  range's end would (the caret's typing style when the caret is there), so a
  re-marked bold word typed on with bold off gains plain letters
  (`state/InheritedStyleTest.kt`).
- [x] **6.15 A style operation drops the line's paragraph style. C.** [Opus]
  [Lane G] `SpanManager.applySingleLineSpanStyle` and
  `removeSingleLineSpanStyle` rebuild the line from its text and character
  styles only, so Ctrl+B on a list or quote line loses the indent
  `ParagraphStyle` the block baked in, and nothing restores it (normalization
  only repairs placeholder lines). Carry `paragraphStyles` through; 6.3's undo
  then restores the line exactly. Done: both replace only the line's span styles
  (`withSpanStyles`), keeping its paragraph styles and other annotations in
  place and its spans in their order rather than sorted by start, so a later
  span still wins (a heading's size over the body size) and the undo gives back
  an equal line. A strike over the head of an italic run now exports the space
  it covers struck (`state/StyleKeepsParagraphStyleTest.kt`).
- [x] **6.22 A replace of nothing moves a block marker. C.** [Opus] [Lane G]
  `RichSpanManager.handleReplace` has no `stickyAtStart` case, so a `Replace`
  over an empty range at a line's start shifts that line's list, quote or
  heading marker off column 0, where an `Insert` at the same place keeps it.
  Found in 7.3, whose `setText` sends an insertion as an `Insert` to avoid it;
  `TextEditorState.replace` with a collapsed range still hits it. Done: a replace of nothing
  without a line break on a line-anchored marker's first line keeps the marker's
  start at column 0 and takes the text in, at its end too, as an insert does;
  that also fixes the undo of a replace that emptied an item's head. A span
  starting after a replace no longer has its end moved along a later line by the
  replacement's column shift. A line break in the text is 7.43's
  (`state/CollapsedReplaceSpansTest.kt`).
- [x] **6.23 Line endings are normalised per entry point. C.** [Opus] [Lane G]
  6.8 normalises in `insertStringAtCursor`, `replace`, `setText`, the IME and
  paste; an operation built directly and handed to `applyOperation` (the
  semantics `setText`'s insert, found in 7.3's rebase) skips all of them.
  Normalise once where `Insert` and `Replace` are applied. Done:
  `applyOperation` normalises an insert's or replace's text (and a replace's
  `oldText`) before the input filter sees it and again after, and a caret put
  after the text lands after what lands; `screenInput` does the same for the
  paths that screen first, so a filter never lands a carriage return.
  `insertStringAtCursor` and `replace` no longer normalise for themselves; the
  IME, typed text, paste and drop still normalise where they need the landed
  length or spot an Enter (`state/OperationLineEndingsTest.kt`).

- [x] **6.28 Undo to origin fails under fuzz seed 777. R.** [Opus] [Lane G]
  `FUZZ_SEED=777 ./gradlew :ComposeTextEditor:desktopTest --rerun --tests
  'e2e.torture.*'` fails `EditorStateFuzzTest` "undo to origin" (the character
  styles differ) and `EditorFuzzE2eTest` "ui undo to origin" (a blockquote span is
  left on line 0) at `5bde800` already, so no recent chunk caused it. Shrink the
  script to the op that breaks the round trip and fix it; add 777 to the fixed
  seeds. Done: 777 shrank to typing over a selection from a plain line into a
  quote line. The replace moved the quote's marker onto the joined line, where a
  delete of the same range drops it (the joined line is the plain line's), and
  undo put the quote back on its own line while the moved one stayed. A
  line-anchored marker whose tail a replace joins onto an earlier line's kept
  head now goes unless that line has the same marker, and a placeholder block
  (a rule, an image) likewise (`state/ReplaceAcrossLinesSpansTest.kt`). A sweep
  of seeds 1 to 3000 found two more undo causes, both fixed: a join wrote a
  style's runs on each side as two overlapping spans, and merged runs a
  character apart, styling the gap (`buildAnnotatedStringWithSpans`); and
  undoing a join split the joined line again, which carries the first line's
  marker and paragraph style over the second's text whenever that marker
  reached past the join point (a character deleted and restored there, a block
  toggle in between). A recorded delete or replace that joins lines now keeps
  the first and last lines as they were, and one that breaks a line keeps that
  line (`OperationMetadata.linesBefore`); undo writes them back exactly
  (`state/UndoLineJoinTest.kt`). Seeds 777, 38, 185 and 359 are in the fixed
  seeds; undo to origin holds for seeds 1 to 3000. What the sweep found besides
  is 6.33 and 7.67.
- [x] **6.29 Nesting a long selection writes per line. S.** [Opus] [Lane G]
  `nestListItems` and `relevelListFollowers` (`richstyle/ListNesting.kt`) move each
  item with `setListLevelRaw`, a line splice and two span-index publishes per item,
  so Tab over a 400-item selection costs 400 splices where 6.17's toggle costs two.
  Collect the moves and write them with `writeLineBlocks`. Done: nesting,
  un-nesting and the followers they lift plan their moves in a `ListMoves`, whose
  level queries read the planned levels, and write them once; Tab or Shift+Tab
  over 400 items, or lifting 399 followers, writes the line list once and the span
  index twice, and clearing an item with followers twice each
  (`state/MultiLineEditCostTest.kt`). Found on the way: undoing a clear or a
  quote of a nested item left the items it lifted past its sibling where they
  were lifted to, since the lines recorded came from a walk that stopped at the
  sibling; they now come from the planned moves (`blocks/NestedListEditingTest.kt`).
- [x] **6.33 A join leaves a block's indent over part of a line. R.** [Opus]
  [Lane G] Deleting or replacing across a quote line and a plain line keeps one
  line's marker (or none) but carries the other's `ParagraphStyle` over its part
  of the joined line (`handleMultiLineDelete`, `handleMultiLineReplace`), so
  Compose lays out that part as a separate, indented paragraph with no marker:
  deleting from column 5 of "seed line" to column 1 of a quote line "second
  line" leaves "seed econd line" with the indent over "econd line". Fuzz seed 246 (the markdown module's
  `MarkdownFuzzFixpointTest`) ends with a fenced line whose paragraph runs are
  `[0-2, 7-22]`, and an Enter there then throws "Paragraph overlap not
  allowed". A joined line should carry one paragraph style run, the one its
  kept marker wants. UI fuzz seed 27 (`FUZZ_SEED=27` on core's
  `EditorFuzzE2eTest` or the markdown module's `MarkdownUiFuzzFixpointTest`)
  throws the same from a Backspace's `handleMultiLineDelete` at op 43, after a
  block toggle and an Enter; found in 7.65's seed sweep.
  Done: every publish now gives each changed line exactly the paragraph styles
  its markers want, each over the whole line (`repairBlockParagraphs`, after
  `normalizeLineBlocks`); a paragraph style no block uses passes through, and a
  rewritten line widens the pending partial relayout. The markers decide
  whatever moved the text: a join, a split, a paste whose markers land after
  its text, a host adding or removing a block span on the public span API. Also
  fixed by it: emptying a quote line dropped its indent while the marker
  stayed, so text typed there again had none (`state/JoinParagraphStyleTest.kt`;
  seed 246 is in the markdown fixed seeds, and UI fuzz seed 27 in
  `EditorFuzzE2eTest`'s and `MarkdownUiFuzzFixpointTest`'s; seeds 1 to 1500 of
  the markdown state test keep every paragraph run whole). `RowListCostTest`'s span removal now reshapes the
  one line that loses its indent.
- [x] **6.34 Undoing a multi-line insert at a paragraph's start drops its
  format. R.** [Opus] [Lane G] A paragraph format (`ParagraphFormatSpanStyle`)
  stays on the first line of a multi-line insert at its paragraph's start
  (`RichSpanManager.handleInsert`), so "target" centred, with "new\nx" pasted
  at its start, leaves the format on "new" and none on "xtarget"; undo deletes
  the first line with its format and "target" comes back plain. Enter at the
  same place keeps the format on both lines. Found in 6.5.
  Done: lines landing at a paragraph's start, inserted or replacing text from
  there (an empty paragraph's, or all of it), leave its format on the first of
  them and on the paragraph's own text after the last, as Enter does; lines
  between stay plain, as a paste inside a paragraph leaves them. Undo gives the
  format back. A copied paragraph format pasted onto a line replaces the format
  there, so a line keeps one (`state/ParagraphFormatTest.kt`; the HTML paste
  test in `e2e/HtmlPasteUndoE2eTest.kt` now has "xtarget" keep its centring and
  undo bring it back).
- [x] **6.35 A join leaves a heading's or fence's text style over part of a
  line. S.** [Opus] [Lane G] A heading or code fence bakes its `textStyle` (the
  heading's size, the fence's monospace) into the line's text. A join keeps one
  line's markers but leaves each piece's text styles as they were: deleting from
  column 4 of "seed" to column 2 of a heading "Heading" leaves a plain line with
  "eading" at the heading's size, and Delete at the end of a heading joins the
  body line below at body size. Word and Google Docs give the joined paragraph
  the first paragraph's style over all of it. Strip an ended block's text style
  and bake a kept one's over the joined line, as `continueLineBlocks` does for a
  heading on a split, or derive both the text and paragraph styles from the
  markers when a line is shaped, as `LineShaper` does for a paragraph format,
  which retires 6.33's repair too. Found in 6.33's review.
  Done: 6.33's repair (now `repairBlockStyles`) also bakes each changed line's
  block text styles over the whole line, after any run of the body style (so a
  body line joined onto a heading takes its size) and before the spans the user
  set inside the heading (so those still win). It strips no text style, since
  one equal to a heading's look on a plain line is the user's own (7.64); the
  edit that moves text strips instead: text landing on a line its own markers
  do not reach (a join's tail after another line's head, a split's tail, text
  an `inheritStyle` replace across lines or breaking its line inherits) leaves
  its source line's block text styles behind. A text style equal to an inline
  style is neither baked nor left behind. Undo gives both lines back as they
  were (`state/JoinBlockTextStyleTest.kt`). Found: 6.38, 7.79.

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
  lists, quotes, fences, links), and the body size through 6.18. iOS carries
  markup both ways since 4.9.
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
- [x] **6.16 Link destinations are sanitised only in HTML. S.** [Opus] [Lane B]
  Markdown import, `setLink` and the in-editor span buffer keep `javascript:` and
  `data:` destinations, and Ctrl/Cmd+click hands them to `onLinkClick` or the
  `UriHandler` (`textEditorPointerInputHandling.kt`). Refuse them where a link is
  opened, with `html/HtmlLinks.kt`'s `sanitizeLinkUrl`, so every source is covered.
  Done, with the one allowlist (relative URLs and http, https, mailto, tel and
  ftp) at import and where a link opens. Markdown import reads a destination
  as a renderer does (backslash escapes and entities decoded, so
  `javascript&#58;` is caught) and keeps a refused link's text without the link
  or its style, as HTML import does; `TextEditorState.setLink` refuses one and
  answers false (it now returns whether it set the link). A link a host attached
  directly stays in the document, and its copies, paste and markdown export
  keep it, but the pointer's link lookup gives it no hand cursor and no
  Ctrl/Cmd+click open, and the semantics text publishes no link annotation for
  it. `onRichSpanClick` and `TextEditorState.linkAt` still hand a host the span
  as it is (`html/LinkDestinationSafetyTest.kt`). A host's own scheme is refused
  as well: 6.25.
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
- [x] **6.17 Multi-line style and block edits copy the line list per line. S.**
  [Opus] [Lane G] `TextEditorState.setLine` copies the whole line list, and the
  multi-line style path in `TextEditManager.applyStyleOperation` and
  `applyLineBlockState` call it once per line, so Ctrl+B or a list toggle over a
  long selection (and their undo and redo) is O(lines x document). Stage the
  line list once per transaction, or collect the lines and write them with
  `replaceLines` as 6.11 did for paste.
  `state/LargePasteCostTest.kt` counts the lines written
  (`TextEditorState.linesWritten`) and shapes for a 400-line paste at the caret,
  over a selection, and through undo and redo. Done (since 7.8 a `setLine` copies
  a chunk and the directory, not the list, but it is still a splice and a publish
  per line): a style operation styles every line it covers and writes them in one
  `replaceLines`, and its undo stages the exact inverse's pieces, each still an
  operation through the pipeline, and writes them once; a line-block toggle plans
  every line (`planLineBlock`, `planDemoteLineBlock`, which `applyLineBlock` and
  `demoteLineBlock` share) and writes them with `writeLineBlocks` (a splice per
  run of lines, one span removal, one addition), as its undo and redo do, and the LineBlock
  operation it records no longer writes the lines a second time. `state/MultiLineEditCostTest.kt`
  counts line-list and span-index publishes (`lineListWrites`, `spanIndexWrites`)
  for bold and a list toggle over 400 of 2,000 lines, and their undo and redo.
  Nesting (Tab) and re-levelling a list's followers still write per line: 6.29.
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
- [x] **6.19 Cut deletes before the clipboard write can fail. S.** [Opus]
  [Lane H] `cutSelection` deletes the selection and then writes the clipboard in
  a coroutine. On the web the context menu's Cut writes through
  `navigator.clipboard`, which an insecure page, a lapsed user gesture or a
  refused permission turns into a logged warning, and the cut text is gone
  except through undo. Write first and delete on success, or refuse Cut where
  the write cannot happen. Done: `ClipboardHelper.setText` answers whether the
  clipboard took the text (the web's refusals, AWT's `IllegalStateException`
  while another application holds the clipboard, Android's refused clip), and
  Cut deletes only after a write that landed, in an undispatched coroutine so a
  write that does not suspend deletes before the action returns; a change to the
  text or the selection while the write is pending makes the cut a copy. A
  refused copy or cut puts back the rich-span buffer it replaced, which still
  describes what the clipboard holds, and the web counts a chord's clipboard
  event as the write only for the text it wrote (`clipboard/CutWriteTest.kt`).
  The `expect` changed its return type (Mac queue). Checked on the iOS
  simulator: compiles, the iOS tests pass, and Cut from the edit menu takes the
  word out and onto the pasteboard, and pastes back bold.
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
  spans on a moved range are 6.21 (done).
  Checked by hand on macOS (2026-10-01): a word moved within the editor, and
  with Option copied; a bold word dragged into TextEdit arrived bold and left
  the editor; text from TextEdit dropped in at the drop caret. Found: a word
  of another size moved or copied within the editor landed in the size of the
  text around the drop, since the markup a drop arrives as carries no font
  size. A drop of the editor's own drag now lands the text as it was dragged,
  which the editor still holds, as a paste of its own copy does
  (`dragdrop/DraggedTextStyleTest.kt`).
- [x] **6.21 A dragged move drops the text's rich spans. S.** [Opus] [Lane H]
  `dropText` deletes the source range and inserts the dragged text, so rich
  spans the HTML cannot carry (highlights, comments, a host's own) are lost
  where cut and paste keeps them through `copyRichSpans` and `pasteRichSpans`.
  Carry them the same way, keyed by the drag id. Done: a drop carrying this
  editor's own drag id, whose source still holds the dragged text and whose
  dropped text is that text, takes the source's rich spans at the drop (the
  same capture a copy makes, `preservedRichSpans`) and adds them over the
  dropped text inside the drop's undo step, a move or a copy. Overlays (spell
  check, find) stay with the passes that draw them, and line markers and
  formats stay with whole lines, which the markup restores. The replay, shared
  with paste, leaves out a span whose style already covers its range, since
  inserting beside or inside such a span stretched it. A drop into another
  editor carries only what its markup does (`dragdrop/DraggedRichSpansTest.kt`).
- [x] **6.25 A host's own link scheme is refused. S.** [Opus] [Lane H]
  Since 6.16 one allowlist (relative, http, https, mailto, tel, ftp) decides
  which links import and open, so a host whose documents link with its own
  scheme (`myapp://scene/3`, `obsidian:`, `sms:`) loses those links on markdown
  or HTML import, and one it attaches directly never reaches its `onLinkClick`.
  Let a host extend the allowlist (a set of schemes on the configuration or the
  state), still refusing `javascript:`, `data:`, `vbscript:` and `file:`.
  Done: `TextEditorState.allowedLinkSchemes` (snapshot state) is the whole
  allowlist, `DEFAULT_LINK_SCHEMES` unless the host assigns its own
  (`DEFAULT_LINK_SCHEMES + "myapp"`), matched ignoring case; relative URLs are
  always kept and `REFUSED_LINK_SCHEMES` (`javascript`, `vbscript`, `data`,
  `file`) always refused, whatever the set says. `sanitizeLinkUrl` takes the
  set, and every place that sanitises reads the state's: HTML import, paste
  (`ClipboardHelper.getText` takes it, so the pasted text keeps the link's
  look) and drop, markdown import, `setLink`, HTML export and copy, the
  pointer's open and hand cursor, and the semantics links, which rescan when
  the set changes. The standalone converters (`toAnnotatedStringFromHtml`,
  `toAnnotatedStringFromMarkdown`) take it too, defaulting to the defaults
  (`html/HostLinkSchemesTest.kt`). A scheme that is not ASCII is refused, and
  the state keeps a copy of the set it is given. `rememberSaveableTextEditorState`
  does not save the set; markdown export still writes every link, as 6.16 left
  it. The iOS clipboard and drag actuals take the set (Mac queue). Found: 6.32.
  iOS checked on the simulator: compiles, the iOS tests pass, and with
  `myapp` allowed, pasting `<a href="myapp://x">x</a>` markup keeps the link's
  look and span.
- [x] **6.26 Bold text at a heading's size copies out as a heading. S.** [Opus]
  [Lane H] HTML copy-out (`html/HtmlTag.kt`, `headerTag` and
  `uniformHeadingTag`) reads a run that is bold at a configured heading size
  as that heading, so a whole line bold at 24 sp is written as `<h2>` though
  it has no heading block. Since 7.46 a pasted size lands as sp relative to the
  body, so a bold word 1.5 times the body size can reach it. Take the heading
  from the line's heading block, which export already knows, rather than from
  the size. Done: it was worse than written: a bold word at a heading's size
  mid line was written as an `<h2>` inside the `<p>`. Export and copy take a
  line's heading from its heading block alone, so such text writes as
  `<strong>` (HTML writes no size, so the size stays only in an in-process
  copy); `uniformHeadingTag` is gone. The standalone `AnnotatedString.toHtml`,
  which has no blocks, still reads a run at a heading's size as that heading
  (`html/HeadingSizeBoldHtmlTest.kt`). Found: 6.31, 7.64.
- [x] **6.20 Drag and drop on Android, iOS and web. S.** [Opus] [Lane H]
  `dragdrop/PlatformTextDrag` has desktop actuals only. Android: build the
  transfer from `ClipData.newHtmlText` with `View.DRAG_FLAG_GLOBAL`, read drops
  from `toAndroidDragEvent().clipData` and its `x`/`y`, and start a drag from a
  long press inside the selection (lane B's touch handling). iOS and web: check
  what Compose Multiplatform's `DragAndDropEvent` exposes there (web has a
  `WebDragAndDropManager`) and fill in the same four functions.
  Android done: a global drag (`View.DRAG_FLAG_GLOBAL`) of
  `ClipData.newHtmlText`, the drag id as the local state, which only this
  process sees; a clip too large for the binder with its markup drags its
  text alone. As in `TextView`, only a drop back into the same editor moves;
  a drop into another editor or app copies. Drops read each item's markup or
  text from `toAndroidDragEvent().clipData`, one item per line, at the event's
  `x`/`y`, through the same drop path as desktop; the editor's own drag takes
  its markup only when it re-parses to the dragged characters. While the
  platform toolbar is up over the selection, a long press inside it starts
  the drag and hides the toolbar, as `EditText` does; with the toolbar gone
  the long press brings it back, a long press elsewhere still selects the
  word, and without a platform toolbar (desktop touch) the long press still
  opens the menu (`dragdrop/TouchSelectionDragTest.kt`, host
  `dragdrop/AndroidTextDragTest.kt`). Checked on the API 36 emulator: a word
  long-pressed and dragged along its line moved there, selected, and one undo
  put it back. A drop from another app was not driven: Chrome's first run
  wants its terms accepted. Found: 6.42 to 6.44.
  Web done: the browser starts the drag itself from a press on Compose's
  draggable canvas, and the editor gives one only for a mouse press it holds
  inside the selection (`SelectionDrag.holdPress`), which waits three times
  the touch slop for it (Firefox's GTK threshold is the slop); that drag then
  has the press, so the selection stays. The drag offers `text/plain`, `text/html`
  and the drag id (a custom type) on the `dragstart` event, written again
  after Compose clears `text/plain`. Compose's web `DragAndDropEvent` carries
  no position or modifiers, and at a drop it carries the last drag Compose
  started when that one never landed on the canvas, so a window capture
  listener records each drag event: the drop reads its `DataTransfer`, its
  position (from the canvas's client rect) and the copy modifier (Ctrl, or
  Option on Apple systems). Compose never tells a target that a drag left the
  canvas or ended, so the listener ends the drop carets then. Only
  a drop back into the same editor moves: Compose reports every drop on the
  page as a move, one nothing took included. Compose also ignores the first
  `dragenter` after any `dragstart`, so after a mouse selection on the canvas
  (whose `dragstart` it refuses) the next drag from outside was never taken; a
  refused `dragstart` is now followed by a `dragenter` Compose drops. Touch on
  the web does not drag (`dragdrop/PlatformStartedDragTest.kt`). Checked in
  headless Chromium against a development build with synthetic pointer and
  drag events: a word dragged from its selection offered its text, markup and
  id, kept its selection while dragged, drew the drop caret, and moved, or with
  Ctrl copied; an italic `text/html` drop from outside landed italic at the
  drop point, also after a refused `dragstart`, and plain text dropped after
  a drag of the selection left and ended elsewhere landed as itself.
  iOS left as it was, no drags and no drops (Mac queue). Drops: Compose
  1.12.1's iOS `DragAndDropEvent` exposes only its `UIDragItem`s, and where
  the drop is (`positionInRoot`, the `UIDropSession`) is internal, so a drop
  cannot be placed. Reading the items would be workable: iOS ignores what
  `onDrop` answers, so their `NSItemProvider`s could load and insert later.
  Drags: Compose's own `UIDragInteraction` on its overlay view starts them
  from a long press, racing the editor's edit menu (3.8); it offers copy only
  (`doesSessionAllowMoveOperation` is false), so a drag within the editor
  could not move; and it is off by default on iPhone. Following up in 6.46.
  Checked on the Mac (2026-10-01): compiles and the iOS tests pass. By hand on
  the iPad Pro 11-inch (M5) simulator, iPadOS 26: a long press inside a
  selected word shows the edit menu on lift and starts no drag; with the
  pointer, a click inside the selection places the caret and a press and drag
  inside it selects from the press; text dragged from Safari's window beside
  the sample app (iPadOS 26 windows apps rather than splitting the screen)
  and dropped on the editor lands nothing and nothing crashes. Not tried from
  Notes, which the simulator lacks.
- [ ] **6.46 Drag and drop on iOS. M.** [Opus] [Lane H] [Mac work] 6.20 found
  drops out of reach through Compose's `DragAndDropEvent` on iOS, which keeps
  the drop's location internal. Once Compose exposes it (recheck on each
  upgrade), drops can go through the existing path, loading the items'
  `public.html` and plain text asynchronously in `onDrop` and dropping
  through `TextDragAndDrop.dropAt`, an own drag known by its item's
  `localObject`. Drags out through Compose's interaction can only copy, so a
  move within the editor needs the drop to know its own drag. Installing the
  editor's own interactions instead competes with Compose's on its overlay
  view (`LocalUIView`), whose drop interaction is hit first and whose
  gesture handling exempts only its own long press. Either way, the long
  press inside the selection that lifts a drag must agree with the edit menu.
- [x] **6.42 A finger drag shows no picture of the text. S.** [Opus] [Lane H]
  The drag's decoration is 1 by 1 pixel, which suits desktop, where the
  platform's cursor shows the drag. On Android nothing follows the finger but
  the drop caret under it. `TextView` shows the text (up to 20 characters) in
  a bubble above the finger; Compose's `ComposeDragShadowBuilder` centres the
  decoration on the finger, so a picture has to sit within its size to show
  above it. Draw the dragged text where a finger started the drag.
  Done: `TextView`'s drag shadow (AOSP `Editor.getTextThumbnailBuilder`) is
  the text cut through the cluster at its 21st character, no ellipsis, at
  `TextAppearance.Large` in the field's text colours with its spans, on
  nothing, and centred on the finger (the default `DragShadowBuilder`), not
  above it; the emulator shows the same. A drag a finger starts
  (`SelectionDrag.start`'s `byFinger`) now draws that
  (`dragdrop/FingerDragPicture.kt`): the cut text in its span styles (a
  heading's size included) over the editor's text style at 22 sp, in the
  editor's text colour, unwrapped and centred, without blocks' indents and
  line heights, as the decoration, which Compose centres on the finger as
  `TextView`'s is. Mouse drags keep the 1 pixel decoration, an external
  mouse on Android too, where `TextView` shows its picture for any pointer
  (`FingerDragPictureTest`). On the API 36 emulator,
  beside an `EditText` holding the same text, a long press inside a selected
  26 letter word and a drag showed "abcdefghijklmnopqrstu" centred on the
  finger in both, the editor's with its bold red run.
- [x] **6.43 A word pasted or dropped back in lands larger. S.** [Opus]
  [Lane H] In the Android sample's rich text editor, copying "world" from
  the first paragraph and pasting it with Ctrl+V a few words on, or dragging
  it there, lands it visibly larger than the text around it, and the line
  grows. The markup round trip (`selectionAsHtml`, then the HTML import's size
  handling of 7.46 and 6.18) is the likely cause. The web demo does the same
  for a dragged move; check desktop.
  Done: not the markup. The demo assigns `richTextStyles` (which opts typed
  text into the body style, 16 sp) over a document of its own whose text
  carries no body style and renders at the host's 14 sp. Text with no style to
  adopt took the body style, so a paste, a drop, a move, and typing too, landed
  at 16 sp on every platform, desktop's in-process copy included. The fallback
  (`fallbackBodyStyle`, for `getSpanStylesForEditAt` and Clear formatting at the
  caret) is now the body style only where the document has no text or carries
  the body style (current or retired) somewhere; the answer is kept per line list
  (`clipboard/PastedTextSizeTest.kt`, host `clipboard/AndroidPastedTextSizeTest.kt`).
  Hammer imports markdown, which puts the body style on every paragraph, so its
  documents fall back as before. Limits: the decision is the whole document's,
  so one body run in a host's document (a paste from a markdown editor) brings
  the fallback back everywhere; a host document emptied and retyped takes the
  body style; an imported document with no paragraph (a lone fence) falls back
  to the host size.
- [x] **6.44 Android drops of text a URI carries. S.** [Opus] [Lane H]
  The editor takes any drag whose description has a `text/*` type, but reads
  only an item's text and markup, so a `.txt` file dragged from Files shows
  the drop caret and then drops nothing. `TextView` reads such items with
  `coerceToStyledText` under `requestDragAndDropPermissions`, which needs the
  activity. Read them the same way, or refuse such drags at the start.
  Done: an item with only a content URI is read at the drop, as `TextView` does,
  through the activity of the node taking it (`droppedText` takes that node,
  `target`; the other platforms ignore it) under the permissions the drop grants,
  released after. Only what the drag grants is read: a drop that grants nothing,
  or a URI that is not `content:`, reads nothing, so another app's drag cannot have
  this app read its own private files for it. A `text/*` file reads by its byte
  order mark, else its type's charset, else UTF-8; an HTML file's markup is parsed
  as a paste's, its blocks included; a type the provider does not give is the
  drag's when it names one. A drop reads at most 1 MiB of files in all, since it
  reads on the main thread; a file past that, another kind of file, or one that
  cannot be read drops nothing (host `dragdrop/AndroidTextDragTest.kt`). Not
  driven on a device: a drag from Files needs a person. Like `TextView`, a file
  from a slow provider (a cloud drive) holds the main thread while it reads, and
  a `text/rtf` or `text/xml` file drops its source. The `expect` changed (Mac queue).
  The iOS actual compiles (2026-10-01, `e6001af2`).
- [x] **6.27 Cut from a canvas-focused editor on the web. C.** [Opus] [Lane H]
  An editable editor whose canvas holds DOM focus (after a touch the
  session does not hand focus back from) takes Ctrl/Cmd+X on the canvas, where
  Compose consumes it before the browser fires a `cut` event, so Cut falls back
  to `navigator.clipboard`, plain text only or nothing on an insecure page. 7.39
  asks for a `copy` event there with `execCommand('copy')`; `execCommand('cut')`
  could do the same for Cut. Done: `ClipboardEventsEffect` listens for cut
  chords on a Compose canvas as well (Cmd+X on Apple systems, else Ctrl+X or
  Shift+Delete; Shift with X is strikethrough) and, while the editor takes input
  (`isFocused`) with a selection and a Cut action, calls `execCommand('cut')`
  inside the key press (a `beforecut` handler enables it in WebKit); the `cut`
  event writes both flavors and the Cut action the key then runs takes it as the
  write and deletes. The browser's own default for an asked event is always
  prevented, so one no handler answers cuts nothing of the page's. Checked in
  Chromium against a development build: with the text area focused, Ctrl+X and
  Shift+Delete dispatched on the canvas each fired one prevented `cut` event
  carrying the selection's markup (`<strong>`, `<s>`) and plain text, removed
  the word, and never called `navigator.clipboard`, which it did before the
  change; Ctrl+Shift+X struck the word through and fired nothing; a read-only
  editor's Ctrl+X fired nothing and its Ctrl+C still fired `copy`. A real
  touch-focused canvas, Safari and Firefox are for QA 2.11. The limits are
  7.39's: the default bindings' chords, and on a page of several viewports the
  one whose editor takes input with a selection answers.
- [x] **6.24 The drop caret past a wrapped row's end. C.** [Opus] [Lane H]
  Since 1.24 a point past a wrapped row's end hits its wrap offset, so a drop
  there inserts at the row's end, but `TextDragAndDrop.offsetAt` keeps only the
  position and `DrawDropCaret` draws it downstream, at the start of the next
  row. Keep the hit's affinity (`TextEditorState.pointerHitAt`) for the drop
  caret. Done: a hover keeps the whole hit (`TextDragAndDrop.dropHit`), and
  `DrawDropCaret` draws it as the caret is drawn (`calculateCursorPosition`
  now has an overload for a position and affinity), so the drop caret past a
  row's end matches the caret a click there places, on an indented empty line
  and a block's line too (`dragdrop/DropCaretAffinityTest.kt`). A hover is
  read on each pointer move only, so a scroll under a still drag leaves the
  drop caret where it was until the pointer moves.
- [x] **6.31 A heading's inline formatting is lost in HTML. S.** [Opus]
  [Lane H] `HtmlExtension.kt` `lineHtml` writes a heading block's line from
  `AnnotatedString(line.text)`, dropping every span to shed the baked heading
  style, so "My *great* title" exports and copies as `<h2>My great title</h2>`.
  Strip only the configured heading style (current or retired) and write the
  rest, as markdown export keeps a heading's emphasis. Done: a heading line
  writes its own spans inside the heading element, retired styles read as
  current, leaving out its heading's look and any heading look, under the
  current or a retired configuration, that is not also an inline style of that
  configuration or the current one (text joined from another heading keeps that
  heading's look, 7.72) (`RetiredStyles.headingLooks`). So "My *great* title" writes as
  `<h2>My <em>great</em> title</h2>` and reads back the same
  (`html/HeadingInlineFormattingHtmlTest.kt`).
  Found: 7.72.
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
- [x] **6.30 Paste reads the selection before it awaits the clipboard. C.**
  [Opus] [Lane H] `pasteClipboard` (`input/BuiltinEditorActions.kt`) takes the
  selection and the insert position, then suspends on
  `readHtmlPasteDocument` and `ClipboardHelper.readCopyId` before it edits.
  On the web the async clipboard can take a while (a permission prompt), and
  a click or edit in between leaves the paste replacing a stale selection and
  placing copied rich spans and HTML blocks at a stale position, or throwing
  when the lines are gone. Read the selection after the clipboard, or
  revalidate it, as 5.4's paste seam does for its own range. Done: every
  clipboard read (the text, its HTML, the copy id) comes first, and the
  selection, the insert position, the size and the screening are read after,
  with no suspension before the edit, so the paste lands where the caret or
  selection is when the clipboard answers. As a drop does, it ends a
  composition begun meanwhile (resyncing the IME) and inserts the text it
  screened without screening it again (`clipboard/PasteReadsSelectionLateTest.kt`).
  A document loaded with `setText` meanwhile takes the paste at its caret.
- [x] **6.32 A link's look crosses into an editor that refuses its scheme. S.**
  [Opus] [Lane H] On desktop a paste or drop between two editors in one process
  takes the exact `AnnotatedString` flavor ahead of the markup, so a `myapp:`
  link copied from an editor that allows the scheme arrives in one that does not
  with the link style baked over its text but no link (the markup parse, which
  reads the receiver's `allowedLinkSchemes`, adds none). Strip the link style
  from runs the receiving parse does not confirm as links, or read the markup
  when the copy came from another state. Done: paste and drop, once their links
  are placed, take the receiver's link style off the landed text wherever no
  link covers it, inside the same undo step and keeping the copied rich spans
  the paste kept (`state.removeLinkLookOutsideLinks`), so the look follows the
  links that landed on every platform and flavor; an in-process copy keeps its
  exact styling otherwise (`clipboard/RefusedLinkLookTest.kt`). A link look
  that is not the receiver's link style (a source editor with other styles, a
  retired style) is not recognised.
- [x] **6.36 A busy clipboard throws into paste. S.** [Opus] [Lane H] Compose's
  desktop `Clipboard.getClipEntry` calls AWT's `getContents` without a catch,
  and AWT throws `IllegalStateException` while another application holds the
  system clipboard open (Windows); the paste coroutine then failed with it.
  Found investigating 4.17. Done: the desktop clipboard reads
  (`ClipboardHelper` and the HTML read) take a clipboard that cannot be read as
  empty and say so on stderr (`ClipboardReadFailureTest`).
- [x] **6.37 Desktop paste reads the clipboard three times. S.** [Opus] [Lane H]
  `pasteClipboard` (`input/BuiltinEditorActions.kt`) reads the text, then the
  HTML (`readClipboardHtml`), then the copy id, each through its own
  `getClipEntry`, on the UI thread. AWT's `getContents` fetches every format
  the source offers each time, and on X11 each transfer can wait on the owner
  up to AWT's data-transfer timeout, freezing the window. The reads can also
  disagree: a clipboard that changes, or turns busy (6.36), between them pastes
  text without its blocks. Read the content once per paste, off the UI thread
  as the primary selection's read is (4.23), and hand it to the three readers,
  as Android's `pasteClip` does. Found reviewing 6.36.
  Done: a paste reads through `readClipboardPaste` (`clipboard/ClipboardHtml.kt`),
  which answers the text, markup and copy id together. On desktop that is one
  AWT read, of the system clipboard off the UI thread; Android, iOS and the web
  keep handing what their `getText` read to the other two readers
  (`PasteReadsClipboardOnceTest`). Found: 6.39.
  Checked by hand in the iOS simulator (2026-10-01): a bulleted list copied in
  the editor pastes as a bulleted list, and so does one copied from a page in
  Safari (the simulator has no Notes).
- [x] **6.38 Part of a heading pasted into another line keeps the heading's
  look. R.** [Opus] [Lane H] Copying "itl" out of an h2 "Title" and pasting it
  into a plain line "hello" leaves "itl" baked with the h2 look (24 sp), though
  the line is no heading (probed through the UI harness's Ctrl+C and Ctrl+V).
  Word and Google Docs give pasted text that carries no paragraph mark the
  destination paragraph's style. A heading pasted whole onto a line of its own
  should keep its look, as `RichPasteBodyStyleTest` expects; text from part of
  one should land without it, and pasted into a heading of another level take
  that heading's (6.35 bakes the receiving heading's look over its line, but
  leaves the pasted one's). HTML export already leaves a foreign heading look
  out of a heading line (6.31). A drop of the same text likely lands the same
  way (not probed). Found in 6.35.
  Done: a paste takes off the pasted text each block look (a heading's, a
  fence's monospace) that the line each part lands on does not bake
  (`removeBlockLooksOffTheirBlocks`), after the copied blocks and the markup's
  are placed; publishing then bakes the line's own look over it (6.35). So
  "itl" from an h2 lands as body text in a plain line and at the h3 look in an
  h3, and a heading pasted with its block, from this editor or from markup,
  keeps its look. A span equal to a block look that the user set on a plain
  line goes too: pasted text cannot tell it from a block's. A look of a
  retired or another editor's configuration is not recognised
  (`clipboard/PastedBlockLookTest.kt`). Found: 6.40, 6.41.
- [x] **6.39 A paste's clipboard read still passes through global stashes off
  desktop. S.** [Opus] [Lane H] `readClipboardPaste` (6.37) answers a paste's text,
  markup and copy id together, but Android, iOS and the web build it from
  `ClipboardHelper.getText`, which leaves what it read in a field on the helper
  (`pasteClip`, `lastReadHtml`, `lastReadCopyId`) for `readClipboardHtml` and
  `readCopyId` to take. A clip with no text leaves Android's `pasteClip` set
  until the next paste, and a later call of either reader answers from it. Each
  actual could build `ClipboardPaste` from its one read (iOS's `readStyled`
  already returns that shape) and drop the stashes. The paste also parses
  foreign markup twice, once for the text and again for its blocks
  (`htmlPasteDocument`); `ClipboardPaste` could carry the parsed document. Found
  in 6.37's review.
  Done: each platform's `readClipboardPaste` builds the paste from its one read
  (Android's `ClipboardHelper.readPaste` of the clip, iOS's `readStyled` of the
  pasteboard, the web's `readPaste` of the event's or the async clipboard's
  flavors), and the stashes and the `readClipboardHtml` expect are gone, so
  `readCopyId` and `getText` read the clipboard themselves. `ClipboardPaste`
  carries the markup as parsed for its text (`document`), which the paste's
  blocks take instead of parsing it again; markup the text did not come from
  (this editor's copy whose markup re-parses to other characters, several items)
  is left off, since its blocks cannot apply. Desktop's drop reads the markup once
  (`readPaste`) (`AndroidRichClipboardTest`, `ClipboardReadFailureTest`).
  Checked on the Mac (2026-10-01): compiles and the iOS tests pass, and in the
  simulator a bulleted list copied in the editor and one copied from Safari
  both paste as lists (6.37's check, run on a build with this change). The
  paste prompts were not counted.
- [x] **6.40 A heading's whole text pasted inside a line makes that line a
  heading. S.** [Opus] [Lane G] Copying all of an h2 "Title" (no line break) and
  pasting it at column 2 of a plain "hello" gives an h2 "heTitlello":
  `addPreservedRichSpans` places the copied heading marker on the line the
  paste lands inside, and publishing bakes its look over all of it. Markup does
  not (`placeHtmlPasteBlocks` leaves a block off a line the paste splices into),
  so `<h2>Title</h2>` pasted there lands as body text. A copied block should
  take a line only where the paste covers it whole, as Word gives text pasted
  without its paragraph mark the destination's style. Found in 6.38's review.
  Done: a copied line marker, block or paragraph format (`anchorsToLine`) lands
  only on a line the paste covers whole, from its start to its end, and takes it
  from any block there that refuses to share it, the first line included (a
  heading's whole text pasted onto an empty list item made it both). So text
  pasted inside a line, or at either end of one, takes that line as it is, as
  markup already did; a paste at a paragraph's start leaves the line its last
  line joins with that paragraph's blocks and format, where 6.33 and 6.34's
  tests had expected the copied ones (`clipboard/PastedBlockLookTest.kt`,
  `state/ParagraphFormatTest.kt`, `state/JoinParagraphStyleTest.kt`). Found: 6.49.
- [x] **6.41 Part of a heading dropped into another line keeps the heading's
  look. S.** [Opus] [Lane H] A drop (`dragdrop/TextDrop.kt` `insertAt`) takes
  the link look off text no link covers, as a paste does, but not a block's
  look off text landing on a line that does not bake it, which a paste does
  since 6.38 (`removeBlockLooksOffTheirBlocks`): dragging "itl" out of an h2
  "Title" into a plain line leaves it at the h2 look. Found in 6.38's review.
  Done: a drop takes them off too, inside its undo step, once its markup has
  restored any whole lines' blocks and a move's source is gone, so against the
  blocks the lines end up with; a move back into its own heading keeps the
  look (`dragdrop/DroppedBlockLookTest.kt`). Found: 6.45.
- [x] **6.45 Styled text inserted outside paste and drop keeps a block's look.
  S.** [Opus] [Lane G] `TextEditorState.insertTypedString(AnnotatedString)`
  (`state/TextEditorStateExt.kt`) and the accessibility insert
  (`insertAtCursor` in `EditorSemantics.kt`) put an `AnnotatedString` in
  through `insertStringAtCursor` with neither `removeLinkLookOutsideLinks` nor
  `removeBlockLooksOffTheirBlocks`, so text carrying a heading's look lands at
  that look on a plain line, as paste did before 6.38. Strip the looks where
  the edit manager inserts styled text, as `resolveInheritedStyle` does for a
  multi-line replace, so every path is covered.
  Done, at the entry points rather than in the edit manager or the public
  `insertStringAtCursor` and `replace`: those also re-insert the document's own
  text (outdent, find's replace, a paste before its blocks land), and a host's
  monospace text equals a fence's look, so stripping there took a code editor's
  font off every outdented line. Text from outside the document
  (`insertTypedString(AnnotatedString)`, the accessibility insert and set text,
  yank) lands through `landingOutsideText`, which in the same undo step takes off
  each block look its line does not bake, unless the text beside it carries that
  look of its own (a host's monospace), and the link look where no link holds it
  (yank after the kill took the link). A typed word that loses a look is a step of
  its own rather than joining the typing run (`state/InsertedBlockLookTest.kt`).
- [x] **6.47 A paste or drop over a composition ends it unoffered. S.**
  [Opus] [Lane H] The paste actions (`input/BuiltinEditorActions.kt`) and a
  drop (`dragdrop/TextDrop.kt`) end a live composition with a bare
  `clearComposingRange` before inserting, so a word the keyboard was still
  composing (Gboard's `don't`, a toolbar Paste tapped right after it) keeps
  its straight apostrophe where a tap, focus loss or the connection's close
  would have offered it (5.9). Go through `TextEditorState.finishComposition`
  there, keeping the resync they request; the behaviors' edit then precedes
  the paste's own `onPaste` offer and the paste lands at the mapped caret.
  Done: a paste or drop finishes the composition
  (`finishCompositionBeforeInsert`) before it reads where it lands, so the
  behaviors' edit of the word is its own undo step, offered before the paste's
  `onPaste`, and the paste goes at the caret or selection mapped through it. A drop
  follows the pointer, as a tap does: its position is read again on the substituted
  text. A paste or drop the filter refuses still ends the composition, as a tap
  would. Known limits: a move whose source a substitution shifted drops as a copy,
  which needs a composition alive through a drag of the editor's own selection;
  other edits the keyboard did not make (cut, yank, a host's `insertTypedString`)
  still end one unoffered, all needing a composition alive beside a selection or
  a host edit mid-word (`clipboard/PasteOverCompositionTest.kt`).
- [x] **6.49 A copied image or rule pasted inside a line leaves a space. C.**
  [Opus] [Lane G] An image or horizontal rule line holds a one-space
  placeholder under its `BlockSpanStyle`. Copying the placeholder without its
  line break and pasting it inside or at the end of a text line lands the bare
  space: the block takes only a line the paste covers whole (6.40), and the line
  model has no inline image. Paste such a block onto a line of its own (split
  the line, as Word puts a pasted picture in its own paragraph when it cannot sit
  inline), or leave the placeholder out. Found in 6.40's review.
  Won't fix: an unusual copy (the placeholder without its line break); Hammer users rarely copy images around.
- [x] **6.48 A moved run without formatting takes the formatting where it lands.
  R.** [Opus] [Lane H] A drop inserts its text through `insertStringAtCursor`,
  which gives text with no span styles of its own the caret's style there, so
  moving an unformatted "plain" next to bold text makes it bold. Even dropped at
  its own start edge, "bold**plain**" with "plain" moved to (0,4) deletes and
  re-inserts it bold (`dropText` refuses only a drop strictly inside the source).
  Word moves the run as it was. Refuse a move onto either edge of its source, and
  insert this editor's own drag with its styles as they were rather than
  inheriting. Found in the housekeeping that shared `settleLanded`.
  Done: a drag of this editor's own text drops the source's characters with exactly
  their styles: no typing style, no destination size, and a run it lands inside
  (bold, a host's size) stays off it, except the line's block look, which the line
  bakes over it, and a link's look, which follows the link. A move dropped on its
  own text or either edge is taken and changes nothing. Text from elsewhere still
  takes the styling where it lands, as a paste does
  (`dragdrop/MovedRunStylesTest.kt`).

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
  padding and the scroll offset (7.36 added the block heights and paragraph
  spacing). `onImeAction` is
  offered only for an action key the host chose (3.11's
  `KeyboardSettings.imeAction`); the default is Enter, where
  `BasicTextField`'s default action is a no-op. Headings and lists
  stay unexposed: an editable node is one text, and native editors
  (`EditText`, `UITextView`) do not expose them either.
- [x] **7.36** [Opus] [Lane M] The semantics text layout (7.4, 7.1) is measured
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
  Done where Compose allows: the layout is measured from the editor's rows, each
  line as the editor shaped it (its block's and 5.7 format's paragraph style, the
  baked indent), so rows break and align as drawn, and the space under a row (a
  block's height, paragraph spacing) is a top-aligned placeholder on the line
  break below it, so rows below an image, a rule or a spaced paragraph sit where
  drawn. A paragraph's line height spreads over a placeholder as over text, so a
  placeholder that missed its row is corrected and the text measured once more.
  It is reused while its measured input is equal, so a span pass (spell check,
  find) reshapes nothing (`SemanticsLayoutTest`). What cannot match: a
  `TextLayoutResult` is one measured text with no constructor from existing
  paragraphs and no offset, so a text edit still shapes the whole document on the
  next request, and the layout still starts at the first row and the text's left
  edge: the content padding, the space above the first paragraph and the scroll
  offset stay out (moving the semantics node to the content origin would move the
  field's own bounds). A block shorter than its line's text, on any row but its
  line's last, or on the last line, keeps the text's height. The first two stay open as 7.57. Checked
  on desktop only; Android shapes line heights through its own spans.
- [x] **7.37** [Opus] [Lane E] Turning input back on while the editor keeps
  focus (`enabled` or, since 7.13, `readOnly` switched off) marks it focused
  but starts no input session until the next tap, by design, so the soft
  keyboard does not rise unasked. Desktop dead keys and IME composition, and
  Android and iOS IME text, do nothing until then. Start a session that shows
  no keyboard, or document the host's `requestFocus` after the toggle.
  Done on Android and desktop: the input node starts a session at once and
  queues a hide of the keyboard behind the session's own request to show it,
  which Android's input service coalesces into none (`startsInputQuietly`, a
  new `expect`; `e2e/InputSessionE2eTest.kt` records the hide through a stand-in
  keyboard controller). Checked on an emulator (API 36, Gboard): with the
  keyboard up, the sample's Read only on and off left the keyboard down
  (`mInputShown=false`) with the editor's input type bound again, and a tap
  raised it. iOS and the web still wait for a tap: iOS's keyboard follows the
  session's first responder and the web's its focused text area, which a hide
  blurs; the iOS half is in the Mac queue.
  iOS checked on the simulator: with `false`, Read only on takes the keyboard
  down, off leaves it down, and a tap raises it and typing works. With `true`
  the keyboard also stays down, but hardware typing goes nowhere until a tap,
  since iOS delivers typed text only to the first responder the hide resigned;
  it gains nothing over `false`, which stays.
- [x] **7.38** [Opus] [Lane K] `SpellCheckingTextEditor` has no `readOnly`
  or `lineLimits` (7.13), and its corrections are menu items that call
  `correctSpelling` directly, past `ContextMenuActions`' editable gate.
  Forward both, and offer no corrections while read-only.
- [x] **7.39** [Opus] [Lane H] On the web a disabled or read-only editor (7.13)
  has no input session, so no backing text area receives the browser's `copy`
  event and `ClipboardEventsEffect` answers nothing: Ctrl+C falls back to
  `navigator.clipboard`, plain text only, and fails where the page may not
  write the clipboard.
  Done: with no text area to type into, the key lands on the canvas, and
  Compose takes it there before the browser would fire a `copy` event. So
  `ClipboardEventsEffect` listens for copy chords (Ctrl+C and Ctrl+Insert, or
  Cmd+C on Apple systems, by `code` as Compose maps keys) on a Compose canvas
  in the capture phase and, while the editor has focus, a selection and a Copy
  action, calls `document.execCommand('copy')` inside the key press (with a
  `beforecopy` handler that enables it in WebKit); the `copy` event it fires
  writes both flavors, as the text area's does, and the Copy action the key
  then runs takes it as done. A selectable `RichTextView` installs the effect
  too. Checked in Chromium against the dev server: a read-only and a disabled
  editor, and a `RichTextView`, each wrote `<strong>`/`<em>` markup and the
  plain text through one prevented `copy` event and never called
  `navigator.clipboard`, for the tools' Ctrl+C and for dispatched Ctrl+C and
  Ctrl+Insert key events carrying a `code`. Safari and Firefox are for QA 2.11.
  The chords are the default bindings', not the editor's own `KeyBindings`, and
  which Compose canvas is the editor's is not known, so on a page of several
  viewports the one whose editor holds focus and a selection answers. Cut on a
  canvas-focused editable editor is 6.27.
- [x] **7.40** [Opus] [Lane F] A single-line editor (7.13) still asks the
  soft keyboard for multi-line text (Android's `TYPE_TEXT_FLAG_MULTI_LINE`;
  iOS the same), so with the default `KeyboardSettings.imeAction` the keyboard
  shows a return key that now does nothing. A single line should ask for
  single-line text and default its action key to Done, and a hardware Enter
  should run `onImeAction` as the keyboard's action key does.
  Done: on Android a single line drops `TYPE_TEXT_FLAG_MULTI_LINE` and
  `IME_FLAG_NO_ENTER_ACTION`, and `KeyboardSettings.imeActionFor` makes its
  default action Done, which the connection's action key presses; the line
  limit coming or going restarts input as a settings change does
  (`KeyboardSettingsTest`). Enter (and Shift+Enter) in a single-line editor
  presses `TextEditorState.effectiveImeAction()` through the host's
  `onImeAction` or the default, on every platform, as a single-line
  `BasicTextField` does, and with `ImeAction.None` does nothing
  (`e2e/SingleLineEnterE2eTest.kt`). Checked on an emulator (API 36, Gboard,
  the sample's Single line switch): `dumpsys input_method` showed
  `inputType=0xc001 imeOptions=0x12000006` (no multi-line flag, Done) with
  a Done key on the keyboard, `adb shell input keyevent 66` hid the keyboard
  and added no line, and switching back restarted input with the multi-line
  type while the keyboard was up. Like the single-line filter, the limit is
  the state's: a multi-line editor sharing a state with a single-line one
  takes it too. The iOS and web
  keyboards are 4.32. The semantics' `onImeAction` still reads the setting
  alone (7.59).
- [x] **7.41** [Opus] [Lane C] No soft-wrap toggle: every line wraps at the
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
  Design (`docs/design/soft-wrap.md`): `softWrap: Boolean = true` on the editor
  composables, as on `BasicTextField`, off for `SingleLine`, and the state's like
  the line limit; the offset is `TextEditorState.horizontalScrollState`, a second
  `TextEditorScrollState`. It is applied in three places rather than at each
  site: the state's view-coordinate conversions (`getPositionForOffset`,
  `calculateCursorPosition`, `getOffsetAtPosition`), which the handles, toolbar,
  menu, magnifier, input method rectangles and cursor anchor already go through;
  one drawing transform, `inContentSpace`, around the text and selection; and
  the few sites that pair raw rows with a pointer. With wrapping off a line is
  shaped unbounded (at least the viewport wide, for alignment), each `LineLayout`
  records its width, and `RowList` keeps the widest line as a running maximum
  per chunk in its directory, rebuilt from the touched chunk as the tops are, so
  no keystroke scans the lines. The caret is kept in view on both axes in one
  scroll job; a horizontal `scrollable` takes Shift+wheel, trackpad and touch;
  desktop and web overlay a `HorizontalScrollbar`.
  Progress, in chunks:
  - State and layout: done. `softWrap` on `BasicTextEditor`, `TextEditor` and
    `SpellCheckingTextEditor`, counted on the state as the single-line limit is;
    unwrapped lines shaped unbounded, `LineLayout.width` and the directory's
    running maximum, the sideways range on `horizontalScrollState`
    (`softwrap/SoftWrapLayoutTest`, which checks the widest line against
    measuring every line through 60 random edits over 400 lines).
  - Drawing and hit testing through the offset: done. `inContentSpace` around the
    text, rich spans, composing underline and selection; the caret and drop caret
    from view metrics, not drawn once scrolled out sideways; line decorators get
    the scrolled offset, drawn unclipped behind every line's text; the pointer
    hit test, the magnifier's row clamp, the vertical goal x (a content column)
    and the handwriting gesture layout (commonMain `input/HandwritingGestureLayout.kt`,
    beside 3.22's previews) add the scroll. The draw recorder follows
    translations (`softwrap/SoftWrapGeometryTest`).
  - Caret visibility, scrolling input, the scrollbar: done. The caret's x is kept
    in view with its row, moving just far enough, in the one scroll job, and
    `scrollToPosition(offset)` (find) reveals x too; a horizontal `scrollable`
    on the editor (wheel, trackpad, touch); a selection drag past a side edge
    auto-scrolls sideways; desktop and web lay Compose's `HorizontalScrollbar`
    over the bottom of the text while there is a sideways range, through a new
    internal `expect` (`EditorHorizontalScrollbar`; on Android and iOS a thumb
    that takes no room, below)
    (`softwrap/SoftWrapScrollingTest`).
  - Input method rectangles, handles, toolbar, magnifier, semantics: done. The
    caret rectangle, Android's cursor anchor (whose watch reads the caret, so a
    sideways scroll resends it), the handles (hidden once their anchor leaves the
    view sideways) and the magnifier already went through the view conversions;
    the skiko text origin subtracts the sideways scroll, the touch toolbar moves
    with it, and the floating cursor's and the semantics' whole-document layouts
    are measured unwrapped (`softwrap/SoftWrapPlatformTest`, `SemanticsLayoutTest`).
  - `SingleLine` scrolls sideways: done. A single-line editor counts as one with
    wrapping off whatever its `softWrap`, so it stays one row tall and follows the
    caret sideways, with no scrollbar (`LineLimitsE2eTest`).
  - The sample app's switch: done. A Soft wrap switch in the demo, and the Code
    Editor demo unwrapped, its line numbers placed by a fixed x.
  - Scrolled variants of the broad tests: done. Wrapped, the sideways scroll is
    0, so code that forgets it passes every wrapped test. The UI storms, the
    invariant fuzz and the markdown fixpoint storm run with wrapping off,
    scrolled sideways between steps, and check that the view follows the scroll
    (`assertViewFollowsSidewaysScroll`); `softwrap/SidewaysGeometryTest` takes
    the geometry harness through the same check scene by scene. The
    differential fuzz runs unwrapped, against a `BasicTextField` too wide to
    wrap, and as a single line. Taking the scroll out of each place that applies
    it fails at least one (`docs/design/soft-wrap.md`, "Testing").

  Done: `softWrap` on the editor composables, `TextEditorState.horizontalScrollState`,
  and `SingleLine` one row scrolling sideways; checked in rendered frames of a
  scrolled code fence, selection and single line. An indent the intrinsic width
  leaves out would wrap an unwrapped line, which is measured again wide enough.
  `LongDocumentBenchmark` (`CTE_BENCHMARK=1`, 2,000 lines of 99 characters at 400
  pixels), wrapping on, before and after, medians of two runs each: keystroke 191
  and 193 us, typing frame 718 and 726 us, idle frame 663 and 660 us, `moveRight`
  4.3 and 3.7 us, a width change 2,434 and 2,457 us; within the noise. Wrapping
  off (2,000 rows): keystroke 120 us, typing frame 874 us, idle frame 838 us, a
  width change 4,298 us. Limits (`docs/design/soft-wrap.md`): the sideways scroll
  is not saved with the state; `RichTextView` always wraps; scroll 0 is the
  left edge, so an unfocused right-to-left line wider than the field shows its
  end; a line wider than 262,142 pixels wraps there; a visible line is drawn
  whole; a scroll alone does not ask
  the desktop and web input method to read the caret rectangle again, as with
  the vertical scroll.

  Wanted for code editing; built on its own branch, `soft-wrap`, and merged
  after 7.57 and 7.86 (owner's decision). Reconciled with them: the semantics
  layout that iOS's input session serves since 7.57 (in place of
  `DocumentTextLayout`) is measured unwrapped and as wide as the widest line, so
  an indented line stays one row there as in the editor; the input method's
  text origin subtracts the sideways scroll and adds the first row's top;
  `CharacterBounds` subtracts the sideways scroll in `documentToCanvas`; the text
  pass that decorations tint draws inside `inContentSpace`. The sideways check
  (`assertViewFollowsSidewaysScroll`) covers the character bounds and the tints
  (`SidewaysGeometryTest`, `CharacterBoundsTest`, `SemanticsLayoutTest`,
  `DecorationDrawingTest`). `LongDocumentBenchmark` wrapping on, before the merge
  and after, two runs each: keystroke 190 and 196 against 194 and 204 us, typing
  frame 723 and 743 against 733 and 746 us, idle frame 686 and 676 against 672
  and 677 us, a width change 2,460 and 2,595 against 2,500 and 2,582 us; within
  the noise.
  Added on the Mac (2026-10-01): Android and iOS showed no sideways indicator,
  where a native scroll view shows one for either axis. Both now draw the
  thumb they draw for the vertical scroll along the bottom edge while the
  text scrolls sideways, fading when it stops, in their own colours
  (`HorizontalScrollIndicator` in `scrollbar/ScrollIndicator.kt`,
  `HorizontalScrollIndicatorTest`). It is display-only and reports no height,
  so `scrollbarBottomPx` stays 0 and nothing is kept clear of it. Seen on the
  iPhone 17 Pro Max simulator; the Android side compiles and its host tests
  pass, not seen on a device.
  Checked on the iPhone 17 Pro Max simulator (iOS 26, 2026-10-01, `9f16a8e9`):
  compiles and the iOS tests pass; Single line stays one row and follows the
  caret; unwrapped text scrolls sideways under a drag; the floating cursor
  goes where the finger does along an indented list line, staying on its row
  for a sideways drag and scrolling the line to follow, and keeps its place on
  screen a row up in text scrolled sideways; the handles and the edit menu
  sit on a word selected after a sideways scroll.
  A sideways storm failed on macOS and Windows (2026-10-01,
  `EditorInvariantFuzzTest`, "sideways invariant fuzz seed 42", after stroke
  51: the stylus area was [1, 4), [14, 18), [29, 32) at scroll 363 and
  [1, 4), [15, 18), [29, 32) at scroll 121). The check's fault, not the
  editor's: the harness probes at content xs it turns into view xs, and
  `textRangeInArea` adds the scroll back, two float additions that round
  differently at the two scrolls. With those fonts the probe at 100.506 sat
  one float step (0.000015 px) from an l's centre at 100.50598, which came
  back as 100.50598 at one scroll and 100.506 at the other, so the area took
  the l at one and not the other. `input/HandwritingGestureLayout.kt` rounds
  nothing. The probes now keep 0.02 px clear of every glyph edge and centre
  on the caret's line (`assertFollowsSidewaysScroll`, `testUtils/uiFuzz`),
  and the answers are still compared exactly. `softwrap/SidewaysProbeTest`
  puts a glyph's centre on a probe with any font, by a letter spacing it
  measures, and failed before the fix both ways (the probe on the centre,
  and the stylus area flipping). Dropping the scroll from any of the three
  handwriting conversions still fails ten tests each (checked by hand).

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
- [x] **7.33** [Opus] [Lane A] Arrow keys inside a mixed paragraph (a Hebrew
  word in English text, or the reverse) move logically, so the caret jumps
  visually at the run boundaries. macOS and Windows move visually through the
  runs, with the caret carrying a direction at each boundary; `BasicTextField`
  is logical here too. Needs `getBidiRunDirection` and a run-aware step, and a
  visual caret position at run boundaries.
  Decided per platform, from the native fields: all of them move visually.
  Cocoa binds the arrows to `moveLeft:` and `moveRight:` (visual; `moveForward:`
  and `moveBackward:` are the logical ones); TextKit's selection navigation
  on iOS moves `.left` and `.right` visually; Windows' Edit and RichEdit
  controls move visually; GTK binds them to `GTK_MOVEMENT_VISUAL_POSITIONS`
  (Qt defaults to logical, but GTK is the Linux reference, as in 1.19);
  Android's `ArrowKeyMovementMethod` steps with `Layout.getOffsetToLeftOf` and
  `getOffsetToRightOf`; Chrome and Safari move visually, and Firefox by
  default (`bidi.edit.caret_movement_style` 2). So one switch,
  `ARROW_KEYS_MOVE_VISUALLY` in `state/VisualCaretMotion.kt`, documents the
  sources and covers every platform; a platform that should differ makes it
  per platform there.
  Done: Left and Right step to the nearest grapheme boundary on screen in
  that direction on the caret's row (`moveCaretVisually`), each boundary
  standing against the glyph before or after it at that glyph's trailing or
  leading edge by its run's direction (`getBidiRunDirection` and
  `getBoundingBox`); where two offsets share a place on screen the step takes
  the one against the glyph it passed. The caret keeps that side
  (`TextEditorCursorState.runSide`, cleared by every other move) and draws
  there, and Up and Down measure from it. Past a row's edge the caret goes
  onto the next row in reading order at its reading start, or the previous
  row at its reading end, and at the document's first or last row it stays.
  A line of plain left-to-right text in a left-to-right paragraph keeps the
  logical step, as does a row whose layout lags the text. Shift extends the
  selection with the same steps (the selection stays a logical range);
  collapsing a selection, word moves, Home and End keep 7.5's paragraph
  rules. An English paragraph in a right-to-left app (7.32) now moves on
  screen too, where 7.5 mirrored it. `e2e/VisualArrowE2eTest.kt`; the
  invariant fuzzer's Left-then-Right check compares the drawn caret in a line
  with right-to-left text, and the differential fuzzer tolerates the
  reference's logical arrows there.
  Checked by hand on macOS (2026-10-01) with `abc אבג def`. The desktop sample
  app against TextEdit: Right from the start, Left from the end and
  Shift+Right move the same in both, one glyph on screen at a time through the
  Hebrew. The iOS sample app in the simulator with a hardware keyboard: Left
  and Right reach the key handler and move visually through the Hebrew.
  Shift+Right across the Hebrew looks a little odd there (the selection is a
  logical range, extended by visual steps); the simulator has no Notes, and
  Reminders' text view is odder with the same text, so iOS stays visual.
- [x] **7.78** [Opus] [Lane C] In a left-to-right paragraph whose row starts
  with right-to-left text, a caret inside that text is drawn at the run's
  right end: `lineTextLeft`, the caret's floor, takes the first character's
  position, which is that run's right end, not the row's left. Found in 7.33.
  Done: such a row's text left is its leftmost glyph's, the left of
  Compose's path over the row, which keeps an indent as the first glyph's
  position does (`GeometryTest`). List markers anchored there move with it.
- [x] **7.6** [Opus] [Lane C] Selection draws one rect per row from x(start)
  to x(end); wrong in right-to-left, and mixed text needs several rects.
  **R** (0.5, `drawing/GeometryTest.kt`, `failsUntil("7.6")`): in a
  right-to-left paragraph a selected line break's sliver is added to the
  right, so it eats a space's width off the selected text instead of lying
  past the text's left end; in "abc אבג def", selecting "c", the space, א and
  ב draws one box over "c", the space and the unselected ג, missing א and ב.
  Done: each row's selection is the boxes of Compose's own selection path
  (`getPathForRange`, what `BasicTextField` draws), one per stretch of the
  row, merged where they touch, through the public
  `TextLayoutResult.getRunBoxes(lineIndex, start, end)` in
  `utils/TextLayoutResultExt.kt`. Android's path for a range ending at a soft
  wrap adds a box to the layout's edge; `getRunBoxes` drops it (read from
  `Layout.getSelectionPath`, not run on a device). The line
  break's sliver lies past the line's visual end, the same x the caret takes
  after End (`rowEndX`): right in a left-to-right paragraph (past a trailing
  right-to-left run too), left in a right-to-left one. `GeometryTest` covers
  both directions and mixed text. Like the left-to-right sliver at the right
  edge, a right-to-left line that fills the row puts its sliver past the left
  edge, where it is clipped.
- [x] **7.7** [Opus] [Lane C] Underline boxes (spell check, composing, links)
  assume no bidi.
  Done: the wavy and dotted underlines (`richstyle/Underlines.kt`, spell
  check and its diagnostics), the highlight fill (`HighlightSpanStyle`, the
  find addon's match and scope fills, both through the new public
  `drawRangeHighlight`) and the IME composing underline mark each stretch of
  the row their range covers, from 7.6's `getRunBoxes`
  (`drawing/BidiDecorationTest.kt`). `getRunBoxes` leaves out the spaces a
  soft wrap hangs past the row, as the old fills did with `getLineRight`, and
  a row with no right-to-left character in a left-to-right paragraph skips
  the path, reading two horizontal positions as before. Links need nothing:
  their underline is a `TextDecoration` in the text, which the layout draws
  per run itself. `getBoundingBoxes`, which assumed one direction, has no
  callers left and is deprecated.

Hit testing and caret x already delegate to Compose and should be correct.

### Performance on long documents

Desktop timings below come from `benchmark/LongDocumentBenchmark` in the
desktop tests, which is skipped unless `CTE_BENCHMARK` is set:
`CTE_BENCHMARK=1 ./gradlew :ComposeTextEditor:desktopTest --tests
'benchmark.LongDocumentBenchmark' --rerun`, results in the test report's
standard output.

Shaping is one line per keystroke. These still scale with document length:

- [x] **7.8** [Fable] [Lane N] Per keystroke: the line list is copied, every
  `LineWrap` is rebuilt, and every rich span is re-anchored. Measured on the
  iOS simulator (4.21): one keyboard edit takes 9.4 ms at 200k characters
  against 0.7 ms at 2k, wherever the caret is. Design in
  `docs/design/incremental-relayout.md`, section 9. Done: the line
  list is chunked (`state/LineList.kt`, chunks of 32 to 64 lines), so an
  edit splices the chunk or two it touches and shares the rest, and a line's
  flat character index is a prefix total in place of the per-revision
  `lineStartOffsets` table (`LineListCostTest`; `DocumentTextCostTest` now
  counts through the readers themselves). The rows are chunked the same way
  (`state/RowList.kt`, one `LineLayout` per line with its rows' bounds and
  tops, block heights, facts and list counters): a keystroke splices its
  line's layout and walks its neighbours only while their facts change, a
  span change resolves its lines without shaping (`LayoutUpdate.Spans`), and
  a `LineWrap` is built when read, with the four row searches answered from
  the directory (`RowListCostTest`). Desktop JVM at 200k characters: a
  keystroke with relayout 837 µs to 174 µs (971 µs to 174 µs run to run),
  the typing frame 725 µs to 708 µs (the frame is the drawing). The
  benchmark's "whole text per revision" reads 141 µs against 7.9's 25 µs
  after this: the splice itself measures 22 µs on a snapshot alone, and 48 µs
  after a keystroke with the heap collected, so the difference is the young
  collection the keystroke used to trigger while it rebuilt every row, now
  landing inside the measured build instead. The rich spans are kept by line
  the same way (`state/SpanIndex.kt`, each line's spans by their columns,
  with the few crossing a line break loose): an edit re-anchors the spans on
  its own lines and splices the index, a span batch rewrites the chunks
  holding its lines, and line-block normalization examines only the lines a
  revision changed (`SpanIndexCostTest`). Desktop JVM at 200k characters
  with a highlight span on every line: a keystroke 2,026 µs to 94 µs. The
  iOS figures need a new simulator run (Mac queue).
  iOS simulator, 2026-10-01 at `0063e6f6` (iPhone 17 Pro Max, Debug, 200k
  characters in 2,000 lines of 99): the keyboard's `editText` block is 1.1 ms
  median (max 1.5 ms) typing at the end and 1.3 ms median (max 2.9 ms) at the
  start, against 9.4 ms at 200k and 0.7 ms at 2k before (4.21), so within
  about twice the 2k figure wherever the caret is.
- [x] **7.9** [Opus] [Lane N] `getAllText()` rebuilds the whole document per
  revision when read by semantics, the Android IME, and the desktop adapter.
  214 µs per revision at 200k characters on the desktop JVM (4.21).
  The readers that need a few characters now read them in place through
  `DocumentSnapshot.chars`, a `CharSequence` over the lines and their
  starts: Android's text before and after the caret, surrounding text,
  caps mode and composing text, the initial surrounding text (now a window
  of 2048 characters each side, which the platform trims), `imeSubSequence`,
  `imeCharAt`, and the code point deletes. The desktop
  adapter already read only windows. The iOS document layout keys on the
  line list instead of the text. What truly needs the whole text is
  semantics, the skiko request's `value()` and `text` (iOS and web), and
  Android's `ExtractedText`; the last three take a plain `String`
  (`plainText`, the styled text's own string when that is built). Both
  whole texts are memoized per text revision and, after an edit, spliced
  from the last revision whose text of that kind was built: its unchanged
  first and last lines are copied as two ranges, and only the changed lines
  and the two at the edges (whose empty annotations a copied range would
  drop) are read. `setLine` and `replaceLines` say which lines changed
  (`LineSplice`); any other writer's are found by identity. A base that
  shares no line with the revision is dropped, so an unread revision keeps
  at most one old text of each kind, and a read one none. `DocumentTextCostTest` and the Android
  host's `InputConnectionReadCostTest` pin the reads. Desktop JVM at 200k
  characters: the styled text per revision 59.5 µs to 24.6 µs, the plain
  text 24.6 µs (62 µs built from the lines). Still O(document) in
  characters copied; a reader that never asks, which is now every reader
  but those three, costs nothing.
  Checked by hand in the iOS simulator (2026-10-01): typing and deletes reach
  the keyboard's mirror, the spacebar trackpad moves through the current
  text, and a hardware keyboard's forward delete (Fn+Delete) deletes one
  character after the caret per press, the suggestions following the word.
- [x] **7.10** [Opus] [Lane N] Any viewport change, height-only included,
  reshapes the entire document. This is every soft keyboard open and close.
  Rows are shaped to the width alone, so a change of height now only moves
  the scroll range, unless the last pass was skipped while the viewport was
  collapsed (which now also invalidates the layout inputs, so no partial pass
  can build on rows that lag the text). `ViewportResizeCostTest` pins a
  height-only resize to no shaping and the same row list. Desktop JVM at 200k
  characters: a height-only change took 101.8 ms, now 8.5 µs. A width change
  still reshapes everything (107 ms); making it lazy is 7.48.
- [x] **7.48** [Fable] [Lane N] A width change (a window resize, a rotation, a
  split screen) reshapes every line at once: 107 ms at 200k characters on the
  desktop JVM, per frame of a window drag. Reshaping the visible rows first
  needs rows that can hold a layout shaped at another width, or an estimated
  height, with the offsets after them provisional until the rest is shaped;
  a scroll position kept by its line rather than its pixel offset while the
  heights settle; and every consumer that reads `lineOffsets` (hit testing,
  caret, selection, scrolling, the scrollbar) tolerating provisional rows or
  forcing the rows it needs. Best designed together with 7.8, which also
  changes how rows are stored. Done, on 7.8's row list
  (`docs/design/incremental-relayout.md`, section 10): a width, style,
  measurer or density change (`LayoutUpdate.Reshape`) shapes the lines with
  a row in the viewport and a viewport beyond each edge at once, keeps the
  scroll anchored to the line at the top of the viewport, and shapes the
  rest between frames in slices of 32 lines, nearest the viewport first,
  each line at its old shape until then; drawing and a scroll to the caret
  shape what they need first; an edit during settling shapes its own line
  (`LazyReshapeCostTest`). Desktop JVM at 200k characters: a width change
  107 ms to 2.4 ms at once, with 101 ms of settling spread over the frames
  after it.
  iOS simulator, 2026-10-01 at `3d2117cb` (iPhone 17 Pro Max, Debug, 200k
  characters in 2,000 lines of 99, temporary logging, rotated by hand there
  and back). The rotation animates the width, 4 to 13 reshapes a turn, one a
  frame: each shapes the 30 to 80 lines around the viewport in 6.6 ms median
  (4.0 to 11.1 ms) where a full pass shaped all 2,000. The rest settles in
  61 or 62 slices over 1.03 s, 257 to 271 ms of shaping in all, with at most
  one frame gap over 20 ms (30 ms the longest) while it does. Turning back
  with the text at rest kept line 202 at the top throughout; turning with a
  fling under way, the fling carried on through the rotation and the settling
  and nothing jumped on screen.
- [x] **7.11** [Opus] [Lane N] Linear scans per frame or event: visible-line
  lookup in drawing, unculled selection drawing, `getWrappedLineIndex`,
  `getOffsetAtPosition` on each drag move. On the iOS simulator at 200k
  characters (4.21), an idle caret-blink frame takes 33 ms, two vsyncs,
  where a 2k document stays under one; typing frames reach 137 ms.
  Selection drawing and the drag's hit test were already binary searches.
  The rest now are too, through `RowSearch.kt` over the row list, which runs
  line by line and top to bottom: the text drawing's two loops over every
  row (each reading every row's height from its layout), the row holding a
  position (`getWrappedLineIndex`, `getWrapForDrawing` and so every caret
  scroll check, `getWrappedLine`, `findSpanAtPosition`, links and the
  magnifier), the row at a height (`firstVisibleOffset`, page moves), and
  the mouse's hover hit test. Selection drawing and the drag auto-scroll use
  the same helpers in place of their own searches. `RowLookupCostTest` holds each lookup to
  2 log2(rows) + 2 reads and `FrameCostTest` a frame to the rows in view.
  Desktop JVM at 200k characters: the blink frame 758 µs to 653 µs, where a
  2k document's is 632 µs; a row lookup 2.0 µs to 0.1 µs; `moveRight`
  7.1 µs to 3.0 µs; a drag move 7.1 µs to 3.2 µs; a typing frame 859 µs to
  724 µs. The iOS figures need a new simulator run (Mac queue).
  iOS simulator, 2026-10-01 at `0063e6f6` (iPhone 17 Pro Max, Debug, 200k
  characters): no idle frame over 20 ms in ten seconds of caret blinking,
  against a 33 ms blink frame before (4.21), so the blink is within a vsync
  as at 2k. Twelve keys typed on the soft keyboard give six frames over
  20 ms, as before, but the worst is 140 ms at the start and 117 ms at the end
  (one 319 ms frame on the first key after the paste), against 137 ms before;
  `editText` is 7.8's. Closing the keyboard costs two frames of about 42 ms,
  opening it 21, 98 and 37 ms.
- [x] **7.12** [Opus] [Lane N] `moveRight` and `moveToNextWord` sum all line
  lengths though `getTextLength()` is constant time.
  Already gone: 1.1 and 1.5 rewrote both to step through the caret's own
  line, and every flat index reads the snapshot's line starts. Every other
  caret motion reads the caret's line and its neighbours, except that the
  word motions walk on to the next line holding a word, as native editors
  do. `CaretMoveCostTest` pins each motion, and the arrow keys through the
  key handler, to at most 6 line reads in a 500-line document. What a
  move still scanned was the layout's rows, in the scroll-into-view check
  (7.11). Desktop JVM at 200k characters (`LongDocumentBenchmark`, 2,000
  lines of 99 characters at 400 px, 4,000 rows, caret on line 100):
  `moveRight` 7.1 µs median, `moveToNextWord` 8.9 µs, of which one row scan
  is 2.0 µs.

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

- [x] **7.14** [Fable] [Lane I] Every special character in prose is escaped, so
  ordinary prose comes out backslash-heavy, and unsupported syntax kept as
  literal text on import (tables, task lists) is exported escaped. Done: a
  character is escaped only where it would start or end syntax in its
  position (`markdownEscapes` in `markdown/MarkdownEscaping.kt`): emphasis
  runs by CommonMark's flanking rules (so `*not*` in dialogue is escaped and
  `a * b` and `snake_case` are not), `==` pairs likewise, backticks always,
  `[` only before a `](` or `][` or as `[^`, `<` before a tag or autolink,
  `&` before an entity, a backslash before punctuation or at a line's end,
  and at a line's start a heading, quote, list or `1984.` marker, a thematic
  break or a setext underline. A table's rows are kept together by export.
  The acceptance corpus is `markdown/ProseEscapingTest.kt`.
- [x] **7.15** [Fable] [Lane I] Paragraphs are exported with single newlines;
  other CommonMark renderers merge adjacent paragraphs. Done: an editor line
  is a paragraph. Export writes a blank line after every block (not between a
  list's items or a fence's lines; a bare `>` inside a quote), an editor's
  own blank line one more, and import takes one blank line after each block
  away again, so the round trip is exact and other renderers keep the lines
  apart; import leaves out only a blank line export would have written, so
  a foreign file's blank line between two fences, list items or quotes stays.
  `MarkdownConfiguration.paragraphSeparator = NEWLINE` keeps the old
  line-per-source-line form, and `importMarkdown(text, separator)` reads one
  file by either rule. Documents saved by the old exporter with double-Enter
  paragraph gaps lose those gaps on first import under the new rule (their
  blank line reads as the separator) unless the host imports them with
  `NEWLINE`. See `docs/design/line-blocks.md`, "Paragraphs".
- [x] **7.45** [Opus] [Lane I] A line indented by four spaces or a tab (Tab
  on a plain line, 2.9) is an indented code block to CommonMark, and a
  paragraph per line (7.15) makes every such line a block start. Export
  writes the spaces as they are, so other renderers show the line as code
  and import reads it as literal text with its indentation. Decide the
  markdown form of a leading indent (`&nbsp;`, a non-breaking space, or no
  form and a stripped indent) and write it into `docs/design/line-blocks.md`.
  Done: `&nbsp;` per leading space and `&emsp;` per leading tab (a `&#9;`
  would collapse in HTML), in a line's body after its prefixes, whenever the
  line holds more than whitespace (`leadingIndents` in
  `markdown/MarkdownEscaping.kt`). The rest of the line is then not at a
  line's start, so it takes no line-start escape, and a delimiter after the
  indent flanks as after punctuation. Import reads the leading entity run
  back as the whitespace through punctuation stand-ins the file does not
  contain, which keep the parse as a renderer's
  (`markdown/LeadingIndentTest.kt`). The string converters (`toMarkdown`,
  `toAnnotatedStringFromMarkdown`) agree. A line of 1 to 3 leading spaces,
  which other renderers dropped, keeps them too. See
  `docs/design/line-blocks.md`, "Leading indent".
- [x] **7.46** [Opus] [Lane H] The markdown importer reads `color` and
  `font-size` out of an inline `style` attribute (7.16,
  `markdown/InlineHtml.kt`) with its own CSS declaration walk, and the HTML
  paste importer has another (`forEachDeclaration` in
  `html/htmlToAnnotatedString.kt`) that ignores both properties. Share one
  walk and read colour and size on paste too, so paste and import agree.
  Done: `html/CssDeclarations.kt` holds the one walk
  (`forEachCssDeclaration`, which drops `!important`), the colour and size
  reading (`cssColorAndSize`: hex, comma or space separated `rgb()`/`rgba()`
  and the basic keywords; px and sp as sp, pt as 4/3 sp, em and % as em), and
  both importers use it; HTML also reads `<font color>`, as markdown import
  does. HTML takes a source's look as formatting only where it differs from
  the source's own text: a colour without hue (black, white, the greys) is
  the source's text colour and is left to the editor's theme, so a Google
  Docs paste is not black on a dark theme; a size is taken relative to the
  size most of the fragment's text carries (none counting as a browser's
  16 px), onto the configuration's body size (as em when that is not in sp),
  so pasted text matches the text around it (6.18) and a word 18 pt in 11 pt
  text lands 26.18 sp in a 16 sp body. A relative size is resolved against
  the size around it, and a zero size or a mostly transparent colour is no
  formatting. A link's colour is the link style's; inside a heading or code
  (a `<pre>`, `<code>` or monospace run) the block's or code style sets size
  and colour, so an IDE's token colours stay out of a fence; background stays
  ignored (`html/HtmlColorAndSizeTest.kt`). A fragment all at one size pastes
  at the body size. Found: 6.26.
  Paste keeps every colour the author chose, greys included, except the
  fragment's most common colour (the source's text colour; a fragment in one
  hued colour keeps it) and near-black, near-white, and translucent colours.
  A hued `background-color`, the `background` shorthand, or `<mark>` pastes as
  the configured highlight, and a highlight copies out as `<mark>`. A pasted
  heading's `margin-top` and `margin-bottom` are dropped: the heading style
  owns its spacing (Google Docs writes 20 pt above a Heading 1).
- [x] **7.47** [Opus] [Lane H] HTML export and import flatten nested lists
  once 5.6 lands: `<li>` inside `<ul>` inside `<li>` imports at level 0, and
  a nested item exports as a sibling. Serialize the level as nested `<ul>`
  and `<ol>` elements (`html/HtmlExtension.kt`,
  `html/htmlToAnnotatedString.kt`). Done: export (and the clipboard's HTML)
  writes an item's nested list inside its `<li>`, which stays open until its
  next sibling. An item nests under the nearest item before it at a shallower
  level, so an orphan is written one below the item before it and a copy that
  starts at a nested item keeps its items' nesting. Import gives a list the
  depth of the lists holding it, inside an item or directly inside another
  list, as browsers render both, and an item that opens with its nested list
  keeps its own empty line (`html/NestedListHtmlTest.kt`). A line that is not
  a list item ends every item in HTML, a blank one too, so an item after a
  blank line that markdown would still nest is written at the top level. See
  `docs/design/line-blocks.md`, "Nested lists".
- [x] **7.16** [Opus] [Lane I] No markdown form for underline, highlight,
  colour, or size, so they are lost. Code fence language tags are dropped.
  Done. Inline styles: underline is `<u>` (Obsidian and Typora write it,
  every renderer shows it; `<ins>` also imports), highlight is `==text==`
  (Obsidian, Typora, iA Writer, markdown-it-mark) or `<mark>` by
  `MarkdownConfiguration.highlightSyntax`, both imported, and a colour or a
  size other than the body's is `<span style="color:#rrggbb">` and
  `<span style="font-size:20px">` (`px` for sp, `em` for em; `<font color>`
  also imports). A configured style writes only its own marker, so a theme's
  bold colour is never the text's. Import reads the parser's inline
  `HTML_TAG` tokens; `==` is rewritten to `<mark>` in a pre-pass that leaves
  code, tables, link destinations, URLs and tags alone. Fence language tags:
  a fence's info string lives in a `CodeFenceLanguageSpanStyle` span on every
  line of the run (`TextEditorState.codeFenceLanguage` and
  `setCodeFenceLanguage`), written after the opening marker; normalization
  keeps a run on one language across edits, and joining two runs keeps the
  first run's. `~~~` fences import too. See `docs/design/line-blocks.md`,
  "Fence languages".

- [x] **7.52 Markdown as a layer, not core. S.** [Fable] [Lane I] This is a
  rich text editor that can be used as a markdown editor; markdown must not be
  baked into core. Today core's rich text styling runs through
  `MarkdownConfiguration` (`TextEditorState.markdownConfiguration`): about 20
  core files read it for heading, emphasis, link, code, and quote styles (the
  block model, formatting toggles, HTML copy and paste, clipboard, drag and
  drop, normalization), and core depends on `org.jetbrains:markdown`. Split:
  core keeps a plain style configuration (for example `RichTextStyles`), the
  block model (headings, lists and nesting, quotes, code blocks with a
  language, rules), and HTML; a new `ComposeTextEditorMarkdown` module, like
  the spell check addon, takes `MarkdownExtension`, import and export,
  escaping, tables, inline HTML, the syntax options (highlight syntax,
  paragraph separator), and the parser dependency, and later 5.3. The sample
  app's markdown demo uses the module. Keep the old names deprecated for one
  release where a type stays (in the module); core's names that took a
  markdown type go, since core cannot alias a type in the module. Touches
  many files: run it alone.
  Progress, in chunks:
  - Design: done, `docs/design/modules.md`: the module table and dependency
    graph, `RichTextStyles` on `TextEditorState.richTextStyles` as the one
    style owner, the block API on the state, the importer seam
    (`applyDocumentBlocks` keyed by span style, with rich spans,
    `LINE_BLOCK_STYLES` for the order), where every public type lands, the
    test split (a test-only dependency of core's desktop tests on the
    module, spiked), and the host migration.
  - Core: done. `RichTextStyles` (`RichTextStyles.kt`) on
    `TextEditorState.richTextStyles`, public get and set, retiring the old
    value and rebaking heading lines on a change; every core reader (the
    block registry, normalization, the formatting toggles, clear formatting,
    HTML, the clipboard on every platform, drag and drop) reads it. The block
    API is on the state (`state/TextEditorStateBlockExt.kt`, and
    `nestListItems`, `unnestListItems` public); `applyDocumentBlocks` is
    public, keyed by span style, and takes the rich spans an importer
    attaches; `LINE_BLOCK_STYLES`, `lineBlocksConflict`, `isNestingBlank`,
    `sanitizeLinkUrl` and the CSS helpers are public. `LineBlockStyle` lost
    its markdown hooks: `markdown/MarkdownBlockSyntax.kt` holds them.
    `MarkdownConfiguration` is the syntax choices alone, `MarkdownExtension`
    reads the state's styles and forwards its block members, deprecated, to
    the state. The markdown package and its tests use core's public API
    only. The sample app's plain rich text demo installs nothing.
  Checked on the Mac: every module compiles for iOS, the iOS tests pass, the
  sample app builds as the `ios` job builds it, and its markdown demo opens and
  renders. Its image shows as an empty block on iOS, before this item too:
  the Xcode run script links the framework with
  `linkDebugFrameworkIosSimulatorArm64`, which copies no Compose resources
  into the app, where `embedAndSignAppleFrameworkForXcode` would.
  - Module: done. `ComposeTextEditorMarkdown` (`composetexteditor-markdown`,
    package unchanged) holds the markdown package, its tests and the parser
    dependency; core depends on no markdown. Core's desktop tests depended on
    the module for their fixtures until 7.62.
    `SpellCheckState.withMarkdown` is gone: `textState.withMarkdown()`.
  - Hosts: done. The markdown demos install the module and the plain demo
    does not; the module is in the Dokka aggregate, `deploy.yml`, and the
    desktop and iOS CI jobs; `docs/MIGRATION.md` lists what a host changes.
    The iOS compile of the module is in the Mac queue.
- [x] **7.62 Core's block tests read their fixtures through the markdown
  module. S.** [Opus] [Lane L] Core's desktop tests depend on
  `ComposeTextEditorMarkdown` so the block tests can build and assert
  documents as markdown text (`docs/design/modules.md`, "Tests"). A core-only
  fixture notation over `applyDocumentBlocks` and the snapshot would let core's
  tests stand on core alone; worth doing if the dependency proves a burden
  (an IDE import cycle, or a markdown change failing core's suite).
  Done: block lines (`testUtils/blockLines`; `docs/TESTING.md`, "Block
  lines"), the markdown block markers a line at a time with no inline syntax
  and nothing between lines, loaded through `applyDocumentBlocks` and read
  from the snapshot, leaving the styles as an importer does.
  Core's tests build and check documents in it, set inline styles and links
  through the state, and no longer depend on the markdown module. What tests
  markdown moved there: the round-trip torture test, the fuzz fixpoint (the
  state fuzz shared as `testUtils/stateFuzz`), link safety, line endings and
  the paragraph format export. The UI fuzz checks its storms' blocks reload
  through block lines instead of markdown (7.65), and the renumbering tests
  read the layout's numbers, since block lines writes every ordered item `1.`
  (`BlockLinesTest`; `docs/design/modules.md`, "Tests").
- [x] **7.65 The UI storms no longer reach a markdown fixpoint. S.** [Opus]
  [Lane L] Since 7.62 the markdown fixpoint runs on the state fuzz alone
  (`MarkdownFuzzFixpointTest`); `EditorFuzzE2eTest` drives key events and the
  clipboard, which build documents the state interpreter does not, and checks
  only that their blocks reload through block lines. The markdown module has
  no UI harness and core's tests no markdown. Give the markdown module a small
  composed harness on core's public API, or record UI storm documents as
  snapshots core writes and the markdown module replays.
  Done: the markdown module has a small composed harness (`markdownUiTest`)
  on core's public API, and `MarkdownUiFuzzFixpointTest` runs the UI storms to
  a markdown fixpoint. The script driver moved to `testUtils/uiFuzz`
  (`FuzzUiDriver`, which both harnesses implement) and the typing and
  clipboard helpers to `testUtils/uiTest`; core still depends on no markdown.
  A sweep of seeds 1 to 300 found no fixpoint failure and one crash, seed 27
  (6.33).
- [x] **7.63 HTML export ignores retired styles. S.** [Opus] [Lane H] The
  markdown exporter writes a span still carrying a retired configuration's
  bold or link style as its marker (`TextEditorState.retiredRichTextStyles`);
  `html/HtmlTag.kt` matches the current styles alone, so after a theme change
  a copy or an HTML export of older text writes a configured colour or size
  where markdown writes `<strong>`. Read the retired styles there too.
  Done: HTML writes no colour or size, so what was lost was a dark theme's
  highlight (no `<mark>` after a switch to light) and a retired link style's
  look written inside its anchor (`<u>`). Export and copy now read a span
  carrying a retired style, and none of the current ones, as the current
  style in that role (`html/HtmlTag.kt`, `RetiredStyles`), with markdown's
  roles and order; a style several retired configurations share is read as
  the most recent one's, in markdown too (`html/RetiredStylesHtmlTest.kt`).
  The retired list is replaced rather than mutated, so an export on another
  thread reads it whole. `AnnotatedString.toHtml(styles)` and
  `ClipboardHelper.setText` without markup have no state and read the
  current styles alone.

- [x] **7.64 Bold text at a heading's size exports to markdown as a heading.
  S.** [Opus] [Lane I] `annotatedStringToMarkdown.kt` `styleMarkers` keeps a
  legacy heading path for spanless content: any run bold at a configured
  heading size writes as that heading's marker, so "a **BIG** b" with the bold
  word at 24 sp exports as `a ` and `## BIG` on lines of their own. HTML takes a
  line's heading from its block alone since 6.26; markdown export should too,
  writing such a run as bold with its size.
  Done: `exportAsMarkdown` takes a line's heading from its heading block alone
  and writes such a run as `<span style="font-size:24px">**BIG**</span>`, inside
  a heading too; a heading's bake under a retired configuration is left out like
  the current one. The standalone `AnnotatedString.toMarkdown`, which has no
  blocks, still reads a run at a heading's size as that heading, as
  `AnnotatedString.toHtml` does (`HeaderSemanticsTest`; `docs/MIGRATION.md`).
- [x] **7.67 An empty quoted list item at the end is not a fixpoint. R.** [Opus]
  [Lane I] Fuzz seed 2482 (`FUZZ_SEED=2482`, the markdown module's
  `MarkdownFuzzFixpointTest`) ends with an empty quoted list item after a
  fence; the first export's last line `> - ` ends in one more space than the
  second export's. Found in 6.28's seed sweep.
  Done: the item held a space, which CommonMark drops after a marker (any list
  item or heading of only whitespace, anywhere). Export writes such a body as
  indent entities (`> - &nbsp;`), and import reads an item's or heading's
  entities back as the whitespace, where a plain line of only entities stays
  an empty line; a foreign `-   ` is still an empty item
  (`WhitespaceOnlyBodyTest`, seed 2482 in `MarkdownFuzzFixpointTest`;
  `docs/design/line-blocks.md`, "Leading indent"). Found: 7.70, 7.71.
- [x] **7.70 A whitespace-only first line loses its whitespace. R.** [Opus]
  [Lane I] A document whose first line is spaces alone (`setText(" \nb")`)
  exports as ` ` and imports as an empty line, so the second export differs.
  The parser drops a `WHITE_SPACE` token at offset 0
  (`markdownToAnnotatedString.kt`, `appendMarkdownNode`); the same line
  anywhere else keeps its spaces.
  Done: a `WHITE_SPACE` token that is the file's own (a line of only
  whitespace) is kept at offset 0 too; a first paragraph's leading spaces
  still drop there (`WhitespaceOnlyBodyTest`). Found: 7.80.
- [x] **7.71 A blank line of a tab gains a blank line on import. R.** [Opus]
  [Lane I] A line of only a tab or four spaces after a paragraph (`a`, `\t`)
  or a list item exports as `a`, a blank line, `\t`; import keeps the
  separator because `isParagraphSeparator` reads the next line as indented
  code, which a blank line never starts, so the second export has two blank
  lines.
  Done: a line of only spaces and tabs is not read as indented code
  (`INDENTED_CODE_LINE`), so the separator before it is dropped after a
  paragraph, list item, quote or fence (`ParagraphSeparationTest`).
- [x] **7.72 Text joined onto a heading keeps the other heading's look. S.**
  [Opus] [Lane I] Deleting the line break between an h2 "Title" and an h3 "Sub"
  leaves "Sub" baked with the h3 look inside the h2 line, so the editor shows it
  at the h3 size and markdown export writes `## Title` and `### Sub` on lines
  of their own. HTML export strips any heading's look from a heading line since
  6.31; a paragraph that takes in heading text writes it as `<strong>`. A join,
  and a paste of heading text into another line, should strip the baked look of
  a heading the text no longer belongs to. Under a configuration whose heading
  look equals an inline style (`header4Style = boldStyle`), a style swap's
  rebake strips the user's spans of that style from the heading's line too,
  since `rebuildWithoutBlock` drops every span equal to the look. Found in 6.31.
  Done with 6.35: a join strips the look of the heading its tail came from, and
  the kept heading's look is baked over the joined line, so deleting the line
  break between "Title" and "Sub" exports as `## TitleSub`
  (`HeaderSemanticsTest`, `state/JoinBlockTextStyleTest.kt`). The paste is
  6.38 and the look equal to an inline style 7.79.
- [x] **7.79 A heading look equal to an inline style takes the user's spans of
  it with the heading. S.** [Opus] [Lane I] Under a configuration whose
  heading look equals an inline style (`header4Style = boldStyle`), demoting
  the heading or a style swap's rebake strips the user's bold inside the line
  too, since `rebuildWithoutBlock` drops every span equal to the look. Such a
  look is ambiguous to 6.35 as well, which neither bakes it over a joined line
  nor leaves it behind when text moves off the heading, so a body line joined
  onto such a heading stays unbolded and a heading's tail joined onto a plain
  line stays bold. Stripping only a run over the whole line would keep a
  user's partial bold; a bold run over the whole line stays ambiguous. Found
  in 7.72.
  Done: no rule over runs can tell them apart, since the span model merges
  equal styles (a word bolded or typed inside the heading joins its look). A
  heading whose style equals an inline style bakes `RichTextStyles.headingLook`
  instead, that style with the default platform style, which draws nothing but
  keeps it unequal, and the importers and exporters read the same look. Such a
  heading now behaves as any other: its look is baked over a joined line and
  left behind by text moving off, the user's bold inside it outlives a
  demotion, a rebake or another level, and exports as bold; bold removed
  inside it leaves the heading's look. The saved state keeps the mark
  (`HeadingInlineLookTest`, `JoinBlockTextStyleTest`,
  `HeadingInlineFormattingHtmlTest`, `HeaderSemanticsTest`;
  `docs/MIGRATION.md`, `docs/design/line-blocks.md`). Found 7.83.
- [x] **7.83 Markdown export drops the user's bold equal to a retired heading
  look. S.** [Opus] [Lane I] `exportAsMarkdown` strips every retired
  configuration's heading look from a heading line, including one that equals
  a current inline style, so after a switch from `header4Style =
  SpanStyle(Bold)` (bold then styled otherwise) to a configuration whose
  `boldStyle` is plain bold, a bold word inside an h4 exports without its
  `**`. HTML export leaves out only looks no current inline style shares
  (`RetiredStyles.headingOnlyLooks`). Found in 7.79.
  Done: as HTML export, a retired heading look that is an inline style of its
  own configuration or of the current one stays, and exports as that style;
  the looks a heading line leaves out are worked out once per export. A
  leftover bake of such a look (an undo past the switch) then exports as the
  style too, as in HTML: the two cannot be told apart
  (`HeaderSemanticsTest`). Found 7.85.
- [x] **7.85 Markdown export leaves another level's heading look on a
  heading line. S.** [Opus] [Lane I] HTML export leaves out every level's
  heading-only look, current and retired, on a heading line, as text pasted
  from another heading keeps that heading's look; `exportAsMarkdown` leaves
  out only the line's own level's, so a `##` look on an h4 line exports as a
  sized span. Share one rule between the two (`RetiredStyles.headingOnlyLooks`
  is core-internal, so markdown keeps its own copy today). Found in 7.83's
  review.
  Done: both exports leave out `RichTextStyles.exportedHeadingLooks` (public, so
  markdown shares it): the line's own look, and every level's look, current and
  retired, that is no configuration's inline style. A look equal to a retired
  configuration's inline style now stays and exports as that style in both. A
  span a host set equal to another level's look on a heading line is left out
  too, as HTML already did: the span model cannot tell it from pasted heading text
  (`HeaderSemanticsTest`).
- [x] **7.80 A foreign paragraph's leading spaces are kept except at the
  file's start. R.** [Opus] [Lane I] CommonMark drops up to three leading
  spaces of a paragraph line, and import does for the first paragraph
  (`   text` reads as `text`), but keeps them on any later one (`a`, a blank
  line, `   text` reads as `   text`, which export then writes as `&nbsp;`
  entities): the parser's `WHITE_SPACE` child of a paragraph drops only at
  offset 0 (`appendMarkdownNode`). Pick one; the editor's own export never
  writes raw leading spaces, so only foreign markdown sees it. Dropping them
  everywhere changes the output `MarkdownParsingTest` pins for its
  mixed-indent input. Found in 7.70.
  Done: CommonMark's rule, everywhere: a paragraph's or list item's leading
  whitespace drops on each of its lines, the first's and every continuation's,
  inside bold or a link too, wherever the paragraph is. A line of only
  whitespace stays (7.70) and a fenced line keeps its whitespace as written;
  the editor's own indent is written as entities, so only foreign files
  change. `MarkdownParsingTest` and one `InlineStyleSyntaxTest` case now pin
  the stripped lines (`WhitespaceOnlyBodyTest`; `docs/design/line-blocks.md`,
  "Leading indent").

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
- [x] **7.42** [Opus] [Lane J] `FindState.selectionBeforeSearch` is a plain range
  that does not follow edits. After Replace moves on to the next match, turning on
  find in selection scopes to the old offsets, which can point at other text or
  past the end of a line. Keep it as a tracked decoration span, like the scope,
  or drop it on any edit.
  Done as both: find's own replacements carry the range along as they do the
  scope, by the length that landed (after the input filter and line ending
  normalization); a replacement inside it stays inside, one across its edge is
  left out. Replace All is included, which 7.29 had to refuse while the range
  went stale. Any other edit drops it, found by the line list's identity. The
  range is now the user's last own selection in the session: one made before
  pressing Replace counts, and clearing the selection keeps it, so find in
  selection with nothing selected uses it too (`FindInSelectionTest`). Not a
  decoration span: a span would ride into the clipboard and undo metadata and
  split on Enter (7.53, 7.54).

- [x] **7.43 Line breaks inserted into a line block. R.** [Opus] [Lane I]
  Text with a line break inserted into a list item or other line block leaves
  the new line as body text, where Enter at the same spot continues the block
  (and Word and Google Docs keep both halves in the list). Seen with find's
  regex replace of ", " by `\n` in "- a, b", which exports "- a" then "b".
  Check programmatic `replace` and plain paste too; fix it in the edit
  pipeline, not in the find addon. Where it lives, from lane I's reading:
  `RichSpanManager.handleInsert` splits a line-anchored span at the caret
  only for a lone `"\n"` insert (its cases 1 to 3); text with a line break
  inside takes the plain path, so the span's end is carried onto the last new
  line and the span straddles the split. The fix is that split for any
  inserted text holding a newline, in `handleInsert` and `handleReplace`, then
  a continuation in `TextEditManager.applyOperation` after `updateSpans`:
  apply the first line's list (at its level), quote and fence blocks to the
  new lines with `applyLineBlock`, inside the same atomic edit, as
  `LineBlockEditBehavior.onNewline` already does for Enter (undo removes the
  lines, so nothing extra is recorded). Headings do not continue (5.5).
  Done: a line-anchored marker on the line an insert or single-line replace
  breaks after its start stays on that first line, trimmed to it; one the break
  lands in front of follows its text down, as Enter at a line's start moves it.
  `applyOperation` then continues that line's blocks onto the other new lines (a
  list item at its level, a quote, a fence) with `writeLineBlocks`, recorded as a
  `LineBlock` step of the same undo group: a redo, which replays the text
  unrecorded, puts the markers back, and an undo never continues a block onto
  the text it restores. Enter (`insertNewlineRaw`) is left to
  `LineBlockEditBehavior`, and an editor without that behavior gets none. A
  heading continues only when the break falls inside its text, as Enter inside a
  heading keeps both halves headings; lines added at its end are body text
  without its text style. A replace across lines leaves its last line the blocks
  of the line its tail came from; one that joins a marker's line onto the kept
  head of an earlier line drops the marker, as a joining delete does. Find's
  replace, programmatic `replace`, paste and typed or IME text all take this
  path, and a block copied in the editor and pasted onto a continued line
  demotes the block that refuses to share it (`pasteRichSpans`)
  (`blocks/LineBreakContinuationTest.kt`). A rich HTML paste lays its own blocks
  over the continued ones afterwards, unrecorded, so a redo of it loses them
  (6.5).
- [x] **7.68 Find's scope does not come back on undo. S.** [Opus] [Lane J]
  The find in selection scope lives only in its `FindScopeStyle` decoration
  span (`FindState.scopeRange`). Since 7.54 undo restores no decorations, so
  deleting the scoped text and undoing before find re-searches leaves no
  scope, and find in selection searches the whole document; text restored at
  the scope's start lands outside it. Keep the scope in `FindState` as well and
  draw the span from it, or re-scope on the undo's edit.
  Done: `FindState` records the scope against each document text it sees
  while find in selection is on (keyed by the text's length and 64-bit hash,
  the last 1000 edits), and an undo that brings back a recorded text lays the
  scope back where it was, so text an undo restores at its start is inside it
  again. An undo is told from a new edit by there being something to redo
  (core has no undo signal); retyping deleted text keeps the scope where it
  now is. An undo after the deletion turned find in selection off turns it
  back on; turning it off yourself, closing, or replacing the document
  forgets the history (`FindInSelectionTest`).
- [x] **7.69 Find counts a replacement the input filter refused. S.** [Opus]
  [Lane J] `replaceAll` returns every target and clears every match, and
  `replaceCurrent` returns true, even when the input filter (a full
  `maxLength`, `SingleLine` against a replacement with a line break) refused
  some or all of them, so the refused matches stay in the text unhighlighted.
  `replace` now returns null for a refused edit (7.55); count and keep those.
  Done: `replaceAll` counts only what landed and keeps the refused matches
  that are still matches as the matches, where the other replacements moved
  them, the first current and selected; a refused `replaceCurrent` returns
  false and keeps its match current (`FindReplaceTest`; the module docs).

### Spell check addon

- [x] **7.20** [Opus] [Lane K] Sentence mode: sentences run across line
  boundaries, offsets shift on indented lines, and each partial check rescans
  the whole document. Tested only against fakes. Also: each period copies the
  sentence built so far (`sentenceBuilder.toString()`) to test for an
  abbreviation, so a long run of periods that end no sentence scans in O(n²).
  Done: a line is a paragraph, so `sentenceSegments` ends every sentence with
  its line and looks ahead no further; each sentence's text is the line's text
  over its range, from its first non-whitespace character to its last, so a
  checker's offsets land on indented lines. `sentenceSegmentsInRange` and
  `findSentenceSegmentAt` segment only their lines. The abbreviation test reads
  the word before the period from the line, at most 16 characters back. A
  partial sentence check covers the whole lines it touches, so an edit in part
  of a sentence no longer leaves a second flag on the rest of it. The ranges an
  edit batch leaves to check now reach every line an insert or replacement
  wrote (a line break, a pasted paragraph), where they stopped on its first
  line. An ellipsis before a capital ends a sentence; its look-ahead started
  two characters late (`SentenceSegmentationTest`, `SegmentationCostTest`,
  `ComputeAffectedRangesTest`, and `SentenceModeSymSpellTest` against a real
  SymSpell checker). Found 7.74.
- [x] **7.74 A batch's earlier ranges are not moved by its later edits. C.**
  [Opus] [Lane K] `SpellCheckingTextEditor`'s `computeAffectedRanges` merges
  the ranges of one debounced batch of edits, each in the coordinates the
  text had when its edit ran, and never moves an earlier range by a later
  edit. A line inserted above an earlier edit in the same batch (Enter on a
  line above, then typing below within the debounce) leaves that range a line
  short of its text, so the check runs on the wrong line. Move each range by
  the edits after it, as `TextEditOperation.transformOffset` does.
  Done: each insert, deletion or replacement moves the ranges gathered before
  it, as text from one point to another replaced by text ending at a third
  (lines added or removed, a joined line's tail), and a range inside what it
  replaced closes up to it (`ComputeAffectedRangesTest`). Not through
  `transformOffset`, which is internal to the core and whose `Replace` ignores
  the lines a replacement adds or removes (housekeeping). A deletion is still
  checked over the range it deleted, read in the text after it, to match what
  invalidation strips (7.76). Found 7.76 and 7.77.
- [x] **7.76 Invalidation reads a deletion's range in the text after it. S.**
  [Opus] [Lane K] `SpellCheckState.invalidateSpellCheckSpans` strips the flags
  that intersect a `Delete`'s or `Replace`'s `range`, which addresses the text
  before the edit, from flags the core has already moved into the text after
  it. Deleting "xxxxxxxxxx " before "teh wrd" strips both flags, which lie where
  the deleted text was; `computeAffectedRanges` re-checks that same extent to
  make up for it (7.74). A multi-line deletion near the document's end leaves
  a range past the last line, which `wordSegmentsInRange` answers with nothing,
  so the flags it stripped there stay off until a full check. Strip, and
  re-check, around the point the deletion closed up, and a replacement's new
  text, in the text after the edit.
  Done: invalidation strips the flags over or touching what an edit wrote, or
  the point a deletion closed up, read in the text after it, as diagnostics
  already did; a flag on the word beside it goes too and the re-check puts it
  back. The batch checks the same extent. That range past the last line did
  not just go unchecked: `LineDiff` never moves a range past the document's
  end, so `settlePartialCheck` spun forever on it; a partial check now cuts
  its range to the document first. A word check replaces the flags of every
  word it scanned, the nearest word beyond each end included
  (`SpellCheckStateTest`, `ComputeAffectedRangesTest`, `SpellCheckE2eTest`).
  Found 7.81.
- [x] **7.77 A batch's ranges are paired with the text at collection. C.**
  [Opus] [Lane K] `SpellCheckingTextEditor` reads `computedAgainst` when the
  debounced collector takes a batch, not when the batch ended. While an earlier
  batch's check holds `checkMutex`, the next batch waits in the buffer, and an
  edit made meanwhile (a later batch's) is already in the text it is paired
  with: `LineDiff` sees no change, so a line inserted above it puts the check a
  line off. Record the text with each batch as it closes.
  Done: the debounce reads the text as each edit of the batch arrives and
  pairs the batch with the last read, which no later edit is in (reading as
  the quiet period ends could take in an edit whose value had not arrived). A
  batch from a document replaced since is dropped, since the replacement has
  its own full check. A batch that waited is moved through the edits since by
  `LineDiff`, whose single band can widen it over the lines between them
  (`SpellCheckE2eTest`, `DebounceUntilQuiescentTest`).
- [x] **7.81 Invalidation reads a burst's edits in the text its later edits
  left. C.** [Opus] [Lane K] Edits that commit before the collector runs (a
  replace-all, whose replacements go last to first) reach
  `invalidateSpellCheckSpans` one by one, each read in the text after the whole
  burst rather than after itself. A replacement that adds lines before an
  earlier-processed one moves that one's text, so its range strips flags
  elsewhere, on lines no batch range re-checks, until a full check. Move each
  edit's range through the edits after it, as `computeAffectedRanges` does,
  once the burst is in. Found in 7.76.
  Done: `TextEditorState.editOperationBursts` hands a collector the edits
  that landed since it last ran, as a list: the editor counts each edit as it
  is applied and as it is emitted, so a collector resumed as a group's first
  edit is announced waits for the rest, and a group that throws is forgotten
  (`EditOperationsDeliveryTest`). The spell check collector strips each
  burst's flags through `invalidateSpellCheckSpans(List)`, which moves each
  edit's range through the later ones (`computeAffectedRanges`, which moves
  every range at once for an edit above them all, as each of a replace-all's
  is: 5000 replacements went from about 170 ms to 2). A single edit keeps its
  overload (`SpellCheckStateTest`, `ComputeAffectedRangesTest`,
  `SpellCheckE2eTest`). Found 7.84.
- [x] **7.84 Diagnostics invalidation reads a burst's edits in the text after
  it. S.** [Opus] [Lane K] `TextDiagnosticsState.invalidate` takes one edit
  at a time off `editOperations`, as spell check did before 7.81, so a
  replace-all whose later replacement adds lines above an earlier one strips
  underlines off the wrong line until the refresh. Move it onto
  `editOperationBursts` and `computeAffectedRanges`. A burst can also span a
  `setText` or `setDocument`, which emits nothing, so its edits are read in the
  new document, where both invalidations can strip flags the new document's
  check has placed. Found in 7.81.
  Done: `TextDiagnosticsState.invalidate(List)` strips each burst's underlines
  through `computeAffectedRanges` and the span index, fed by `editOperationBursts`;
  the single-edit overload stays. A burst leaves out the edits applied before the
  document was last replaced, noted as the replacement is applied so a collector
  resumed while its group commits drops them too, and forgotten if the group throws
  (`EditOperationsDeliveryTest`, `TextDiagnosticsStateTest`). The debounced partial
  spell check reads bursts as well, each edit tagged with its document, so a batch
  spanning a replacement checks only the new document's edits.
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
- [x] **7.44** [Opus] [Lane K] `correctSpelling`, `applySentenceCorrection` and
  `applyFix` drop the flag before the replacement, which `TextEditorState.inputFilter`
  or a single-line limit (7.13) may cut short or refuse: the word is left unflagged and
  uncorrected, or partly replaced, until the next re-check. Keep the flag when the
  filter changes the replacement.
  Done: the three replace first and clear the flags on and touching the text only
  when it landed as given (`replaceFlagged`). A refused replacement is no edit, so
  nothing would re-check it: the text and its flag stay as they were. A changed
  one is an edit like any other, left to the edit's re-check: keeping the flag
  there was tried and dropped, since the flag describes text that is gone, in
  `SpellCheckingTextEditor` the edit's invalidation removes it at once, and without
  that it marks text nothing has checked. A fix removes only its own underline, as
  before (`SpellCheckStateTest`, `TextDiagnosticsStateTest`).
- [x] **7.61 `TextDiagnosticsE2eTest` is flaky. R.** [Opus] [Lane K] Rerun alone
  at `ec0f83f`, "spelling and diagnostics underline side by side" failed once in
  three (`expected:<1> but was:<0>`), and in a full `./gradlew check` "a fix
  shows its label, and applies its replacement" timed out waiting for its
  condition (2000 ms). Both look like waits on the asynchronous check that are
  too short or too early under load. Find what they wait on and wait for it.
  `SpellCheckIgnoreE2eTest` "ignore clears every flag of the word and keeps it
  clear" fails the same way under load ("other words are still checked",
  expected 1, was 0) and passes alone.
  Done: the scans ran on `Dispatchers.Default`, which waiting for idle does
  not wait for. An internal `LocalScanContext`, read by
  `rememberSpellCheckState` and `rememberTextDiagnosticsState`, lets the UI
  tests (`setScanningContent`) run them on the test's dispatcher. Three forced
  runs alongside the core suite with `--parallel` all passed.

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
  spans (lists at any nesting level, as `bullet:2`, quotes, fences and their
  language, rules, headings, links); each line's paragraph
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
- [x] **7.24** [Opus] [Lane M] The state needs a `TextMeasurer` and a scope,
  so it cannot be created outside composition.
  Design: a second constructor, `TextEditorState(initialText)`, makes a state
  that borrows both from the editor showing it. The model needs neither:
  layout waits for a viewport, which only a composed editor sets, so the text,
  spans, edits, undo and the format extensions all work before one is shown.
  `scope` stays a `val`, but becomes a scope that forwards to the one bound
  now, so what captured it (the scroll manager, the context menu, addons)
  follows a re-bind. Unbound it is a cancelled scope, so a scroll asked for
  with no editor showing is dropped. `textMeasurer` throws until bound.
  `BasicTextEditor` and `RichTextView` bind a borrowing state to their own
  composition's scope and measurer before reading either, re-bind it when a
  new composition shows it (a view model's state across a configuration
  change), and unbind it when the composition that bound it leaves. The
  existing constructor is unchanged and never borrows.
  Done as designed. Each composition's loan is a `RememberObserver` made
  while composing (so the composition reads it at once) and taken back when
  forgotten or abandoned; with two showing the state, the latest lends, and
  the other takes over when it leaves. With none left the state drops the
  measurer and the canvas coordinates, which would keep the departed
  composition (an Android activity) alive, skips layout until lent another,
  and hides the caret handle, whose timers ran on the departed scope
  (`HostCreatedStateE2eTest`). A composable that reads `textMeasurer` before
  the editor showing a borrowing state has composed throws, as documented.
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

- [x] **7.49** [Opus] [Lane H] HTML export and import ignore a paragraph's
  format (5.7): `text-align`, `margin-top`, `margin-bottom`, `text-indent`,
  `padding-left` and `line-height` on the paragraph would carry it, as the
  clipboard's HTML and a host's export want. Done
  (`html/ParagraphFormatCss.kt`): export and the clipboard's HTML write a
  line's format as the inline style of its `<p>`, heading or `<li>`:
  `margin-top` and `margin-bottom` (dp as px), `text-align`, `margin-left`
  for the indent (the start-side margin Word and Google Docs write; sp as px,
  em as em), `text-indent` for the first line's, and `line-height` (sp as px,
  em as a plain multiple). Import and paste read those and the `margin` and
  `padding` shorthands, `padding-left` and the inline-start forms (a margin
  and a padding add up), in px, pt, in, cm, mm, em and rem, from the element
  that holds a line or an `<li>`; a zero margin or indent is the default, so
  Google Docs' zeroed margins add nothing, and a negative `text-indent` is a
  hanging first line. A line height every one of two or more paragraphs
  carries is the source's own spacing (Google Docs writes 1.38 on each) and
  is left to the editor's, as 7.46 does a base colour and size, so a fragment
  whose every paragraph has one line height loses it. A paragraph split by
  `<br>` gives its space before and first-line indent to its first line and
  its space after to its last. A pasted paragraph's format replaces the one a
  paste at a line's start leaves there. Code fence lines, rules and images
  carry none (`html/ParagraphFormatHtmlTest.kt`).
- [x] **7.50** [Opus] [Lane K] `wordSegments()` and the sentence segmentation
  copy the whole line list (`textLines.toList()`) for a snapshot before
  scanning, though the list is immutable since 7.8; the spell checker's full
  scan pays an O(lines) copy it no longer needs.
  Done: both iterate the line list they start on, which an edit replaces rather
  than changes, so a scan still sees one revision; one that stops early reads
  only the lines it reached, and a sentence the lines it looks ahead to
  (`SegmentationCostTest`). Found while there: 7.56.
- [x] **7.51** [Opus] [Lane M] The semantics text with links
  (`EditorSemantics.textWithLinks`) walks every rich span per revision to find
  the links, so a spell-checked document pays O(spans) per keystroke while a
  screen reader is on; the per-line index (7.8) could answer for the lines
  that hold links.
  Done: `SemanticsDocument` keeps the links it found in each chunk of the span
  index, by the chunk's identity, and a revision scans only the chunks it does
  not share with the last one read (a keystroke or a spell-check pass rewrites
  one or two), plus the loose spans; the text is still cached per revision
  (`SemanticsLinksCostTest`). Still per revision with a link present: the whole
  text is copied into the published string with its links, as `getAllText`'s
  splice copies it, and an undo back to an older revision rescans its chunks.
- [x] **7.53** [Opus] [Lane H] `TextEditorState.copyRichSpans` keeps decoration
  spans, so a copy carries spell-check flags, find highlights and find's scope to
  the clipboard, and a paste lays them over the pasted text, where their owners
  do not expect them (a second find scope, a stale flag). Drop decorations there,
  as the saver does and a drag's carried spans do (6.21): leave `isDecoration`
  spans out of `preservedRichSpans`. (Filed as 6.24 on the lane H branch before
  the merge; 6.24 here is the drop caret.) Done: `preservedRichSpans`, which a
  copy and a drag share, leaves decorations out, inside the copy or running past
  it (`spans/RichSpanClipboardTest.kt`). A delete's undo metadata is 7.54.
- [x] **7.54** [Opus] [Lane G] A delete whose metadata holds decoration spans (a
  spell-check flag or find highlight on the deleted text) never joins a typing run
  (`TextEditHistory`'s delete merge and `isSingleTypedChar`), so backspacing
  through a flagged word leaves one undo step per character, and its undo puts the
  decoration back where its owner no longer tracks it. `withoutErasedRun` already
  ignores decorations; the merge and the restore should too. Done: `recordEdit`
  leaves decorations out of the spans it records, so the merge, the typing check,
  the erased-run check and the restore all see content spans only
  (`state/DecorationUndoTest.kt`).
- [x] **7.55** [Opus] [Lane G] `TextEditorState.replace` and `insertText` return
  nothing, so a caller cannot tell whether the input filter (7.13) refused or
  changed its text. Find (7.42) and spell check (7.44) infer it from the line
  list's identity and the change in length, which a behavior that edits during the
  same call would throw off. Return what landed, or null when refused. Done:
  `replace` and `insertStringAtCursor` return the range their text landed in
  (after the filter and line ending normalization; collapsed when nothing was
  inserted), or null when the filter refused it. Find's replace
  (`FindState.replaceInGroup`) and spell check's correction (`replaceFlagged`)
  read it instead of comparing lengths (`state/EditResultTest.kt`).
- [x] **7.56** [Opus] [Lane K] `wordSegments()` opens its ICU word cursor with
  `use` inside the sequence builder, so a consumer that stops early (`first`,
  `find`, an abandoned iterator) never closes it, and the native break iterator
  waits for the finalizer. The spell checker drains the sequence, so only other
  callers leak; close the cursor per line or return a closeable scan.
  Done: the scan segments its lines in batches, each with its own cursor
  closed before the batch yields, so no cursor is open across a yield. A
  cursor per line made a whole scan about 2.5 times slower on desktop (20,000
  lines: 80 ms against 30 ms); batches double from one line to 256, so an
  early stop reads at most about twice the lines it reached and a whole scan
  opens a cursor per 256 lines (`SegmentationCostTest`).
- [x] **7.57** [Opus] [Lane M] What 7.36 could not match in the semantics text
  layout: character bounds sit off by the content padding, the space above the
  first paragraph and the editor's scroll offset, since a `TextLayoutResult`
  cannot be offset and moving the semantics node to the content origin would move
  the field's bounds; and a text edit shapes the whole document again on the next
  request, since a `TextLayoutResult` cannot be put together from the editor's
  per-line layouts. Needs a semantics node or a platform accessibility hook that
  answers character bounds from the rows directly.
  Designed in `docs/design/accessibility-text-layout.md`. Only Android reads
  character bounds from the layout per request (TalkBack's character-location
  extra, a range at a time) and offers a hook: a delegate wrapper installed
  through `ViewCompat` around Compose's, answering that key from a
  `CharacterBounds` semantics property the editor publishes, which maps an index
  to its row's glyph box through the content origin and the scroll (both axes).
  The layout stays for LINE and PAGE granularity. iOS has no bounds in its
  accessibility element; VoiceOver's caret geometry is the input session's
  `textLayoutResult`, which moves to the semantics layout (rows as drawn, one
  cache) plus the first row's top. Desktop's bridge never translates bounds and
  has no other seam, so 7.36 stays there (an upstream issue). Web reads nothing.
  Chunks: common provider and host test; Android bridge and device test; iOS.
  Done: the editor and the read-only view publish `CharacterBounds`, each
  character's row glyph box past the content padding and the space above the
  first paragraph, less the scroll, in root coordinates, measuring nothing
  (`CharacterBoundsTest`: padding, a heading, a rule, indents, a list, wrapped
  rows, right to left, an emoji, scrolled, no reshaping). Per platform:
  Android answers TalkBack's character-location extra from it through a
  wrapper around Compose's delegate, which forwards everything else
  (`EditorAccessibilityBridgeTest`). Checked on the API 36 emulator
  (`EditorCharacterLocationsTest`, which the CI emulator job runs): the
  platform's own request on a padded, scrolled editor gets the drawn glyphs,
  where Compose alone answered 72 px left of them; the text, a selection, a
  LINE move and `setText` still answer. Compose checks a request's start
  against the content description when there is one, so an editor with one
  (Hammer's) had every range past it refused; the bridge answers them. With
  TalkBack on in the emulator, TalkBack itself asked for no character
  locations (it asks only for magnification, braille or Select to Speak, which
  the run did not drive). iOS: the input session serves the semantics layout
  (rows as drawn, one cache per state, let go with the composition;
  `DocumentTextLayout` is gone) placed at the first row's top
  (`ImeTextLayoutE2eTest`), so the floating cursor and UIKit's vertical moves
  follow a heading's or an indented paragraph's rows. Checked on the iPhone
  17 Pro Max simulator (2026-10-01, `4d0a7cae`, the Markdown demo with its
  first paragraph out of view): compiles and the iOS tests pass; the spacebar
  trackpad's caret moved from a plain row into an indented list row at the
  same x (196 pt) as into a plain row, and into a heading's row at the nearest
  boundary to its x, drawn at the heading's height; how far it goes for a
  drag follows the keyboard's own acceleration (one row for a slow 12 pt, 13
  rows for a quick 100 pt); Up and Down with a hardware keyboard keep their
  column through the heading and the list, with soft wrap on and off; with
  soft wrap off and the text scrolled sideways, the trackpad's caret moved a
  row up at the same place on screen. VoiceOver on a device is still in the
  Mac queue. VoiceOver's caret outline is unchanged: the editor runs on
  Compose's legacy iOS text input, whose view answers empty caret rectangles,
  and only its native text input reads the layout for them (see the design). Desktop keeps 7.36's layout: its bridge translates nothing and
  has no other seam (an issue for Compose Multiplatform). Web reads nothing.
  Limits: a line out of view that the draw has not shaped at a new width yet
  answers from its provisional rows (Android clips those boxes away); a LINE or
  PAGE move after an edit still measures the whole document once, which
  answering those moves from the rows in the bridge would end, worth doing only
  if a TalkBack user reports line moves lagging in a long document; a block that
  replaces its text answers the block's row for its characters.
- [x] **7.58** [Opus] [Lane D] Tab at a list item's start where nesting is
  not allowed does nothing (`handleIndent` in `input/BuiltinEditorActions.kt`),
  because leading spaces in an item did not survive a markdown round trip.
  Since 7.45 they do (`- &nbsp;&nbsp;item`), so decide whether Tab there
  should insert the indent text, as it does inside an item's text.
  Done: a list's first top-level item, which has nothing to nest under, takes
  the indent text. Google Docs nests it anyway and Word indents the whole
  list; the line model can do neither, and an indent of the item's text is
  the nearest visible answer, survives a round trip and is taken back by
  Shift+Tab. A nested item already one below the item above is left alone:
  Shift+Tab there un-nests it, so an indent would be left behind. So is a
  blank first item, whose indent would keep Enter from ending the list (and
  would not round trip). A selection of the item's text is kept. Tab over
  several lines treats each line as Tab alone does (`NestedListE2eTest`,
  `TabE2eTest`; `docs/design/editor-actions.md`, "Tab").
- [x] **7.59** [Opus] [Lane M] The semantics offer `onImeAction` from
  `KeyboardSettings.imeAction` alone, so a single-line editor's default
  action key, Done since 7.40, is not offered to accessibility services and
  tests as a single-line `BasicTextField`'s is. Read
  `TextEditorState.effectiveImeAction()` instead.
  Done: the semantics read `effectiveImeAction()`, the action key the
  keyboard shows, so a single line offers Done and a multi-line editor's
  default Enter still offers nothing (`SingleLineEnterE2eTest`). The action
  reports failure when there is no handler to run (an unfocused editor with no
  host `onImeAction`).
- [x] **7.60 `setText` leaves the caret past the new text. R.** [Opus]
  [Lane M] `TextEditorState.setText` (both overloads) replaces the lines
  without coercing the caret, unlike `setDocument`: type "hello", call
  `setText("")`, and the caret stays at (0, 5); the next typed character, or
  an input method's commit, throws `StringIndexOutOfBoundsException` in
  `mergeAnnotatedStrings`. The selection and composing region are probably
  stale the same way. Found by the Android emulator smoke test (0.7); the
  failing case is `state/SetTextCaretTest.kt`, marked `failsUntil("7.60")`.
  Done: both `setText` overloads reset as `setDocument` does (one helper): the
  selection and composing region are dropped and the cursor is coerced into
  the new text.
- [x] **7.66 The single-line action key is the state's, not the editor's. C.**
  [Opus] [Lane M] `TextEditorState.isSingleLine` is true while any composed
  editor shows the state with `EditorLineLimits.SingleLine`, and
  `effectiveImeAction()` reads it, so a multi-line editor showing the same
  state beside a single-line one offers Done in its semantics (7.59), and its
  Enter presses the action key instead of starting a line. Pass each editor's
  own line limit to the semantics, the key handler and the Android
  `EditorInfo`, or document one state per line limit. Found in 7.59's review.
  Done: the action key is each editor's, as with `BasicTextField`'s per-field
  `lineLimits`. The semantics read their own editor's; the focused editor's
  input node lends the state its limit with its default action
  (`TextEditorState.focusedEditor`), which Enter, the Android `EditorInfo` and
  the single-line input filter follow, so a multi-line editor beside a
  single-line one keeps Enter and adds lines, which the single-line editor
  shows as rows. With no editor focused, or a `RichTextView` focused, the
  host's edits are still screened while any single-line editor shows the
  state (`SingleLineEnterE2eTest`, `KeyboardSettingsTest`). Found 7.73.
- [x] **7.73 Edits that reach an unfocused editor follow the focused one. S.**
  [Opus] [Lane M] With two editors on one state (7.66), the input filter's
  single-line screen and the action key's default come from the editor
  holding focus. A drop on the other editor, or an accessibility `SetText`,
  `InsertTextAtCursor` or `OnImeAction` on it, is screened by the focused
  editor's line limit and runs its default (Next moves focus from the
  focused editor). Pass the target editor's limit and default through those
  entry points (`dragdrop/`, `EditorSemantics.kt`) where they do not focus
  it first. Rare: one state shared by editors with different limits.
  Done: each editor's input node keeps its own `FocusedEditor` record, which
  the drop and the semantics reach through `TextInputRequester.editor`, and
  `TextEditorState.asEditor` lets it stand in for the focused editor's for the
  length of the call. Next and Previous aimed at an unfocused editor focus it
  first, then move on from it; Done hides the keyboard as before. The keyboard's
  own configuration (`EditorInfo`, the restart on a settings change) still
  reads the focused editor alone. An accessibility `InsertTextAtCursor` of a
  lone line break that the limit refuses now reports failure
  (`SharedStateTargetE2eTest`, `DropTargetLineLimitTest`). Found 7.75.
- [x] **7.75 A paste aimed at an unfocused editor follows the focused one. S.**
  [Opus] [Lane M] The paste action screens what it pasted inside a coroutine,
  after the clipboard read, so `TextEditorState.asEditor` (7.73) cannot reach
  it: an accessibility `PasteText` on an unfocused single-line editor beside a
  focused multi-line one on the same state pastes line breaks. Carry the
  target editor into the action (`EditorActionContext`) and screen with its
  limit once the read returns. Rare, as 7.73 is.
  Done: an action run through an editor's menu or semantics
  (`ContextMenuActions.perform`) runs as that editor (`asEditor`), and paste
  takes the answering editor when it starts, so its screen after the read
  follows the editor it was aimed at; a keyboard paste follows the editor
  focused at the keypress, though focus moves while the read is suspended
  (`SharedStateTargetE2eTest`). Found 7.82.
- [x] **7.82 A host action that edits after suspending follows the focused
  editor. S.** [Opus] [Lane M] A host action registered through
  `EditorActionRegistry` that suspends before it edits (fetching text, then
  inserting it) runs its edit outside `asEditor`, and `EditorActionContext`
  does not say which editor it was aimed at, so run on the unfocused editor
  of two sharing a state it follows the focused one's line limit. Rare, as
  7.73 is.
  Done: an `EditorActionContext` records the editor its action was run on
  (the one whose menu or semantics ran it, else the focused one) and
  `asTarget` runs an edit as that editor, after a suspend too, wherever focus
  has moved since. A menu's enabled check is asked as its editor as well
  (`SharedStateTargetE2eTest`; `docs/design/editor-actions.md`).
- [x] **7.86 Decorations as a public API. S.** [Opus] [Lane M] Spell check and
  find keep their overlays out of undo, copies and exports by marking their
  styles `isDecoration` and filtering `getAllRichSpans()` by style to replace
  them; a host has no way to do the same for its own (syntax colours, lint
  marks) without that bookkeeping, no way to colour text without making the
  colour part of it, and every `updateRichSpans` re-resolves the lines it
  touches. Give hosts decoration layers: keyed by owner, set, replaced by
  lines or range and cleared without touching another's, with a text colour,
  background and underline, cheap for a whole file.
  Done: `DecorationLayer`, `DecorationStyle` (a `textColor` beside the usual
  drawing) and the ready-made `Decoration`, with `setDecorations`,
  `replaceDecorations`, `clearDecorations` and `decorations`
  (`com.darkrockstudios.texteditor.decoration`). Setting a layer rewrites the
  span index once and lays out, normalizes and re-resolves no line;
  `updateRichSpans` takes the same path when every span only paints. A text
  colour is a `SrcAtop` tint over the drawn text in a layer, so no line is
  shaped for it; emoji, and text with a background of its own, are left out
  of the tint. The text is drawn in a pass of its own, between the host's
  `decorateLine` (drawn behind every line, as documented, rather than
  interleaved) and the foreground spans, so the layer neither clips nor tints
  anything else. Over 5,000 lines with 45,000 colour spans a whole layer
  sets in about 10 ms, one line's replace takes 17 us, a keystroke is
  unchanged, and a frame of tinted code takes 3.6 ms against 1 ms on the
  software canvas (`DecorationBenchmark`; `DecorationLayerTest`,
  `DecorationCostTest`, `DecorationDrawingTest`, the `decorations` golden;
  `docs/design/decorations.md`, `docs/MIGRATION.md`).
  Spell check and find stay on `updateRichSpans`: moving them is 7.88.
- [x] **7.88 Spell check and find on decoration layers. S.** [Opus] [Lanes J,
  K] Both find their own overlays by filtering `getAllRichSpans()` by style,
  which builds the whole span set each time, and both public style classes
  (`FindMatchStyle`, `SpellCheckStyle`, `DiagnosticStyle`) would need a layer
  to become `DecorationStyle`s, a change to their constructors. Each state
  owning a `DecorationLayer` would let them read and replace their own by line
  (`decorations(layer, lines)`) and drop the scans. Found in 7.86.
  Done: find, spell check and diagnostics each draw on a layer of their own
  (a `FindState` each; spell check and diagnostics one each, the default of
  their styles, which were matched by class) and read their marks with
  `decorations(layer[, lines])`. Their styles are `DecorationStyle`s taking a
  layer, the old constructors kept (`docs/MIGRATION.md`). Find lays again only
  the lines whose highlights changed, and a step swaps two lines. Over 5,000
  lines a find update after an edit takes 3.5 ms against 7.2 ms, a step 20 us
  against 5.3 ms, and a diagnostics refresh 3.9 ms against 6.1 ms
  (`FindBenchmark`, `SpellCheckBenchmark`); none reads or clears another
  owner's spans (`FindDecorationLayerTest`, `DecorationLayerCoexistenceTest`;
  `docs/design/decorations.md`).

## Housekeeping

- [x] The built-in paste (`pasteClipboard` in `input/BuiltinEditorActions.kt`)
  captures `answeringEditor` and replays it through `asEditor` by hand, as
  `EditorActionContext.asTarget` (7.82) now does for any action; use it. Found
  in 7.82's review.
  Now it edits through `asTarget`, a read that waits while focus moves included
  (`SharedStateTargetE2eTest`). The middle-click primary paste, which has no action
  context, still takes `inputRequester.editor` by hand.
- [x] Paste (`landPaste` in `input/BuiltinEditorActions.kt`) and drop
  (`insertAt` in `dragdrop/TextDrop.kt`) each settle the text they land by
  hand: rich spans, `applyHtmlPasteBlocks`, `removeLinkLookOutsideLinks`,
  `removeBlockLooksOffTheirBlocks`. 6.41 came from the two drifting apart;
  share one helper. Found in 6.41.
  Now both settle through `settleLanded` (`clipboard/PasteHtmlBlocks.kt`), the
  paste taking its copied spans from `copiedRichSpansFor` (which `pasteRichSpans`
  shares); a move settles once its source is gone. Found: 6.48.
- [ ] On each Compose Multiplatform upgrade, recheck the web drag workarounds
  in `dragdrop/PlatformTextDrag.wasmJs.kt` (6.20) against
  `WebDragAndDropManager`: the `dragenter` sent after a refused `dragstart`
  (Compose's `previousDragEventIsStart`), and the window listener standing in
  for the drop's `DataTransfer`, position and modifiers and for `onExited` and
  `onEnded`. Drop each once Compose covers it.
- [x] Android reads a clip's items into styled text twice: `ClipboardHelper.getText`
  and the drop's `droppedText` (`dragdrop/PlatformTextDrag.android.kt`), each
  preferring an item's markup unless it is this app's own and re-parses to
  other characters. Share one helper. Found in 6.20.
  Now both read through `readStyledItems` (`clipboard/ClipboardHelper.android.kt`),
  the drop adding the files of URI-only items (6.44). A drop carries its markup as
  parsed (`DroppedText.document`) on Android, desktop and the web, which `dropText`
  takes through `htmlPasteDocument` as a paste does instead of parsing it again.
- [x] The README's "Work left to do" is stale: desktop copy and paste now
  preserves formatting. Now it lists right-to-left drawing and arrows (7.6,
  7.7, 7.33) beside CommonMark; the rich clipboard (6.7, 4.9) and sentence
  spell checking (7.20) are gone.
- [x] `TextEditorScrollManager.scrollToCursor()` is public and bypasses
  `cursorScrollSuppressed` (1.23); only `ensureCursorVisible` honours it.
  Now it honours it too (`TextEditorScrollManagerTest`).
- [x] `TextEditorScrollManager.scrollToPosition(offset, animated = false)`
  animates anyway unless `top` is set: the path that scrolls just far enough
  always calls `animateScrollTo` (found in 3.9). That path now goes through
  the pixel `scrollToPosition`, which honours `animated` and without it now
  scrolls before returning rather than a dispatch later
  (`TextEditorScrollManagerTest`).
- [x] `imeSetComposingRegion` (`input/ImeEditLogic.kt`) clears the composition
  when an IME passes the bounds reversed; `BaseInputConnection` orders them.
  Found in 4.27, whose expectation follows the contract. Now it orders them too
  (`ImeEditLogicTest`).
- [x] Stray `println` calls in `state/TextEditorState.kt` and
  `SpellCheckState.kt`. Removed.
- [x] The sample app's toolbar Link button attaches its own
  `sample.LinkSpanStyle`, not the library's, so those links get no hand icon
  and do not open on Ctrl+click (1.15). Markdown-parsed links do. Now it
  calls `setLink` and `unlink` in one undo step, its dialog refuses a URL the
  editor would not set, and a selection ending where a link starts no longer
  edits that link; the sample's `LinkSpanStyle` is gone.
- [x] `docs/design/text-input-sessions.md` describes iOS as routing through
  the shared IME logic; it does not yet (4.2). True since 4.2's Linux part.
- [x] `getOffsetAtCharacter` returns a negative char for negative input.
  It now clamps to the document start (`CharacterIndexConversionTest`).
- [x] The sample app's toolbar Highlight button attaches a `HighlightSpanStyle`
  rich span (`sample.HIGHLIGHT`), which markdown export does not serialize;
  the markdown form of a highlight is the configuration's `highlightStyle`
  span style (7.16), which the button should toggle instead. Now it toggles
  that, and the plain rich text demo seeds its highlight the same way and
  takes the theme's styles, so the highlight reads in dark mode.
- [x] `rememberTextEditorStyle` leaves `backgroundColor` out of its `remember`
  keys, so a new background colour is ignored until another key changes.
- [x] The document content is not snapshot state, so the skiko input session
  (4.2) collected `editOperations` and `documentGeneration` to bump a
  snapshot-backed revision its text reads folded in. The bump landed one
  dispatch after the caret move, so a keystroke evaluated the platform's
  `snapshotFlow` twice, and each evaluation built the whole text. Now the
  request's reads (text, length, characters, selection, composition, the iOS
  layout) fold in `TextEditorState.textRevision`, which advances wherever
  `content` is published with new lines, in the same apply as the caret, and
  the session collects nothing: a keystroke re-runs an observer once, a
  request no session wraps sees a forward delete, and a rich-span pass
  (spell check, find) re-runs none (`SkikoInputMethodRequestTest`).
  `skikoMain` is iOS code as well (Mac queue).
- [x] `TextEditOperation.Replace.transformOffset` ignores the lines a
  replacement adds or removes: an offset on a later line keeps its line, and
  one on the replaced range's last line, after it, moves by the length change
  as if on one line. Only tests call `transformOffset` today. Found in 7.74.
  Fixed rather than removed, since every operation's mapping is the first
  layer of `docs/design/edit-operation-offset-transforms.md`: positions from
  the range's end on follow the new text's end, by its lines, as after an
  insert, and one inside keeps its count of characters from the range's start,
  up to the new text's end (`TransformOffsetTest`). The replace span handler
  keeps its own mapping, as the design doc notes.
- [x] The parallel lanes table leaves out items their lane tags name: 7.27
  (lane C), 7.48 (N), 7.50 and 7.56 (K), 7.51 and 7.57 (M); 7.57 is still
  open. Found reconciling lane I's 7.70 and 7.71. Now each row lists every
  item, open or done, that carries its lane's tag.

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
| 3.17 | On an iPad with an Apple Pencil (the Simulator here offers no Pencil input), in the iOS sample app: tap, double-tap a word and drag across text with the Pencil. 3.17 added the `expect` `Modifier.stylusHandwriting`, whose non-Android actual in `skikoMain` returns the modifier unchanged | The Pencil places the caret and selects as a finger does, as before | Compiles and the iOS tests pass (2026-10-01, `9f16a8e9`). The Pencil check needs a device |
| 7.57 | On an iOS device, with VoiceOver on, note what its caret outline shows on the focused editor (the simulator has no VoiceOver) | VoiceOver's outline is expected unchanged (the legacy text input view answers no caret rectangle); record what it shows | The rest passed 2026-10-01 at `4d0a7cae` on the iPhone 17 Pro Max simulator (iOS 26); see 7.57 |
