# Incremental relayout: design reference

Design record for issue #36 (PR #83). Covers the layout pass that turns document
content into renderable line offsets: what it computes, when it runs, how it
avoids re-shaping the whole document on every edit, and the invariants that keep
the incremental path indistinguishable from a full one.

## 1. The problem being solved

Layout state is `TextEditorState.lineOffsets`, a list of `LineWrap`s: each
visual (wrapped) line with its pixel offset, its paragraph's
`TextLayoutResult`, and its resolved rich spans. Everything downstream reads
it: drawing, cursor placement, hit testing, scrolling, selection.

Text shaping (`TextMeasurer.measure`, font shaping plus line breaking) is by
far the most expensive work per edit. A layout pass that shapes all N
paragraphs makes every keystroke O(N), and any path that runs one pass per
mutation multiplies that: a spell-check pass adding one underline span per
misspelling performs M full-document shaping passes for M misspellings. On
imported manuscripts this froze the UI thread hard enough to require killing
the app (hammer-editor#805).

The design goal, in one rule: **shape only the lines whose content changed,
and run at most one pass per logical operation**. Everything else a line
carries (y offset, span resolution, ordered-list numbering, code-fence
boundaries) is arithmetic and set lookups, cheap enough to recompute for every
line on every pass.

## 2. The dirty descriptor: `LayoutUpdate`

`updateBookKeeping(update: LayoutUpdate)` takes a description of how much work
the pass must do:

- `Full`: re-shape every line. Required when an input that affects all lines
  changes: text style, measurer, density, viewport width, or a line-block
  normalization rewrite at commit. A change of the viewport's height alone
  runs no pass when the rows are current (the last pass laid out the same
  lines at the same width, with no transaction open): it only moves the
  scroll range.
- `Partial(remeasureFirst, remeasureLast, lineDelta)`: re-shape only that
  range, expressed in **post-edit** line indices. `lineDelta` is the post-edit
  line count minus the pre-edit count. A line after the range reuses its
  previous `TextLayoutResult`, looked up by its pre-edit index
  (`index - lineDelta`); a line before the range reuses by its own index.
- `SpansOnly`: the empty `Partial`. Span overlays changed but no text moved,
  so nothing re-shapes at all.

Reused lines still get fresh offsets, freshly resolved spans, and fresh
ordered-list numbers and code-fence boundaries. The last two are derived from
whole-document hash sets built each pass, which is what keeps them correct for
lines outside the dirty range: attaching an ordered-list span to one line can
renumber a run of lines that were never edited.

Cached span lists are never reused. Edits re-anchor spans into new `RichSpan`
instances, so a cached list on a shifted line would carry pre-edit ranges into
drawing and hit testing.

### The single producer

For document operations, `TextEditManager.layoutUpdateFor` is the only
producer of operation dirt. It derives the range from the operation itself:

| Operation | Update |
|---|---|
| Insert | `Partial(pos.line, pos.line + newlines, +newlines)` |
| Delete | `Partial(start.line, start.line, -(end.line - start.line))` |
| Replace | `Partial(start.line, start.line + newlines, newlines - deletedLines)` |
| StyleSpan | `Partial(start.line, end.line, 0)` |
| RichSpan | `SpansOnly` |
| LineBlock | `Partial(min touched line, max touched line, 0)` |

The declared `lineDelta` is cross-checked against the line counts the edit
actually produced; any disagreement (clamped deletes, the keep-one-empty-line
floor) degrades to `Full`. Operation handlers mutate lines through `setLine`,
which posts no layout request, so the operation-level range is authoritative
and callers never hand-declare ranges. An under-declared range cannot exist by
construction.

## 3. Deferral to transaction commit

`updateBookKeeping` called while a `withAtomicEdit` transaction is open does
not run. The request merges into a pending `LayoutUpdate` and the commit
flushes one pass. This is what makes one logical operation cost one pass no
matter how many primitives it touched, and it is also what collapses a
markdown import (text publish plus block decoration plus link spans, all in
one transaction) into a single whole-document pass.

Commit order matters and is fixed:

1. Publish the revision (after line-block normalization; a normalization
   rewrite bumps the layout generation because it can touch lines no operation
   declared).
2. Flush the pending layout.
3. Scroll the cursor into view. The scroll target is computed from
   `lineOffsets`, so it must read the freshly flushed layout; scrolling
   mid-transaction reads pre-edit offsets and can fling the viewport to the
   document top.
4. Run the queued commit actions (the `editOperations` announcements).

A throwing transaction discards all four: the draft, the pending layout, the
pending scroll, and the queued announcements. Announcing a discarded edit
would hand subscribers (spell check, autosave) a range that does not exist in
the reverted document.

### Merge soundness

Pending partials merge by range union only when neither side can have shifted
the other's line coordinates (`LayoutUpdate.mergedWith`):

- Two delta-0 partials: union. No line moved, both ranges are in commit
  coordinates.
- Two structural partials (both `lineDelta != 0`): `Full`. Their ranges are in
  different coordinate spaces and cannot be composed by arithmetic alone.
- One structural, one stable: union only if the stable range lies entirely
  above the shift point (`stable.last < structural.first`), where no
  coordinate can have moved. Anything else is `Full`.

`Full` is always sound, so the merge errs toward it. A partial pass is an
opportunistic optimization, never a requirement; correctness never depends on
a merge staying partial.

## 4. Reuse guards

A partial pass is only sound against the exact layout the previous pass
produced. `updateBookKeeping` evaluates its guards at execution time and
degrades the update to `Full` when:

- there is no previous layout (`lineOffsets` empty, e.g. the pass before the
  viewport got its first real size),
- `layoutInputGeneration` moved since the last completed pass (any full
  invalidator fired, even one whose own pass was skipped by the viewport
  sentinel; a pass the sentinel skips is itself an invalidator, since it
  leaves the rows behind the text), or
- the previous pass's line count does not equal the current count minus the
  update's declared delta.

Because the guards run inside the pass rather than at the call sites, a stale
or mis-declared `Partial` arriving from anywhere produces a correct (merely
slower) full pass, never a corrupt layout.

## 5. Span overlays are measure-free

Rich span decorations (spell-check underlines, find highlights) are view
overlays over unchanged text; adding or removing them never shapes a line.
Batch producers go through `TextEditorState.updateRichSpans(remove, add)`:

- one transaction, so readers never observe the swap half-applied,
- bulk set operations in `RichSpanManager` (two snapshot copies total, not one
  per span),
- each added span clamped onto the current document, mirroring what the edit
  pipeline's `clampAllToDocument` does. Overlay ranges are computed
  asynchronously, so they can arrive pointing past a document that shrank in
  the meantime; unclamped, such a span is invisible, uncollectable by range
  queries, and still counted by span scans,
- one `SpansOnly` pass at commit.

The spell checker routes every decoration path through this API, so a full
check on a document with hundreds of misspellings costs one measure-free pass.

## 6. Cost invariants and tests

The costs are pinned by regression tests rather than left to profiling. A
mock `TextMeasurer` counts `measure` calls (`testUtils/countingMeasurer/`,
shared across modules):

- `EditRelayoutCostTest`: typing one character shapes 1 line; Enter shapes 2;
  a line join shapes 1; pasting N lines shapes N; undo and redo shape their
  ranges; a style span shapes its range; span overlays shape 0.
- `SpellCheckRelayoutCostTest`: full and partial spell-check passes shape 0.
- `ImportRelayoutCostTest`: a markdown import shapes the document exactly
  once, decorated or not.
- `RangedRelayoutParityTest`: after each scripted mutation (multi-line edits,
  undo/redo, span changes that renumber lists or flip code-fence boundaries,
  wrapped paragraphs), the incremental `lineOffsets` must equal a forced full
  relayout field for field.
- `LayoutUpdateMergeTest` and `UpdateRichSpansClampTest`: the merge soundness
  rules and batch clamping.

## 7. Per-line span queries

`DocumentSnapshot` memoizes a per-line span index (`richSpansByLine`, every
span grouped under each line it covers), built lazily once per span revision
and shared across text-only revisions. `getSpansForLineWrap`,
`getSpansInRange`, and the line-anchored block queries all read it, so each
per-line query the layout pass runs costs the spans on that line, not the
spans in the document. `SpanScanCostTest` pins a relayout to a constant number
of flat span-set iterations.

## 8. Whole-document text

`DocumentSnapshot.getAllText()` (the whole document as one styled string, read
by semantics) and `plainText` (the same without styles, read by the skiko
input request and Android's extracted text) are memoized per text revision:
built on first read and shared across span-only revisions, the same pattern as
the span index. After an edit, a revision's text is spliced
from the last revision whose text was built: that one's unchanged first and
last lines are copied as two ranges of its text, and only the changed lines
are read, with the line at each end of a copied range, whose empty
annotations (an empty block line's styles) a copied range would drop. The
edit says which lines changed (`LineSplice`, from `setLine` and
`replaceLines`); any other writer's lines are compared by identity. An unread
revision keeps at most one earlier text of each kind alive, never a chain,
and drops it once its own text is built.

Readers that need a few characters read `DocumentSnapshot.chars` instead, a
`CharSequence` over the lines and their starts, so an input method's reads
around the caret never build the whole text.

## 9. Per-keystroke work that does not scale with the document (7.8)

Sections 2 to 8 make shaping proportional to the edit. Three other costs of a
keystroke were still proportional to the document: the line list was copied,
every `LineWrap` was rebuilt (the y offsets after the edit move, and every row
carried an absolute one), and every rich span was re-anchored into a new set.
The design below removes all three with one structure used three times: a
persistent chunked sequence.

### 9.1 The chunked sequence

A chunked sequence holds its elements in chunks of 32 to 64 (a document under
32 lines is one chunk), with a directory of the first index of each chunk and,
per measure the sequence carries, the running total at each chunk's start.
A chunk carries the same running totals for its own elements. So an indexed
read is a binary search over the directory (a hint remembers the last chunk
hit, which sequential reads and edits at the caret keep hitting) and an array
read; a prefix total is two array reads; and the element at a total (the line
at a flat character index, the row at a height) is two binary searches.

A splice replaces elements `[from, to)` with a replacement list. The chunks
before and after the touched ones are shared with the previous revision. The
touched chunks' surviving elements and the replacement are re-chunked into
chunks of at most 64; when that region is under 32 elements and a neighbour
chunk exists it absorbs that chunk, so the size invariant holds. Then the
directory is rebuilt from the first touched chunk on. A splice therefore costs
O(replacement + 64 + chunks), never O(elements), and leaves the previous
revision intact: the snapshot stays an immutable value that any thread can
hold, and undo keeps working on operations, not on structure.

The chunk count is O(elements / 32), so the directory rebuild is not
logarithmic; for the documents this editor is for (thousands of paragraphs)
it is a few dozen integers. A three-level tree would make it logarithmic and
is the upgrade path if a document of hundreds of thousands of lines ever
matters.

### 9.2 The line list

`DocumentSnapshot.lines` is a `LineList`: a chunked sequence of
`AnnotatedString` whose measure is the line's length plus one (its line
break). It is the `List<AnnotatedString>` the public API has always exposed
(`RandomAccess`, immutable), and it replaces the flat `lineStartOffsets`
table: the flat character index of a line and the line holding a flat index
are the same prefix-total reads, so nothing is rebuilt per revision.
`replaceLines` splices; `setText`, `setDocument`, `processLines` and the
public constructor re-chunk a foreign list once, in O(lines). Identity is
unchanged: every text edit publishes a new `LineList` and a span-only
revision keeps the old one, which `KillRing`, `WordCounter`, the skiko
document layout and the height-only viewport path all rely on.

The whole-text splice of section 8 needs no change: its base records the
line starts of the revision it was built from (now the old `LineList`), and
the edit's `LineSplice` says which lines to read.

### 9.3 The row list

`TextEditorState.lineOffsets` is a `RowList`: a chunked sequence of one
`LineLayout` per logical line, with two measures, the line's row count and
its height. A `LineLayout` is the shaping result of one line plus what the
pass derived for it: its rows' wrap starts and their tops within the line
(read once from the `TextLayoutResult`, never again), the block height, the
ordered-list numeral, the code-fence boundary, the paragraph spacing
(section 11), and the width it was shaped at.

The `List<LineWrap>` the consumers read is materialized on read: `get(row)`
finds the line by row count, computes the row's absolute top from the running
heights, and builds the `LineWrap` with the same fields as before. `LineWrap`
itself does not change, so drawing, hit testing, the caret, scrolling and the
row searches read what they always read; `RowSearch.kt`'s binary searches run
unchanged over the materialized list, and the `RowList` answers the four
searches it can answer from its directory without materializing a row (the
generic `firstRowWhere` stays for the callers with their own predicate).
A row's `richSpans` are the spans on its line, from the snapshot the pass ran
against, filtered to the row at materialization; a `LineLayout` holds no span
list, so a shifted line never carries a stale one (the rule of section 2
stands, with nothing to cache).

A partial pass shapes its lines into new `LineLayout`s and splices them over
the old ones; the lines after the edit keep their layouts and move with their
chunk, their tops following from the running heights. The derived facts that
depend on neighbours are recomputed for the lines whose facts can have
changed: the list run and the fence run the edit touches (walked up and down
through the per-line span index, O(run)), plus one line each side for the
fence edges. A span-only pass (`LayoutUpdate.Spans(first, last)`, which
replaces the unbounded `SpansOnly` wherever the caller knows its lines)
rebuilds the `LineLayout`s of those lines without shaping, for their block
height and facts, and splices them in the same way; the callers that cannot
say (a block image finishing its load) still pass the whole document, which
is a pass without shaping, as before.

The invariants `RowSearch.kt` relies on hold: rows ordered by line, a line's
rows by wrap start, tops non-decreasing. Section 11 loosens "each row starts
where the one above ends" on purpose.

### 9.4 Rich spans by line

`DocumentSnapshot` stores its rich spans in a `SpanIndex`: a chunked sequence
keyed by line, each entry the spans that start and end on that line as
line-relative `(startChar, endChar, style)` triples, plus one flat set of the
spans that cross a line break. Nearly every span is single-line (blocks,
links, spell-check underlines, find highlights, images, rules, fence
languages), so the flat set is normally empty and never large. A text edit
that inserts or deletes lines splices the index like the line list, and the
spans on the lines after the edit move with their chunk untouched: no line
number is stored in them.

Re-anchoring an edit reads only the spans on the edit's pre-edit lines and
the crossing set, runs them through the existing insert, delete and replace
transforms, merges same-line duplicates of line-anchored styles among the
results, clamps them onto the post-edit lines, and writes them back to the
edit's post-edit lines. The transforms shift a span on any other line by the
edit's line delta and nothing else, which is exactly what the splice does.

The public `richSpans: Set<RichSpan>` is materialized on first read per span
revision, with absolute ranges, as is each line's list (`spansOn(line)`,
which replaces the `richSpansByLine` map). Readers that walk the document
(export, `getAllRichSpans`, the saver) still pay O(spans) once; the per-line
readers (layout, hit testing, drawing, the line-block queries) pay for their
line. `getSpansInRange` walks the range's lines. Span-only mutations write
one line or a batch of lines and share every other chunk.

Line-block normalization runs on every publish and used to scan every span.
A snapshot now records how many of its first and last lines are unchanged
since the last published revision (narrowed across a transaction's mutations
exactly as the whole-text base is, section 8), and normalization examines
only the lines between, plus the fence run they touch for the language
repair.

### 9.5 Costs to pin

Each chunk lands with a counting test: the lines a keystroke's edit copies
(`LineListCostTest`: a splice copies at most two chunks and the directory),
the `LineLayout`s a keystroke rebuilds and the rows it materializes
(`RowListCostTest`: an edit rebuilds its lines and their runs, a frame
materializes the rows in view plus the searches), and the spans an edit
transforms (`SpanIndexCostTest`: the spans on the edit's lines and the
crossing set). `EditRelayoutCostTest`, `RowLookupCostTest`, `FrameCostTest`,
`SpanScanCostTest` and `RangedRelayoutParityTest` keep their bounds; the
parity test is the guard for the materialized rows, since it compares them
field by field against a full pass.

## 10. Lazy width reshape (7.48)

A width change (a window drag, a rotation, a split screen) needs every line
shaped again, and section 9.3 makes that possible without doing it at once.
`updateBookKeeping(LayoutUpdate.Reshape)`, which the viewport width, the text
style, the measurer and the density changes post, runs when the row list
exists for the current lines:

1. Shapes the lines with a row in the viewport, plus one viewport's worth
   beyond each edge, synchronously, and splices them in. Every other
   `LineLayout` stays as it is: shaped at the old width, so its rows have the
   old wrap starts and its height is the old height, marked provisional by
   the width it records.
2. Keeps the scroll anchored to a line: the first visible row's line, at its
   offset within the viewport before the change. After the synchronous part
   the scroll value is moved so that line's top is where it was on screen.
3. Launches one settling job on the state's scope that shapes the provisional
   lines outward from the viewport (above and below alternately, so what a
   scroll reaches next is shaped first), in slices of at most 32 lines,
   splicing each slice in, re-anchoring the scroll when a slice above the
   anchor changes height, and yielding to the frame between slices. A new
   `Reshape` cancels the job and starts over; an edit's partial pass shapes
   its own lines at the current width and leaves the job running, since the
   row list it re-reads on every slice carries the edit.

Consumers tolerate provisional rows: they are rows with a layout, only at
another width, so geometry off screen is approximate until the job reaches
it. The text drawing forces the rows it is about to draw before reading them
(a viewport of lines, bounded), and a scroll to the caret forces the caret's
line, so what is on screen is always shaped at the current width. Everything
else (hit tests, which are in the viewport anyway; page moves; the scrollbar,
whose range settles as heights do) reads what is there. The total content
height is provisional the same way, as it is in a browser during a resize.

The first layout of a document (no rows yet), a guard degradation, and a
`Full` posted explicitly stay synchronous: `Full` keeps its meaning of "shape
everything now", which the parity test and the tests that count a full pass
depend on, and a document with no old layouts has nothing provisional to
show. Tests and benchmarks call `settleLayout()` to run the job to the end
synchronously.

## 11. Paragraph spacing and formatting (5.7)

Nothing in the model has ever separated paragraphs vertically, and the only
alignment, indent and line height are the global ones in `textStyle`. The
block model bakes a `ParagraphStyle` into a block line's text and strips it
again by equality, so per-paragraph formatting cannot share that slot without
breaking every block toggle. It is a rich span instead: `ParagraphFormat`, a
line-anchored (`stickyAtStart`) content style carrying `spaceBefore` and
`spaceAfter` in dp, and optionally an alignment, a start indent and a first
line indent in sp, and a line height in sp; each field null means "the
editor's default". One span per line, ranging over the whole line like a
block marker, kept by the same edit rules (it survives an edit within its
line, dies with its line, and an Enter at the line's end continues it onto the
new line as blocks do, through `LineBlockEditBehavior`). A global default
comes from `TextEditorStyle.paragraphSpacing` (space after every paragraph,
zero by default, so existing editors do not move).

**Layout.** A `LineLayout` carries its space before and after in pixels. Its
rows' tops start below the space before, and its height includes both, so the
row list's running heights place the next line below the gap. `LineWrap.offset`
and `paragraphTop` are the text's top, as before, and `effectiveHeight` is
still the row's text height: the gap is outside every row. Alignment, indent
and line height are applied at shaping time by measuring the line with a
`ParagraphStyle` merged from the block's indent and the format's fields (the
stored text is untouched, and the merge mirrors the indent baking the pass
already does for the outer style), so the caret, hit testing and selection
follow the layout with no further work.

**Geometry with gaps.** The rows stay ordered by line and wrap start and their
tops non-decreasing, so every binary search in `RowSearch.kt` still holds;
what no longer holds is "each row starts where the one above ends", and the
comment there says so. A point in a gap belongs to the row above it
(`lastRowAtOrAbove`), as a point below the last row does, so a click in the
spacing after a paragraph lands on that paragraph's last row; the space
before the first line belongs to the first row. The caret is drawn on its
row; the selection paints its rows only, with the line-break sliver on the
row's height, leaving the gaps clear; the text drawing and the hover hit
test read `paragraphTop`; scrolling keeps a row, not its gaps, in view.

**Serialization.** The saver serializes `ParagraphFormat` as a built-in kind
with its fields as the argument, so a saved state restores it. Markdown has
no paragraph spacing, alignment, indent or line height: export writes the
text without them and import reads none, so a document that round-trips
through markdown loses its paragraph formatting (recorded in 5.7). HTML could
carry all of it as inline styles on the paragraph; that is a separate item.
