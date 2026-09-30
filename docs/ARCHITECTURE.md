# Architecture

The 10,000 foot view of how ComposeTextEditor is designed to work. Each section
covers one subsystem; sections are added as they are documented. Finer-grained
design references live in `docs/design/`.

## The major nouns

The system is a one-way loop: **input** (keys, pointer, IME) is translated into
**operations**, operations mutate the **state**, the state produces **layout**,
and the **view** draws the layout. Each noun below owns one link of that chain.

### Coordinates: `CharLineOffset` and `TextEditorRange`

`CharLineOffset` is the editor's native coordinate: a zero-based logical line
index plus a character offset within that line. `TextEditorRange` is an ordered
pair of them. Every selection, span, edit, and cursor position speaks these two
types. The alternate coordinate is the flat character index over the whole
document (used by IMEs and find); `TextEditorState` converts between the two
through the line list's running character totals, so conversion is a binary
search over a few dozen chunks and an array read, not a walk over the document.

The other axis is logical versus visual lines: a logical line (one entry in the
document) may wrap into several visual rows. Visual rows exist only in layout
output (`LineWrap`, below); the document model never sees them.

### `TextEditorState`: the beating heart

The single source of truth for one editor: document content, cursor, selection,
scroll, undo history. Apps hoist one via `rememberTextEditorState` (or
`rememberSaveableTextEditorState`, which saves the text, styles, built-in rich
spans, caret, selection and top line, but not the undo history) and drive the
editor through it; everything else in the system either feeds it or reads
it. It is deliberately a facade: related concerns are delegated to focused
sub-objects (`cursor`, `selector`, `scrollManager`, `editManager`,
`richSpanManager`), and the state's own job is to hold the document, run the
layout pass, and enforce the transaction rules that keep the two consistent.

### `DocumentSnapshot`: the document itself

