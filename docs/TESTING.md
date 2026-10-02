# Testing

## Suites

| Suite | Command | What it covers |
| --- | --- | --- |
| Editor, desktop JVM | `./gradlew :ComposeTextEditor:desktopTest` | Unit tests and headless end-to-end tests of the real composable |
| Markdown addon | `./gradlew :ComposeTextEditorMarkdown:desktopTest` | Markdown import and export: round trip, escaping, tables, links, images, nesting; layout only in the UI fuzz fixpoint, in the test font |
| Find addon | `./gradlew :ComposeTextEditorFind:desktopTest` | Find and replace, through the find bar |
| Spell check addon | `./gradlew :ComposeTextEditorSpellCheck:desktopTest` | Spell check and diagnostics |
| Android host tests | `./gradlew :ComposeTextEditor:testAndroidHostTest` | Android input logic on the JVM |
| iOS simulator (Mac only) | `./gradlew :ComposeTextEditor:iosSimulatorArm64Test` | What only UIKit can answer |
| Android emulator smoke | `./gradlew :androidApp:connectedDebugAndroidTest` | Key events and an input method's edits reach the editor on a device |
| iOS simulator smoke (Mac only) | `xcodebuild test -project sampleAppiOS/SampleAppiOS.xcodeproj -scheme SampleAppiOS -destination 'platform=iOS Simulator,name=iPhone 17 Pro'` | Typing in the running sample app reaches the editor and reads back through accessibility |
| Browser | `cd browserTests && npx playwright test` | Real key presses and input method compositions in Chromium against the built wasm demo |
| Gradle check | `./gradlew check` | The JVM and host suites and lint, as the Ubuntu `build` job runs it |

The iOS smoke test lives in the shared `SampleAppiOS` scheme. Xcode prefers a
personal copy of a scheme in `xcuserdata` over the shared one, and an older personal
copy has no tests (`xcodebuild` then says the scheme is not configured for the test
action); delete `sampleAppiOS/SampleAppiOS.xcodeproj/xcuserdata/*/xcschemes/SampleAppiOS.xcscheme`
to use the shared scheme.

Narrow a run while iterating with `--tests`, for example
`./gradlew :ComposeTextEditor:desktopTest --tests 'e2e.NavigationE2eTest'`.
Core's tests stand on core alone: the block tests build and read their
documents in block lines (below), and what tests markdown is in the markdown
module's suite.

## Block lines

`testUtils/blockLines/utils/BlockLines.kt` is a notation for a document's line
blocks, so a test can load a document and check one as a line of text without a
markdown parser: `state.setBlockLines("- a\n  - b")` loads it through
`applyDocumentBlocks` as an importer does, and `state.blockLines()` reads it back
from the snapshot. Each notation line is one document line: its markers, then
its text.

| Marker | Block |
| --- | --- |
| `> ` | blockquote, first when it stacks |
| `#` to `######`, then a space | heading of that level |
| two spaces per level, then `- ` | bullet item |
| two spaces per level, then `1. ` | ordered item (any number reads; `1.` is written) |
| three backticks, then a space | code fence line |
| `---` alone | horizontal rule |
| `![alt](source)` alone | image, given an `imageProvider` |

