# Testing

## Suites

| Suite | Command | What it covers |
| --- | --- | --- |
| Editor, desktop JVM | `./gradlew :ComposeTextEditor:desktopTest` | Unit tests and headless end-to-end tests of the real composable |
| Find addon | `./gradlew :ComposeTextEditorFind:desktopTest` | Find and replace, through the find bar |
| Spell check addon | `./gradlew :ComposeTextEditorSpellCheck:desktopTest` | Spell check and diagnostics |
| Android host tests | `./gradlew :ComposeTextEditor:testAndroidHostTest` | Android input logic on the JVM |
| iOS simulator (Mac only) | `./gradlew :ComposeTextEditor:iosSimulatorArm64Test` | What only UIKit can answer |
| Everything that runs on Linux | `./gradlew check` | The above that Linux can build, as CI runs it |

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
