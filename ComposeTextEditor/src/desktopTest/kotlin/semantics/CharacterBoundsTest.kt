package semantics

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.CharacterBounds
import com.darkrockstudios.texteditor.CharacterBoundsKey
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.characterLocations
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.cursor.getWrapForDrawing
import com.darkrockstudios.texteditor.effectiveHeight
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.ParagraphFormatSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.CaretAffinity
import com.darkrockstudios.texteditor.state.RowList
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import com.darkrockstudios.texteditor.state.setParagraphFormat
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleHeader
import utils.EditorUiTestScope
import utils.editorUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The character bounds a screen reader asks for are the glyphs as drawn, in root
 * coordinates: past the content padding and the space above the first paragraph, less
 * the scroll. The expected origin comes from the node's position and the padding, not
 * from the canvas the provider reads, and each box is checked against the caret.
 */
@OptIn(ExperimentalTestApi::class)
class CharacterBoundsTest {

	private val paragraphs = "A heading here\n" +
		"A first paragraph long enough to wrap onto several rows here.\n" +
		" \n" +
		"An indented paragraph that also runs on for a few rows at this width.\n" +
		"A list item that wraps once or twice at this width.\n" +
		(1..30).joinToString("\n") { "Line $it of the rest." }

	private fun EditorUiTestScope.bounds(): CharacterBounds {
		waitForIdle()
		return editorNode().fetchSemanticsNode().config[CharacterBoundsKey]
	}

	/** Where the document's origin sits in root, from the node, the padding and both scrolls alone. */
	private fun EditorUiTestScope.canvasOrigin(startPadding: Float): Offset =
		editorNode().fetchSemanticsNode().positionInRoot +
			Offset(startPadding - state.horizontalScrollState.value, -state.scrollState.value.toFloat())

	/**
	 * Every character's box is its row's glyph box moved by [origin] and its paragraph's
	 * top; a text row's top is where the editor draws the row, and a letter's left edge
	 * is where the caret before it is drawn (left-to-right text).
	 */
	private fun assertDrawn(state: TextEditorState, bounds: CharacterBounds, origin: Offset) {
		val text = state.getAllText().text
		for (index in text.indices) {
			val position = state.getOffsetAtCharacter(index)
			val row = assertNotNull(state.lineOffsets.getWrapForDrawing(position, CaretAffinity.Downstream))
			val box = assertNotNull(bounds.boundsOf(index), "bounds of $index")
			if (row.richSpans.any { it.style is HorizontalRuleSpanStyle }) {
				// Unwrapped, a rule is drawn across the content's width.
				val width = state.viewportSize.width + state.horizontalScrollState.maxValue
				val block = Rect(0f, row.offset.y, width, row.offset.y + row.effectiveHeight)
				assertNear(block.translate(origin), box, "the rule's row at $index")
				continue
			}
			if (text[index] == '\n') {
				assertEquals(0f, box.width, "a line break is zero width")
			} else {
				val expected = row.textLayoutResult.getBoundingBox(position.char)
					.translate(origin + Offset(row.offset.x, row.paragraphTop))
				assertNear(expected, box, "character $index '${text[index]}'")
			}
			if (row.blockHeight == null) {
				assertTrue(abs(box.top - (origin.y + row.offset.y)) < 1f, "top of $index: ${box.top}, row at ${origin.y + row.offset.y}")
			}
			if (text[index].isLetter()) {
				// The caret's position is in view x, which the sideways scroll is already out of.
				val caret = origin.x + state.horizontalScrollState.value + state.getPositionForOffset(position).position.x
				assertTrue(abs(box.left - caret) < 1f, "left edge of $index '${text[index]}': ${box.left}, caret at $caret")
				assertEquals(index, bounds.indexAt(Offset(box.left + 1f, box.center.y)), "index at the left edge of $index")
			}
		}
	}