A `\` after the markers keeps the rest as text (`\- not a list`), and is
written wherever text would read as a marker. Unlike markdown it is strictly a
line per line: no inline syntax, no blank line between paragraphs (a blank
notation line is a blank document line), and a fence marks each of its lines.
Inline styles and links are set through the state (`addStyleSpan`, `setLink`).
A load leaves the styles as a format importer does: assigned, so typed text
takes the body style, and the body style under every line; `asImported = false`
loads bare text. Ordered numbers are the layout's (`orderedNumbers()` in a UI
test). `BlockLinesTest` pins the notation.

The state fuzz (`testUtils/stateFuzz`) is shared the same way: core runs its
storms to the undo-to-origin invariant, the markdown module to a markdown
fixpoint. So is the UI storms' driver (`testUtils/uiFuzz`, with the typing and
clipboard helpers in `testUtils/uiTest`): core's `EditorFuzzE2eTest` runs them
through `editorUiTest`, and the markdown module's `MarkdownUiFuzzFixpointTest`
through its own small composed harness, `markdownUiTest`.

Each desktop suite runs in one JVM with a 1 GB heap (the root
`build.gradle.kts`); the core suite's heap stays under 200 MB after a
collection. MockK keeps every mock, child mocks included, and every call
recorded on one with its arguments and a stack trace, until the JVM exits.
`countingMeasurer`, whose mocks the editor calls per line, does not record
`measure` and drops every mock's recorded calls when it makes a measurer. A
new mock that is called per line should not record those calls
(`excludeRecords`).

## The test font

The UI test harnesses (`editorUiTest`, `differentialUiTest`, `findUiTest`,
`spellCheckUiTest`) lay text out in a bundled font, `TestFontFamily`
(`testUtils/testFont/`), so wrapping and text widths are the same on every
machine. It is a subset of Noto Sans Regular 2.004 under the SIL Open Font
License (`testUtils/testFont/resources/fonts/OFL.txt`), made with fontTools:

```bash
python3 -m fontTools.subset NotoSans-Regular.ttf \
  --unicodes="U+0000-036F,U+0370-03FF,U+0400-045F,U+1E00-1EFF,U+2000-206F,U+20A0-20C0,U+2100-2122,U+FEFF,U+FFFD" \
  --layout-features='*' --name-IDs='*' --notdef-outline --no-hinting \
  --output-file=NotoSans-Regular.ttf
```

Characters outside the subset (CJK, Hebrew, Arabic, emoji) fall back to the
system's fonts, and bold and italic are synthesized. A harness's `textStyle`
keeps the test font unless it names another family. Tests that compose
`BasicTextEditor` themselves, or replace `state.textStyle` outright, lay text
out in the system font; keep those free of assumptions about text width.

To check that nothing depends on the machine's fonts, run the suites with
fontconfig restricted to one other font, here DejaVu (Linux):

```xml
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <dir>/usr/share/fonts/truetype/dejavu</dir>
  <cachedir>/tmp/fc-dejavu-cache</cachedir>
  <selectfont><rejectfont><glob>*MathTeXGyre*</glob></rejectfont></selectfont>
  <alias><family>sans-serif</family><prefer><family>DejaVu Sans</family></prefer></alias>
  <alias><family>serif</family><prefer><family>DejaVu Serif</family></prefer></alias>
  <alias><family>monospace</family><prefer><family>DejaVu Sans Mono</family></prefer></alias>
</fontconfig>
```

```bash
FONTCONFIG_FILE=/path/to/fonts.conf ./gradlew \
  :ComposeTextEditor:desktopTest --rerun \
  :ComposeTextEditorFind:desktopTest --rerun \
  :ComposeTextEditorSpellCheck:desktopTest --rerun
