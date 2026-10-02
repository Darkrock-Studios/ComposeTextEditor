# Decorations

A decoration is a view of the text, not part of it: a syntax highlighter's
colours, a linter's underlines, a search's matches. Decoration layers
(`com.darkrockstudios.texteditor.decoration`) are the public API for them: any host
can draw on one, and spell check, diagnostics and find each draw on layers of
their own.

## Rules

1. **Never content.** A decoration enters no undo step, no edit operation, no
   copy, drag, saved state or export, and does not advance the text revision.
   An undo does not bring back one an edit removed; its owner draws it again.
2. **Keyed by owner.** Each owner holds a `DecorationLayer` (compared by
   identity) and sets, replaces and clears only its own:
   `setDecorations(layer, spans)`, `replaceDecorations(layer, lines, spans)`,
   `replaceDecorations(layer, range, spans)`, `clearDecorations(layer)`,
   `decorations(layer)`. A span's style must be a `DecorationStyle` of that
   layer, so one owner can never remove another's.
3. **Only paints.** Setting a layer lays out no line, normalizes no line block
   and re-resolves no row, however many lines it covers.
4. **Follows the text.** A decoration is a rich span in the document's span
   index, so edits around and inside it move it as any span, until its owner
   replaces it.

## Storage

Decorations live in the span index beside content spans (see
`incremental-relayout.md`, 9.4), keyed per line. A line's lookup costs that
line's spans, and an edit re-anchors only the spans on the lines it touched. A
span crossing a line break would be kept loose and re-anchored on every edit,
so the API splits one into a piece per line as it is set.

`swapPaintOnlySpans` applies a change in one transaction: the index is rewritten
once (`SpanIndex.swapping`, each touched line filtered and appended to once),
the publish marks no line for block normalization, and the layout update names
no line, so the rows are only pointed at the new index. `updateRichSpans` takes
the same path when every span it is given only paints (`paintsOnly`: a
decoration that anchors, shapes and numbers no line).

`DecorationBenchmark` (run with `CTE_BENCHMARK=1`) times 5,000 lines of code
with a colour on every token, 45,000 spans, on the Linux desktop: setting the
whole layer takes about 10 ms and clearing it 8 ms, replacing one line's spans
17 us, and a keystroke costs what it costs without them (about 0.2 ms). No line
is shaped (`DecorationCostTest` counts it).

## Text colour without layout

`DecorationStyle.textColor` recolours the text under a decoration. Shaping the
colour into the line would lay the line out again; so would drawing the line a
second time in another colour, since Compose's Skia paragraph rebuilds or
relayouts when its paint colour changes. Instead the text is drawn once, as it
is, inside a layer, and each coloured stretch is painted over with
`BlendMode.SrcAtop`, which takes the rectangle's colour and keeps the glyphs'
coverage, antialiasing included (`DrawTextTint.kt`). The layer is opened only
when a row in view has a coloured decoration, once per frame, and holds the
text alone: the text is drawn in its own pass, after the host's `decorateLine`
and before every foreground span and the composing underline, so a gutter or a
margin mark is neither clipped by the layer nor tinted. With wrapping off
the layer and its tints are opened inside `inContentSpace`, so they scroll
sideways with the text and stop at the canvas's edges.

Consequences:

- A tint wins over a colour the text has of its own.
- A rectangle fills whatever is under it in the layer, so text with a
  background of its own (a span style's, such as inline code's) is left out of
  the stretches and keeps its colours.
- A tint would flatten a colour glyph into a silhouette, so emoji are left out
  too: pictographs outside the basic plane, those inside it shown as emoji by
  default, characters an emoji presentation selector follows, and the joiners
  and keycap marks. A heuristic: a few symbols some fonts draw in colour are
  tinted.
- Where two decorations colour the same text, the one drawn last wins; the
  order between layers is not defined.
- A glyph whose ink overhangs its advance keeps the base colour on the
  overhanging part.

The tint costs the frame: about 1 ms with no decoration, 3.6 ms with every token
of 40 lines in view tinted, on the benchmark's software canvas. A third of the
difference is the rectangles, which a GPU canvas fills far faster; most of the
rest is finding each stretch's boxes.

## Spell check, diagnostics and find

Spell check, diagnostics and find each draw on a layer of their own and
read their marks back with `decorations(layer)` or `decorations(layer, lines)`,
which build only their own spans, never the whole span set
(`getAllRichSpans()` builds and hashes every span after any change). Their
styles are `DecorationStyle`s of that layer, so none clears another's or a
host's.

Spell check's layer is `SpellCheckStyle`'s, and diagnostics' is
`DiagnosticStyle`'s default, each shared by every state of its kind, as their
styles were matched by class before: a state made for an editor takes over the
marks an earlier one left, and a style a host makes without a layer is replaced
and cleared as before. Each `FindState` has a layer of its own, as its styles
were matched by identity.

A recheck or an invalidation reads its own flags on the lines it covers,
keeping the flags touching the range (not only those sharing a character with
it, as `replaceDecorations` by range takes), and swaps them through
`updateRichSpans`, which takes the paint-only path, as does a full check for
the whole layer. `SpellCheckBenchmark` (5,000 lines, two flags, two diagnostics
and three other highlights a line): a diagnostics refresh after an edit 6.1 ms
to 3.9 ms. A spell recheck after an edit was already line-local (about
0.17 ms), and a full check's 26 ms is its scan, so neither moves.

In find, a search lays again only the lines whose highlights differ from the
matches, so after an edit that is the edited lines; stepping to the next match,
with the text unchanged since the highlights were laid, swaps the two lines the
current highlight leaves and reaches. The in-selection scope, which crosses
lines, is added whole (`addRichSpan`) rather than split per line, so it follows
edits as one range. `FindBenchmark` (5,000 lines, 15,000 matches, 10,000 other
spans): an update after an edit 7.2 ms to 3.5 ms, a step 5.3 ms to 20 us.
`DecorationLayerCoexistenceTest` checks the owners leave each other alone and
read no other's spans.

## Looks

`Decoration` is a ready-made look: a text colour, a background (drawn behind
the text) and an underline (solid, wavy or dotted). It carries no data and is
not hit-testable, so a click passes to what it covers. A host that wants a
click to read something (a diagnostic's fixes) implements `DecorationStyle`
itself.
