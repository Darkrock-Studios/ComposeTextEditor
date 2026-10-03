# Screen-reader character bounds from the editor's rows

How screen readers get character bounds from the editor's rows, which
`GetTextLayoutResult` alone cannot give them: the semantics text layout (`SemanticsDocument` in `EditorSemantics.kt`) starts at the
first row's top and the text's left edge, so its character bounds miss the content
padding, the space above the first paragraph and the scroll offset, and a text edit
shapes the whole document again on the next request (about 100 ms at 200k characters
on desktop JVM, going by the cost of a whole reshape). A `TextLayoutResult` cannot
be offset and cannot be put together from the editor's per-line layouts, so the fix is
a second channel that answers from the rows, and the layout stays only where a
platform has no other seam. Compose Multiplatform 1.12.1 sources were read for this.

## Who asks for character bounds, and how

**Android** (`AndroidComposeViewAccessibilityDelegateCompat`). Two readers of
`GetTextLayoutResult`, both on the unmerged config of the node that has the text:

- `addExtraDataToAccessibilityNodeInfoHelper` answers
  `EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY` with `getBoundingBoxes(node, start, length)`:
  one `RectF` per character of the requested range, `getBoundingBox(i)` translated by
  `layoutNode.innerCoordinator.positionInRoot()`, clipped to the node's visible bounds
  (null when outside), then to screen. The key is offered in `availableExtraData` only
  while the node contains `GetTextLayoutResult` and has text. The request is a range,
  not the whole layout; the framework caps it at 20,000 characters. TalkBack asks per
  granularity move when something draws from it (magnification following the reading
  cursor, a braille display), Select to Speak asks per sentence it highlights. Creating
  the node info itself does not touch the layout.
- `getIteratorForGranularity` asks for the whole layout for LINE and PAGE granularity
  moves (`AccessibilityIterators.LineTextSegmentIterator`), on each move. Character,
  word and paragraph moves do not.

The inner coordinator detail matters: Compose translates by the layout node's innermost
coordinator, so the semantics node's own bounds and the translation are different
points of the same node. Our semantics modifier sits on the outer, unpadded box in
`BasicTextEditor`; the padding is on the canvas box inside it and the scroll is a draw
translation, so neither reaches the coordinator.

**iOS**. The accessibility element (`Accessibility.ios.kt`) reads
`GetTextLayoutResult` for nothing but the static-text trait; its frame is the node's
bounds. Character geometry goes through UIKit's `UITextInput` on the first responder,
CMP's `NativeTextInputView`: `caretRectForPosition`, `selectionRectsForRange`,
`firstRectForRange` and `closestPositionToPoint` are served by the internal
`NativeTextInputConnection` from the input session's `textLayoutResult`, translated by
`unclippedTextOffsetInRoot`. The editor's `SkikoTextEditorInputMethodRequest` serves a
plain whole-document layout (`DocumentTextLayout`, the base style only) and the canvas
origin less the scroll, so padding and scroll are right there; the space above the
first paragraph is not, and a heading or an indented line is measured as plain text.
UIKit reads the layout on every text or selection change (`onTextFieldValueUpdated`
calls `onViewGeometryUpdated`), so iOS pays one whole-document shape per keystroke
today, VoiceOver or not. VoiceOver uses this geometry for the cursor outline on the
insertion point and selection while the editor is focused; the legacy
`ComposeTextInputView` answers empty rectangles.

**Desktop** (`ComposeAccessible.kt`, the Java accessibility bridge: NVDA and JAWS
through the Access Bridge, VoiceOver on macOS, Orca). `ComposeAccessibleText`
answers `getCharacterBounds(i)` with `getBoundingBox(i)` and `getIndexAtPoint` with
`getOffsetForPosition`, both untranslated, and asks `GetTextLayoutResult` on every call
(no caching upstream). `AccessibleText` bounds are relative to the accessible's
location, the node's position in root, so this is the only platform that reads a
`TextLayoutResult` for bounds and offers no other seam. NVDA asks for the caret's
bounds after every caret move.

**Web** (`ComposeWebSemanticsListener`). The node is mirrored into a hidden DOM
element with `contenteditable` at the node's bounds. Nothing reads
`GetTextLayoutResult`; there are no character bounds to fix.

## The offsets