```

`--rerun` matters: the environment is not a task input, so without it Gradle
reports the tests up to date from the previous run.

## Differential tests

### The reference rule

When a behaviour question comes up (where does the caret go, what does this
chord do), the answer is what the platform's native editor does. On desktop the
practical reference is Compose's `BasicTextField`: it runs in the same test
harness and gets these conventions right. Wherever the editor and
`BasicTextField` were both probed for caret and keyboard behaviour,
`BasicTextField` gave the native answer, with these exceptions, which the
differential tests allow for (`referenceQuirk` and `differentialFuzz` in
`utils/DifferentialFuzz.kt`):

- With a selection, its Home and End measure from the selection's start and end
  rather than the caret, and so do its Shift paragraph jumps.
- Home on an empty last line moves to the end of the line above.
- End stops before a row's trailing spaces, and Up and Down stop before a row's
  trailing spaces when the goal x is over or past them.
- It has no caret affinity, so a caret on a wrap offset is on the lower row to
  it: Home from there stays put, End runs on to the lower row's end, and Up and
  Down measure from that row.
- A word wider than the row is broken where it starts instead of moving to the
  next row.
- A page move neither starts nor follows a goal x, so it measures from the
  caret, and Up or Down after one measures from where it landed. PageUp and
  PageDown stop on the first and last rows instead of going on to the document
  start and end.
- Its goal x survives typed text and Enter, so Up or Down after typing measures
  from the column the typing started at.
- Its word motions stop after every punctuation character, where GTK, `EditText`
  and Cocoa skip punctuation (they agree that an emoji is a word of its own).
  Its Ctrl+Left and Ctrl+Backspace step back one character at a time and stop
  at the first segment that began before the step, so they pass over
  one-character segments (a space, a mark, a flag, a lone ideograph) and stop
  one short of a word when the step lands inside it, where GTK and Cocoa go to
  the previous word's start.
- Its paragraph direction is the whole text's, from its first strong character,
  so a right-to-left paragraph after a left-to-right one keeps left-to-right
  arrows, where native editors and the editor resolve each paragraph on its own.
- Its arrow keys inside a mixed-direction paragraph are logical, where native
  editors and the editor move visually through the runs
  (`e2e/VisualArrowE2eTest.kt`).

### The harness

`differentialUiTest` (`utils/DifferentialHarness.kt`) composes the editor beside
`BasicTextField(TextFieldState)`, both in the test font and as wide as each
other, and replays one keystroke script through each, the reference first. Key
bindings are pinned on both sides, so the suite means the same on every OS.
`e2e/differential/BasicTextFieldParityTest.kt` is the suite of scripted cases,
compared after every stroke by `assertMatchesNative`. A case the editor gets
wrong today stays in the suite with `divergesUntil` describing what it should
do; it fails once the editor matches, so the fix removes the marker.

`e2e/differential/DifferentialFuzzTest.kt` replays seeded scripts of typing,
navigation, selection and word motion over emoji, ZWJ sequences, flags,
combining marks and right-to-left words, wrapped, unwrapped and as a single
line. A divergence at a reference quirk is tolerated: the editor is reset to the
reference's state and the script goes on. Any other divergence fails with a
transcript and the seed; replay it with `FUZZ_SEED=<seed>`.

`e2e/torture/EditorInvariantFuzzTest.kt` runs the same storms through the
editor alone and checks `EditorInvariant`s (`utils/EditorInvariants.kt`) after
every stroke: no lone surrogate, the caret on a grapheme boundary, Down moving
one visual row, Left then Right returning, and the view following the sideways
scroll. `FUZZ_INVARIANTS` names the ones to check (`all` for every one).

## Geometry assertions

`utils/Geometry.kt` (editor desktop tests) runs the editor's draw functions
against its current state and records the shapes (`utils/DrawRecorder.kt`)
instead of reading pixels: `drawnCaret()`, `drawnSelection()` and
`drawnHandles()` inside `editorUiTest`, in the text canvas's coordinates.
`independentLayout(text)` lays the same text out with Compose alone at the
editor's width, the reference to compare against, and `rowBox(row)` is the
editor's own row. `assertRectEquals` and `assertOffsetEquals` compare within
half a pixel. `drawing/GeometryTest.kt` is the suite. A case the editor gets
wrong today goes inside `failsUntil("<item>")`, which fails once the case
passes, so the fix removes the marker; keep an assertion outside the block that
holds both before and after the fix, so a different breakage still fails.

## Wrapping off

With wrapping on, the default, the sideways scroll is 0, so a test that only
wraps cannot see code that forgets it. Code that pairs a row's offsets with view
or pointer coordinates goes through the state's conversions or adds the sideways
scroll (`docs/design/soft-wrap.md`, "Testing"). The sideways variants guard the
rule: the UI storms, the invariant fuzz and the markdown fixpoint storm each run
with wrapping off over lines wider than the editor, scrolled sideways between
steps, and `assertViewFollowsSidewaysScroll` (`assertStateFollowsSidewaysScroll`
in the markdown suite) checks after each step that what the view answers moves
with the scroll. `softwrap/SidewaysGeometryTest` puts the geometry harness
through the same check scene by scene, with clicks, touch handles, the toolbar
and the magnifier. The differential fuzz also runs unwrapped and as a single
line, scrolled sideways between strokes; it compares the edits only. A new test
of geometry or pointer input belongs beside them when the scroll could change
its answer.

## Golden screenshots

`golden/GoldenScreenshotTest.kt` captures a few small scenes in the test font
(the caret, a selection across wrapped and empty lines, spell check squiggles,
nested list markers, the composing underline, paragraph spacing, each touch
handle shape) and compares
each with a PNG in `ComposeTextEditor/src/desktopTest/goldens/`. A pixel counts
as changed when a channel differs by more than 32 of 255, and a scene fails when
more than 0.1% of its pixels change. A failure writes `<name>-actual.png`,
`<name>-expected.png` and `<name>-diff.png` (changed pixels in red) to
`ComposeTextEditor/build/golden-failures/`; CI keeps them as the
`golden-failures-check` artifact.

They run on Linux only and are skipped elsewhere. The font is pinned, but Skia
rasterises glyphs with FreeType on Linux, Core Text on macOS and DirectWrite on
Windows, so antialiasing differs by OS; one set of goldens, rendered where the
Ubuntu CI job runs, keeps them meaningful. Geometry assertions cover the other
platforms.

To update the goldens after an intended visual change, on Linux:

```bash
./gradlew :ComposeTextEditor:desktopTest --tests 'golden.*' -PupdateGoldens
```

Look at every changed PNG before committing it. A new Compose or Material
version can change the default colours or antialiasing and needs the same.

## Android emulator smoke test

`androidApp/src/androidTest/.../EditorTypingSmokeTest.kt` composes an editor in
a real activity, types with injected key events
(`Instrumentation.sendStringSync`), and composes and commits through the
editor's own `InputConnection`. It needs a running emulator or a device; with
several attached, pick one with `ANDROID_SERIAL`:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest
```

