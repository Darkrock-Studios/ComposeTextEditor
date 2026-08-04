# Edit operations and offset transforms

How positions survive edits. Every edit moves text, and everything in the
system that holds a `CharLineOffset` (the cursor, the selection, the IME
composing region, every rich span, the undo history) addressed the document as
it stood *before* that edit. This doc describes the rules by which each of
those positions is carried across a mutation, and the rules for writing new
code that holds positions.

The one-sentence summary: **a position survives an edit only by being
transformed by the operation, re-derived from scratch, or deliberately
discarded.** There is no fourth option; a cached offset that takes none of
these paths is a corruption bug waiting for the right edit.

## Layer one: the operation's own arithmetic

Every `TextEditOperation` implements `transformOffset(offset): offset`, mapping
a pre-edit position to its post-edit equivalent. This is pure coordinate
arithmetic with no knowledge of what the position means:

- **`Insert` at position P**: positions before P are unchanged. Positions on
  later lines shift down by the number of inserted newlines. A position on P's
  line at or after P shifts right by the inserted length, or, when the inserted
  text contains newlines, re-homes onto the last inserted line at the
  equivalent column.
- **`Delete` of range R**: positions before R are unchanged. Positions on lines
  below R shift up by the number of deleted lines. A position on R's end line
  past the range slides left onto the join point. A position inside R collapses
  to R's start.
- **`Replace`**: delete-then-insert semantics; positions after the range shift
  by the length delta, positions inside keep their offset relative to the range
  start.
- **`StyleSpan`, `RichSpan`, `LineBlock`**: identity. These change styling and
  decorations, never text length, so no position moves.

One honesty note: the production consumers of `transformOffset` are the rich
span insert and delete handlers described below. The replace handler computes
its own mapping inline (it needs intermediate values, like the post-replace end
position, that the pure transform does not expose), and `Replace`'s
`transformOffset` is exercised only by tests. The rules are intended to agree;
if they ever diverge, the handler is the behavior that ships.

## Layer two: what each consumer does with an edit

Raw arithmetic is not a policy. Each holder of positions crosses an edit in its
own deliberate way:

- **The cursor is intent, not arithmetic.** Every operation carries explicit
  `cursorBefore` and `cursorAfter` fields, chosen by whoever built the
  operation. `applyOperation` places the caret at `cursorAfter`; undo returns
  it to `cursorBefore`. The cursor is never run through `transformOffset`,
  because where the caret lands is part of what an edit *means* (backspace
  lands on the deleted character's position, paste lands after the pasted
  text), not a consequence of range math.
- **The selection and the IME composing region are discarded.** Any
  content-mutating operation clears both at the top of `applyOperation`;
  span-level operations (`StyleSpan`, `RichSpan`, `LineBlock`) keep them.
  These are transient view states, cheap to rebuild and dangerous to keep, so
  the policy is the simplest sound one: positions must not outlive the text
  they addressed.
- **Character styles travel with the text.** Inline `SpanStyle` ranges live
  inside each line's `AnnotatedString`, so they are never transformed as
  positions; they are spliced as content. `SpanManager` rebuilds the affected
  lines' span lists as part of applying the edit (merging adjacent equal
  spans, dropping duplicates, shifting in-line ranges).
- **Rich spans are transformed, with policy on top.** The rest of this doc.

## Layer three: rich span re-anchoring

`RichSpanManager.updateSpans` runs inside every operation's transaction, after
the text mutation. It rebuilds the entire span set: each existing span is
handed to a per-operation handler, and whatever the handler contributes becomes
the new set. *Not contributing is how a span is destroyed*; there is no
separate delete step. Two normalization passes then run over the result (see
below).

The handlers apply `transformOffset` arithmetic where it is sound, and override
it where the span's meaning demands something else:

### Inserts

A plain (no-newline) insert transforms both endpoints, with one exception:
for styles flagged `stickyAtStart` (line-anchored gutter markers: bullet,
blockquote), an insert at the span's exact start keeps the start pinned, so
typing the first character into an empty list item lands *inside* the span
instead of pushing the marker off the line.

