# Block kinds

A block that spans lines, an ordered list, a code fence or a table, needs more
than a line marker: its lines take facts from their neighbours, those facts can
change how a line is laid out, and edits must keep its structure. `BlockKind`
(`state/BlockKind.kt`) is where such a block plugs in, so the layout passes and
the edit pipeline ask every kind in `BLOCK_KINDS` rather than naming one.

The interface is internal while it settles; a second complex block (callouts,
collapsible sections) is meant to test it before it is opened to hosts.

## Hooks

| Hook | Asked by | Ordered lists | Code fences | Tables |
| --- | --- | --- | --- | --- |
| `walk` | `LineFacts`, every layout pass | numeral, counters | card edge | `TableCellFacts` |
| `walkStart` | a partial pass, where to resume | the line | the line before | the row before |
| `shapesDifferently` | a partial pass, reshape or reuse | | | column, count, header |
| `shape` | `LineShaper` | | | column width, bold header |
| `place` | `LineLayout`, side-by-side lines | | | cell box in its row |
| `spacing` | `LineLayout`, a line in the flow | | | |
| `deletionPieces`, `afterJoin` | `TextEditManager` | | | `tablePreservingPieces` |
| `beforePastedBlocks`, `settlePasted` | paste and drop | | | own lines, broken tables as text |
| `repair` | `normalizeLineBlocks` | | language spans | |

Two hooks live elsewhere because they are not about one kind:

- **`EditBehavior`** claims keys before the default edit: Enter, Backspace,
  Delete and, with `onIndent`, Tab and Shift+Tab. `TableEditBehavior` is one.
- **`RichSpanStyle.inlineOnly`** marks a line that holds inline content alone. The
  shared code asks `isInlineOnlyLine`, so stacking, toggles, normalization,
  paste, the line-break filter, paragraph formats, word deletion and markdown
  shortcuts treat any such line as they treat a table cell.

## Facts

A line's facts are a `BlockFacts`, one value per kind, kept on its `LineLayout`;
a line with none shares `BlockFacts.NONE`. Facts are values: a partial pass
compares them to decide whether a line it walks past changed, and a walk resumes
from the facts of the line before. A partial pass walks from the least
`walkStart` of the kinds, and past the edit until a line keeps its layout and
its facts and ends its band.

## Geometry

A kind that `place`s a line lays it out beside others: its `LinePlacement` gives
the box, the text's x, its padding and its band. Readers of geometry (hit
testing, selection, Up and Down, gestures) use `LineWrap.box` and the band, never
the kind. A kind that adds `spacing` gives a line room above or below its rows,
and `LineWrap.spaceBefore` and `spaceAfter` hand a style that room, so a card
drawn across lines can close the gaps between them.

A style reads its kind's facts from `LineWrap.blockFacts`, an opaque handle a
new kind needs no field for. The first kinds' draw data is also kept as fields
(`orderedListNumber`, `codeFenceBoundary`, `tableCell`), since public styles
read them.
