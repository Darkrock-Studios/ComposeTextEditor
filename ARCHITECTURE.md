# Architecture

The 10,000 foot view of how ComposeTextEditor is designed to work. Each section
covers one subsystem; sections are added as they are documented. Finer-grained
design notes live in `docs/design/`.

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

Layout state is `TextEditorState.lineOffsets`, a list of `LineWrap`s: each
visual (wrapped) line with its pixel offset, its paragraph's
`TextLayoutResult`, and its resolved rich spans. It is rebuilt by
`updateBookKeeping`, and everything downstream reads it: drawing, cursor
placement, hit testing, scrolling, selection.

Text shaping is by far the most expensive work per edit, so the pass is built
around one rule: **shape only the lines whose content changed**. Everything
else a line carries (y offset, span resolution, ordered-list numbering,
code-fence boundaries) is arithmetic and set lookups, recomputed for every
line on every pass.

### The dirty descriptor

`updateBookKeeping` takes a `LayoutUpdate` describing how much work is needed:

- `Full` re-shapes every line. Required when an input that affects all lines
  changes: text style, measurer, density, viewport size, or a normalization
  rewrite.
- `Partial(remeasureFirst, remeasureLast, lineDelta)` re-shapes only that
  range, expressed in post-edit line indices. A line after the range reuses
  its previous `TextLayoutResult`, looked up by its pre-edit index
  (`index - lineDelta`).
- `SpansOnly` is the empty `Partial`: span overlays changed but no text moved,
  so nothing re-shapes at all.

For document operations, `TextEditManager.layoutUpdateFor` is the single
producer of the dirty range. It derives the range from the operation itself
(insert, delete, replace, style span, rich span, line block) and cross-checks
the declared line delta against the line counts the edit actually produced.
Any disagreement degrades to `Full`. Callers never hand-declare ranges, so an
under-declared range cannot exist.

### Deferral to commit

A relayout requested while a transaction is open does not run. It merges into
a pending `LayoutUpdate` and the commit flushes a single pass, so an operation
lays the document out exactly once no matter how many primitives it touched.
The commit order is: publish the revision, flush the layout, scroll the cursor
into view (so it reads the fresh offsets), then run the commit actions that
announce the edit to the outside world.

Merging two partials is a range union only when neither can have shifted the
other's line coordinates; every ambiguous composition degrades to `Full`
(`LayoutUpdate.mergedWith`). `Full` is always sound, so the design errs toward
it: a partial pass is an opportunistic optimization, never a requirement.

### Reuse safety

A partial pass is only sound against the exact layout the previous pass
produced. `updateBookKeeping` degrades to `Full` when the cached layout is
missing, when a generation counter says a full invalidator fired since the
last pass, or when the line count disagrees with the update's delta. Reused
lines get fresh offsets and freshly resolved spans; cached span lists are
never reused, because edits re-anchor spans and a stale list would carry
pre-edit ranges into drawing and hit testing.

### Span overlays are measure-free

Rich span decorations (spell-check underlines, find highlights, block markers)
are view overlays over unchanged text. Adding or removing them never shapes a
line. Batch producers go through `updateRichSpans(remove, add)`: one revision,
one `SpansOnly` pass, with each added span clamped onto the current document
so a range computed asynchronously against an older revision cannot land
out of bounds. The spell checker decorates any number of misspellings this
way for zero shaping cost.

### Cost invariants

The costs are pinned by counting-measurer regression tests rather than left to
profiling: typing one character shapes one line, pasting N lines shapes N,
span overlays shape zero, and a markdown import shapes the document once
(`EditRelayoutCostTest`, `SpellCheckRelayoutCostTest`,
`ImportRelayoutCostTest`). `RangedRelayoutParityTest` holds the incremental
pass to field-for-field equality with a forced full relayout.
