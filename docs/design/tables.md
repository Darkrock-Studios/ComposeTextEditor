# Tables

How the editor holds a GFM pipe table as a block, like lists, quotes, headings,
fences, rules and images: the model, the side-by-side layout, editing, and the
markdown and HTML round trips. It began as a proposal comparing three shapes; the
recommended one was built, and where the build departed from the proposal it says
so. Terms: a *table row* is a row of cells; a *visual row* is one wrapped row of
text, what `RowList` hands out as a `LineWrap`.

## The shape chosen

A table is a run of consecutive cell lines in row-major order, one document line
per cell. Every line-based service (typing, IME, undo, inline styles, spell check,
find, decoration layers, word count, the screen reader's character bounds) works
on a cell unchanged, because a cell is a line. The one new core idea is lines laid
out side by side, contained in the layout pass and `RowList`.

Two shapes were rejected. One line per table row, with cells split by a hidden
delimiter, would give every one of the 31 readers of a `TextLayoutResult` a cell
dimension, and every edit path a guard against the delimiter. An atomic block
edited through an overlay editor would keep the cells' text out of the document,
and with it spell check, find and the edit stream, and would duplicate focus, IME
and touch handling for one block.

## Model

- **The marker.** A cell line carries `TableCellSpanStyle.of(column, alignment)`,
  a per column and alignment singleton (16 columns, `MAX_TABLE_COLUMNS`, by four
  `TableAlignment`s), sticky at its start like every block marker. Its
  `LineBlockStyle` bakes the alignment as the line's `ParagraphStyle.textAlign`.
- **Rows are derived.** A cell starts a table row when the line before it is no
  cell, or a cell of the same or a later column; otherwise it continues the row.
  A skipped column leaves a gap in its row rather than a misaligned cell.
- **The header is derived**, not stored as the proposal had it: the table's first
  row is its header. Its bold is applied when the line is shaped, under the
  styles the text carries, and never enters the text, so no format writes it and
  deleting the header row makes the next one the header with nothing to repair.
- **A table's columns** are its header row's, from column 0 to its last cell's.
  `TextEditorTable` (from `tableAt` on the state or a `DocumentSnapshot`) reads a
  table's rows, columns and alignments.
- **Stacking.** A cell conflicts with every block, another column's cell too, and
  never gives way: `lineBlocksConflict` reports the conflict and
  `LineBlockStyle.refusedBy` makes a heading, list, quote or fence put on a cell
  line a no-op, so a toolbar cannot break a table one cell at a time. A rule or
  an image put on a cell is taken off it by normalization. Text pasted or
  dropped into a cell brings only its inline styles and links. A cell is
  a block, not a blank line (`isNestingBlank`), and takes no paragraph format.
  These rules belong to `RichSpanStyle.inlineOnly`, which a cell sets: the
  shared code asks `isInlineOnlyLine`, not whether a line is a cell.

## Layout

Tables plug into the layout passes and the edit pipeline as a `BlockKind`
(`TableKind`, see `block-kinds.md`): its walk gives a cell its facts, and its
`shape` and `place` lay it out. Bands, advances and the row searches below are
generic placement, for any kind that places lines.

- **Cell placement.** The walk that numbers lists (`LineFacts`) also derives each
  cell's `TableCellFacts`: its table row, its row's column count (the header's,
  or more for a row reaching past it), whether it starts or ends its row and
  whether its row is the table's last. A cell is shaped at its column's share of
  the viewport less its padding, wrapping whether lines wrap or not, and its
  `LineLayout` carries a `LinePlacement`: its box, its text's x, and its band's
  height. Placement is generic: a band is any run of lines laid out side by
  side, and a table row is the one kind there is.
- **One top per table row.** A line's *advance*, what moves the lines after it
  down, is its height, but a cell's is nothing until the last cell of its row,
  which advances by the row's height (the tallest cell's), and below the
  table's last row by the paragraph spacing too. `RowList` sums advances, so the
  cells of a row share a top whichever chunks they fall in. `finishBands`
  gives each row its height after its cells are laid out; every pass widens to
  whole bands (`bandStart`, `bandEnd`) so a row is never laid out in
  part, and a cell whose facts would shape it differently (another column count,
  in or out of the header) is shaped again rather than handed the new facts.
- **Bands.** A row's band is the row itself, but a cell's row's is its whole table
  row (`LineWrap.bandTop`, `bandBottom`), so bands run top to bottom though a
  wrapped cell's rows pass the next cell's, and every y search stays binary.
  `rowAtPoint` refines a band to the cell under x and its row under y; the hit
  test, the pointer's character, handwriting gestures and page moves use it.
- **x.** `LineWrap.offset.x` is a cell's text x, honoured by the caret, the hit
  test, selection (kept in the cell's box), span drawing, the composing underline
  and the semantics bounds. `LineWrap.box` carries the cell's box, and
  `LineWrap.tableCell` its row and column, for its style to draw its borders
  (each line once) and the header's fill. Geometry readers (hit testing,
  selection, Up and Down, gestures) read only the box and the band, never the
  table.
- **Up and Down** move by row index, not by y as the proposal assumed, so from or
  into a table they go by geometry: to the row above or below the band, in the
  cell under the goal x.
- A table is sized to the viewport and never scrolls sideways; with soft wrap off
  its cells still wrap, and it adds nothing to the content width.

## Editing