	private fun assertNear(expected: Rect, actual: Rect, message: String) {
		val off = maxOf(abs(expected.left - actual.left), abs(expected.top - actual.top), abs(expected.right - actual.right), abs(expected.bottom - actual.bottom))
		assertTrue(off < 0.01f, "$message: $actual, drawn at $expected")
	}

	@Test
	fun `a padded, scrolled document with a heading, a rule and indents answers the drawn glyphs`() = editorUiTest(
		initialText = AnnotatedString(paragraphs),
		width = 260.dp,
		height = 200.dp,
		contentPadding = PaddingValues(start = 24.dp, top = 16.dp, end = 8.dp, bottom = 16.dp),
	) {
		state.toggleHeader(0..0, 1)
		state.addRichSpan(TextEditorRange(CharLineOffset(2, 0), CharLineOffset(2, 1)), HorizontalRuleSpanStyle)
		state.paragraphSpacing = 6.dp
		state.setParagraphFormat(0..0, ParagraphFormatSpanStyle(spaceBefore = 20.dp))
		state.setParagraphFormat(3..3, ParagraphFormatSpanStyle(indent = 18.sp, firstLineIndent = 12.sp))
		state.toggleBulletList(4..4)
		waitForIdle()
		assertTrue(state.lineOffsets[0].offset.y >= 20f, "precondition: space above the first paragraph")
		assertTrue(state.lineOffsets.size > state.textLines.size, "precondition: the text wraps")

		val bounds = bounds()
		assertDrawn(state, bounds, canvasOrigin(startPadding = 24f))

		test.runOnIdle { state.scrollState.scrollTo(150) }
		waitForIdle()
		assertEquals(150, state.scrollState.value)
		assertDrawn(state, bounds, canvasOrigin(startPadding = 24f))
	}

	@Test
	fun `with wrapping off, scrolled sideways, the bounds are what is drawn`() = editorUiTest(
		initialText = AnnotatedString((1..6).joinToString("\n") { if (it == 3) " " else "Line $it runs well past the right edge of a narrow editor, unwrapped." }),
		width = 200.dp,
		softWrap = false,
		contentPadding = PaddingValues(start = 24.dp, top = 8.dp),
	) {
		state.addRichSpan(TextEditorRange(CharLineOffset(2, 0), CharLineOffset(2, 1)), HorizontalRuleSpanStyle)
		waitForIdle()
		assertEquals(6, state.lineOffsets.size, "precondition: one row per line")
		val bounds = bounds()
		test.runOnIdle { state.horizontalScrollState.scrollTo(150) }
		waitForIdle()
		assertEquals(150, state.horizontalScrollState.value)

		assertDrawn(state, bounds, canvasOrigin(startPadding = 24f))
	}

	@Test
	fun `the top padding is above the first row until scrolled away`() = editorUiTest(
		initialText = AnnotatedString((1..40).joinToString("\n") { "line $it" }),
		contentPadding = PaddingValues(start = 10.dp, top = 30.dp),
	) {
		val node = editorNode().fetchSemanticsNode().positionInRoot
		assertEquals(node.y + 30f + state.lineOffsets[0].offset.y, assertNotNull(bounds().boundsOf(0)).top, 0.5f)
		test.runOnIdle { state.scrollState.scrollTo(40) }
		waitForIdle()
		assertEquals(node.y - 40f + state.lineOffsets[0].offset.y, assertNotNull(bounds().boundsOf(0)).top, 0.5f)
	}

