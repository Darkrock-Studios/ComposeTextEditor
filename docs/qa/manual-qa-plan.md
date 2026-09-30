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

1. Right-click in the editor with a selection: Undo / Redo, Cut / Copy / Paste /
   Paste as Plain Text, Select All, in three groups.
2. **Expect:** each does exactly what its keyboard chord does, including keeping list
   and quote styling on copy; Paste as Plain Text drops copied formatting.
3. Right-click with **no** selection in a fresh document. **Expect:** Undo, Redo, Cut
   and Copy show disabled; Paste, Paste as Plain Text and Select All are enabled.
   Type a word and right-click again: Undo is enabled and undoes it.
4. RichTextView demo (read-only): right-click. **Expect:** only Copy (disabled
   without a selection) and Select All. Typing changes nothing.
4a. With the caret in the editor, press Shift+F10, then the Menu key (Windows and
   Linux; macOS has neither, and in a browser note whether either reaches the page). **Expect:** the menu opens under the caret; Down,
   Up and Enter pick an item, Escape closes it and typing lands in the editor.
4b. In an editor with start padding (the sample's `TextEditor` has 16 dp), right-click
   a word. **Expect:** the menu's corner is at the pointer, not 16 dp to its left.
5. Select a word, then right-click inside it. **Expect:** the selection stays and the
   menu offers Cut and Copy. Right-click outside it. **Expect:** the caret moves to the
   click and the selection clears before the menu opens (1.9). In the RichTextView demo
   a right-click outside the selection keeps it.
6. Middle-click in the editor, with and without a selection. **Expect:** the caret and
   selection do not change (primary-selection paste is not built, 4.23).
7. Repeat 5 and 6 on Android with a USB or Bluetooth mouse. Android delivers every
   mouse button as a press, which the desktop tests cannot reproduce.
8. Click outside the editor so it loses focus, then right-click inside it.
   **Expect:** the menu opens and the editor is focused behind it (1.22): Escape
   closes the menu and typing lands in the editor. On Android with a mouse, note
   whether the soft keyboard rises for this right-click (focus alone may start the
   input session); a right-click on an already focused editor must not raise it.

### 2.7 Export while editing (#48)

1. Markdown demo. Hold down a key to type continuously and click **Roundtrip** during
   the typing burst (or trigger export from another thread if you have a harness).
2. **Expect:** no `ConcurrentModificationException`, no interleaved/garbled export.

### 2.8 Line endings (6.8)

1. Windows: copy three lines from Notepad (CRLF) and paste. Linux or macOS: copy three
   lines of a CRLF file from a terminal (`printf 'a\r\nb\r\nc'`) or an editor that
   keeps CRLF. Android, iOS, web: paste CRLF text from any source that keeps it.
2. **Expect:** three lines, the caret at the end of the third, and no stray character
   or extra width at any line end. Arrow Right from a line's end goes straight to the
   next line.
3. Copy two lines out of the editor and paste into Notepad (Windows), TextEdit or a
   terminal. **Expect:** two lines in every target. On Windows, also paste into the
   web demo from Notepad and out of it into Notepad (the browser, not the editor,
   supplies CRLF there).

## 3. Key bindings and input

Guards #45, #53, #87.

### 3.1 macOS bindings (#45)

On macOS, verify each:

| Chord | Expected |
| --- | --- |
| Cmd+C / Cmd+V / Cmd+X | Copy / paste / cut |
| Cmd+A | Select all |
| Cmd+Z / Cmd+Shift+Z | Undo / redo |
| Option+Left / Option+Right | Word start / word end (the end of the current word, or of the next one) |
| Option+Delete (forward delete) | Delete to the word end |
| Option+Up / Option+Down | Paragraph start (then the previous one's) / paragraph end (then the next one's); with Shift, selects |
| Cmd+Left / Cmd+Right | Line start / line end |
| Cmd+Up / Cmd+Down | Document start / end |
| Option+Backspace | Delete previous word |
| Cmd+Backspace | Delete to line start |
| Cmd+Fn+Delete (forward delete) | Delete to the end of the visual row; nothing at the row's end |
| Ctrl+K | Delete to the end of the paragraph, past any wrap; at its end, join the next paragraph |
| Ctrl+A / Ctrl+E | Paragraph start / end, past any wrap; with Shift, selects |
| Ctrl+F / Ctrl+B | One character forward / back; with a selection, collapses it |
| Ctrl+N / Ctrl+P | One row down / up, keeping the column through a short line |
| Ctrl+D / Ctrl+H | Delete forward / backward |
| Ctrl+Y | Yank: puts back what Ctrl+K, Cmd+Backspace or Cmd+Fn+Delete last deleted, over any selection; Ctrl+K pressed several times in a row yanks back as one piece; Cmd+V still pastes the clipboard, which the kills leave alone |
| Option+8 | Types `{` (unclaimed Option chords must fall through to text) |
| Cmd+Shift+V / Cmd+Option+Shift+V | Paste as plain text: no copied formatting, takes the style where it lands |
| Shift+Return | New line |
| Cmd+Return / Option+Return / Ctrl+Return | Nothing in the editor (left for the host to claim) |

After **Option+Backspace**, **Cmd+Backspace** and **Cmd+Fn+Delete**, press Cmd+Z. **Expect:** the deleted
text returns *and the caret lands at the correct end of it* (this was the undo defect
fixed alongside #45).

### 3.2 AltGr on Windows (#53)

Requires a layout with AltGr: switch the Windows input to Hungarian or Polish.

1. Hungarian: AltGr+X, AltGr+V. **Expect:** the accented characters are typed. Nothing
   is cut or pasted.
2. Polish: AltGr+Z. **Expect:** `ż` typed, not an undo.
3. With the US layout restored, plain Ctrl+X/V/Z still work.

### 3.3 Formatting chords (#22, #87)

Bold, italic, underline, strikethrough and inline code are built-in actions on
Ctrl/Cmd+B, I, U, Shift+X and E.

1. Markdown demo. Select a word, press Ctrl/Cmd+B. **Expect:** bold toggles on; the
   toolbar Bold button lights up.
2. Press it again. **Expect:** bold toggles off.
3. Select a range that is partly bold and press Ctrl/Cmd+B. **Expect:** the whole range
   becomes bold, and the toolbar Bold button lights only once it is.
4. Toggle bold from the **toolbar button** and confirm the chord and the button agree
   (they share one implementation).
5. With no selection, press Ctrl/Cmd+B and type. **Expect:** the typed text is bold;
   press it again and the text typed after is not.
6. Repeat 1 and 2 with italic (I), strikethrough (Shift+X) and inline code (E). Export
   the markdown. **Expect:** `*`, `~~` and backticks where the styles were applied.
7. Select a paragraph with bold, italic, a link and a heading line in it and press
   Ctrl/Cmd+\. **Expect:** bold and italic go; the heading keeps its size, the link
   stays a link; one undo brings the formatting back. At a bare caret after
   Ctrl/Cmd+B, Ctrl/Cmd+\ makes the next typed text plain.
8. Press an unbound Ctrl chord (say Ctrl+J). **Expect:** nothing happens and the editor
   does not become unresponsive; the keystroke is not silently swallowed into text.

### 3.4 Read-only enforcement (#87)

1. RichTextView demo. Attempt paste (Ctrl+V), Ctrl+B, backspace, Enter.
2. **Expect:** the document is unchanged by every one of them. Copy and selection still
   work.

### 3.5 Windows and Linux chords

| Chord | Expected |
| --- | --- |
| Ctrl+Shift+V | Paste as plain text: no copied formatting, takes the style where it lands |
| Ctrl+Insert / Shift+Insert / Shift+Delete | Copy / paste / cut (Shift+Delete with no selection does nothing) |
| The dedicated Cut, Copy and Paste keys, where the keyboard has them | Cut / copy / paste, on every platform |
| Shift+Enter | New line |
| Ctrl+Enter / Ctrl+Shift+Enter / Alt+Enter | Nothing in the editor (left for the host to claim) |

### 3.5a Tab and focus

On every desktop platform, and in a browser (where Ctrl+Tab switches browser tabs instead).

| Keys | Expected |
| --- | --- |
| Tab / Shift+Tab on a body line | Four spaces in / one level of leading spaces or a tab out |
| Tab over several lines, some of them list items | Every line but the list items is indented; one undo reverts it |
| Tab at the start of a bullet or numbered item | Nothing; the item keeps its marker and gains no leading spaces |
| Ctrl+Tab / Ctrl+Shift+Tab (on macOS, Control+Tab) | Focus moves to the next / previous control; the text is unchanged |
| Escape, then Tab | Focus moves to the next control; any other key between the two cancels this, and so does leaving the editor and coming back |
| A host with `TabSettings(movesFocus = true)` | Tab and Shift+Tab move focus and never indent |

### 3.6 Caret motion

On every desktop platform unless a row names one; the macOS chords are in 3.1.

| Keys | Expected |
| --- | --- |
| Left / Right with a selection | The selection collapses to its start / end; the caret moves no further |
| Up on the first row / Down on the last row | Document start / end; with Shift, selects to it |
| Up / Down through a short line, in a proportional font | The caret keeps its x on longer lines past the short one; a Left, Right, click or edit starts a new column |
| Up / Down in a wrapped paragraph | One visual row at a time, never skipping a row |
| PageDown / PageUp in a long document | The caret moves a viewport's height and keeps its place on screen and its column; the view scrolls with it |
| PageDown on the last page / PageUp on the first | Document end / start |
| Arrow down past the viewport bottom while typing | The view scrolls just enough to show the caret's row, with no extra margin |
| Left / Right across an emoji, a family ZWJ sequence, a flag, or "e" plus a combining accent | One step per cluster, never stopping inside one; Shift selects the whole cluster |
| Backspace after an emoji, a ZWJ sequence, a flag, a skin tone, or a keycap | The whole sequence goes at once |
| Backspace after a combining accent (type "e" then U+0301, or Vietnamese "ế") | Only the accent goes; the base stays. Delete before the pair removes both |
| Up / Down / End onto a row that wraps right after an emoji | The caret lands after the emoji, never inside it |
| End on a row that wraps mid-word (narrow the window) | The caret sits at the end of that row, drawn there, not at the start of the next row; End again stays; Home returns to the row's start; Down moves one row; typing inserts at the wrap |
| End on a row that wraps after a space | The caret sits after the space at the row's right edge |
| Down through a wrapped paragraph from a caret at a row's right edge | One row at a time, staying at the right edge |
| Left / Right, Shift+Left, Ctrl+Left / Ctrl+Right, Cmd+Left on macOS, in an all-Hebrew or all-Arabic paragraph, with the editor's text style set to `TextDirection.Content` (the sample apps leave it unset, so also confirm the paragraph then stays left-to-right based and logical) | The caret moves the way the arrow points: Left goes on through the text, Ctrl+Left to the next word, Cmd+Left to the line's visual left (its end); Home and End, Ctrl+Backspace and Ctrl+Delete stay logical |
| The same arrows in an English paragraph followed by a Hebrew one | Each paragraph follows its own direction |
| Ctrl+Right / Ctrl+Delete on Linux | To / delete to the end of the word, or of the next one |
| Ctrl+Right / Ctrl+Delete on Windows | To / delete to the start of the next word; from a line's last word, the line end first, and an empty line is a stop (hammer-editor#852) |
| Ctrl+Left / Ctrl+Backspace on Windows | To / delete to the start of the word, or of the previous one on the line, else the line start; from a line start, the previous line's end (only the line break goes), and an empty line is a stop |
| Ctrl+Left / Ctrl+Right through "hello, world... (again)" | Word starts and ends only; punctuation is skipped |
| Ctrl+Left / Ctrl+Right through "don’t", "naïve" typed with a combining mark, "日本語を勉強します", and "a 😀 b" | The contraction and the accented word are one stop each, Japanese steps by dictionary word, the emoji is a stop of its own |
| Double-click on "don’t", on an emoji, on a comma, on a space after a word | Selects the whole contraction; the whole emoji; nothing; the word before the space |
| Ctrl+Up | Paragraph start, then the previous paragraph's start; with Shift, selects |
| Ctrl+Down on Linux | Paragraph end, then the next paragraph's end; with Shift, selects |
| Ctrl+Down on Windows | The next paragraph's start; from the last paragraph, the document end; with Shift, selects |
| Ctrl+Up / Ctrl+Down with a selection, no Shift | Jumps from the selection's start / end |

### 3.7 Mouse selection (desktop, and Android with a mouse)

Compare against a native text field on the same machine. The double-click window is
the platform's double-tap timeout (300 ms on desktop), not the OS mouse setting.

1. Double-click a word and hold the button down. **Expect:** the word is selected
   before release. Drag right, then left past the start. **Expect:** the selection
   grows and shrinks by whole words and always keeps the first word.
2. Triple-click and drag down. **Expect:** whole lines, the first line always kept.
3. Click, then shift+double-click a later word. **Expect:** the selection runs from
   the click to the end of that word.
4. Click twice slowly, or twice a few characters apart. **Expect:** two single
   clicks, no word selection.
5. Hover the text. **Expect:** an I-beam over the editor and over a selectable
   RichTextView.
6. Markdown demo, over a link: hover, then hold Ctrl (Cmd on macOS) and move the
   mouse a little. **Expect:** the I-beam turns into a hand only with the key held.
   Ctrl/Cmd+click opens the link in the browser; a plain click places the caret; a
   drag that starts on the link selects and opens nothing.
7. RichTextView demo: click the link. **Expect:** it opens, with a hand shown over
   it. Drag a selection across it. **Expect:** nothing opens.
8. In a long document, drag a selection below the editor and hold the mouse still.
   **Expect:** the editor keeps scrolling and the selection keeps growing; farther
   below scrolls faster; moving back inside stops it. Repeat above the editor.
9. In a document of long wrapped paragraphs, triple-click a paragraph and drag
   below the editor, holding still. **Expect:** the scroll runs at one even speed
   with no lurching or jumping back (1.23). Move the mouse back inside and
   release. **Expect:** the view then scrolls to show the caret at the end of the
   last selected paragraph.

### 3.8 Web input (built demo)

Run 1 to 6 in Chrome, Firefox and Safari on desktop, and 7 on phones.
Roadmap 4.11, 4.12, 4.22 and 4.25.

1. Markdown Editor (Blank). Click in the editor and type. Right-click in the
   text, press Escape, and type `;` `=` and a letter. **Expect:** all three
   insert at the right-click position.
2. Click the toolbar's Bold button, then type `;=a`. **Expect:** typed at the
   caret, in bold.
3. Windows with a Polish or German layout: after a right-click and Escape,
   type an AltGr character (AltGr+Z, AltGr+Q). **Expect:** it is typed once.
4. German layout: the dead key on the `´` key, then `e`. **Expect:** `é`, no
   stray `=` or `´`.
5. Markdown demo: at the end of a bullet item press Enter twice, then type a
   word. With a browser IME or a phone keyboard, also leave a list this way
   and accept an autocorrect suggestion right after. **Expect:** the second
   Enter ends the list, and nothing lands one character off.
6. Composition with a real IME (roadmap 4.12; synthetic events already pass):
   fcitx5 or ibus with Mozc on Linux, the macOS Japanese keyboard, Microsoft
   IME on Windows. Type "nihongo", convert, pick a candidate, commit; repeat
   in the middle of a line and over a selected word; Backspace inside a
   composition. **Expect:** underlined composing text, the candidate window
   at the caret, the chosen word committed once where the caret was.
7. Phones (roadmap 4.11), Android Chrome with Gboard and iOS Safari: tap the
   editor. **Expect:** the keyboard rises with a capital for the first
   letter, suggestions work, autocorrect replaces the word once, Backspace
   deletes one character, and the caret stays visible above the keyboard.
   Tap an empty part of the page outside the editor: the keyboard may hide;
   tapping the editor brings it back.

### 3.9 IME candidate window (desktop)

Roadmap 4.19. With a CJK input method (fcitx5 or ibus with Mozc or Pinyin on
Linux, the macOS Japanese keyboard, Microsoft IME on Windows):

1. Type a composition at the end of a long line, then on a new line, then
   after scrolling the document. **Expect:** the candidate window sits at the
   caret each time, not one keystroke or one line behind.

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
5. Long-press a word, then grab a handle a little off its centre and hold still.
   **Expect:** the selection does not change on grab (3.2). Drag it: the edge follows
   the finger by exactly the distance moved.
6. Drag the start handle past the end handle, and the end handle past the start.
   **Expect:** the handles cross and the selection runs from the fixed end to the
   finger, with no jumping (3.1, hammer-editor#956). Dropping one handle exactly on
   the other leaves at least a character selected.
7. Select a short word (two or three letters) and grab the end handle. **Expect:** the
   end handle moves, not the start one.
8. Long-press blank space between words. **Expect:** the caret moves there and no
   handles appear.
9. Tap in the text. **Expect:** a single handle appears under the caret. Drag it: the
   caret follows. Leave it for 4 seconds, or type, or press an arrow key on a
   hardware keyboard. **Expect:** it disappears. Mouse clicks never show it.
10. On Android 9 (API 28) or later, drag a selection handle and the caret handle.
    **Expect:** the system magnifier appears above the finger, showing the row being
    dragged, follows the finger sideways, and goes when the finger lifts (3.6). Below
    API 28 there is none. None on desktop, iOS, or web.
11. In a long document, drag the end handle below the editor and hold still.
   **Expect:** it keeps scrolling and the selection keeps growing (3.4); dragging
   back inside stops it. Repeat with the start handle above the editor.
12. Double-tap a word. **Expect:** the word selects with handles and the keyboard
    rises (3.7). Double-tap and keep the second finger down, then drag across the
    line. **Expect:** the selection grows by whole words, the first word always kept,
    the page does not scroll under the finger, and the magnifier follows the moving
    end. Two taps a second apart, or on different words, place the caret twice.
13. Long-press a word and, without lifting, drag along and down a line.
    **Expect:** the same word-by-word growth as 12, no scrolling under the finger,
    and the keyboard up when the finger lifts. Drag below the editor and hold.
    **Expect:** it auto-scrolls. Compare the hold time before the word selects with
    the system's long-press setting (Settings > Accessibility > Touch & hold
    delay): the editor follows it.
14. Long-press a word and lift. **Expect:** Android's floating toolbar appears over
    the word with Cut, Copy, Paste and Select all (3.8), with the keyboard up. Drag
    a handle. **Expect:** the toolbar hides during the drag and returns when the
    handle drops. Tap Select all. **Expect:** everything selects with handles and
    the toolbar comes back over the visible rows. Scroll. **Expect:** the toolbar
    moves with the text. Type a letter, or tap the caret handle twice. **Expect:**
    the toolbar goes.
15. Blank Markdown demo (empty editor): long-press. **Expect:** the toolbar offers
    Paste and Select all only, and Paste inserts the clipboard. In a document,
    long-press an empty line, and separately tap the caret handle after a tap.
    **Expect:** the same Paste and Select all toolbar; the caret does not move.
    Copy a word with a long-press and the toolbar's Copy, then tap elsewhere and
    paste through the toolbar. **Expect:** the word arrives.
16. Desktop with a touch screen, or a mouse right-click for the menu: long-press
    empty space or tap the caret handle. **Expect:** the context menu opens with
    Paste and Select All. Long-press a word. **Expect:** it selects with handles
    and no menu; a second long-press on the selection opens the menu (unchanged).
17. Long-press a word, then press Back to dismiss the keyboard, and tap a toolbar
    button or another field so the editor loses focus while the handles stay. Drag
    a handle. **Expect:** on the drop the editor is focused again and the keyboard
    rises, so typing replaces the selection (3.13).
18. Put two fingers down on the text and hold. **Expect:** no word selects and the
    keyboard stays down. Pinch or two-finger scroll with both fingers on the text.
    **Expect:** the caret does not move and the keyboard stays down. A second
    finger on the host outside the editor is not seen by it, so that case is not
    covered.

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
2. A **bold run with trailing whitespace** (select `word` including the space, bold
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
6. Type "don’t" with a typographic apostrophe. **Expect:** no squiggle; the word is
   looked up as "don't".
7. Scroll a long spell-checked document quickly. **Expect:** smooth scrolling; squiggles
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

## 8b. Drawing, caret and scrolling

Guards roadmap items 1.8, 1.10, 1.11, 1.17, 1.18, 3.12 and 4.14. Desktop and
Android unless a step says otherwise.

1. Rich Text Editor demo: click in the padding left of a line, right of it, and
   above the first line. **Expect:** the caret lands at that row's start, its end,
   or the first row under the pointer; nothing is dead.
2. Select a word. **Expect:** no caret while text is selected; it reappears, shown,
   the moment the selection collapses.
3. Put the caret mid-line and press Delete (forward delete) repeatedly, slowly.
   **Expect:** the caret stays solid while deleting and only blinks once you stop.
4. Select across an empty line and across several line ends. **Expect:** each line
   end, and the empty line, shows a narrow highlighted sliver.
5. Select text, then click another control (desktop) or dismiss focus (Android).
   **Expect:** the selection turns a dimmed grey; touch handles disappear. Right-
   clicking a selection must not dim it.
6. A one-line document. **Expect:** it does not scroll at all.
7. Android: scroll a long document; pull past its top and bottom. **Expect:** the
   stretch overscroll; the thin indicator on the right shows while scrolling, is
   sized to the visible share, and fades shortly after.
8. Desktop and web (built demo): a long document. **Expect:** a scrollbar along the
   right edge whose thumb drags the document, a press on the track above or below
   the thumb pages toward the pointer (and keeps paging while held), and no thumb
   once the document fits (delete most of it).

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
