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

Adding a new block style is one instance, not changes to apply, demote,
toggle, Enter, and Backspace separately, plus one row in each format's syntax
table: the markdown module keeps a block's prefix (what export writes) and
pattern (what import recognizes) per block span style
(`MarkdownBlockSyntax`), ordered by core's `LINE_BLOCK_STYLES`, so it peels
markers in the order core resolves a stack.

The registry per style configuration (`RichTextStyles`): the *prefix blocks*
(blockquote, the six heading levels, ordered list, bullet list) round-trip
through a single-line prefix (`> `, `# `, `1. `, `- `), in match-priority
order. Code fence is a *wrap block*: it round-trips through ``` markers around
a contiguous run and is handled out-of-band by the importer and exporter.

## Stacking rules

Which blocks may share a line is defined in one predicate (`lineBlocksConflict`, public so an importer peels by it):

- The two list styles exclude each other.
- Headings exclude each other and both list styles (`- # item` is a bullet
  holding literal text, not a bulleted heading).
- Blockquote stacks with lists and headings (`> - item`, `> # Title`).
- A task stacks with a list item, whose box it is, and a quote; it takes no
  heading, and its two states exclude each other (see "Task lists").
- Code fence stacks with nothing, and its text is code: it holds no inline style or
  link, which markdown cannot write inside a fence. An edit does not put one there:
  a style added over a fence line skips it, text landing on one keeps only the
  fence's monospace and the body style, and what a toggle or join brings onto one is
  taken off inside the same undo step. HTML import keeps none inside a `<pre>`.
- A table cell stacks with nothing, and unlike the others it never gives way:
  putting another block on a cell line does nothing (`docs/design/tables.md`).

One resolution point (`resolveLineBlock`) turns the predicate into an actual
demotion and rebuild: applying a block first demotes whatever conflicts with
it, then rebuilds the line with the new indent. The per-line toggle and the
importers both resolve through it, so a stack of blocks produces the same line
whether the user typed it or an import placed it.

## Nested lists

A list line carries its nesting level in its span style: `BulletListSpanStyle.of(level)`
and `OrderedListSpanStyle.of(level)` are per-level singletons (identity-compared,
like heading levels), levels 0 to `MAX_LIST_LEVEL` (7), and the bare names
`BulletListSpanStyle`, `OrderedListSpanStyle`, `BulletList` and `OrderedList`
are level 0. The registry holds a `LineBlockStyle` per kind and level; the
paragraph indent grows by one gutter per level, and the marker anchors to the
text's left edge as before, so drawing follows the indent. Bullets cycle disc,
circle, square by level, as browsers and Google Docs draw them; numbers are
decimal at every level, as CommonMark renderers show them, and count per
level: a level-k item continues its level's run, restarts every deeper level,
and a bullet or a non-list line at a level ends the run at and below it.

A line has one list block at one level (the stacking rule "the two list
styles exclude each other" holds across levels). **Orphans:** markdown can
place a level-k item only after, skipping blank lines, a list line at level
k − 1 or deeper with the same quote status; any other line, or a change of
quote status, ends the nesting. The model does not enforce this, on purpose:
deleting a parent line leaves its children where they are, as Google Docs and
Word do, and a normalization clamp would sit outside undo history, so undoing
that delete could not bring the children's levels back (the one repair that
was tried and rejected). An orphan is therefore the one state the model
allows that the text form cannot hold, a deliberate exception to rule 1:
export writes it at the level its predecessor allows, so it reloads one
level shallower, a mild and visible change rather than a silent one. The
edit paths keep followers valid inside their own undo step so the exception
is rarely reached (`recordListEdit` in `richstyle/ListNesting.kt`, one undo
step with the followers it touches): nesting a line (Tab) leaves its
followers where they are, its former children now its siblings, as Google
Docs does; un-nesting a line (Shift+Tab), making it body text (toggle,
heading, Backspace at level 0), re-quoting it or exiting the list brings the
items nested under it up to what it now allows, the subtree moving together
and ending at its first sibling; quoting nested items brings them up to what
the items before them in the quote allow. A selection moves only its own items, as in
Docs; the items under the last of them follow it.

