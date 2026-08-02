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
  changes: text style, measurer, density, viewport size, or a line-block
  normalization rewrite at commit.
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
  sentinel), or
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
  queries, and still counted by span scans (which would falsely trip the
  spell-check guard),
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

## 7. Deferred follow-ups (from issue #36)

- **Per-line span index.** `getSpansForLineWrap` still filters the flat span
  set once per virtual line, O(virtual lines x spans) per pass. Measure-free
  passes made this the dominant remaining term on span-heavy documents.
- **Semantics versioning.** `BasicTextEditor` still rebuilds
  `editableText = state.getAllText()` (an O(document) concatenation) whenever
  semantics are rebuilt.
