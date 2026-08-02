# Architecture

The 10,000 foot view of how ComposeTextEditor is designed to work. Each section
covers one subsystem; sections are added as they are documented. Finer-grained
design references live in `docs/design/`.

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