	@Test
	fun `right-to-left text is answered from its own edges`() = editorUiTest(
		initialText = AnnotatedString("שלום עולם, זו שורה ארוכה שנשברת לכמה שורות ברוחב הזה\nmixed עברית and English"),
		width = 200.dp,
		contentPadding = PaddingValues(start = 12.dp),
	) {
		assertTrue(state.lineOffsets.size > state.textLines.size, "precondition: the text wraps")
		val bounds = bounds()
		val text = state.getAllText().text
		val origin = canvasOrigin(startPadding = 12f)
		val hebrew = text.indices.filter { text[it] in 'א'..'ת' }
		for (index in hebrew) {
			val box = assertNotNull(bounds.boundsOf(index))
			val caret = origin.x + state.getPositionForOffset(state.getOffsetAtCharacter(index)).position.x
			assertTrue(abs(box.right - caret) < 1f, "right edge of $index: ${box.right}, caret at $caret")
			// Where a tap there would put the caret: Skia answers the next offset inside a
			// right-to-left glyph's leading half.
			val inside = Offset(box.right - 1f, box.center.y)
			assertEquals(state.getCharacterIndex(state.getOffsetAtPosition(inside - origin)), bounds.indexAt(inside))
		}
		for (index in text.indices.filter { text[it] != '\n' }) {
			val position = state.getOffsetAtCharacter(index)
			val row = state.lineOffsets.getWrapForDrawing(position, CaretAffinity.Downstream)!!
			assertNear(
				row.textLayoutResult.getBoundingBox(position.char).translate(origin + Offset(row.offset.x, row.paragraphTop)),
				bounds.boundsOf(index)!!,
				"character $index",
			)
		}
	}

	@Test
	fun `an emoji's box covers its glyph`() = editorUiTest(
		initialText = AnnotatedString("a 😀 b"),
	) {
		val bounds = bounds()
		val origin = canvasOrigin(startPadding = 0f)
		val emoji = assertNotNull(bounds.boundsOf(2))
		val before = origin.x + state.getPositionForOffset(CharLineOffset(0, 2)).position.x
		val after = origin.x + state.getPositionForOffset(CharLineOffset(0, 4)).position.x
		assertTrue(emoji.width > 0f, "the emoji has a width: $emoji")
		assertEquals(before, emoji.left, 1f)
		assertEquals(after, emoji.right, 1f)
		assertEquals(2, bounds.indexAt(Offset(emoji.left + 1f, emoji.center.y)))
	}

	@Test
	fun `a line break sits at its row's end and an index past the text is null`() = editorUiTest(
		initialText = AnnotatedString("first line\nsecond"),
		contentPadding = PaddingValues(start = 20.dp),
	) {
		val bounds = bounds()
		val origin = canvasOrigin(startPadding = 20f)
		val lineBreak = assertNotNull(bounds.boundsOf(10))
		assertEquals(0f, lineBreak.width)
		assertEquals(origin.x + state.getPositionForOffset(CharLineOffset(0, 10)).position.x, lineBreak.left, 0.5f)
		assertNull(bounds.boundsOf(-1))
		assertNull(bounds.boundsOf(state.getTextLength()))
		assertEquals(state.getTextLength(), bounds.indexAt(Offset(10_000f, 10_000f)))
	}

	@Test
	fun `a request reshapes nothing, after a span pass or an edit`() = editorUiTest(
		initialText = AnnotatedString(paragraphs),
		width = 260.dp,
	) {
		fun layouts() = (state.lineOffsets as RowList).let { rows -> List(rows.lineCount) { rows.layoutOf(it) } }
		fun assertUnchangedBy(request: () -> Unit) {
			test.runOnIdle { state.settleLayout() }
			waitForIdle()
			val before = layouts()
			request()
			before.zip(layouts()).forEachIndexed { line, (was, now) -> assertSame(was, now, "line $line was shaped again") }
		}
		val bounds = bounds()
		val all = { for (index in 0..state.getTextLength()) bounds.boundsOf(index) }
		assertUnchangedBy(all)

		state.updateRichSpans(
			emptyList(),
			listOf(RichSpan(TextEditorRange(CharLineOffset(1, 2), CharLineOffset(1, 7)), SpellCheckStyle)),
		)
		assertUnchangedBy(all)

		test.runOnIdle {
			state.cursor.updatePosition(CharLineOffset(state.textLines.lastIndex, state.textLines.last().length))
			state.insertStringAtCursor(" more")
		}
		assertUnchangedBy(all)
	}

