# Tables

A proposal, not an implemented design: what it would take to hold a GFM pipe
table in the editor as a block, like lists, quotes, headings, fences, rules and
images are held today. It records what the code allows, the three shapes a
table could take, why one of them is recommended, and a chunk plan. Roadmap
item 7.89 tracks it. Terms: a *table row* is a row of cells; a *visual row* is
one wrapped row of text, what `RowList` hands out as a `LineWrap`.

## What the code holds today

- **A table is literal text.** `MarkdownTables.tableRowIndices` finds a GFM
  table (header, delimiter row, body up to a blank line or another block) only
  to keep its lines out of inline parsing; the importer appends the rows as
  written, and export writes a cell's pipe bare, so a `\|` splits a cell on
  reload (`line-blocks.md`, known limitations). The parser is the JetBrains
  one with `GFMFlavourDescriptor`, which already builds `GFMElementTypes.TABLE`,
  `HEADER`, `ROW` and `CELL` nodes; the importer reads only
  `GFMElementTypes.STRIKETHROUGH` from the GFM set.
- **HTML paste flattens a table.** `htmlToAnnotatedString` treats `<tr>` and
  `<table>` as block tags (a line break) and `<td>`/`<th>` as cells separated
  by a tab, "matching a plain-text copy of a table". Export has no table.
- **One line, one layout.** `LineLayout` holds exactly one `TextLayoutResult`
  and the arrays derived from it (`rowStarts`, `rowEnds`, `rowTops`,
  `blockHeights`). A `LineWrap` is built on read with `offset = Offset(0f, y)`:
  every visual row starts at x = 0, a list's indent living in the paragraph
  style. 31 files under `ComposeTextEditor/src/commonMain` name
  `TextLayoutResult` or read a row's `textLayoutResult` (caret, hit test,
  selection boxes, visual caret motion, handwriting gestures, composing
  underline, run boxes, decorations, semantics bounds).
- **Rows stack.** `RowList` keeps per-chunk prefix sums of line heights
  (`Chunk.top`, `firstRow`) and answers y queries by binary search over row
  tops (`searchLastRowAtOrAbove`, `searchFirstRowEndingAtOrBelow`), which
  assumes tops never decrease along the row index. Draw culling, hit testing
  and scroll-to-caret all go through these.
- **A block is a line marker.** `LineBlockStyle` bundles a sticky-at-start
  `RichSpanStyle`, a `ParagraphStyle` and an optional baked `SpanStyle`;
  per-level singletons (`BulletListSpanStyle.of(level)`) are identity-compared.
  Run state (ordered numerals, fence edges) is derived per line in the
  `LineFacts` walk and stored on `LineLayout`, never in the marker. A
  `BlockSpanStyle` (rule, image) sits on a one-space placeholder line, sets the
  row's height through `blockHeight(density, viewportWidth)` and draws the
  whole row itself (`replacesText`).
- **Edits are line and character based.** `TextEditOperation` is `Insert`,
  `Delete`, `Replace`, `StyleSpan`, `RichSpan` (add or remove one span) and
  `LineBlock`; `withAtomicEdit` makes several one undo step, as the nested-list
  edits do (`recordListEdit`).
- **Smart editing hooks per key.** `EditBehavior` has `onNewline`,
  `onNewlineLanded`, `onBackspace`, `onDeleteForward`, `onTextInput` and
  `onPaste`; `LineBlockEditBehavior` is the block one. Tab is handled in
  `TextEditorKeyCommandHandler` and `KeyBindings` (nest a list item or insert
  the indent text).
- **Decorations and semantics address lines.** Decoration layers (7.86, 7.88),
  spell check, find and the screen-reader character bounds (7.57,
  `CharacterBounds` in `EditorSemantics.kt` on `native-parity`; this branch's
  base predates it) all work in `CharLineOffset` ranges and read the editor's
  rows. Anything that is a document line gets
  them for free; anything that is not gets none of them.

## Options

### A. One line per table row, cells split by a hidden delimiter

Each table row is one document line; cells are separated by a reserved
character (a tab or a private-use code point) that drawing hides; the line is
laid out as N columns, each cell wrapping within its column, the visual row as
tall as the tallest cell.

- Caret and selection: a contiguous character range, so a drag across cells
  selects the delimiters too; Left/Right crosses a cell edge by stepping over
  the delimiter; Up/Down inside a wrapped cell needs to know which cell's rows
  it is moving through.
- Typing and edges: Enter must not split the line (it moves down a row or adds
  one); Backspace and Delete must refuse to remove a delimiter; paste must strip
  delimiters and newlines; IME composition over a delimiter must be refused.
  Every edit path that sees text needs the guard, including host edits and the
  semantics `insertAtCursor`.