- **`TableEditBehavior`**, ahead of `LineBlockEditBehavior`: Enter goes to the cell
  below, adding a row from the last one; Backspace at a cell's start and Delete at
  its end join nothing; Backspace in the first cell of an empty row deletes the
  row; Backspace at the start of the line after a table steps into its last cell,
  deleting that line when it is empty unless another table follows it.
- **Line breaks.** `InlineOnlyLineBreaks`, ahead of every other input filter, makes
  a line break landing in a cell a space, Enter's too, which the behavior has
  already taken.
- **Deletions across cells.** A user deletion or replace whose range crosses a
  table's edge or its cells is applied a line at a time in one edit group
  (`tablePreservingPieces`, the table's `BlockKind.deletionPieces`): each cell
  it covers is cleared, the lines between tables are deleted as usual, and a
  replace's text goes in at the range's start. A range taking a whole table and
  more deletes the table. A word or line deletion stops at a cell's edge.
- **Tab** and Shift+Tab (`TableEditBehavior.onIndent`) move to the next and
  previous cell and select its text; Tab from the last cell adds a row.
- **Structural edits**, one undo step each: `insertTable` (in place of an empty
  line, never against another table, with a line after it at the document's
  end), `insertTableRow`, `deleteTableRow`, `insertTableColumn`,
  `deleteTableColumn`, `setTableColumnAlignment`, `convertTableToText` and
  `deleteTable`. Inserting lines beside an empty line carries its markers along,
  so each of these writes the markers of every line it touches.
- A fuzz test (`TableFuzzTest`) runs random edits, structural ones included, and
  checks every table stays rectangular and that undoing them all gives the
  document back. The shared storms run over tables too (see `docs/TESTING.md`).

## Markdown

- **Import.** Tables are found in the fence-stripped lines before the paragraph
  separators are taken out, since the blank line that ends a table is one. As
  GFM has it, a header or delimiter row indented like code starts no table (a
  header continuing a paragraph aside), and a heading, quote, list item, fence,
  rule or HTML block that can interrupt a paragraph ends one.
  `readTables` splits a row at its unescaped pipes, `\|` a pipe in its cell
  (inside a code span too), reads the alignments from the delimiter row, pads or
  cuts a row to the header's width and drops columns past
  `MAX_TABLE_COLUMNS`. Each cell becomes a line, led through the parse by a
  punctuation character the source does not hold, so nothing in a cell starts a
  block; the lead is taken out afterwards with the styles and links moved back.
  A table inside a quote stays literal text.
- **Export.** A table row is written at its first cell, `| a | b |`, with the
  delimiter row after the header and each pipe in a cell escaped. A cell's spaces
  at either end are not written, as GFM trims them. A blank line always follows
  a table, and under `ParagraphSeparator.NEWLINE` one goes before it too, or a
  table could continue the list item or quote above it; import takes those
  blank lines out again. Plain lines that would read as a table under single
  newlines have their delimiter row's pipes escaped.
- **Fixpoint.** `TableMarkdownTest` checks export, import, export stability over
  random tables (inline styles, pipes, backslashes, marker shapes and padding in
  cells, empty cells, ragged rows) and over random table edits.

## HTML

- **Import.** A `<table>` becomes a line per cell, row by row through its
  `<thead>`, `<tbody>` and `<tfoot>`, the first row the header whatever its tags;
  a `colspan` pads its row with empty cells, `rowspan` is ignored, every row is
  padded to the widest, and a column's alignment is its header cell's
  `text-align` or `align`. Inside a cell, blocks, nested tables and `<br>` run on
  as spaces, rules and images are dropped, a heading's look is not kept, and
  inline styles, colours and links are.
- **Export.** Cell lines write as `<table>` with `<thead>` for the header row and
  `<tbody>` for the rest, `<th>` and `<td>`, and `style="text-align"` per aligned
  cell. A copy reads the header from the whole table, so a copy starting in a
  body row stays a body row.
- **Paste.** A table pasted into the middle of a line is given lines of its own,
  so its first and last cells keep their markers; pasted into a cell it is
  flattened to text by the line-break filter. Pasted cells that would leave a
  table with a row short of its columns or past them (part of a table, or rows
  beside a table of another width) paste as text; whole rows paste as a table.
  This holds for a copy inside the editor too.

## Not done

- A copy writes a cell a line as plain text, not the tab-separated rows the
  proposal had: tabs would need a plain-text flavor apart from the copied text on
  every platform's clipboard, and would break the in-editor paste's matching of
  copied text where a platform carries no copy id.
- A multi-cell paste over a cell does not write down and right; it pastes as text.
- Column widths and resizing (nothing in GFM to hold them), merged cells,
  multi-line cells, cell backgrounds, header-less tables, a rectangular column
  selection, sorting, a table scrolled sideways on its own, right-to-left column
  order, and table semantics for screen readers beyond the cells' character
  bounds.
- The iOS and Android checks of the layout belong in the Mac queue.

## Risks, as they came out

- **`RowList` tops.** The non-monotonic tops inside a table row were met with
  bands and per-line advances; the chunk directory still answers every search
  without walking a table's cells.
- **Chunk boundaries and the lazy reshape.** Every pass, the settling slices
  included, widens to whole table rows, so a row is never shaped in part or
  placed at two tops; `TableLayoutTest` checks the lazy and settling passes
  against a full one.
- **`offset.x` readers.** Each reader of a row's geometry was moved to the x and
  the band; the golden `table.png` and the layout tests pin them.