**Markdown.** Export indents a level-k item by the content offset of its
level-(k − 1) ancestor: two columns after `- `, the marker's width after
`1. `, tracked down the walk, so a child of `10. ` starts four columns in.
Import peels the quote, reads the leading spaces (a tab counts four), and
resolves a marker's level from the open ancestors: the level is the number of
enclosing items whose content offset the indent reaches, the item's own
content offset is its indent plus its marker and one space, a deeper indent
than the maximum clamps, and a non-list non-blank line or a change of quote
status closes every open item. A marker shape at the start of an item's body
(`- 1990. plans`) stays literal, one marker per line, as before.

**HTML.** Export writes a nested item's list inside its parent's `<li>`
(`<ul><li>a<ul><li>b</li></ul></li></ul>`): each item is a container that
stays open while the lists under it are written. An item nests under the
nearest open item at a shallower level, so an orphan is written one below
the item before it and a copied selection that starts at a nested item keeps
its items' nesting. HTML cannot hold another line inside an item, so any
line that is not a list item, a blank one too, and any change of quote,
closes every item: unlike markdown, which looks through blank lines, an item
after a blank line starts a new list at the top level. Import gives a `<ul>`
or `<ol>` the depth of the lists holding it, whether it sits inside an item
or directly inside another list, as browsers render both; its `<li>`
children take that level, and an `<li>` that opens with a nested list is an
empty item line of its own.

**Editing** (Google Docs, Word, Notion and Apple Notes agree on these): Tab
at the start of a list item nests it one level, never deeper than one below
the item above it, and a multi-line selection nests each selected item where
allowed; Shift+Tab un-nests one level; Enter continues the list at the same
level; Enter on an empty nested item un-nests it, and on an empty top-level
item ends the list; Backspace at the start of a nested item un-nests it, and
at a top-level item makes it body text. Tab inside an item's text inserts the
indent text, as Word does, and so does Tab at the
start of a list's first item, which has nothing to nest under. Toggling a list
kind onto a line that is the other kind keeps its level; onto body text
starts at level 0.

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
every publish at the `withAtomicEdit` commit boundary, over the lines the
revision changed since the last publish (the state narrows that range with
every mutation) and, when a span was added, removed or lost or a line came or
went, the fence runs those lines touch. It removes disallowed block spans from
placeholder lines and rebuilds those lines without the orphaned indent. Because it runs at the one point every revision passes
through, the invariant holds no matter which path attached the span: a toggle,
either importer, smart Enter, a host app on the public span API, or span
re-anchoring after an edit. The repair is deterministic and outside undo
history, and since only blank lines classify as placeholders, the most it can
ever discard is a marker on empty content.

After it, `repairBlockStyles` gives each of those lines exactly the
paragraph styles its markers want, each over the whole line; a paragraph style
no block uses passes through. A line's text and its markers move separately: a
join keeps one line's markers while each piece brings its own indent over its
part, a split carries the indent onto a line the marker stays off, emptying a
line drops the indent while the marker stays, and a paste lands its pieces'
indents before their markers. Compose lays out each paragraph style run as a
paragraph of its own and rejects a line where two overlap, so the markers
decide. A run of a block's paragraph style no marker asks for goes, a host's
own `ParagraphStyle()` included, since that is a heading's.

The same repair bakes each block's text style (a heading's look, a fence's
monospace) over the whole line, after any run of the body style so the
heading's size wins, and before the spans the user set inside it. It never
strips a text style no marker asks for, since a span equal to a heading's look
on a plain line is the user's own. The edit that moves text strips instead:
text landing on a line its own markers do not reach (a join's tail kept after
another line's head, a split's tail, text a replace across lines or a breaking
replace inherits) leaves its source line's blocks' text styles behind, and the
markers where it lands bake theirs. A heading whose style equals an inline
style (`header4Style = boldStyle`) bakes `RichTextStyles.headingLook`, that
style with the default platform style, which draws nothing but keeps it
unequal: the span model merges equal styles, so a look equal to the user's
bold would take it along wherever it went. A fence's monospace equal to an
inline style cannot be told from the user's own, so it is neither baked by the
repair nor left behind by a move.

