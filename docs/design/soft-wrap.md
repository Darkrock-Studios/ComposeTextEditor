# Soft wrap off and horizontal scrolling (7.41)

With wrapping on (the default) every line wraps at the viewport's width and the
editor scrolls vertically only. With wrapping off a line stays one row however
long it is, and the editor scrolls sideways too: what a code editor needs.
`EditorLineLimits.SingleLine` always works this way, as `BasicTextField`'s single
line does: one row that follows the caret sideways.

## Public API

- `softWrap: Boolean = true` on `BasicTextEditor`, `TextEditor` and
  `SpellCheckingTextEditor`, as on `BasicTextField`. `SingleLine` turns it off.
  The layout is the state's, so, like the single-line limit, wrapping is off
  while any editor showing the state has it off.
- `TextEditorState.horizontalScrollState`, a second `TextEditorScrollState`
  beside `scrollState`: 0 shows the content's left edge, and its maximum is the
  widest line (plus room for the caret) less the viewport's width. With wrapping
  on its range is empty.
- What is documented as view coordinates now accounts for the horizontal scroll
  as it does for the vertical: `getPositionForOffset`, `calculateCursorPosition`,
  `CursorMetrics` and `lastCursorMetrics` subtract it, `getOffsetAtPosition` adds
  it. With wrapping on nothing changes.
- A `decorateLine` decorator gets the offset the line's text is drawn at, which
  moves with the horizontal scroll, and is not clipped: a gutter drawn to the
  left of the canvas places itself by its own x, not by `offset.x`.

## Two spaces

Content space has x from the canvas's left edge at scroll 0 and y from the top
of the first line; a row's `LineWrap.offset` is in content space (its x is 0).
View space is the canvas's: content less the two scroll values. Pointer events,
popups, input method rectangles and accessibility are view space; rows are
content space.

The horizontal scroll is applied in few places, not at each of the call sites
that apply the vertical one:

1. **State conversions.** The functions above convert between the spaces, and
   every consumer that goes through them inherits the offset: the touch handles
   and their popups (which hide when their anchor leaves the visible bounds, now
   sideways too), the touch toolbar and context menu, the magnifier, the desktop
   and web input method's caret rectangle, Android's cursor anchor (whose watch
   reads the caret, so a sideways scroll sends a new one), drag and drop's drop
   caret, and the stylus.
2. **One drawing transform.** `DrawScope.inContentSpace(state) { }` translates
   by the horizontal scroll, widens the scope's `size` to the content width (so
   what spans the full width, a code fence card, a blockquote's background, a
   rule, spans the widest line), and with wrapping off clips sideways to the
   canvas, which is otherwise unclipped. The text with its rich spans and
   composing underline (`DrawEditorText`) and the selection (`DrawSelection`)
   draw inside it, in content x and view y as before. The caret and the drop
   caret are drawn from view-space metrics outside it, and are not drawn when
   scrolled out of view sideways (wrapped, a caret past the right edge is still
   pulled back inside). Line decorators run outside it.
3. **The few places that pair raw rows with a pointer point** add the scroll by
   hand: the hit test in `textEditorPointerInputHandling` (`characterAt`), the
   magnifier's clamp to the row, the handwriting gesture layout, the vertical
   goal x (a column in content x, so Up and Down keep it across a sideways
   scroll), and the skiko input method's text origin.

## Layout with an unbounded width

With wrapping off `LineShaper` measures with `softWrap = false` and
`Constraints(minWidth = viewport, maxWidth = Infinity)`: a long line keeps its
natural width, and a short one the viewport's, so a right-to-left or centred
line aligns within the viewport as before. A width change still reshapes every
line lazily (7.48), for that alignment, and so does turning wrapping off; until a
line is reached it keeps its old width, as it keeps its old height, so the range
grows as the reshape settles.

Each `LineLayout` records its width when it is shaped: its text's extent from the
side the text starts on to the row's end with its trailing spaces, whatever width
it was laid out at, so a line not yet reshaped after a width change still has the
right width. `RowList` keeps the widest line
the way it keeps the tops: each `Chunk` holds the widest of its lines, computed
when the chunk is built, and the directory holds a running maximum per chunk,
rebuilt from the first touched chunk on a splice, as the tops are. The content
width is the last entry: a keystroke costs what the directory already costs
(7.8), and nothing scans the lines. A line shaped with wrapping on records no
width, so wrapping on adds one float per chunk and no range.

`TextEditorScrollManager.updateContentWidth` sets the range: from 0 to the
content width plus one space's width (`lineBreakWidth`, room for the caret and
a selected line break's sliver past the widest line), less the viewport width.

## Keeping the caret in view

`ensureCursorVisible`, `scrollToCursor`, `snapCursorVisible` and
`scrollToPosition(offset)` bring the caret's x into view as well as its row,
moving just far enough, as `BasicTextField` does: typing past the right edge
scrolls by what was typed, End scrolls to the line's end, Home back to 0. Both
axes animate in the one scroll job, so stopping it stops both. Whether the caret
is in view (a resize keeping it there) checks x too.

## Input and the scrollbar

A second `Modifier.scrollable(Orientation.Horizontal)` on the horizontal state,
enabled with wrapping off. Desktop Compose turns Shift and the wheel into a
horizontal delta, a trackpad sends one, and a touch drag goes to whichever
orientation passes the touch slop first. Content space is not mirrored in a
right-to-left layout, and bare `scrollable` (unlike `horizontalScroll`) does not
flip for one, so the direction is not reversed.

On desktop and the web Compose's `HorizontalScrollbar` lies over the bottom
edge of the text while the content is wider than the viewport; a single line
has none, as `BasicTextField` has none. Android and iOS show no sideways
indicator. A selection drag past the left or right edge auto-scrolls sideways at
the vertical one's speed, with the dragged point held inside the viewport.

## Platforms

The skiko input method's text origin subtracts the scroll; its document layout
(iOS's floating cursor) and the semantics text layout are measured unwrapped.
Neither carries the scroll offset, as neither carries the vertical one.

## Known limits

- The horizontal scroll is not saved with the state, unlike the first visible
  line.
- `RichTextView` always wraps.
- A visible line is drawn whole, so a very long line costs all its glyphs each
  frame (Skia clips them).
- Compose's `Constraints` cannot hold a width past 262,142 pixels, so a line
  wider than that (some 30,000 characters) wraps there.
- An unbounded measure reads the line's intrinsic width, a second Skia layout
  pass over the shaped text, which the tight wrapped measure skips.
