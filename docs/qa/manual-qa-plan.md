# Manual QA plan: v2.3.1 → next release

Covers the 47 commits landed on `main` since `v2.3.1`. Each section names the PRs it
guards so a failure can be traced back to the change that introduced the risk.

Run everything in the **sample app** (`:sampleApp`) unless a step says otherwise. The
demos referenced by name are the buttons on the home menu: Rich Text Editor, Markdown
Text Editor, Markdown Editor (Blank), Spell Check, Code Editor, Find Demo,
RichTextView.

## 0. Pre-flight (automated, before any manual work)

| Check | Command | Gate |
| --- | --- | --- |
| Full multiplatform build + tests | `./gradlew check` | Green. Catches iOS/wasm breakage that desktop tests miss. |
| Desktop e2e/torture suites | `./gradlew :ComposeTextEditor:desktopTest` | Green, no flakes on a second run. |
| Spell check module | `./gradlew :ComposeTextEditorSpellCheck:desktopTest` | Green. |
| Wasm demo builds | `./gradlew :sampleApp:updateDemo` | Produces a loadable demo. |

If any of these are red, stop: the manual pass is not meaningful yet.

## 1. Platform matrix

The changes are not evenly distributed across platforms. Suggested effort:

| Platform | Depth | Why |
| --- | --- | --- |
| **Desktop (Windows)** | Full pass | Only platform with the HTML clipboard flavor and the copy-id provenance check (#38, #50, #79). AltGr fix is Windows-specific (#53). |
| **Desktop (macOS)** | Full pass of §2 + §3, smoke the rest | Cmd-based key bindings (#45) and Option chords have no other coverage. |
| **Android** | Full pass of §3, §4, §7 + smoke the rest | Touch focus rework (#88), IME routing through the behavior chain (#87), and the indented text-left fix (#91) are all Android-only paths. |
| **Desktop (Linux)** | Smoke | Shares the desktop code path with Windows minus AltGr. |
| **iOS** | Smoke | Only clipboard `expect/actual` and key-binding stubs changed. |
| **wasmJs** | Smoke | Same. Run against the built demo, not a dev server, so the release artifact is what gets tested. |

Smoke = §8 only.

## 2. Clipboard and rich copy/paste

Guards #38, #50, #79, #74, #49, #48.

**Highest-risk area in this release.** Desktop copy now attaches a process-unique copy
id, and paste only re-applies the in-editor span buffer when that id matches.

### 2.1 Round-trip inside the editor (desktop, Android)
1. Markdown demo. Select a range covering a bold word, a bulleted list item, and a
   blockquote line.
2. Ctrl/Cmd+C, click at the end of the document, Ctrl/Cmd+V.
3. **Expect:** styling, bullets, and the quote marker all survive. Nothing is stacked
   twice on a line.
4. Repeat with Ctrl/Cmd+X instead of copy: original range is removed cleanly, with no
   orphaned gutter markers left on the line.

### 2.2 Partial-span copy does not drag markers (#74)
1. Markdown demo. Select **part of the text inside** a bullet list item (not the whole
   item).
2. Copy, then paste **into the middle of** a plain paragraph line, and into the middle
   of a line that is already a blockquote.
3. **Expect:** pasted content is plain text. No bullet appears on the target line, and
   the blockquote line does not gain a stacked bullet marker.
4. Now paste the same fragment onto an **empty line of its own**. **Expect:** it
   arrives as a bullet item. A block is applied only when the pasted text becomes a
   whole line; spliced into an existing line it never is.

### 2.2b Blocks survive an edit between copy and paste
The in-editor rich-span buffer is cleared by any edit that is not the paste's own, so
this exercises the clipboard's HTML as the block carrier.
1. Markdown demo. Select whole lines covering two bullet items and a blockquote line.
   Copy.
2. Click at the end of the document and press **Enter** to open a fresh line.
3. Paste.
4. **Expect:** the bullets and the blockquote all arrive intact. Getting plain text
   here is the regression this guards.

### 2.3 Foreign clipboard cannot resurrect the span buffer (#79)
1. Desktop. In the editor, copy a styled run (say a bold, bulleted line).
2. Switch to Notepad / TextEdit / a browser, type the **exact same characters** as the
   copied text, and copy them there.
3. Return to the editor and paste.
4. **Expect:** plain, unstyled text. The earlier in-editor styling must not come back.
5. Variant: open **two** sample app instances, copy in one, paste in the other.
   **Expect:** the text arrives (via HTML/plain flavors) but the second instance does
   not reapply the first instance's span buffer verbatim.

### 2.4 Cross-application rich paste (desktop) (#38, #50)
1. Copy a bulleted list from a browser page (a Wikipedia list works).
2. Paste into the Markdown demo. **Expect:** arrives as a bulleted list, not as literal
   `-` characters or as a single paragraph.
3. Copy a heading + bold paragraph from a word processor. **Expect:** heading level and
   bold survive.
4. Reverse direction: copy a selection covering **bullets, a blockquote and a heading**
   out of the editor and paste into a browser rich-text field or word processor.
   **Expect:** the list arrives as a list, the quote as a quote, the heading at its
   level; no raw HTML markup text. Blocks arriving as flat paragraphs is the
   regression this guards.
5. Bold body text pasted out must **not** land as an `<h4>` in the target app.

### 2.5 Paste font size (#49)
1. Markdown demo. Place the caret at **offset 0** (very start of the document) and
   paste some copied text.
2. **Expect:** pasted text renders at the same size as the surrounding body text, not
   smaller.
3. Select the entire first paragraph and paste over it. **Expect:** same, correctly
   sized.
4. In the Blank Markdown demo (empty document, never typed into), paste immediately.
   **Expect:** correctly sized.

### 2.6 Context menu parity (#87, #50)
1. Right-click in the editor with a selection: Cut / Copy / Paste / Select All.
2. **Expect:** each does exactly what its keyboard chord does, including keeping list
   and quote styling on copy.
3. Right-click with **no** selection. **Expect:** Cut/Copy appear disabled (or hidden)
   rather than doing something destructive; the menu is never empty.
4. RichTextView demo (read-only): right-click. **Expect:** Copy and Select All work,
   editing actions are absent or disabled. Typing changes nothing.

### 2.7 Export while editing (#48)
1. Markdown demo. Hold down a key to type continuously and click **Roundtrip** during
   the typing burst (or trigger export from another thread if you have a harness).
2. **Expect:** no `ConcurrentModificationException`, no interleaved/garbled export.

## 3. Key bindings and input

Guards #45, #53, #87.

### 3.1 macOS bindings (#45)
On macOS, verify each:

| Chord | Expected |
| --- | --- |
| Cmd+C / Cmd+V / Cmd+X | Copy / paste / cut |
| Cmd+A | Select all |
| Cmd+Z / Cmd+Shift+Z | Undo / redo |
| Option+Left / Option+Right | Word-wise motion |
| Cmd+Left / Cmd+Right | Line start / line end |
| Cmd+Up / Cmd+Down | Document start / end |
| Option+Backspace | Delete previous word |
| Cmd+Backspace | Delete to line start |
| Option+8 | Types `{` (unclaimed Option chords must fall through to text) |

After **Option+Backspace** and **Cmd+Backspace**, press Cmd+Z. **Expect:** the deleted
text returns *and the caret lands at the correct end of it* (this was the undo defect
fixed alongside #45).

### 3.2 AltGr on Windows (#53)
Requires a layout with AltGr: switch the Windows input to Hungarian or Polish.
1. Hungarian: AltGr+X, AltGr+V. **Expect:** the accented characters are typed. Nothing
   is cut or pasted.
2. Polish: AltGr+Z. **Expect:** `ż` typed, not an undo.
3. With the US layout restored, plain Ctrl+X/V/Z still work.

### 3.3 Custom action binding (#87)
The sample app registers Ctrl+B (Cmd+B on macOS) for bold as the worked example.
1. Markdown demo. Select a word, press Ctrl/Cmd+B. **Expect:** bold toggles on; the
   toolbar Bold button lights up.
2. Press it again. **Expect:** bold toggles off.
3. Toggle bold from the **toolbar button** and confirm the chord and the button agree
   (they share one implementation).
4. Navigate back to the home menu and re-enter the demo a few times. **Expect:** the
   chord still works and is registered exactly once (the `DisposableEffect`
   unregister path).
5. Press an unbound Ctrl chord (say Ctrl+J). **Expect:** nothing happens and the editor
   does not become unresponsive; the keystroke is not silently swallowed into text.

### 3.4 Read-only enforcement (#87)
1. RichTextView demo. Attempt paste (Ctrl+V), Ctrl+B, backspace, Enter.
2. **Expect:** the document is unchanged by every one of them. Copy and selection still
   work.

## 4. Touch, focus and the soft keyboard (Android)

Guards #88, #91. **Android device or emulator required.** Use the Markdown demo, which
was lengthened specifically so it can be scrolled and flung.

### 4.1 Pan does not raise the keyboard
1. Fresh entry into the demo, keyboard down.
2. Drag a finger on the **text** to scroll. **Expect:** the document scrolls and the
   keyboard stays down.
3. Fling hard. **Expect:** momentum scrolling works, i.e. the fling is not cancelled
   mid-flight.
4. Scroll to the bullets / blockquote / code fence far below the fold and confirm they
   render correctly after a long scroll.

### 4.2 Tap focuses
1. Tap on a plain paragraph. **Expect:** caret placed, keyboard rises.
2. Tap on a **bulleted list item** and on a **blockquote line**. **Expect:** both focus
   and raise the keyboard (these were unfocusable at one point during the PR).
3. Long-press a word. **Expect:** the word selects, handles appear, and the keyboard
   does **not** slide up over the selection handles. Then type: the selection is
   replaced.
4. Tap right next to a selection handle. **Expect:** focus and caret placement, not a
   swallowed tap.

### 4.3 Spans do not fight the keyboard
1. Spell Check demo. Tap a **misspelled** word. **Expect:** the suggestion popup opens
   and the keyboard does not rise over it.
2. Tap a **correctly spelled** word. **Expect:** ordinary caret placement + keyboard.
   No context menu appears for a word with nothing to suggest.
3. Markdown demo: tap a **link** span. **Expect:** the host click listener fires; no
   keyboard slides up over whatever it opened.

### 4.4 Indented lines draw correctly (#91)
This is the Android-only `getLineLeft` fix. Compare side by side with desktop.
1. Markdown demo, on Android: place the caret on an **empty** bulleted list item (press
   Enter at the end of an item to make one).
2. **Expect:** the caret sits just after the bullet marker, indented **once**, not
   double-indented.
3. **Expect:** the bullet and ordered-list numbers draw in the gutter at the correct
   indent, not flush against the left canvas edge.
4. Check the same for: highlight decorations, spell-check squiggles, and Find match
   highlights on indented lines (use the Find demo, search for a term inside a list
   item).
5. Tap-to-place-caret on an indented line lands where you tapped (bounding boxes go
   through the same helper).

### 4.5 IME edits behave like hardware keys (#87)
Soft keyboard only:
1. Backspace at **column 0** of a bulleted list item. **Expect:** the item demotes to a
   plain line (same as the hardware key), and Ctrl+Z / the Undo button restores it.
2. Backspace at column 0 of an item **on the first line** of the document. **Expect:**
   same demotion, no no-op.
3. Press Enter on an **empty** list item. **Expect:** the block exits to a plain line.
4. Type a word, let autocorrect replace it. **Expect:** the replacement lands correctly
   and the caret/keyboard state stays in sync (no doubled or dropped characters).
5. Use the keyboard's forward-delete if available. **Expect:** it deletes forward.
6. Type with a swipe/gesture keyboard across a list item boundary. **Expect:** no
   duplicated text, no caret jumping backwards.

## 5. Line blocks, markdown and HTML semantics

Guards #57, #63, #66–#74, #78, #80.

### 5.1 Round-trip fidelity (the Roundtrip button)
Markdown demo has a **Roundtrip** button that exports to markdown and re-imports.
Press it after each of these and confirm the document is unchanged:

1. Baseline demo document, untouched.
2. A **bold run with trailing whitespace** (select `word ` including the space, bold
   it). Roundtrip: **expect** the bold survives and no literal `**` characters appear
   (#70).
3. A **bold run overlapping an inline code span**. Roundtrip: **expect** no asterisks
   leak inside the backticks, and a second roundtrip is stable (#73).
4. **Headings**: set H1 through H6 with the toolbar `H` cycle button. Roundtrip:
   **expect** every level preserved.
5. Headings + font size change: apply H2, then press the font-size **increase** button
   twice, then Roundtrip. **Expect:** the line is still an H2 (headings no longer
   reverse-match on font size) (#78).
6. **Links**: select text, click the Link toolbar button, enter `https://example.com`.
   Roundtrip: **expect** the link text *and* URL survive. Click the Link button again
   on that text: **expect** the dialog pre-fills the existing URL (#80).
7. Underline some text with **no** link. Roundtrip: **expect** no empty `[]()` link is
   fabricated (#80).
8. Lists, nested-looking lists, blockquotes, code fences, horizontal rules, and images
   all survive a roundtrip.

### 5.2 Block stacking and joins (#57, #63, #72)
1. Put the caret on a bulleted item, click Blockquote. **Expect:** a consistent result
   (quote + list resolved by one rule), identical to what importing the equivalent
   markdown produces.
2. Place the caret at the **start** of a list item and press Backspace to join it with
   the line above (a plain paragraph). **Expect:** the joined line does not keep the
   bullet marker.
3. Join two **adjacent items of the same kind** (delete the newline between them).
   **Expect:** they rejoin as one item, marker intact.
4. Delete an item's **last character** without deleting the line. **Expect:** the item
   survives as an empty item with its marker.
5. Select across a **code fence and a blockquote** and delete. **Expect:** the joined
   line has one block kind, not two stacked, and no crash.
6. Forward-delete (Del) at the end of a plain line whose next line is a list item.
   **Expect:** the marker is not dragged onto the plain line.

### 5.3 Editing at block boundaries (#67, #68, #66)
1. Create a code fence directly above a blockquote. Delete the newline between them to
   join. Then **type a character exactly at the join point**. **Expect:** no crash
   (this was an `AnnotatedString` overlapping-paragraph exception).
2. Select a range **spanning multiple lines** inside a list and type over it (replace).
   **Expect:** the typed text appears; the replacement is not dropped. Then Undo:
   **expect** the original returns with no crash.
3. Select an entire list item's text (within the line) and paste over it. **Expect:**
   the bullet marker stays anchored at column 0.
4. Select from the start of an item and replace. **Expect:** the marker does not detach
   from column 0.

### 5.4 Style span adjacency (#69)
1. Type `bold X bold` where the two `bold` words are already bold and `X` is not.
2. Apply bold anywhere else on the same line.
3. **Expect:** the single unstyled character between the two bold runs stays unstyled
   (styles must not bridge a one-character gap).

## 6. Undo and redo

Guards #75, #71, #55, #45.

### 6.1 Wordwise coalescing (#75)
1. Blank Markdown demo. Type `the quick brown fox` in one go.
2. Press Ctrl/Cmd+Z once. **Expect:** one *word* disappears, not one character.
3. Keep undoing to empty, then redo back up. **Expect:** redo restores word by word and
   ends at exactly the typed text.
4. Type a word, press Enter, type another word. Undo. **Expect:** the newline breaks
   the run (undo does not swallow across it).
5. Type a word, **click elsewhere with the mouse**, type another word. Undo. **Expect:**
   the click broke the run.
6. Type a word, then **paste**. Undo. **Expect:** the paste undoes as its own entry and
   is not merged with the typing.
7. Hold Backspace across several words. Undo. **Expect:** the deletion returns
   word-wise, not character-by-character.

### 6.2 History depth (#75)
1. Blank demo. Type continuously for ~30 seconds (or paste-and-edit repeatedly to build
   well over 100 entries).
2. Undo repeatedly. **Expect:** you can get back to the empty document; early edits
   were not silently dropped (the cap is now 1000).

### 6.3 Undo of block operations (#71, #55)
1. Backspace at column 0 of a list item (smart demotion). Ctrl/Cmd+Z. **Expect:** the
   item comes back. Undo must **not** skip past to an earlier edit.
2. Press Enter on an empty list item (block exit). Undo. **Expect:** the empty item is
   restored.
3. Load the demo document (rich, with bullets, quotes, fences, a rule, and an image).
   Type a character somewhere, then Undo.
   **Expect:** every rich span in the document is still there. This is the #55
   regression: undo of an insert previously wiped all of them.
4. Redo the same insert. **Expect:** spans still intact.

## 7. Spell check

Guards #89, #90, #65, #83.

1. Spell Check demo. Type a misspelled word. **Expect:** a squiggle appears after the
   normal debounce.
2. Fix the word. **Expect:** the squiggle clears.
3. Right-click (desktop) / tap (Android) the misspelled word. **Expect:** suggestions;
   picking one replaces the word and clears the squiggle.
4. Toggle spell checking off and immediately back on while typing. **Expect:** no stuck
   squiggles on correct words, no missing squiggles on wrong ones.
5. **Guard removal check (#90):** paste a large block of text that is almost entirely
   nonsense words (or switch the checker to a language the text is not in).
   **Expect:** every word gets squiggled and the editor stays responsive. Checking must
   **not** suspend itself, and there is no "resume" state to get stuck in.
6. Scroll a long spell-checked document quickly. **Expect:** smooth scrolling; squiggles
   render correctly deep in the document, not just near the top (#65).

## 8. Performance and smoke pass

Guards #83, #84, #85, #86, #56, #60, #65. This is also the **smoke list** for the
lower-depth platforms in §1.

1. App launches, home menu renders, every demo opens and closes without error.
2. Markdown demo: typing feels immediate. Type a long paragraph at the **bottom** of a
   long document. **Expect:** no growing per-keystroke lag as the document gets longer.
3. Scroll a long document top to bottom. **Expect:** no jank, block decorations
   (bullets, numbers, quote bars, fences, rules, images) all draw at the right place at
   every scroll position.
4. Load / roundtrip a large markdown document (a few hundred lines with many list
   items). **Expect:** import is fast and does not visibly hitch (this was quadratic
   before #56).
5. Import a document with **no** blocks at all (plain paragraphs only). **Expect:**
   fast, no second layout pass.
6. Resize the window (desktop) / rotate the device (Android). **Expect:** re-wrap is
   correct, decorations follow the new wrap, caret stays with its character.
7. Code Editor demo: syntax decorations render and follow edits.
8. Find demo: Ctrl+F, search a term with many hits, step through matches. **Expect:**
   highlights land on the right characters, including on indented lines.
9. Undo/redo toolbar buttons enable and disable correctly as history is consumed.
10. Dark mode toggle: everything remains legible.

## 9. Consumer API sanity

Guards #82, #48, #87, #90. Not strictly manual UI testing, but worth one pass before
tagging, since these change what downstream code compiles against.

1. `TextEditorState` now has **identity** equality; content comparison moved to
   `contentEquals()` (#82). Check the release notes call this out: any consumer using a
   state as a `remember` key, a map key, or in an `==` comparison changes behavior.
2. `DocumentSnapshot` and `TextEditorState.snapshot()` are public (#48): confirm they
   are documented and appear in the Dokka output.
3. New public surface to spot-check in the API docs: `KeyBindings`, `LocalKeyBindings`,
   `platformKeyBindings`, `isCtrlShortcut`, `EditorCommand.Action` + `Builtins`,
   `EditorActionRegistry`, `EditBehavior`, the `keyBindings` parameter on both editor
   composables, `LinkSpanStyle` / `setLink` / `linkAt`, `HeaderSpanStyle`.
4. `SpellCheckState` / `rememberSpellCheckState` lost their guard parameters and
   `resumeSpellChecking` (#90). None of it shipped in a release, so no migration note
   is needed, but confirm nothing in the sample app or docs still references them.
5. Run `./gradlew updateDocs` and confirm Dokka generates cleanly with the new symbols.

## Sign-off

| Area | Windows | macOS | Linux | Android | iOS | wasm |
| --- | --- | --- | --- | --- | --- | --- |
| §2 Clipboard | | | | | | |
| §3 Key bindings | | | | | | |
| §4 Touch / focus | n/a | n/a | n/a | | | n/a |
| §5 Blocks / markdown | | | | | | |
| §6 Undo / redo | | | | | | |
| §7 Spell check | | | | | | |
| §8 Perf / smoke | | | | | | |