A character's drawn box is its row's `TextLayoutResult.getBoundingBox(char)` moved by
`(0, wrap.paragraphTop)` in document space (the draw anchors each line's layout at its
paragraph top; `rowTops` differ only where a block makes a row taller, and the glyphs
stay at the layout's position). Document space to the semantics node:

- x: plus the canvas origin in the node (the start padding, `contentOrigin.x`), less
  the horizontal scroll (`scrollX`, 0 while lines wrap).
- y: less `scrollState.value`. The top padding is scroll range (`minValue` is minus
  the top padding), and the first paragraph's `spaceBefore` is in the row's offset, so
  `offset.y - scrollState.value` already covers both; `getPositionForOffset` does this.

Root coordinates follow from the canvas's own coordinates; Android goes on to screen
with the root's `LayoutCoordinates.localToScreen`, which is what Compose's own
`view.localToScreen` does.

## Options weighed

(a) **A semantics node at the content origin.** Compose translates by the layout
node's inner coordinator, so what would have to move is the node's content, not the
semantics modifier: placing the canvas by layout at `(padding, padding - scroll)`
instead of translating in the draw. A separate child node sized to the document and
placed at minus the scroll would get clipped bounds that hug the text, and the field's
focus, pointer input and scrolling live on the outer box, so the text field would
split in two for TalkBack. Both move the field's bounds or restructure the editor,
help only Android, and leave the per-edit shape. Rejected.

(b) **A partial or cheaper `TextLayoutResult`.** Every reader indexes the layout by
absolute document offsets (`getBoundingBox(start + i)`, `getLineForOffset`), so a
layout over a subset answers wrong or throws; `MultiParagraph` lays out every
paragraph in its constructor and takes no existing `Paragraph`s, so there is no cheap
construction. Rejected, except as the fallback the desktop bridge forces.

(c) **Platform hooks answering from the rows.** Android has one: the view's
accessibility delegate is installed through `ViewCompat.setAccessibilityDelegate`
once, in `AndroidComposeView`'s init, and `AndroidComposeView` does not override
`getAccessibilityNodeProvider`, so a wrapping delegate installed the same way sees
every provider call, including `addExtraDataToAccessibilityNodeInfo`, and can answer
the character-location key itself while forwarding everything else. iOS has none
beyond the session's `textLayoutResult`; desktop none beyond `GetTextLayoutResult`.
Chosen where it exists.

(d) **Serve the IME's layout from the semantics layout on iOS.** Not a bounds hook,
but it makes iOS rows match the drawn ones at no extra cost and halves the shaping
(one cache instead of two). Chosen.

## The design

### Common: a character-bounds provider on the semantics node

`EditorSemantics.kt` gets

```kotlin
internal interface CharacterBounds {
	/** The character at [index] as drawn, in root coordinates, or null when out of range. */
	fun boundsOf(index: Int): Rect?
	/** The character nearest [position], in root coordinates. */
	fun indexAt(position: Offset): Int
}
internal val CharacterBoundsKey = SemanticsPropertyKey<CharacterBounds>("CharacterBounds")
```

`SemanticsDocument` implements it and `editorSemantics` and `viewSemantics` publish it
next to `getTextLayoutResult` (`this[CharacterBoundsKey] = document`). Unknown keys
are ignored by every platform bridge, so it is inert off Android.

`boundsOf(index)`: `state.getOffsetAtCharacter(index)`, the row through
`lineOffsets.getWrapForDrawing(position, Downstream)`, the box from
`wrap.textLayoutResult.getBoundingBox(position.char)` moved by `(0, wrap.paragraphTop)`
into document space, then less both scrolls (`documentToCanvas`, the one place
the horizontal scroll joins the vertical one) onto the canvas, and through the canvas's
transform to root (`transformFrom`, so a scaled ancestor is mapped as `indexAt` maps
it). A line break gets the zero-width `getCursorRect` at the row's end. A row whose
block replaces its text (a rule, an image) answers the block's row, which is what is
drawn (with wrapping off, as wide as the content, as `inContentSpace` draws it). `indexAt` is the inverse through `getOffsetAtPosition` and `getCharacterIndex`,
and -1 when there is no answer. Both answer nothing while the rows lag the text (a pass
skipped while the viewport is collapsed, or inside a transaction).

A request measures nothing. A line the rows have not shaped at the current width yet,
after the lazy width pass, answers from its provisional rows: the draw shapes every
line in view first, so only lines out of view can be provisional, and Android clips
their boxes to the node's visible bounds anyway. Shaping one from a request would move
the scroll and stop a fling under way.

The coordinates: the canvas's `onGloballyPositioned` (below the padding) stores its
`LayoutCoordinates` in a `CanvasPlacement` the editor remembers apart from its
`SemanticsDocument`, which is rebuilt when links start or stop being published; the
`SemanticsDocument` reads it. The same in `RichTextView`, whose padding is on its canvas
box too. Root coordinates come from the canvas alone, so the semantics node's own
position does not enter.

Nothing is cached: the rows are the editor's cache and every edit already replaces
them. Requests arrive on the main thread on every platform (Android's interaction
controller posts to the view's thread; desktop's bridge calls on the EDT), so plain
state reads are safe.

### Android: a delegate wrapper answering the character-location key

`androidMain/.../EditorAccessibilityBridge.kt`, an `AccessibilityDelegateCompat`
installed once per `AndroidComposeView` from a composable the editor and the view call
(`expect @Composable fun PlatformAccessibilityBridge()`, a no-op elsewhere): take
`ViewCompat.getAccessibilityDelegate(LocalView.current)`, and unless it is already a
bridge, wrap it and `ViewCompat.setAccessibilityDelegate(view, bridge)`. It forwards
every delegate method and, through its own `AccessibilityNodeProviderCompat`, every
provider method (`createAccessibilityNodeInfo`, `findAccessibilityNodeInfosByText`,
`findFocus`, `performAction`) to the Compose delegate, except:

`addExtraDataToAccessibilityNodeInfo(id, info, key, args)` with `key ==
EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY`: find the provider for `id` and, if there is
one, answer; otherwise forward. The provider is found through public API:
`(view as RootForTest).semanticsOwner.unmergedRootSemanticsNode`, depth-first for the
node whose `id` matches (the host view's id is the root node's), then
`config.getOrNull(CharacterBoundsKey)`. The walk is per request, which is rare enough.
The answer follows Compose's shape (`SemanticsNode.characterLocations` in common code,
so the desktop tests cover it): a negative start, no length or a start past the text
are left to Compose, an
`Array<RectF?>(length)`, each `boundsOf(start + i)` clipped to the node's
`boundsInRoot` as Compose clips its own, null when outside or past the text, then
both corners to screen (one canvas-to-root and one root-to-screen matrix per request),
`info.extras.putParcelableArray(key, rects)`. The start is checked against the node's
text: Compose checks it against its iterable text, which prefers the content
description, so for an editor with one (Hammer's, the sample's) Compose refuses every
range past the description's length, and the bridge answers them. The library
depends on `androidx.core` directly for this (Compose already brings it at runtime).