- Markdown and undo: free. A row is a line, operations stay line and character
  based, export is `cells.joinToString(" | ")`.
- Spell check, find, decorations, semantics: see the delimiter as a character;
  find and the fuzzers must learn to skip it; a screen reader reads it.
- **Core cost: the crux.** `LineLayout` would hold N layouts, and each of the
  31 consumers of `textLayoutResult` would gain a cell dimension: which cell a
  character is in, that cell's origin, its own wrap rows. `LineWrap` would no
  longer map one visual row to one `(virtualLineIndex)` of one layout.
  Prohibitively wide for a first version.

### B. One line per cell, a cell marker carrying the column (recommended)

Each cell is one document line carrying `TableCellSpanStyle.of(column, align,
header)`, a `LineBlockStyle` like a list level: a per-column singleton with the
column's alignment baked into its `ParagraphStyle.textAlign`, and the header
flag for the first row. A table is a run of consecutive cell lines; a column-0
cell starts a table row and a column-k cell after a column-(k - 1) cell
continues it; a non-cell line ends the table. The column count and each
line's table row index are derived in the `LineFacts` walk, as ordered
numerals and fence edges are, never stored in the marker, so inserting or
deleting a row renumbers nothing. A cell line is shaped at its column's width
and placed at its column's x; the cells of one table row share a top and the
row is as tall as its tallest cell.

- Caret: a cell is an ordinary line, so typing, inline styles, IME, Left/Right
  within a cell, word motion, selection within a cell, undo and redo need no
  change. Left at a cell's start and Right at its end cross to the neighbour
  cell because that is the previous or next line. Up/Down already pick a visual
  row by y and a character by x (1.2), so once rows are placed side by side
  they land in the cell above or below. Home/End stop at the cell.
- Selection: lines are in row-major order, so a drag across cells selects a
  contiguous line range, rendered as the cells' own selection boxes (Word's
  behaviour for a drag through a table, not Docs' rectangle). Deleting such a
  selection would join cell lines; a `TableEditBehavior` clears the selected
  cells' contents instead, and a cut copies them.
- Enter, Tab, Backspace, Delete at cell edges: `TableEditBehavior`, registered
  beside `LineBlockEditBehavior`. Enter moves to the cell below, or appends a
  table row from the last row (Docs); Tab and Shift+Tab move to the next and
  previous cell, Tab at the last cell appends a row; Backspace at a cell's
  start and Delete at its end do nothing (no join), Backspace on an empty row's
  first cell deletes the row (one atomic edit, undoable); a typed or pasted
  newline becomes a space (`onPaste`, `onNewline`); a multi-line insert from a
  host (`insertAtCursor`) is flattened the same way. Insert and delete row and
  column, and "convert to text", are host actions through `withAtomicEdit`.
- Wrapping inside a cell: per cell, by that line's own layout; the visual rows
  of a wrapped cell are its own `LineWrap`s. With soft wrap off, cells still
  wrap within their column: a table is sized to the viewport, not scrolled
  sideways, in a first version.
- Column widths: equal shares of the viewport width less the gutter, with a
  minimum column width (GFM carries no widths, so nothing to persist); a table
  wider than the viewport at the minimum scrolls sideways through the 7.41
  path later, out of scope at first.
- Copy and paste: a selection inside one cell copies as text; across cells, as
  tab-separated rows in plain text (what spreadsheets paste) and `<table>` in
  HTML; a multi-cell paste over a cell writes down and right, the overflow
  appending rows.
- Undo: cell edits are ordinary line edits; structural edits are atomic
  multi-line edits, as list nesting is today.
- Spell check, find, decorations, semantics character bounds, word count:
  free, each cell being a line in `CharLineOffset` space. Find's match scroll
  and highlight use rows, which still exist per cell.
- Markdown: one table row per line on export; the importer places the cell
  lines from the parser's `CELL` nodes.
- **Core cost:** one new concept, lines laid out side by side, in the layout
  pass and the row list, and an x origin per visual row honoured everywhere
  (today `LineWrap.offset.x` is always zero: `DrawEditorText` and the hit test
  `pointerHitAt` already subtract it, while `getPositionForOffset` places the
  caret at `caretX - scrollX` with no `offset.x` term).
  The rest is the usual one block instance plus one edit behaviour plus one
  syntax row per format.

### C. An atomic block, edited through an overlay

One placeholder line with a `TableBlockSpanStyle(cells, alignments)`, a
`BlockSpanStyle` that measures its cells with `state.textMeasurer`, reports the
grid's height and draws it, as an image does; a per-cell overlay editor (a
small `BasicTextEditor` placed over the active cell) edits the model, each
change recorded as a `TextEditOperation.RichSpan` remove and add pair.

- Cheapest to display: no layout change at all. Markdown round trip is trivial
  because the span holds the model.