	@Test
	fun `lines left provisional by a width change are not shaped by a request`() = editorUiTest(
		initialText = AnnotatedString(paragraphs),
		width = 260.dp,
		height = 120.dp,
	) {
		val bounds = bounds()
		test.runOnIdle {
			state.onViewportSizeChange(Size(state.viewportSize.width - 60f, state.viewportSize.height))
			val rows = state.lineOffsets as RowList
			val before = List(rows.lineCount) { rows.layoutOf(it) }
			val scroll = state.scrollState.value
			for (index in 0..state.getTextLength()) bounds.boundsOf(index)
			assertSame(rows, state.lineOffsets, "the rows were replaced")
			before.forEachIndexed { line, layout -> assertSame(layout, rows.layoutOf(line), "line $line was shaped again") }
			assertEquals(scroll, state.scrollState.value)
		}
	}

	@Test
	fun `a document laid out behind its text answers nothing`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo"),
	) {
		val bounds = bounds()
		test.runOnIdle {
			state.onViewportSizeChange(Size(state.viewportSize.width, 0f))
			state.insertStringAtCursor("zero\n")
			assertNull(bounds.boundsOf(0))
			assertEquals(-1, bounds.indexAt(Offset.Zero))
		}
	}

	@Test
	fun `a read-only view answers past its padding`() = runComposeUiTest {
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = AnnotatedString("Hello world, a read-only view that wraps across a few rows at this width."))
			RichTextView(state = state, modifier = Modifier.width(200.dp), contentPadding = PaddingValues(start = 14.dp, top = 9.dp))
		}
		waitForIdle()
		val node = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNode()
		val bounds = node.config[CharacterBoundsKey]
		val origin = node.positionInRoot + Offset(14f, 9f)
		val text = state.getAllText().text
		for (index in text.indices) {
			val position = state.getOffsetAtCharacter(index)
			val row = state.lineOffsets.getWrapForDrawing(position, CaretAffinity.Downstream)!!
			assertNear(
				row.textLayoutResult.getBoundingBox(position.char).translate(origin + Offset(row.offset.x, row.paragraphTop)),
				bounds.boundsOf(index)!!,
				"character $index",
			)
		}
	}

	@Test
	fun `a platform request for a range is clipped to the editor as Compose clips its own`() = editorUiTest(
		initialText = AnnotatedString((1..40).joinToString("\n") { "line $it" }),
		height = 120.dp,
		contentPadding = PaddingValues(start = 16.dp, top = 8.dp),
		contentDescription = "Notes",
	) {
		test.runOnIdle { state.scrollState.scrollTo(60) }
		waitForIdle()
		val root = test.onAllNodes(isRoot(), useUnmergedTree = true).onFirst().fetchSemanticsNode()
		val editor = editorNode().fetchSemanticsNode()
		val bounds = editor.config[CharacterBoundsKey]
		val length = state.getTextLength()

		val all = assertNotNull(root.characterLocations(editor.id, 0, length + 5))
		assertEquals(length + 5, all.size)
		// The editor, at the top left of the window, is 400 by 120.
		val visible = Rect(0f, 0f, 400f, 120f)
		for (index in 0 until length) {
			val box = assertNotNull(bounds.boundsOf(index))
			val expected = if (box.overlaps(visible)) box.intersect(visible) else null
			assertEquals(expected, all[index], "character $index")
		}
		assertTrue(all.take(length).any { it == null }, "precondition: rows scrolled out of view")
		assertTrue(all.take(length).any { it != null }, "precondition: rows in view")
		assertTrue(all.drop(length).all { it == null }, "past the text")

		assertNull(root.characterLocations(editor.id, -1, 3), "a negative start")
		assertNull(root.characterLocations(editor.id, 0, 0), "nothing asked for")
		assertNull(root.characterLocations(editor.id, length, 1), "a start past the text")
		assertNotNull(root.characterLocations(editor.id, "Notes".length + 1, 1), "a start past the content description")
		assertNull(root.characterLocations(root.id, 0, 1), "a node without character bounds")
		assertNull(root.characterLocations(Int.MAX_VALUE, 0, 1), "no such node")
	}

}