`getTextLayoutResult` stays, for two reasons: Compose offers the character-location
key in `availableExtraData` only while the node has it, and LINE and PAGE granularity
moves read it. A LINE move after an edit shapes the whole document once (user-driven,
a few per minute at most); taking that over too means intercepting
`ACTION_NEXT/PREVIOUS_AT_MOVEMENT_GRANULARITY` in the wrapper and re-sending the
traversal event Compose sends, and is left as a later chunk.

Fragility: everything used is public API (`ViewCompat`, `AccessibilityDelegateCompat`,
`AccessibilityNodeProviderCompat`, `RootForTest.semanticsOwner`, `SemanticsNode`).
The assumptions on Compose are that it installs its delegate through `ViewCompat`
once and never reinstalls it (true in 1.12.1, `AndroidComposeView.android.kt`), and
that it keeps offering the key for nodes with `GetTextLayoutResult`. If the view is
not a `RootForTest` or has no delegate, the bridge installs nothing and the semantics
layout answers alone.

### iOS: the session's layout becomes the semantics layout

`SkikoTextEditorInputMethodRequest.textLayoutResult` serves the semantics layout
instead of `DocumentTextLayout`, which goes away. The layout's cache moves out of
`SemanticsDocument` into `SemanticsLayout`, one per `TextEditorState`
(`state.semanticsLayout`), which every `SemanticsDocument` and the request read, since
the request is built from the state alone and the layout depends on nothing else.
Before the editor is laid out (a width of one pixel) the request answers null, as
before; the semantics action still measures, since desktop's bridge needs an answer
there. Rows then match
the drawn ones in styled documents, and the span-pass reuse applies. The whole-document
shape per text revision stays, since `NativeTextInputConnection` indexes one layout by
absolute offsets and is internal. `unclippedTextOffsetInRoot` adds the first row's top
(`lineOffsets.firstOrNull()?.offset?.y ?: 0f`) so the space above the first paragraph
is counted; padding and both scrolls were already right. With wrapping off the
layout is measured unwrapped and as wide as the widest line, since an intrinsic width
leaves out an indent and would break that line.

Found while doing it: the editor's session asks for no `PlatformImeOptions`, so CMP
runs it on the legacy `ComposeTextInputConnection`, not `NativeTextInputConnection`.
That one reads the layout only for the floating cursor (`getCursorRect`, then
`getOffsetForPosition` relative to it) and UIKit's vertical moves (`getLineForOffset`),
reads `unclippedTextOffsetInRoot` only to notice a geometry change, and its view
answers empty caret and selection rectangles. So the row-matched layout reaches the
floating cursor and vertical moves, and VoiceOver's caret outline stays as it was until
the editor moves to native text input (`usingNativeTextInput(true)`), a change of the
whole iOS input path that is not part of this design. The offset is right for that day.