- Everything else is lost: the cells' text is not in the document, so spell
  check, find, decoration layers, semantics character bounds, word count,
  selection across cells, search and replace, and the edit stream see a single
  space. Undo granularity is the whole table. The overlay needs its own focus,
  IME session, scroll following and touch handles, duplicating the hardest
  parts of the editor for one block. A dead end for Hammer, where spell check
  and find inside prose are the point.

## Recommendation

Option B. It is the one that keeps "a table is a block like the others" true:
a `LineBlockStyle` instance with derived run state, one `EditBehavior`, one
syntax row per format, and every line-based service working unchanged. Its
single new core idea, lines laid out side by side, is contained in the layout
pass and `RowList`, and the spike below shows the per-cell layout arithmetic
is simple and cheap. Option A's cost is spread over every consumer of a
layout; option C saves that cost by giving up the features the editor exists
for.

### What the spike showed

A throwaway desktop test (not committed) laid one document line out as three
fixed-width columns with Compose's `TextMeasurer`, each cell wrapping on its
own, the visual row as tall as the tallest cell, with a hit test and a caret
rect mapped through a `(cell, offset)` pair. All three checks passed:

- Cells wrap independently and the row's height follows the tallest cell, not
  the first.
- For every caret offset in every cell, a probe one pixel right of the caret
  hit-tests back to the same cell and offset, and a point in a column's empty
  space below its text still hits that column.
- Measuring three cells costs about twice one line's measure (25 us against
  13 us at the test's font), not three times, so a table's layout is not a
  cost concern.

Not proven by the spike: the `RowList` changes below, which are the real
risk.

## Nested content in cells

Inline styles only: bold, italic, strikethrough, code, highlight, links. GFM
itself allows nothing else, so the text form could not hold a block in a cell
and rule 1 of `line-blocks.md` settles it.

- `lineBlocksConflict`: a cell conflicts with every other block, and inside a
  table the conflict resolves the other way round from a list's: applying a
  heading, list, quote, fence, rule or image to a cell line is a no-op, so the
  toolbar cannot break a table one cell at a time. The cell style itself is
  always clearable through "convert to text", which turns the run into
  paragraphs in one undo step, so rule 4 holds.
- A typed marker shape at a cell's start (`- item`, `# title`) stays literal,
  as inside a list item today; export escapes it, import reads cells inline
  only (no block peeling), so the round trip holds.
- Multi-line content (a paste, a host insert, a `<br>` or `<p>` in an HTML
  cell) flattens to one line with spaces; a block inside an HTML cell
  (`<ul>` in a `<td>`) flattens to its text.

## Markdown round trip

