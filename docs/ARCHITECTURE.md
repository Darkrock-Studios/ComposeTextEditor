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
using a per-revision line-start table, so conversion is an array read, not a
walk over the document.

The other axis is logical versus visual lines: a logical line (one entry in the
document) may wrap into several visual rows. Visual rows exist only in layout
output (`LineWrap`, below); the document model never sees them.

### `TextEditorState`: the beating heart

The single source of truth for one editor: document content, cursor, selection,
scroll, undo history. Apps hoist one via `rememberTextEditorState` and drive
the editor through it; everything else in the system either feeds it or reads
it. It is deliberately a facade: related concerns are delegated to focused
sub-objects (`cursor`, `selector`, `scrollManager`, `editManager`,
`richSpanManager`), and the state's own job is to hold the document, run the
layout pass, and enforce the transaction rules that keep the two consistent.

### `DocumentSnapshot`: the document itself

The document is an immutable value: one `AnnotatedString` per logical line plus
a flat set of `RichSpan`s, published wholesale on every mutation (see
"Document model and transactions" below). The snapshot also memoizes the
indices derived from it (line-start offsets, spans grouped by start line), so
hot queries stay cheap and survive across revisions that did not invalidate
them.

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
  horizontal rules), and `isDecoration` for view overlays.

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
operation passes, and it owns the invariant sequencing: clear a selection the
edit would invalidate, apply the text change inside a transaction, move the
cursor, re-anchor rich spans, record undo history, derive the layout pass, and
announce the operation on `editOperations`. Code that mutates lines without
going through an operation is a bug by definition; it would bypass history,
span re-anchoring, and the edit stream all at once.

`TextEditHistory` holds the undo and redo stacks. Each entry pairs the
operation with the `OperationMetadata` needed to reverse it (deleted text,
deleted spans). Consecutive single-character typing and backspacing coalesce
into wordwise runs, so undo peels words, not keystrokes.

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
- **`TextEditorSelectionManager`**: the selection range and the gesture state
  behind it (touch handles, drag). Rule: any content mutation clears the
  selection; only span-level operations keep it.
- **`TextEditorScrollManager`** (with `TextEditorScrollState`): scroll offset,
  total content height, visible-range queries, and `ensureCursorVisible`, which
  is deferred through transactions so it always reads fresh layout.
- **`PlatformTextEditorExtensions`**: per-platform IME glue (Android cursor
  anchor monitoring; empty elsewhere).

### Layout output: `LineWrap`

The layout pass (`updateBookKeeping`, see below) turns the document into
`lineOffsets`: one `LineWrap` per visual row, carrying its pixel offset, its
paragraph's shaping result, its resolved rich spans, and precomputed draw facts
(ordered-list numeral, code-fence edge, block height). `LineWrap` is the
contract between state and view: drawing, hit testing, cursor placement, and
scrolling consume it and never re-measure text themselves.

### The view layer

`TextEditor` is the batteries-included composable (Material surface, focus
border) wrapping `BasicTextEditor`, which owns the canvas, gesture handling,
scrollbar, context menu, and IME wiring; `RichTextView` renders the same
content read-only. Input arrives through platform key, pointer, and IME
handlers whose only job is translation: raw events become cursor moves,
selection changes, or `TextEditOperation`s. The view renders what `lineOffsets`
says and holds no document state of its own.

### Observation and extensions

The state exposes a small reactive surface: `editOperations` streams applied
operations, `cursorDataFlow` snapshots caret position, styles, and selection
for toolbars, and `snapshot()` hands any thread a coherent document revision.
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
  motion is not something a host can register. Windows/Linux and macOS
  conventions ship as two `KeyBindings` values; hosts can substitute their
  own and register actions for their own chords to bind.
- **Typed characters.** Printable typing that arrives as raw key events
  (desktop `KEY_TYPED`, hardware keyboards on Android, browser keydown on
  wasm) inserts through the same handler, gated by a per-platform predicate
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
semantic edits (newline, backspace, forward delete) and may have no trigger at
all, because an IME can commit a newline or delete a character without ever
producing a key event. Line-block smart editing is the first behavior, which is
what makes it reach every input path rather than only the ones that go through
key handling. Both, and the reasoning for keeping them separate:
[design/editor-actions.md](design/editor-actions.md).

The IME contract runs in two directions. Commands flow in, and each one lands
in a single shared implementation (`ImeEditLogic` in commonMain) so that
composing-region and cursor semantics are byte-for-byte identical on every
platform; the per-platform adapters are pure translation. State flows out,
because an IME keeps its own mirror of the text around the cursor and will
issue commands against a stale buffer unless it is told about every change.
On Android that direction is driven entirely by observing the state's flows,
never by manual notify calls. The session machinery, the Android
`InputConnection`, and the per-platform differences:
[design/text-input-sessions.md](design/text-input-sessions.md).

Pointer input is three cooperating handlers on the canvas: caret placement
and span clicks, drag selection, and multi-click (word, then line). The
load-bearing distinction is *mouse-like versus finger*, detected from pointer
buttons rather than pointer type because Android reports external mice as
`Touch`. Mouse-like input places the caret on press, drags to select, and
extends with shift-click; finger input places the caret on release,
long-presses to select a word or open the context menu, and drags selection
handles.

## Document model and transactions

The document is an immutable `DocumentSnapshot`: one `AnnotatedString` per line
plus a flat set of `RichSpan` decorations. Every mutation publishes a whole new
snapshot, so a reader on any thread always sees a complete, self-consistent
revision.

Edits that must land together run inside `TextEditorState.withAtomicEdit`. The
transaction accumulates mutations in a draft and publishes them as one revision
at commit, after line-block normalization. A throwing transaction discards the
draft along with everything staged against it: the deferred relayout, the
cursor scroll, and the queued `editOperations` announcements. Nothing observes
a half-applied edit, and nothing announces an edit that never landed.

## Layout: the deferred, incremental relayout pass

Layout state is `TextEditorState.lineOffsets`: every visual (wrapped) line with
its pixel offset, its paragraph's shaping result, and its resolved rich spans.
Everything downstream reads it: drawing, cursor placement, hit testing,
scrolling, selection.

Text shaping is by far the most expensive work per edit, so the layout pass
(`updateBookKeeping`) is built around two rules:

- **Shape only the lines whose content changed.** A `LayoutUpdate` describes
  each pass's dirty range, derived centrally from the edit operation itself;
  unchanged lines reuse their previous shaping result and only their offsets,
  spans, and numbering are recomputed. Span overlays (spell-check underlines,
  find highlights) shape nothing at all.
- **One pass per logical operation.** Relayouts requested inside a transaction
  merge and flush as a single pass at commit, in a fixed order: publish the
  revision, flush the layout, scroll the cursor against the fresh offsets,
  announce the edit.

The incremental path is opportunistic, never load-bearing: guards degrade any
pass that cannot be proven sound to a full relayout, which is always correct.
Costs are pinned by counting-measurer regression tests (a keystroke shapes one
line, a spell-check pass shapes zero) and a parity suite holds incremental
output to field-for-field equality with a full pass.

Details: [design/incremental-relayout.md](design/incremental-relayout.md)