The document is an immutable value: one `AnnotatedString` per logical line plus
a flat set of `RichSpan`s, published wholesale on every mutation (see
"Document model and transactions" below). The lines are held chunked
(`LineList`, chunks of 32 to 64 lines with a directory of each chunk's first
line and first character), so an edit copies the chunk or two it touches and
shares the rest with the previous revision, and a line's flat character index
is a prefix total rather than a table rebuilt per revision. The snapshot also
memoizes the span index (spans grouped by line), which survives across
revisions that did not invalidate it. The whole text as one string is built
only for the readers that need it (semantics, the skiko input request,
Android's extracted text), spliced from the last built revision; everything
else reads characters in place through `chars`.

### Two span systems

Styling lives in two deliberately separate places:

- **Character styles** (`SpanStyle`) live inline in each line's
  `AnnotatedString`: bold, italic, color, font size. They travel with the text
  through every edit. `SpanManager` is the normalizer that keeps them minimal
  when an edit splices a line (merging adjacent equal spans, dropping
  duplicates, shifting ranges).
- **Rich spans** (`RichSpan`: a `TextEditorRange` plus a `RichSpanStyle`) are
  decorations Compose's text stack cannot express: list bullets and numbering,
  blockquote bars, code-fence cards, links, highlights, spell-check underlines.
  A `RichSpanStyle` paints itself into the canvas (over the text, or under it
  via `drawBackground`) and declares its behavior: `stickyAtStart` for
  line-anchored gutter markers that must track their whole line,
  `BlockSpanStyle` for spans that own an entire line and its height (images,
  horizontal rules), `isDecoration` for view overlays, and `isHitTestable`,
  false for a span that only tints and must leave clicks to what it covers.

The `isDecoration` flag is a load-bearing distinction: content spans (things
that round-trip through markdown) enter undo history and announce themselves on
the edit stream; decorations (spell-check underlines, find highlights) do
neither, so an overlay pass can never pollute undo or masquerade as an edit.

The block decorations that pair a rich span with a paragraph indent (lists,
quotes, headings, fences) are a subsystem of their own with validity and
round-trip rules: [design/line-blocks.md](design/line-blocks.md).

### The edit pipeline: `TextEditOperation`, `TextEditManager`, `TextEditHistory`

Every mutation of the document is described by a `TextEditOperation` value:
`Insert`, `Delete`, `Replace`, `StyleSpan`, `RichSpan`, or `LineBlock` (an
atomic list/quote/fence toggle). An operation is data, not behavior: it carries
the affected range or text, the cursor position before and after (so undo and
redo restore the caret exactly), and knows how to transform any
`CharLineOffset` across itself, which is how everything anchored to a position
survives an edit.

`TextEditManager.applyOperation` is the single choke point through which every
operation passes, and so the one place the state's `inputFilter` screens an
edit that adds text (a maximum length, a single line, the host's own rules)
before it is applied; undo and redo, which replay accepted edits, skip it, and
so does an entry point that screened first over the whole range it replaces
(typing over a selection, the IME, paste). It also owns the invariant
sequencing: clear a selection the
edit would invalidate, apply the text change inside a transaction, move the
cursor, re-anchor rich spans, record undo history, derive the layout pass, and
announce the operation on `editOperations`. Code that mutates lines without
going through an operation is a bug by definition; it would bypass history,
span re-anchoring, and the edit stream all at once.

`TextEditHistory` holds the undo and redo stacks. An entry is one recorded
operation paired with the `OperationMetadata` needed to reverse it (deleted
text, deleted spans), or a group of them. Consecutive single-character typing
and backspacing coalesce into wordwise runs, so undo peels words, not
keystrokes. IME commits and composition updates are recorded as typing
whatever their length, so a composed word and its commit fold into the run
they rewrite rather than leaving one step per keystroke.

One transaction is one undo step. Operations recorded inside a
`withAtomicEdit` are staged and land as a single group entry when the
outermost transaction commits (a group of one is recorded as that operation,
so typing keeps coalescing); a throwing transaction drops them with the
draft. `TextEditorState.editGroup` is the public face of this: a host wraps a
compound edit in it and gets one undo step that restores text, spans, and
caret. Undo of a group reverts its operations last to first inside one
transaction, redo replays them first to last.

How positions (cursor, selection, spans, history) are carried across edits:
[design/edit-operation-offset-transforms.md](design/edit-operation-offset-transforms.md).

### `RichSpanManager`: keeping spans anchored

The bookkeeper for the document's rich spans. Its two jobs: publish span
mutations copy-on-write into the snapshot, and re-anchor every span across each
edit using the operation's own offset transform. It also serves the
line-indexed queries layout and drawing rely on.

### The delegates: cursor, selection, scroll

- **`TextEditorCursorState`**: the caret. Its position, blink visibility, and
  the *typing styles*: the set of `SpanStyle`s the next typed character will
  carry, derived from the text around the caret or toggled by toolbar actions.
  The caret moves by grapheme cluster, never by UTF-16 unit: `TextBreaks`
  wraps the platform's ICU break iterators (skia's on desktop, iOS and web,
  `android.icu` on Android) behind one `expect`, and every motion, forward
  delete, and hit test snaps through it. Backspace is the one asymmetric edit:
  it removes the previous code point, or a whole emoji sequence, as
  `BasicTextField` and `EditText` do, so a combining mark comes off its base
  on its own. Words come from the same place: `wordRuns` segments a line with
  the platform's ICU word iterator and tags each segment lexical, emoji, or
  other, and word motion, double-click selection (`findWordSegmentAt`) and the
  spell checker's candidates (`wordSegments`) all read that one segmentation.
  The caret also carries an affinity (`CaretAffinity`): a position on a wrap
  offset belongs to two visual rows, and the affinity says which one the caret
  is on. Positions stay affinity-free; the motions read the caret's row
  through `TextEditorState.cursorRowIndex()`, drawing, handles, the touch
  toolbar and scrolling through the affinity overloads of `getWrapForDrawing`
  and `getPositionForOffset`, and every move resets the caret to downstream
  unless it deliberately lands at a row's end (End, or a vertical move past
  the row's end).
- **`TextEditorSelectionManager`**: the selection range and the gesture state
  behind it (touch handles, drag). Rule: any content mutation clears the
  selection; only span-level operations keep it.
- **`TextEditorScrollManager`** (with `TextEditorScrollState`): scroll offset,
  total content height, visible-range queries, and `ensureCursorVisible`, which
  is deferred through transactions so it always reads fresh layout. The range
  runs from minus the top padding (the first row below the top padding) to the
  last row and bottom padding at the viewport's bottom, and is empty when
  everything fits. A soft keyboard is met two ways: one drawn over the
  editor is a covered strip the caret is kept above (`KeyboardCover.kt`), and
  a window that shrinks the editor instead keeps a caret that was in view in
  view (`onViewportSizeChange`).
- **`PlatformTextEditorExtensions`**: per-platform IME glue (Android cursor
  anchor monitoring; empty elsewhere).

### Layout output: `LineWrap`

The layout pass (`updateBookKeeping`, see below) turns the document into
`lineOffsets`: one `LineWrap` per visual row, carrying its pixel offset, its
paragraph's shaping result, its resolved rich spans, and precomputed draw facts
(ordered-list numeral, code-fence edge, block height). `LineWrap` is the
contract between state and view: drawing, hit testing, cursor placement, and
scrolling consume it and never re-measure text themselves. Behind the list is
a `RowList`: one `LineLayout` per logical line (the shaping result and the
facts derived for it), chunked like the line list with running row counts and
heights, so an edit splices the layouts of the lines it touched and every
other line moves with its chunk; a `LineWrap` is built when it is read. The
rows run line by line, each line's by wrap start, and top to bottom with no
gaps, so finding the row that holds a position or sits at a height is a binary
search (`RowSearch.kt`, answered from the `RowList`'s directory without
building a row), and a frame reads only the rows in view.

### The view layer

`TextEditor` is the batteries-included composable (Material surface, focus
border) wrapping `BasicTextEditor`, which owns the canvas, gesture handling,
scrollbar, context menu, and IME wiring; `RichTextView` renders the same
content read-only. Input arrives through platform key, pointer, and IME
handlers whose only job is translation: raw events become cursor moves,
selection changes, or `TextEditOperation`s. The view renders what `lineOffsets`
says and holds no document state of its own. Content padding belongs to the
editor: the top and bottom padding are scroll range, and the start and end
padding are applied inside the canvas, below its pointer input, so a press
anywhere in the padding reaches the nearest row. The editor fills its height
unless its `lineLimits` size it to its laid-out rows, read from `lineOffsets`
in a layout modifier on its outer node.

Accessibility services see the editor through its semantics
(`EditorSemantics.kt`), modelled on `BasicTextField`'s: the whole text as an
editable field, the selection, and the actions a screen reader or test drives.
A disabled editor reports itself disabled and offers no edit actions; a
read-only one (`readOnly`) shows and moves its caret but is gated exactly as a
disabled one is for input, menus and edit semantics, and reports itself not
editable rather than disabled. `isFocused` means focused and taking input;
`hasFocus` means focused. Its
`setText` is an edit, not a document load: it replaces only the part of the
text that differs, as one undo step, so the rest keeps its spans. Copy, cut,
paste and the long-press menu run through the action registry, as the keyboard
and context menu do, so a read-only editor refuses the same edits; links ride
in the text as URL links. `getTextLayoutResult` is a whole-document layout
measured on request, because the editor has no single one. `RichTextView`
publishes the same text and layout as a read-only text (and, when selectable,
the selection and copy). The
document is not snapshot state, so the semantics block reads the state's
`revision`, a snapshot-state counter every published revision advances, to stay
current; the word count does the same.

### Observation and extensions

The state exposes a small reactive surface: `editOperations` streams applied
operations, `cursorDataFlow` snapshots caret position, styles, and selection
for toolbars (starting with the current one), `wordCount` counts words through
the same ICU segmentation as word motion and spell check, recounting only the
lines an edit replaced, and `snapshot()` hands any thread a coherent document
revision.
Extensions build on exactly this surface plus the public span API: the markdown
module converts to and from markdown text, and the spell-check and find modules
(separate artifacts) watch `editOperations` and paint their results as
decoration rich spans through `updateRichSpans`, without ever touching editor
internals.

## Input: from raw event to operation

Input is the front of the one-way loop, and its whole job is translation:
everything the user does becomes a cursor move, a selection change, or a
`TextEditOperation`. Input code never mutates lines. Three sources, three
translation paths:

- **Commands.** `KeyBindings` maps the platform's key chords onto the
  `EditorCommand` vocabulary: motions and actions named by intent
  (`WordLeft`, `DeleteWordBackward`, `Paste`), not by keys. The split is
  three ways: bindings know which chord means what, the
  `EditorActionRegistry` on the state knows what an action *does*, and
  `TextEditorKeyCommandHandler` implements only caret motion, because a
  motion is not something a host can register. The arrow keys are visual:
  in a paragraph the layout resolves as right-to-left, the handler mirrors
  the bound motion (Left and Right, the word motions through
  `KeyBindings.wordForward` and `wordBackward`, line start and end) before running it; Home,
  End, deletes and the Emacs chords stay logical. Windows/Linux and macOS
  conventions ship as two `KeyBindings` values; hosts can substitute their
  own and register actions for their own chords to bind.
- **Typed characters.** Printable typing that arrives as raw key events
  (desktop `KEY_TYPED`, hardware keyboards on Android, a browser keydown on
  wasm while the canvas rather than the input session's textarea holds DOM
  focus) inserts through the same handler, gated by a per-platform predicate
  for "this event is a typed character", because every platform signals that
  differently and guessing wrong either drops or double-inserts keystrokes.
- **The IME.** Everything that *composes* text (soft keyboards, autocorrect,
  dead keys and accents, CJK input, emoji pickers) arrives through a platform
  text-input session rather than as key events.

All of this converges on one modifier node, `TextEditorInputModifierNode`,
which also owns the session lifecycle: gaining focus launches the platform
input session, losing focus (or disabling the editor) cancels it.

Two extension points hang off this, and they are not interchangeable. An
**action** is invoked by name, so it needs something to invoke it: a chord, a
menu item, a toolbar button. An **edit behavior** intercepts one of the
semantic edits (typed text, newline, backspace, forward delete) and may have
no trigger at all, because an IME can commit a word or a newline, or delete a
character, without ever producing a key event. Line-block smart editing is the
first behavior, which is what makes it reach every input path rather than only
the ones that go through key handling; the typed-text hook sees what every
path commits (never an IME's composing updates) and is what smart punctuation,
markdown as you type, and auto-link build on. Both, and the reasoning for
keeping them separate: [design/editor-actions.md](design/editor-actions.md).

The IME contract runs in two directions. Commands flow in, and each one lands
in a single shared implementation (`ImeEditLogic` in commonMain) so that
composing-region and cursor semantics are byte-for-byte identical on every
platform; the per-platform adapters are pure translation. There are two of
them: the Android `InputConnection`, and one skiko
`PlatformTextInputMethodRequest` in the `skikoMain` source set shared by
desktop, iOS, and web, each contributing only its `ImeOptions`. State flows out,
because an IME keeps its own mirror of the text around the cursor and will
issue commands against a stale buffer unless it is told about every change.
On Android every report goes through one flush that compares the finished state
against what the keyboard was last told, run when a batch edit ends or posted
after any other change, never from inside an edit. The session machinery, the Android
`InputConnection`, and the per-platform differences:
[design/text-input-sessions.md](design/text-input-sessions.md).

Pointer input on the canvas is split by device. One handler owns every mouse
gesture; two own the finger ones (handle drags, and taps with long
presses). The load-bearing distinction is *mouse-like versus finger*, detected
from pointer buttons rather than pointer type because Android reports external
mice as `Touch`. Mouse-like input places the caret on press, extends with
shift-click, and counts presses into double and triple clicks (word, then
line) by the platform's double-tap timeout and touch slop; a plain press inside
the selection is held instead, and moving past the slop drags the selection
out through the platform's drag and drop (`dragdrop/`, desktop so far), which
also drops text in; a drag extends by
whatever unit the press selected, and keeps scrolling while it is held above
or below the viewport. Only the primary button places the caret or selects;
the secondary button opens the context menu, keeping a selection it lands
inside. Finger input places the caret on release and shows a caret handle
under it, long-presses to select a word or open the context menu, and drags
the caret and selection handles. A span click is reported on release, when the
press and release land on the same span without a drag, so placing the caret
or selecting never reads as a click; links open by the host's `onLinkClick` on
Ctrl/Cmd+click in an editor and on a plain click in `RichTextView`.

## Document model and transactions

The document is an immutable `DocumentSnapshot`: one `AnnotatedString` per line
plus a flat set of `RichSpan` decorations. Every mutation publishes a whole new
snapshot, so a reader on any thread always sees a complete, self-consistent
revision.

Lines are separated by `\n` alone. Every path text enters by (`setText`, the
insert and replace calls, typed and IME text, paste, markdown and HTML parsing)
turns `\r\n` and a lone `\r` into `\n` first, so no line holds a carriage
return (`setDocument` alone takes its lines as given). Copy writes `\n`; converting to a platform's native line ending is the
platform clipboard's job (AWT does it on Windows).

`setDocument` is the inverse of `snapshot()`: it loads a snapshot, rich spans
included, as one revision, so a document moves between editors without a
markdown round trip. It drops decoration spans, clamps spans onto the incoming
lines, and clears undo history like any other document load. `setText` keeps
only character-level spans. Both announce the swap by bumping `documentGeneration`
once it commits, which is how spell check knows to re-scan.

Edits that must land together run inside `TextEditorState.withAtomicEdit`
(public as `editGroup`). The transaction accumulates mutations in a draft and
publishes them as one revision at commit, after line-block normalization, and
records the operations made inside it as one undo step. A throwing transaction
discards the draft along with everything staged against it: the deferred
relayout, the cursor scroll, the history entries, and the queued
`editOperations` announcements, and puts the caret and selection back where
they were. Nothing observes a half-applied edit, and nothing announces or
remembers an edit that never landed.

## Layout: the deferred, incremental relayout pass

Layout state is `TextEditorState.lineOffsets`: every visual (wrapped) line with
its pixel offset, its paragraph's shaping result, and its resolved rich spans.
Everything downstream reads it: drawing, cursor placement, hit testing,
scrolling, selection.

Text shaping is by far the most expensive work per edit, so the layout pass
(`updateBookKeeping`) is built around two rules:

- **Shape only the lines whose content changed.** A `LayoutUpdate` describes
  each pass's dirty range, derived centrally from the edit operation itself;
  unchanged lines keep their layouts in place, and only the lines whose
  neighbour-derived facts (list numbering, fence edges) change are touched,
  the walk stopping at the first line that keeps both its facts and its list
  counters. Span overlays (spell-check underlines, find highlights) shape
  nothing at all and re-resolve only their lines, and neither does a viewport
  that changes only its height (a soft keyboard): rows depend on the width
  alone.
- **One pass per logical operation.** Relayouts requested inside a transaction
  merge and flush as a single pass at commit, in a fixed order: publish the
  revision, flush the layout, scroll the cursor against the fresh offsets,
  announce the edit.

The incremental path is opportunistic, never load-bearing: guards degrade any
pass that cannot be proven sound to a full relayout, which is always correct.
Costs are pinned by counting-measurer regression tests (a keystroke shapes one
line, a spell-check pass shapes zero, a paste writes the line list once) and a parity suite holds incremental
output to field-for-field equality with a full pass.

Details: [design/incremental-relayout.md](design/incremental-relayout.md)