## Keyboard traces

A keyboard trace records what an Android soft keyboard did through the editor's
`InputConnection`, so a bug a user hits with their keyboard replays as a host
test. To record one, the host sets a recorder on the editor's state, the user
reproduces the problem, and the host hands them the text to attach:

```kotlin
val recorder = KeyboardTraceRecorder()
state.keyboardTrace = recorder
// ... the user types ...
val trace = recorder.trace()
state.keyboardTrace = null
```

The trace holds the document's text and everything typed while recording;
tell the user before they send it.

A trace is UTF-8 text, one event per line. Strings are double quoted with
`\\`, `\"`, `\n`, `\r`, `\t` and `\uXXXX` escapes; `#` starts a comment.

| Line | Meaning |
| --- | --- |
| `keyboard-trace 1` | The format version |
| `device ...`, `note ...` | Free text, ignored by the replay |
| `text "..."`, `select a b`, `compose a b` or `compose none` | The editor when recording started (character offsets) |
| `open id inputType imeOptions "keyboard"` | A connection the keyboard was given, with its `EditorInfo` and the keyboard's id and language |
| `> id name args = result` | A command from the keyboard and what it returned |
| `? id name args = result` | A read and what it returned |
| `< selection s e cs ce`, `< restart`, `< extracted token` | What the editor told the keyboard |
| `~ replace a b "text"`, `~ select a b`, `~ compose ...` | A change not made by the keyboard: a key event's, a context menu action's or the host action key's effect, a tap, the host's own edit |
| `~ document "..."` | The host replaced the whole document (`setText`, `setDocument`) |
| `= text "..."`, `= select a b`, `= compose ...` | The editor's state when the trace was taken |

