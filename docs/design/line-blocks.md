# Line block styles: design reference

Working document for issue #43 and the `fix/43-block-toggle-select-all` branch (PR #57).
It records the original problem, how the code handled line blocks before the branch,
what we actually want from the system, and what we have tried so far and how it failed.
Line references are to `main` unless marked otherwise.

## 1. The original problem

Issue #43, reported from Hammer: a user pasted ~2500 words, selected all, applied a
formatting change across the whole selection, and after the (separate, since-fixed #42)
crash and a reload, **every line of the document carried a literal `- ` prefix**.
Removing the strays by hand triggered more #42 crashes. The corruption survived
save/reload, so it was persisted content, not a render glitch.

Investigation on this branch established:

- The reported step ("applied a header change") cannot produce the symptom. Header
  cycling only touches character-level `SpanStyle`s and round-trips cleanly.
- A **line-block toggle over a select-all** (the issue's own title) reproduces the
  symptom exactly: every line, blank separators and horizontal rules included, gets a
  marker, and parts of the result do not survive a save/reload.
- The concurrency race hypothesized in the issue was already closed by #48 (snapshot
  reads); it is not the mechanism here.

Three distinct defects were identified, of which only some produce true corruption:

| # | Behavior on `main` | Persisted corruption? |
|---|---|---|
| D1 | Sweep stamps a marker on every **blank line** (`- ` on each separator) | No: `- ` re-imports as an empty bullet, stable round trip. Severe noise the user must remove by hand, but not data loss. |
| D2 | Sweep stamps a marker on **rule/image lines**; export writes `- ---` | Yes: re-import matches the bullet pattern first, the rule span is destroyed, the text becomes the literal `---`, and the next export escapes it to `- \-\-\-`. Unrecoverable. |
| D3 | Import peels only the **outermost** stacked marker. Quote legitimately stacks with a list, so export writes `> - item`; import returns a quote whose *text* is `- item`, and the next save escapes the dash into the prose | Yes: pre-existing, independent of the sweep. This is the sharpest form of "a `- ` written into my text". |
| D4 | A quote span on a rule line exports as `> ---` (the prefix loop runs on rule lines), but import checks the HR token against the **raw** line, so `> ---` comes back as a quote whose text is the literal `---` | Yes: pre-existing on `main`, no sweep involved. Found while analyzing Mechanism B; same family as D2 but reachable through ordinary HTML paste (`<blockquote>a<hr>b</blockquote>`) followed by a markdown save. |

## 2. The model as it stood on `main`

### 2.1 Two-layer document representation

A document is:

- **Lines**: `List<AnnotatedString>`, each carrying character-level `SpanStyle`s
  (bold, header sizes, etc.) and `ParagraphStyle`s baked into the line itself.
- **Rich spans**: a copy-on-write `Set<RichSpan>` in `RichSpanManager`, each a
  `(TextEditorRange, RichSpanStyle)` pair. These are decorations drawn over or
  instead of the text (gutter bullets, quote bars, rule lines, images, spell-check
  underlines).

Both layers publish atomically through `TextEditorState.withAtomicEdit` (a draft
`DocumentSnapshot` committed as one revision), which is what #48 built.

### 2.2 What a "line block" is

A bullet / ordered-list / blockquote / code-fence line is a **bundle of three things
that must stay in sync** (`richstyle/LineBlockStyle.kt`):

1. A line-anchored `RichSpan` (`stickyAtStart = true`) whose style draws the gutter
   marker (dot, numeral, bar) or tints the fence card.
2. A `ParagraphStyle` indent baked into the line's `AnnotatedString`
   (`BULLET_LIST_PARAGRAPH_STYLE` etc.).
3. Optionally a baked-in `SpanStyle` (`CodeFence` bakes monospace).

`LineBlockStyle` bundles these with the two serialization hooks: `markdownPrefix`
(what export writes) and `markdownPattern` (what import recognizes). Registries:
`LINE_BLOCK_STYLES` (prefix styles: quote, ordered, bullet, in match-priority order)
and `ALL_BLOCK_STYLES` (adds `CodeFence`, which round-trips via ``` markers instead
of a per-line prefix).

Stacking rules live in `mutuallyExcluded(applied)`: the two list styles exclude each
other, fence excludes everything, quote stacks with lists.

### 2.3 Placeholder lines

Horizontal rules and images are a different species: the line's *text* is a single
space (`HR_PLACEHOLDER` / `IMAGE_PLACEHOLDER`) and the span **owns the whole line**,
drawing the rule or image instead of the text (`BlockSpanStyle.replacesText()`).
Nothing in the model prevented a line-block span from also sitting on such a line.

### 2.4 Operations

- `applyLineBlock(line, block)` / `demoteLineBlock(line, block)`: the per-line
  primitives. Apply is idempotent, demotes conflicting styles first, attaches span
  then rebuilds the line with the indent.
- `TextEditManager.toggleLineBlock(lines: IntRange, block)`: the sweep behind every
  toolbar button. On `main` it acted on **every line in the range**, direction chosen
  by `anyOff = lines.any { !hasLineBlock(it, block) }` (mixed selection turns all
  on). Records one atomic undo entry with per-line before/after content and spans.
- Smart editing: Enter at the end of a block line continues the block onto the new
  line (`applyLineBlock` on both halves of the split); Enter on an empty block line
  exits the list; Backspace at column 0 demotes before merging.
- Derived state: ordered-list numbers are **not stored**. `updateBookKeeping`
  (`TextEditorState.kt:916-1004`) walks the lines and increments a run position,
  resetting to zero at every non-OL line. Export does the same independently
  (`runPositions` in `exportAsMarkdown`). Fence grouping is likewise derived from
  contiguity, in export's open/close bookkeeping and in the renderer.

### 2.5 Serialization

Two importers/exporters share one attachment path:

- **Markdown export**: takes one snapshot (`DocumentBlocks`), walks lines, prepends
  `markdownPrefix` for each block style present on the line, converts the body via
  `toMarkdown` (which escapes markdown specials in plain text: a literal `- ` at the
  start of a plain paragraph exports as `\- ` and is safe).
- **Markdown import**: a pre-pass over raw lines. Fence stripping first; then per
  line: HR token check, standalone-image check, block-marker match
  (`main`: **first matching style only**, marker stripped, line index recorded);
  then the GFM parser over the residue; then `setText` (drops all spans) +
  `applyDocumentBlocks` re-attaches rules, images, and blocks via `applyLineBlock`,
  all in one revision.
- **HTML** (`HtmlExtension`): same shape. `containersFor(line)` derives
  `<blockquote>/<ul>/<ol>/<pre>` nesting from the same `DocumentBlocks` snapshot;
  `importHtml` funnels into the same `applyDocumentBlocks`.

The essential property: **the persisted form is markdown text; every block span must
round-trip through a textual marker that import can unambiguously reverse.** The
in-memory model was more permissive than the serialized form could express, and every
gap between the two is a silent-corruption site (D2, D3).

## 3. What we want (target properties)

Stated as properties of the system rather than patches to functions.

### P1. A validity definition, written down

Classify lines into three kinds: **content**, **blank**, **placeholder** (rule or
image, text owned by the span). A document is *valid* when:

- Placeholder lines carry no line-block span (nothing to decorate; not serializable).
- Per line, styles respect the stacking rules (at most one list style; fence alone;
  quote may stack with one list).
- Every block span is paired with its paragraph-indent on the same line.

### P2. Round trip is the contract

For every valid document: `import(export(doc))` preserves text and block structure,
and `export(import(text))` is stable after one normalization pass. This should be a
**property test** over generated documents (random lines, blanks, rules, images,
stacked styles), not a growing pile of example tests. Every corruption bug so far is
a violation of this property that an example test happened not to cover.

### P3. Marker parsing mirrors marker emission, via one rule set

Import's peeling and export's prefixing must be inverse images of the same stacking
rules (`mutuallyExcluded`). Import peels only combinations that can legally coexist
on one line: after peeling a list marker, only a quote could still be pending, never
the other list style. Anything the model cannot represent stays literal text
(protected by body escaping on the way back out).

### P4. Invariants enforced at one chokepoint

`main` enforced stacking inside `applyLineBlock` and nothing else anywhere. The
branch scattered more guards across three layers, and each guard's blast radius
surprised us. Choose one enforcement point:

- **Option A, guarded primitives**: `applyLineBlock` is the sole gate (rejects
  placeholder lines, resolves stacking); sweeps and importers trust it. Requires the
  primitive to know *why* it is being called (user toggle vs import re-attachment),
  which is what bit us with the HTML quote-around-`<hr>` case.
- **Option B, commit-time normalization**: primitives stay permissive; a
  normalization step at the `withAtomicEdit` commit boundary repairs violations
  (drop block spans from placeholder lines, resolve stacking). One place, sees the
  whole document, importer and toggle and smart-Enter all covered for free.
- **Option C, policy at the sweep only**: primitives and importers stay as `main`;
  only the user-facing toggle filters its target lines. Smallest surface, but
  invariants are then only conventions, and importers can still construct invalid
  documents (they do today: HTML can put a quote on a rule line).

### P5. Toggle semantics defined by user intent, per direction and per style

- **Clear direction: never filtered.** Removing a style from a selection removes it
  from every line in the selection. There must be no reachable state that a toggle
  cannot undo (the branch created two such states).
- **Apply direction: per-style policy**, because the styles genuinely differ:
  - *Fence*: contiguity is the point; every selected line joins the fence, blanks
    included (a code block containing a blank line must be expressible).
  - *Bullet / ordered / quote*: blank separator lines are arguably prose structure.
    Whether to skip them is a **product decision, still open** (see §5): Word and
    Google Docs turn blanks into empty list items; skipping them fragments ordered
    numbering and reads oddly on toggle-off.
- **Direction decided from the lines the apply-policy would act on**, but clearing
  still sweeps the full range.
- Policy keys off the **requested selection**, not the subset of it that happens to
  exist, so a selection dragged past end-of-document behaves like the same selection
  clipped.

### P6. Run-sensitive styles are handled run-aware

Ordered numbering and fence grouping are derived from contiguous runs. Any policy
that can fragment a run (skipping a line mid-selection) changes meaning, not just
appearance. Either policies must preserve runs for run-sensitive styles, or the
derived-state walkers must learn to bridge gaps (e.g. OL numbering continuing across
a blank line inside a list). Bridging is itself a model change with its own
serialization consequences; do not do it casually.

## 4. What we tried on this branch, and what broke

Branch commit `2704f6f`, reviewed at high effort (10 findings, 7 confirmed by
running the code). Three mechanisms were added; every confirmed regression traces to
one of them.

### Mechanism A: blank-line skip in `lineBlockTargets`

Intent: stop D1 (markers on every separator). Implementation: multi-line sweeps
filter to non-blank lines; single-line toggles exempt; both toggle directions and
all four styles go through the filter.

| Regression | Root cause |
|---|---|
| A block on a blank line survives a select-all clear and no multi-line toggle can ever remove it (Enter-continuation creates exactly such lines) | Filter applied to the clear direction |
| A selected code block containing blank lines becomes several disjoint fences; a fence spanning a blank line is no longer creatable by selection | Filter applied to `CodeFence`, which needs contiguity |
| Select-all ordered list over blank-separated paragraphs numbers `1. 1. 1.` | Filter fragments the run that numbering derives from |
| A sweep whose range extends past EOF collapses to `present.size == 1` and re-acquires the blank-stamping behavior on a trailing empty line | Single-line exemption keyed off surviving lines, not the requested selection |

Lesson: one filter for four styles × two directions was wrong on three of the eight
combinations it silently changed.

### Mechanism B: `acceptsLineBlock` guard (placeholder lines)

Intent: fix D2 (rule/image destruction). Implementation: predicate rejecting lines
carrying `HorizontalRuleSpanStyle`/`ImageBlockSpanStyle`, wired into **both**
`lineBlockTargets` and the `applyLineBlock` primitive.

| Regression | Root cause |
|---|---|
| HTML import of `<blockquote>a<hr>b</blockquote>` drops the quote from the `<hr>` line; export splits one quote into two around a bare rule, baked in on first save | Guard fires inside the primitive, which the HTML import path relies on to attach a quote to a rule line (valid in HTML, unrepresentable in our markdown) |
| A block span that *already* sits on a rule/image line (host apps can construct this via public `addRichSpan`) becomes permanently un-removable | Guard filters `lineBlockTargets` in both directions, including clear |

Also flagged: the predicate hardcodes the two styles instead of using the existing
`BlockSpanStyle.replacesText()` classification, so the next placeholder style would
silently lose the protection.

Lesson: the guard is right as an *apply-time* rule for user toggles, wrong as a
universal precondition. And it exposed a real modeling question: HTML can express a
quote containing a rule; our markdown layer cannot. Refusing the span (branch) splits
the quote; allowing it (main) corrupts the markdown round trip. Neither is correct;
the model has to pick a representation (e.g. quote-on-placeholder is valid in-memory
and markdown export handles it, or it is invalid and HTML import must normalize).

### Mechanism C: multi-marker peel (`peelLineBlocks`)

Intent: fix D3 (stacked `> - item`). Implementation: peel repeatedly, each style at
most once.

| Regression | Root cause |
|---|---|
| `- 1990. The year everything changed` peels `- ` then `1990. `, records both list styles; `applyDocumentBlocks` demotes one and the author's literal `1990. ` is silently deleted | "Each style at most once" is the wrong stop condition; the real rule is "peel only what can legally coexist", i.e. `mutuallyExcluded` |

What held up: the D3 diagnosis itself, the direction-from-acted-lines idea (right
idea, wrong filter set), body-text escaping as the safety net for plain lines, and
the regression tests for D2/D3 round trips.

### Adjacent known issues (out of scope, worth their own tickets)

- Export of a document whose last line is a header appends a trailing blank line
  that survives re-import (stable at +1).
- Toggling a style off after a blanket apply does not restore styles lines carried
  before the apply (undo does restore; conventional toolbar behavior).
- `> ---` (a rule inside a quote) imports as a quote line whose body is the literal
  `---`; related to the same representability question as Mechanism B.

## 5. Decisions

### Decided

1. **Blank lines under a multi-line apply: do not skip them.** Match Word/Docs:
   blanks become empty list items. D1 is declared a non-bug (it round-trips stably;
   the reporter's real data loss was D2/D3/D4). This deletes Mechanism A entirely
   and with it all four of its regressions. If empty-item noise proves to be a real
   product complaint, it returns later as a per-style apply policy (§3 P5), designed
   with run-awareness (§3 P6) from the start.
2. **Enforcement point: commit-time normalization** at the `withAtomicEdit` commit
   boundary (§3 P4 option B). The pass sees the whole draft once, right before
   publish, and covers every attachment path (toggle, both importers, smart Enter,
   host apps via the public `addRichSpan`, span re-anchoring after edits) without
   any path needing to know the rules. Precision on healing: normalization repairs
   invalid **span states**; it cannot restore text that already lost its span.
   First-generation corrupted saves (`- ---`) are healed by the import change below;
   second-generation escaped text (`\-\-\-`) is unrecoverable and stays literal.

### Rationale for decision 3: peel first, then classify

D4 shows `main` already corrupts quote-on-rule through markdown alone, so "keep the
permissive model and do nothing" is not on the table. The resolution makes
the model and the text form agree by fixing the **import order**: peel stacked
markers off the line first (by the grammar below), then classify the residual body
(HR token → rule placeholder + span; standalone image → image placeholder + span;
otherwise text). Consequences:

- `> ---` and `> ![alt](src)` become fully representable: quote **may** stack on a
  placeholder line. Export already emits this form today; only import must learn it.
- The HTML `<blockquote>a<hr>b</blockquote>` case stops being a conflict: the quote
  span stays on the rule line, both exporters render it correctly.
- List and fence spans on a placeholder line remain invalid (a bullet on a rule has
  no meaning in the model) and are stripped by normalization.
- A first-generation D2 save (`- ---`) heals on load: peel `- `, classify `---` as
  a rule, normalization strips the bullet span. The rule comes back.

The peel grammar mirrors emission exactly (§3 P3): styles peel in
`LINE_BLOCK_STYLES` registry order (quote, then one list style), each at most once,
and a style may only peel if it is not `mutuallyExcluded` with anything already
peeled. This is what fixes Mechanism C's `- 1990. The year` text loss: after a list
marker, the other list style is excluded, so `1990. ` stays in the body.

3. **Placeholder stacking: quote may stack on placeholder lines; lists and fences
   may not.** Import peels markers first, then classifies the residual body. `> ---`
   and `> ![alt](src)` become representable and round-trip; normalization strips
   only list/fence spans from placeholder lines.
4. **Branch mechanics: reset to `main` and rebuild clean**, force-pushed to PR #57.
5. **Property tests: yes.** Round-trip property testing over generated documents is
   the regression gate for this subsystem, alongside the example tests.

## 6. The plan as implemented

1. **Import pipeline** (`MarkdownExtension.importMarkdown`): peel markers in
   `LINE_BLOCK_STYLES` registry order, each style at most once, a style only
   eligible when compatible (not mutually excluded) with everything already peeled.
   This is a single forward pass that exactly mirrors the emission order. Then
   classify the residual body: HR token or standalone image makes the line a
   placeholder, recording the quote peel (if any) and dropping list peels; anything
   else keeps all peels and the body goes to the GFM parser.
2. **Commit-time normalization** (`TextEditorState`): every published revision
   passes through a pure `DocumentSnapshot -> DocumentSnapshot` step that removes
   list/fence spans from placeholder lines and rebuilds those lines without the
   orphaned indent. No-op fast path when the document has no placeholder lines.
   The `applyLineBlock` primitive stays permissive, as on `main`.
3. **Toggle** (`TextEditManager.toggleLineBlock`): eligibility is per style, from
   one span-set snapshot: every in-range line for `Blockquote`; every in-range
   non-placeholder line for the others. Blank lines are always eligible. Both
   directions act on the eligible set, and the direction is decided from it. This
   preserves "clear is never blocked" because the only excluded lines are ones that
   cannot carry the style at all.
4. **Tests**: example tests for each defect and each decision, plus a seeded
   generator-based round-trip property test (`export` then `import` then `export`
   must be stable, text and block placement preserved).