- **Import.** Walk `GFMElementTypes.TABLE`: the `HEADER` row's cells become
  header cell lines, the delimiter row gives each column's alignment (`:--`,
  `:-:`, `--:`, or none), each `ROW`'s cells become body cell lines, with the
  existing inline walk inside each cell. A ragged row is padded with empty
  cells or truncated to the header's count, as GFM renders it. The parser
  already unescapes `\|` inside a cell, including inside a code span (a GFM
  special case: a pipe in a cell's code span must still be written `\|`).
  `tableRowIndices` and the literal path go away, along with the known
  limitation.
- **Export.** A table is one block in the walk, like a fence run: a row per
  line, `| ` and ` |` around cells joined by ` | `, then the delimiter row
  from the alignments, with `\|` escaped in every cell (the inline escaper
  already lists `|` as escapable), including inside code spans. An empty body
  is valid (header and delimiter only). A cell's inline styles export through
  the existing per-line path.
- **Fixpoint.** `MarkdownFuzzFixpointTest` and the torture test gain a table
  generator (1 to 6 columns, 0 to 5 rows, random alignments, cells with inline
  styles, pipes, backslashes, markers at the cell start, empty cells, ragged
  rows). The property stays export-import-export stable. Today's generators
  cannot form a table by accident because a body pipe is written `\|`.
- **Existing documents.** A Hammer document holding a literal pipe table
  starts importing as a table; one holding tab-separated lines from an earlier
  HTML paste does not.

## HTML

- **Paste.** `<table>` becomes a run of cell lines: `<th>` or a `<thead>` row
  marks the header row (a table with no header row gets its first row as the
  header, since GFM needs one); `<tr>` starts a table row, `<td>` a cell;
  blocks inside a cell flatten to inline text; `colspan` pads with empty cells
  and `rowspan` is ignored; alignment comes from `text-align` on the cell or
  column. Docs, Word and browsers all paste `<table>` markup, so the one path
  serves them.
- **Copy.** Cell lines in a selection export as `<table>` with `<thead>` and
  `<tbody>`, `<th>` for the header row and `style="text-align"` per cell;
  partial selections copy the touched rows with only their selected cells,
  through the container stack `HtmlExtension` already keeps for nested lists.
  Plain text copies tab-separated rows, which is also what the editor's own
  HTML import used to produce.

## Chunk plan

Sizes are relative to the roadmap's recent items (7.86 was S).

1. **Model (M).** `TableCellSpanStyle.of(column, align, header)` as per-column
   singletons (columns capped, say at 16), `LineBlockStyle` entries, the
   conflict rule and its "cell wins" resolution, `TableFacts` derived in the
   `LineFacts` walk (table row index, column count, first and last row, so
   drawing knows its edges) and carried on `LineLayout`, `applyTable` and
   `removeTable` block operations with tests, `documentBlocks` listing tables
   for the serializers.
2. **Layout (L, the crux).** In the layout pass, shape a cell line at its
   column's width (`LineInputs` gains a per-line width; `shape` takes
   constraints per line) and in `RowList` give the cells of one table row one
   top and the row the tallest cell's height: a chunk's `top` advances once per
   table row, after its last cell. The y binary searches must answer on
   non-monotonic tops (a wrapped first cell's second visual row sits below the
   next cell's first), so a grouped row searches by its table row's top and
   bottom, then steps by x to the cell and by y within it. `LineWrap.offset.x`
   becomes the cell's x and is honoured in `caretX`, `getPositionForOffset`,
   selection boxes, the composing underline, decoration drawing and the
   semantics bounds. Borders and the header fill draw from the cell style's
   `drawBackground` and `drawCustomStyle` using the facts. A splice widens to
   whole table rows so a group never straddles a rebuilt chunk boundary.
   Geometry tests through the desktop harness, soft wrap on and off, and a
   golden screenshot.
3. **Editing (M).** `TableEditBehavior` (Enter, Tab, Shift+Tab, Backspace and
   Delete at cell edges, newline flattening on type, paste and host insert,
   multi-cell selection delete and cut), host actions for insert and delete
   row and column, "convert to text", the toolbar wiring in the sample, and
   the fuzzer's invariants (a table run stays rectangular after every op).
4. **Markdown (M).** Import from the GFM nodes, export with the delimiter row
   and pipe escaping, removal of the literal path, the fuzz generator and the
   fixpoint property, an entry in `MarkdownBlockSyntax` or its table-shaped
   sibling.
5. **HTML (M).** `<table>` import and export, plain-text tab rows, the
   clipboard tests, drag and drop of a cell selection.
6. **Polish (S).** Sample app demo with a table, `line-blocks.md` cross
   reference, `MIGRATION.md` note on the import change, the iOS and Android
   checks in the Mac queue.

Chunks 1, 4 and 5 can proceed on a fixed-width stand-in for 2 (every cell on
its own visual row, drawn with a column label) so the serializers and edit
behaviour do not wait on the layout.

## Out of scope for a first version

Column widths and resizing (nothing in GFM to persist them in), merged cells,
multi-line cells, cell backgrounds, header-less tables, a rectangular (column)
selection, sorting, a table scrolled sideways on its own, RTL column order,
table semantics for screen readers beyond character bounds (the cells read as
consecutive lines), and nested blocks in cells (never, per the markdown
contract).

## Risks

- **`RowList` tops.** The non-monotonic row tops inside a table row are the
  one place the design bends an invariant the row list is built on. The fix
  is local (search by the table row's extent, then by x and y), but
  `incremental-relayout.md` sections 9.3 and 9.5 pin costs that the group
  bookkeeping must keep: a table row's extent must be readable from the chunk
  directory without walking its cells.
- **Chunk boundaries and the lazy width reshape (7.48).** A table row that
  straddles two chunks, or whose cells are shaped under different generations
  while a width change settles, would place cells at different tops. Widening
  splices to table rows and shaping a table row's cells together closes both.
- **Selection semantics.** Row-major selection is Word's, not Docs'; a
  rectangular selection would need a second selection model and is deferred.
- **`offset.x` plumbing.** Several readers assume x = 0; one missed reader
  shows as a caret or highlight drawn in the first column. The geometry
  harness (0.5) catches it if a table scene is added to it.
- **Markdown corners.** `\|` inside code spans, a cell that is only
  whitespace, a delimiter row with fewer cells than the header (not a table in
  GFM), a table immediately after a paragraph (GFM allows it, a paragraph
  cannot interrupt it the other way); the fuzz generator must produce each.
- **Behaviour change for existing documents.** Literal pipe tables become
  tables; a user who typed a pipe table on purpose as text sees it rendered.
  Expected, but worth a migration note.

## Prohibitively complex

Per-cell layout inside one line (option A), nested blocks in cells, merged
cells, and a column selection. Each either multiplies every layout consumer by
a cell dimension or asks the text form to hold what GFM cannot.