`KeyboardTraceReplayer` (`androidHostTest`) replays one against a fresh editor
and lists every place it parts from the trace: a different result, a report
the editor did or did not make, a checkpoint that does not hold. A host test
cannot run the key pipeline, so a key event's effect comes from the `~` lines
after it; `getCursorCapsMode` and the cursor anchor are not compared, and a
change to the keyboard settings is not replayed.

`KeyboardTraceCorpusTest` replays every `.trace` file in
`ComposeTextEditor/src/androidHostTest/resources/keyboard-traces/`. To add a
reported bug, drop its trace there named after the keyboard and the issue. A
recording of a bug replays the bug, so before it goes in, edit the lines that
show it (a report, a result, a checkpoint) to what `EditText` does: the replay
then fails until the fix, which lands with it.

## Browser tests

`browserTests/` is a Playwright project that drives the built wasm demo in
Chromium. Compose mirrors the semantics tree into the page for accessibility;
the tests find controls there, click the canvas at their bounds, and read the
editor's text from its textbox node (the input session's textarea is edited by
the browser too, so it proves nothing). Build the demo, then run:

```bash
./gradlew :sampleApp:wasmJsBrowserDistribution
cd browserTests
npm ci
npx playwright install chromium   # once; add --with-deps on a bare machine
npx playwright test
```

`playwright.config.ts` serves the demo itself (`serve.mjs`); `DEMO_DIR` points
it at another build, for example the development one. The suite runs one page
at a time.

`tests/composition.spec.ts` composes through Chromium's own input method,
driven over the DevTools protocol (`tests/ime.ts`): `Input.imeSetComposition`
and `Input.insertText` make the browser fire `compositionstart`,
`compositionupdate`, `beforeinput` (`insertCompositionText`), `input` and
`compositionend` on the focused field and edit it as an operating system input
method does. It covers a dead key, a Japanese composition with conversion, a
cancelled composition, and typing after a commit. A case the editor gets wrong
today is `test.fixme` with its roadmap item; to reproduce one, change it to
`test` and run it with `--repeat-each=10` (and `--workers=5` for the failures
that need load).

## CI

`.github/workflows/ci-build.yml` runs on every push to `main` and
`native-parity`, on pull requests to `main`, and before a release
(`deploy.yml` calls it):

| Job | Runner | Runs |
| --- | --- | --- |
| `build` | Ubuntu | `./gradlew check`, the goldens included |
| `desktop-macos` | macOS | The four desktop suites, Mac key bindings |
| `desktop-windows` | Windows | The four desktop suites |
| `android-emulator` | Ubuntu, API 35 emulator | The Android smoke test |
| `browser` | Ubuntu, Chromium | The browser tests, typing and composition, against a production build of the demo |
| `ios` | macOS | The iOS compile, the iOS tests, the sample app build, and its UI smoke test |

`.github/workflows/os-input-nightly.yml` runs nightly (and on pushes to
`native-parity` that change it): real X key events under the US International
layout, typed with `xdotool` into an editor window on a virtual display.

Each keeps its reports as an artifact when it fails.

## Real OS input

`testUtils/osInput/drive.sh` opens `osinput/OsInputProbe.kt`, a window with one
focused editor that writes its text to `<dir>/text`, and presses real X key
events into it with `xdotool`: dead keys and AltGr under the US International
layout. The nightly job runs it on Xvfb. It needs an X11 session: on Wayland,
`setxkbmap` does not change the layout and `xdotool` reaches only XWayland
windows. Keys go to whatever window has focus, so leave the desktop alone while
it runs. From the repository root:

```bash
setxkbmap -layout us -variant intl
testUtils/osInput/drive.sh /tmp/os-input
```
