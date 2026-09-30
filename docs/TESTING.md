# Testing

## Suites

| Suite | Command | What it covers |
| --- | --- | --- |
| Editor, desktop JVM | `./gradlew :ComposeTextEditor:desktopTest` | Unit tests and headless end-to-end tests of the real composable |
| Find addon | `./gradlew :ComposeTextEditorFind:desktopTest` | Find and replace, through the find bar |
| Spell check addon | `./gradlew :ComposeTextEditorSpellCheck:desktopTest` | Spell check and diagnostics |
| Android host tests | `./gradlew :ComposeTextEditor:testAndroidHostTest` | Android input logic on the JVM |
| iOS simulator (Mac only) | `./gradlew :ComposeTextEditor:iosSimulatorArm64Test` | What only UIKit can answer |
| Android emulator smoke | `./gradlew :androidApp:connectedDebugAndroidTest` | Key events and an input method's edits reach the editor on a device |
| Browser | `cd browserTests && npx playwright test` | Real key presses in Chromium against the built wasm demo |
| Gradle check | `./gradlew check` | The JVM and host suites and lint, as the Ubuntu `build` job runs it |

Narrow a run while iterating with `--tests`, for example
`./gradlew :ComposeTextEditor:desktopTest --tests 'e2e.NavigationE2eTest'`.

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

## Geometry assertions

`utils/Geometry.kt` (editor desktop tests) runs the editor's draw functions
against its current state and records the shapes (`utils/DrawRecorder.kt`)
instead of reading pixels: `drawnCaret()`, `drawnSelection()` and
`drawnHandleCenters()` inside `editorUiTest`, in the text canvas's coordinates.
`independentLayout(text)` lays the same text out with Compose alone at the
editor's width, the reference to compare against, and `rowBox(row)` is the
editor's own row. `assertRectEquals` and `assertOffsetEquals` compare within
half a pixel. `drawing/GeometryTest.kt` is the suite. A case the editor gets
wrong today goes inside `failsUntil("<item>")`, which fails once the case
passes, so the fix removes the marker; keep an assertion outside the block that
holds both before and after the fix, so a different breakage still fails.

## Golden screenshots

`golden/GoldenScreenshotTest.kt` captures a few small scenes in the test font
(the caret, a selection across wrapped and empty lines, spell check squiggles,
nested list markers, the composing underline, paragraph spacing) and compares
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
it at another build, for example the development one.

## CI

`.github/workflows/ci-build.yml` runs on every push to `main` and
`native-parity`, on pull requests to `main`, and before a release
(`deploy.yml` calls it):

| Job | Runner | Runs |
| --- | --- | --- |
| `build` | Ubuntu | `./gradlew check`, the goldens included |
| `desktop-macos` | macOS | The three desktop suites, Mac key bindings |
| `desktop-windows` | Windows | The three desktop suites |
| `android-emulator` | Ubuntu, API 35 emulator | The Android smoke test |
| `browser` | Ubuntu, Chromium | The browser tests against a production build of the demo |
| `ios` | macOS | The iOS compile, the iOS tests, and the sample app build |

Each keeps its reports as an artifact when it fails.