### Desktop: no seam, the semantics layout stays

The bridge translates nothing and reads one layout per call, so neither the offsets
nor the per-edit shape can be fixed from the library; the start padding could be
folded into every paragraph's `TextIndent` (breaks stay, since the available width
grows by the same amount), but the vertical offset cannot, and half a fix is not
worth the asymmetry. The upstream fix is for `ComposeAccessibleText` to translate by
the text's origin inside the node, which needs a semantics property Compose does not
have; worth an issue against Compose Multiplatform. The lazily measured, per-revision
cached semantics layout is the right answer until then.

### Web: nothing

No reader; nothing to add.

## Testing

Host (`desktopTest/semantics/CharacterBoundsTest`, next to `SemanticsLayoutTest`): a
padded editor scrolled into a document with a heading, a block image and paragraph
spacing. For every character, `boundsOf(i)` equals the drawn glyph box
(`wrap.textLayoutResult.getBoundingBox(char)` moved by the content origin and
`wrap.paragraphTop - scrollState.value`), `indexAt(boundsOf(i).center) == i`, a line
break's box sits at its row's end with zero width, an index past the end is null;
after a span pass and after an edit at the end, a request leaves every `LineLayout`
identity in the row list as it was (no reshaping). `RichTextView` the same with its
padding. With wrapping off, scrolled sideways, the same per-character check, and
`assertViewFollowsSidewaysScroll` reads the bounds at two sideways scrolls.

Android: the module has host tests only (no Robolectric). The forwarding is a host test
with mocks (`androidHostTest/semantics/EditorAccessibilityBridgeTest`); the
deterministic end-to-end check is a device test next to the emulator smoke test
(`androidApp/src/androidTest/.../EditorCharacterLocationsTest`, which the CI emulator
job runs): compose an editor, `UiAutomation.getRootInActiveWindow()`, find the editor node, call
`refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)` with a range on a
scrolled, padded editor and assert the rectangles against the drawn rows through the
state, and check the text, a selection, a LINE move and `setText` still answer through
the wrapped delegate. Manual check: build the sample, start the API 36 `FastTrackCiRepro` emulator
read-only on a free port, enable TalkBack
(`adb shell settings put secure enabled_accessibility_services
com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService`
and `accessibility_enabled 1`), turn on magnification following the reading cursor
(or Select to Speak where the image has it), and a debug log in the bridge shows the
requests and their ranges while TalkBack reads a scrolled document; line granularity
still works.

iOS, on the Mac: run the sample on a simulator with Accessibility Inspector, focus the
editor scrolled into a heading, and check the inspector's caret frame sits on the
drawn caret; drag the spacebar trackpad over a styled document and check the caret
lands on the drawn rows; type at 200k characters and note the per-keystroke cost is no
worse than before (one shape, as today).

## Chunk plan

1. Common provider: `CharacterBounds`, `CharacterBoundsKey`, `SemanticsDocument`
   implementing it, the outer-box coordinates, publishing in `editorSemantics` and
   `viewSemantics`, `CharacterBoundsTest`. About 150 lines plus the test. Medium.
2. Android bridge: `EditorAccessibilityBridge`, `PlatformAccessibilityBridge()` and its
   calls, the device test source set with one test, the emulator check. About 250
   lines. Medium.
3. iOS: hand the `SemanticsDocument` to `SkikoTextEditorInputMethodRequest`, drop
   `DocumentTextLayout`, add the first row's top to `unclippedTextOffsetInRoot`, the
   check on the Mac. About 40 lines. Small.
4. Later, optional: LINE and PAGE granularity from the rows in the Android bridge,
   which would end the last whole-document shape on Android. Large; only if a TalkBack
   user reports line navigation lag on a long document.

## Spike results

Source-level, against the 1.12.1 sources in the Gradle cache (no runtime spike: the
module has no Robolectric, and TalkBack is only known to request character locations
for features that draw from them, magnification, braille, Select to Speak, so an
emulator run would not have been decisive on its own):

- Android's character locations are answered per request from a range, through the
  provider's `addExtraDataToAccessibilityNodeInfo`, with `GetTextLayoutResult` needed
  only to make the key available; the delegate is installed once through `ViewCompat`,
  so a wrapping delegate sees the request. A `Modifier.Node` cannot learn its semantics
  id (`requireLayoutNode` and `LayoutNode` are internal), hence the lookup through
  `RootForTest.semanticsOwner` by a custom semantics key.
- Desktop's bridge never translates character bounds and asks the layout action on every
  call; a `false` from the action makes its `textLayoutResult!!` throw, so the layout
  must always be served there.
- iOS's accessibility element has no character geometry; the input session's
  `textLayoutResult` is the only channel, read on every text change.
