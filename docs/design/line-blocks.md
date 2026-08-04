# Line blocks

The design reference for line-anchored block styles: bullets, ordered lists,
blockquotes, headings, and code fences. It defines what a line block is, which
combinations are valid, how the system enforces validity, and how blocks
round-trip through markdown. The failed approaches that shaped these rules are
in the history of PR #57.

## Design rules

The rules that govern any change to this subsystem:

1. **Round trip is the contract.** The persisted form is markdown text. Every
   state the in-memory model can represent must round-trip through a textual
   marker that import can unambiguously reverse; any gap between what the
   model allows and what the text form can express is a silent-corruption
   site. The regression gate is a generator-based property test
   (export, import, export must be stable), not a pile of example tests.
2. **Import mirrors export through one rule set.** Marker peeling and marker
   emission are inverse images of the same stacking rules. Import never peels
   a combination that could not legally coexist on one line; anything the
   model cannot represent stays literal text, protected by body escaping.
3. **One enforcement point.** Validity is enforced by a normalization pass at
   the commit boundary, not by guards scattered through primitives. The
   primitives stay permissive.
4. **Clearing a style is never blocked.** There must be no reachable state
   that a toggle cannot undo.
5. **Run-sensitive styles need run-aware policies.** Ordered numbering and
   fence grouping are derived from contiguous runs; any policy that can
   fragment a run changes meaning, not just appearance.

## What a line block is

A line block is a bundle of three things that must stay in sync, expressed as
one `LineBlockStyle` value:

1. A line-anchored `RichSpan` (`stickyAtStart = true`) whose style draws the
   gutter marker (dot, numeral, bar) or tints the fence card.
2. A `ParagraphStyle` indent baked into the line's `AnnotatedString`.
3. Optionally a baked-in `SpanStyle` for the line text (code fences bake
   monospace; headings bake the configured heading style).

The bundle also carries the two serialization hooks, `markdownPrefix` (what
export writes) and `markdownPattern` (what import recognizes), so adding a new
block style is one instance, not changes to apply, demote, import, export,
toggle, Enter, and Backspace separately.

The registry per markdown configuration: the *prefix blocks* (blockquote, the
six heading levels, ordered list, bullet list) round-trip through a single-line
prefix (`> `, `# `, `1. `, `- `), in match-priority order. Code fence is a
*wrap block*: it round-trips through ``` markers around a contiguous run and is
handled out-of-band by the importer and exporter.

## Stacking rules

Which blocks may share a line is defined in one predicate (`conflicts`):

- The two list styles exclude each other.
- Headings exclude each other and both list styles (`- # item` is a bullet
  holding literal text, not a bulleted heading).
- Blockquote stacks with lists and headings (`> - item`, `> # Title`).
- Code fence stacks with nothing.

One resolution point (`resolveLineBlock`) turns the predicate into an actual
demotion and rebuild: applying a block first demotes whatever conflicts with
it, then rebuilds the line with the new indent. The per-line toggle and the
importers both resolve through it, so a stack of blocks produces the same line
whether the user typed it or an import placed it.

## Line kinds and validity

Lines come in three kinds: **content**, **blank**, and **placeholder**. A
placeholder line is one owned by a full-line span (`BlockSpanStyle` with
`replacesText()`: a horizontal rule or an image) *and* whose text is still
blank. Text presence beats span presence: a line merge can re-anchor a rule's
span onto a text line, and that line is not a placeholder; its text keeps its
own formatting.

A document is valid when every block span sits on a line that can carry it:

- Ordinary and blank lines carry anything the stacking rules allow.
- A placeholder line may carry a blockquote (`> ---` is representable).
- An image placeholder may additionally carry one list style
  (`1. ![shot](url)` is a numbered figure).
- Nothing else stacks on a placeholder: a bullet on a rule has no meaning and
  no serialized form.

Enforcement is `normalizeLineBlocks`, a pure snapshot-to-snapshot repair run on
every publish at the `withAtomicEdit` commit boundary. It removes disallowed
block spans from placeholder lines and rebuilds those lines without the
orphaned indent. Because it runs at the one point every revision passes
through, the invariant holds no matter which path attached the span: a toggle,
either importer, smart Enter, a host app on the public span API, or span
re-anchoring after an edit. The repair is deterministic and outside undo
history, and since only blank lines classify as placeholders, the most it can
ever discard is a marker on empty content.

## Serialization

**Export** walks lines from one snapshot, prepending each block's
`markdownPrefix` in emission order, then converting the body with markdown
escaping. Escaping is the safety net for plain text: a literal `- ` at the
start of a plain paragraph exports as `\- ` and survives.

**Import** runs peel-then-classify on each raw line, after fence stripping:

1. **Peel** stacked markers in registry order, each style at most once, a
   style eligible only while it does not conflict with anything already
   peeled. This single forward pass exactly mirrors emission: after a list
   marker peels, the other list style is excluded, so `- 1990. The year`
   keeps its literal `1990. ` in the body.
2. **Classify the residue**: a horizontal-rule token or standalone image makes
   the line a placeholder carrying the compatible peels (the quote, and for an
   image one list style); anything else keeps all peels and the body goes to
   the markdown parser.

HTML import and export share the same block attachment path and derive their
container nesting from the same snapshot walk, so both serializers agree on
what a line's blocks are.

## Toggle semantics

`toggleLineBlock` is the sweep behind every toolbar button. From one span-set
snapshot it computes the *eligible* lines: those in the selection that can
carry the style at all (blockquote takes every line; a list style skips rule
placeholders; fence takes only non-placeholder lines; blank lines are always
eligible). Both directions act on the eligible set, and the direction is
decided from it: if any eligible line lacks the block, the toggle applies it
everywhere; otherwise it clears everywhere. Because the only excluded lines
are ones that cannot carry the style at all, clearing is never blocked.

Blank lines under a multi-line apply become empty items, matching Word and
Google Docs. Skipping them was rejected: it fragments the runs numbering
derives from, and a skip on the clear direction creates unreachable spans.

The whole sweep records one atomic `LineBlock` undo entry snapshotting each
affected line's content and block-span set before and after, so undo and redo
restore paragraph style, text style, and every marker (including any
conflicting block demoted as a side effect) in one step.

## Smart editing

- Enter at the end of a block line continues the block onto both halves of the
  split.
- Enter on an empty block line exits the block instead, routed through the
  toggle so the demotion lands in undo history.
- Backspace at column 0 of a block line demotes first (marker off, content
  kept); a second backspace merges. Exception: when the previous line carries
  the same block, backspace merges directly, so joining two adjacent items is
  one keystroke.

## Derived run state

Ordered-list numerals and fence grouping are not stored. Layout derives them
from contiguous runs of same-styled lines (the numbering run position and the
fence-card edge each `LineWrap` carries), and export derives them again
independently. This is why rule 5 exists: anything that fragments a run
(a skipped line mid-selection) renumbers a list or splits a fence, and
bridging runs across gaps would be a model change with serialization
consequences of its own.

## Known limitations

- Nested blocks are unsupported: indented list items do not parse, and a
  nested `> > ` quote collapses one level per import pass.
- Exporting a document whose last line is a heading appends a trailing blank
  line that survives re-import (stable at one extra line).
- Toggling a style off after a blanket apply does not restore the styles lines
  carried before the apply; undo does. This matches conventional toolbar
  behavior.