A newline insert is a line split, handled structurally rather than by raw
transform: a split before the span moves it down intact, a split after leaves
it alone, and a split *inside* the span produces two spans, one per half. This
is why pressing Enter mid-item leaves both halves decorated.

### Deletes

Deletes are the only handler that needs the operation's metadata (the deleted
text), and the guard is deliberate: with no metadata to reason from, the
handler passes spans through untransformed rather than destroying them; a
stale position beats silent data loss.

A pure newline delete is a line join, again handled structurally: nothing on
the receiving line moves, the joined line's content slides onto the join
point, everything below shifts up one line.

Survival policy for line-anchored spans (sticky markers and full-line blocks):

- A marker pulled off column 0 by a join has lost its line. It survives only
  when the receiving line already carries a same-style marker at column 0
  (rejoining the two halves of a previously split item); otherwise the
  receiving line keeps its own identity and the marker dies with its line.
- A span emptied within its own line survives if its style renders when empty
  (an empty list item is still an item). A span emptied by a multi-line delete
  died with its content.

### Replaces

The replace handler maps positions with its own delete-then-insert arithmetic:
spans before the range are untouched, spans after shift by the length delta,
spans straddling the range are bridged across the new text, spans that end
inside it are truncated at the range start, and spans that begin inside it
keep their surviving tail. Spans entirely inside the range are destroyed, with
one meaning-driven exception: a sticky marker whose own line's text is
replaced in a single-line operation survives, re-anchored to column 0, because
that is *editing the item's text*, not deleting the item. The same reasoning
re-anchors a sticky marker to column 0 of the line its tail survives on.

### Normalization passes

Two passes run over every rebuilt set:

- **Merge line-anchored duplicates**: a join of two same-style items naturally
  yields two adjacent markers on one line; same-style sticky spans starting on
  the same line collapse into one union span, restoring the "one gutter marker
  per line" invariant the styles assume.
- **Clamp to the document**: handler arithmetic near line joins can land a
  hair past a shortened line, so every range is coerced onto positions that
  exist. A range clamped to zero width survives only if its style renders when
  empty.

## Undo, redo, and relative positions

Undo does not rewind state; it applies *inverse operations* through the same
`applyOperation` pipeline (with history recording off), so span re-anchoring,
selection clearing, layout derivation, and edit announcement all follow the
exact rules above. An insert undoes as a delete of the inserted range, a delete
as an insert of the captured text, a replace as the mirrored replace;
`cursorBefore`/`cursorAfter` swap roles. Redo replays the original operation.

Positions in history are stored in the coordinates of the revision the
operation was built against; replaying through the pipeline is what keeps them
meaningful. The one place stored positions cross revisions directly is rich
span restoration: spans destroyed by a delete are captured as
`PreservedRichSpan`s holding positions *relative to the deleted range's start*.
Undo re-bases them at the restored range's start and lands them through the
clamped add path, because the recorded offsets predate the undo's own mutation.
The copy/paste rich-span buffer uses the same relative-position scheme for the
same reason: the paste target is not the copy source.

## Rules for new code

- Never hold a `CharLineOffset` or `TextEditorRange` across an edit you did
  not make. Either subscribe to `editOperations` and transform your positions
  with each operation, re-derive them from the document, or accept invalidation
  the way the selection does. Overlay producers that compute ranges
  asynchronously (spell check, find) must land them through the clamped paths;
  the document may have shrunk since they were computed.
- A new `TextEditOperation` kind is three obligations, not one: its
  `transformOffset`, its `updateSpans` handler (where "contribute nothing"
  means "destroy every span"), and its `LayoutUpdate` derivation.
- Meaning beats arithmetic. Every exception above (sticky starts, split spans,
  join survival, replace-within-item) exists because the correct answer for
  "where is this span now" depends on what the span *is*. When adding a new
  span behavior, decide its edit semantics first and make the handler say so;
  do not let raw range math decide.