## Serialization

**Export** walks lines from one snapshot, prepending each block's
prefix in emission order, then converting the body with markdown
escaping. Escaping is the safety net for plain text, applied only where a
character would start or end syntax in its position (`markdownEscapes`): a
literal `- ` at the start of a plain paragraph exports as `\- ` and survives,
`*not*` in dialogue is escaped by CommonMark's flanking rules, and an
apostrophe, a hyphen mid-sentence, an underscore inside a word or an asterisk
between spaces is written as typed. Emphasis whose delimiters could not open
or close where they stand, by the same rules (`**Note:**text`), or would not
pair as written by CommonMark's own pairing pass, is written as `<em>`,
`<strong>` or `<del>`, which import reads back; so is a strike or highlight
with whitespace at an edge, where it shows (bold and italic shrink onto their
text), and code another style covers is written as `<code>`. An indent stays
entities inside a style that opens in it. Whatever is styled in the editor
reads back as the same text and styles (`StyledRoundTripTest`). Line-start rules read
the body, so a marker shape at the start of a list item's body
(`- 1990. plans`) is escaped as well, since it would otherwise nest a list. Unsupported syntax kept as
literal text on import (a task list's `[ ]`, a table inside a quote) is written
back as it was, and a quoted table's rows are kept together. A table outside a
quote is a block of its own, read and written whole (`docs/design/tables.md`).

**Import** runs peel-then-classify on each raw line, after fence stripping. An
indented code block, a run of lines indented four columns where no paragraph or
list item takes them, is stripped as a fence is, its indent off; export never
writes one, since it writes a leading indent as entities.

1. **Peel** stacked markers in registry order, each style at most once, a
   style eligible only while it does not conflict with anything already
   peeled. This single forward pass exactly mirrors emission: after a list
   marker peels, the other list style is excluded, so `- 1990. The year`
   keeps its literal `1990. ` in the body.
2. **Classify the residue**: a horizontal-rule token or standalone image makes
   the line a placeholder carrying the compatible peels (the quote, and for an
   image one list style); anything else keeps all peels and the body goes to
   the markdown parser.
3. **Parse each block alone**: a paragraph's or list item's lines, with the
   lazy and indented lines that go on them and a setext heading's lines, are
   one parse, so inline syntax pairs only inside its block and an HTML block
   ends with it. Fenced lines and placeholders go into the text unparsed. A
   paragraph keeps a line per source line, but a list item, which has one line,
   takes its lazy and indented lines onto it, their line breaks spaces (under
   `BLANK_LINE` only: single-newline export wrote an item and a plain line after
   it so), and so is a code span across lines, which CommonMark reads as one line.

HTML import and export share the same block attachment path and derive their
container nesting from the same snapshot walk, so both serializers agree on
what a line's blocks are.

### Paragraphs

An editor line is a markdown paragraph. CommonMark joins adjacent lines into
one paragraph and reads a line after a list item or a quote as its
continuation, so writing lines with single newlines, though the editor's own
importer reads them back, makes every other renderer merge them. Under
`MarkdownConfiguration.paragraphSeparator = BLANK_LINE` (the default) export
puts one blank line after every block (a paragraph, heading, rule, image, or
the last line of a list or fence) except between the items of one list and
the lines of one fence, which stay together; inside a quote the blank line is
a bare `>` so the quote continues. An editor's own blank line is then written
as itself after that separator, so k blank editor lines between two blocks
are k + 1 blank lines in the file, and none is one. Import is the inverse:
after each block (a fenced line, or any line that is not blank; a bare `>`
line is blank) the one blank line export would have written there is left
out, and the rest are the editor's. Import reads the same line kinds as
export, so it leaves out only what export writes: nothing between two fenced
lines, a bare `>` only between two quoted lines, an empty line otherwise.
Between two list items it leaves one
out too, which export never writes there: a single one is CommonMark's loose
list, whose items are one list. The mapping is a bijection on the editor's
own output, so the round trip is exact; a foreign file's single blank line
between two fences or quotes stays and keeps them apart, its single soft break still imports as two lines and is
written back as two paragraphs, and its extra blank lines beyond the first
are kept as editor blank lines. A blank line is a line with blank text and no
block but a quote: an empty list item, heading or fenced line is a block.
`NEWLINE` keeps the old rule, a line per source line, for documents that
must not change on the next save; `importMarkdown` also takes the rule to
read one file by, for a host opening files written under the other.

### Leading indent

A line's leading spaces and tabs (Tab on a plain line inserts four spaces)
have no markdown form of their own: four spaces or a tab open
an indented code block where a block can start, which since every line is a
paragraph is every line, and a paragraph drops up to three. Export writes
each leading space as `&nbsp;` and each leading tab as `&emsp;` (a `&#9;`
is a tab, which HTML collapses), in a line's body after its block prefixes,
whenever the line holds more than whitespace; a line of only whitespace is a
blank line, as before, except in a list item or heading, which writes a
body of only spaces and tabs as entities (`- &nbsp;`), since CommonMark
reads a marker followed by whitespace alone as an empty item. Renderers
show both entities as space, and an entity is not whitespace to the block
parser, so what follows it is not at a line's start: it needs none of the
line-start escapes (`&nbsp;&nbsp;- item` is prose), and a delimiter after
it flanks as it does after punctuation (the entity's `;`), so
`*"quoted"*` still opens emphasis there and a lone `*` is escaped where it
could close one. The entities are always the line's first characters: a
style or link over an indent starts after it, and one ending in the next
line's indent closes at the end of the line before, so an indent's own
styling (an underline under it) is not kept.

Import reads a line's leading run of space and tab entities, after any
block prefixes, back as the spaces and tabs: `&nbsp;`, `&#160;`, `&#32;`
and their hex and named forms as spaces, `&emsp;`, `&#9;` and `&Tab;` as
tabs. Through the parse each stands in as a Unicode punctuation character
the file does not contain, as the entity's `;` stands to a renderer, and
becomes the whitespace again afterwards, one character for one, so no span
moves and no character the file holds is mistaken for one. An entity
elsewhere on a line stays literal text, as before, a typed `&nbsp;` is
escaped (`\&nbsp;`) by the prose escaping rules, and fenced lines keep their
whitespace as written. A foreign line of only such entities (a spacer)
reads as an empty line, unless it is a list item or heading, which holds
the entities' whitespace. Raw leading spaces and tabs on a foreign
paragraph's line drop on every line, the first's and each continuation's, as
CommonMark strips them; export never writes them, so this touches only foreign
files. A line of only whitespace stays, and a fenced line keeps its
whitespace as written. Rejected: a non-breaking space character, which
is invisible in the file and reads back as content rather than indent; and
no form (stripping the indent), which loses text on every save.

### Fence languages

A fence's info string (` ```kotlin `) is not a line block: it belongs to the
run, and the text form holds exactly one per fence. It lives in a
`CodeFenceLanguageSpanStyle` span on every line of the run, as the fence
marker itself does, attached by import off the undo history like the blocks,
written after the opening marker by export from the run's first line, and read
or set (for the whole run, one undo step) through
`TextEditorState.codeFenceLanguage` and `setCodeFenceLanguage`. One span per
line is what lets the language survive whatever the fence survives: a split at
the run's first line, a join with the line above, fencing the line above,
un-fencing the first line, splitting a run in two. Each leaves some line of
the run with the language, and normalization gives the run's language (its
first line's, or the first found down the run) to every line without one and
drops any span off a fence; undoing the edit leaves it likewise, and a
toggle's undo snapshot includes the line's language span. A line already
holding a different language keeps it: the text form holds one info string
per fence, so joining two runs writes the first run's, but undoing the join
gives the second run its own back, and un-fencing the first run's lines makes
the second's the run's. A language holding a backtick or a line break cannot
be written and is dropped.

### Fence markers

Export writes a run's fence one backtick longer than the longest backtick run
a line of it starts with, so no line inside closes it early. A fence closes
only at a marker with nothing after it, and an empty one is one empty fenced
line. A fence a foreign file opens inside a quote or a list item, which a fence
cannot stack with, is read as code out of its container: its lines lose the
container's markers and indent, the fence ends with the container, and the
item or quote line that opened it is dropped.

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

- Enter at the end of a block line continues its blocks onto both halves of the
  split, except a heading: Enter at a heading's end, empty or not, opens a body
  line after it. A split inside a heading keeps both halves headings. The
  markers Enter sets are recorded with the split, so a redo restores them.
- Enter on an empty block line other than a heading exits the block instead
  (an empty quoted list item leaves the list and stays quoted), routed through
  the toggle so the demotion lands in undo history.
- Line breaks that arrive any other way (a paste, a replace, find and replace,
  typed or IME text holding a break) continue the broken line's blocks onto the
  new lines the same way, recorded in the edit's own undo step (an undo never
  continues a block onto the text it restores); a heading continues only when
  the break falls inside its text. A replace across lines leaves its last line
  the blocks of the line its tail came from.
- Backspace at column 0 of a block line demotes first (marker off, content
  kept); a second backspace merges. Exception: when the previous line carries
  the same block, backspace merges directly, so joining two adjacent items is
  one keystroke.

## Task lists

GFM's task list items, `- [ ]` and `- [x]`, are list items with a box. A task
is a line marker of its own, `TaskSpanStyle.UNCHECKED` or `CHECKED`, on a
bullet or ordered item at any level. Its indent adds to the list's, and the
box is drawn in the room it makes: in place of a bullet, after a numeral.

- **A task needs a list.** `TaskKind`'s repair takes a task off a line that is
  no list item, so a list toggled off, an item left with Enter, or a host's span
  leaves no stray box. `toggleTaskList` makes plain lines bullet items first.
- **Editing.** A click or a tap on the box checks or unchecks it, one undo step,
  except in a read-only editor; elsewhere on the line it places the caret as
  ever. The `ToggleTask` action does it for the caret's or the selection's tasks,
  bound to no key by default (Ctrl+Enter, which apps use for it, is a host's),
  and an accessibility action, "Check task" or "Uncheck task", for the caret's.
  Enter after a checked item starts an unchecked one (`LineBlockStyle.
  continuesAs`); on an empty task it leaves the list. Backspace at an item's
  start takes the box off first, whatever the item follows or is nested in.
- **API.** `toggleTaskList`, `setTaskChecked`, `toggleTaskChecked`,
  `taskCheckedAt` and `isTask`.
- **Markdown.** Import reads `[ ]`, `[x]` or `[X]` and then whitespace or the
  line's end at the start of a list item's body; export writes `[ ] ` or `[x] `
  after the item's marker, and escapes an item's text that would read as a box.
  Typing `[ ] ` or `[x] ` at a list item's start makes it a task.
- **HTML.** A task exports as GitHub writes one, an `<li class=
  "task-list-item">` led by a disabled checkbox. A checkbox in a list item, or
  Google Docs' `<li role="checkbox" aria-checked>`, imports as a task.

## Derived run state

Ordered-list numerals and fence grouping are not stored. Layout derives them
from contiguous runs of same-styled lines (the numbering run position and the
fence-card edge each `LineWrap` carries), and export derives them again
independently. This is why rule 5 exists: anything that fragments a run
(a skipped line mid-selection) renumbers a list or splits a fence, and
bridging runs across gaps would be a model change with serialization
consequences of its own.

## Known limitations

- A nested `> > ` quote imports as one quote; only lists nest.
- Exporting a document whose last line is a heading appends a trailing blank
  line that survives re-import (stable at one extra line).
- Toggling a style off after a blanket apply does not restore the styles lines
  carried before the apply; undo does. This matches conventional toolbar
  behavior.
- A table cell's spaces at either end are not written: GFM trims them.
- An ordered task item's numeral shares the gutter with the box, so a numeral of
  three digits or more runs into it.
- A fence language filled in by normalization is outside undo history: joining
  a fence that has a language with one that has none tags the second with the
  first's, and undoing the join leaves that tag in place.
