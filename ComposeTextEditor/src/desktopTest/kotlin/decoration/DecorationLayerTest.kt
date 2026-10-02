package decoration

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.decoration.Decoration
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.decoration.DecorationStyle
import com.darkrockstudios.texteditor.decoration.clearDecorations
import com.darkrockstudios.texteditor.decoration.decorations
import com.darkrockstudios.texteditor.decoration.replaceDecorations
import com.darkrockstudios.texteditor.decoration.setDecorations
import com.darkrockstudios.texteditor.html.withHtml
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.textEditorStateSaver
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Decoration layers (7.86): a host's own overlays, keyed by owner, that never become part of the document. */
class DecorationLayerTest {

	private val syntax = DecorationLayer("syntax")
	private val lint = DecorationLayer("lint")
	private val keyword = Decoration(syntax, textColor = Color.Red)
	private val warning = Decoration(lint, background = Color.Yellow)

	private fun editor(text: String, scope: TestScope = TestScope()) =
		TextEditorState(scope = scope, measurer = mockk(relaxed = true), initialText = AnnotatedString(text))

	private fun span(line: Int, start: Int, end: Int, style: DecorationStyle) =
		RichSpan(TextEditorRange(CharLineOffset(line, start), CharLineOffset(line, end)), style)

	private fun TextEditorState.ranges(layer: DecorationLayer) =
		decorations(layer).map { "${it.range.start.line}:${it.range.start.char}-${it.range.end.line}:${it.range.end.char}" }.sorted()

	@Test
	fun `a layer is set, read and cleared`() {
		val state = editor("val x = 1\nfun f() = 2")
		state.setDecorations(syntax, listOf(span(0, 0, 3, keyword), span(1, 0, 3, keyword)))
		assertEquals(listOf("0:0-0:3", "1:0-1:3"), state.ranges(syntax))

		state.setDecorations(syntax, listOf(span(1, 4, 5, keyword)))
		assertEquals(listOf("1:4-1:5"), state.ranges(syntax))

		state.clearDecorations(syntax)
		assertTrue(state.decorations(syntax).isEmpty())
	}

	@Test
	fun `layers keep apart`() {
		val state = editor("val x = 1")
		state.setDecorations(syntax, listOf(span(0, 0, 3, keyword)))
		state.setDecorations(lint, listOf(span(0, 0, 3, warning), span(0, 4, 5, warning)))

		state.setDecorations(syntax, listOf(span(0, 8, 9, keyword)))
		assertEquals(listOf("0:0-0:3", "0:4-0:5"), state.ranges(lint))

		state.clearDecorations(lint)
		assertEquals(listOf("0:8-0:9"), state.ranges(syntax))
		assertTrue(state.decorations(lint).isEmpty())
	}

	@Test
	fun `a replace by lines leaves the layer's other lines`() {
		val state = editor("a b\nc d\ne f")
		state.setDecorations(syntax, (0..2).map { span(it, 0, 1, keyword) })
		state.setDecorations(lint, listOf(span(1, 0, 1, warning)))

		state.replaceDecorations(syntax, 1..1, listOf(span(1, 2, 3, keyword)))

		assertEquals(listOf("0:0-0:1", "1:2-1:3", "2:0-2:1"), state.ranges(syntax))
		assertEquals(listOf("1:0-1:1"), state.ranges(lint))
	}

	@Test
	fun `a replace by range takes the decorations overlapping it`() {
		val state = editor("one two three")
		state.setDecorations(syntax, listOf(span(0, 0, 3, keyword), span(0, 4, 7, keyword), span(0, 8, 13, keyword)))

		// Touching "one" and "three" at its ends, sharing a character with "two" only.
		state.replaceDecorations(syntax, TextEditorRange(CharLineOffset(0, 3), CharLineOffset(0, 8)), listOf(span(0, 4, 6, keyword)))

		assertEquals(listOf("0:0-0:3", "0:4-0:6", "0:8-0:13"), state.ranges(syntax))
	}

	@Test
	fun `a span of another layer, or not a decoration, is refused`() {
		val state = editor("val x")
		assertFailsWith<IllegalArgumentException> { state.setDecorations(syntax, listOf(span(0, 0, 3, warning))) }
		assertFailsWith<IllegalArgumentException> {
			state.setDecorations(syntax, listOf(RichSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3)), SpellCheckStyle)))
		}
		val anchored = object : DecorationStyle {
			override val layer = syntax
			override val stickyAtStart: Boolean get() = true
		}
		assertFailsWith<IllegalArgumentException> { state.setDecorations(syntax, listOf(span(0, 0, 3, anchored))) }
	}

	@Test
	fun `a span across lines is kept a piece per line, past the end clamped`() {
		val state = editor("/* one\n\ntwo */ x")
		state.setDecorations(
			syntax,
			listOf(
				RichSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(2, 6)), keyword),
				RichSpan(TextEditorRange(CharLineOffset(2, 7), CharLineOffset(2, 40)), keyword),
			),
		)
		assertEquals(listOf("0:0-0:6", "2:0-2:6", "2:7-2:8"), state.ranges(syntax))
	}

	@Test
	fun `decorations follow edits until replaced`() {
		val state = editor("val x\nval y")
		state.setDecorations(syntax, listOf(span(0, 0, 3, keyword), span(1, 0, 3, keyword)))

		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertStringAtCursor("  ")
		assertEquals(listOf("0:2-0:5", "1:0-1:3"), state.ranges(syntax))

		state.cursor.updatePosition(CharLineOffset(0, 0))
		state.insertNewlineAtCursor()
		assertEquals(listOf("1:2-1:5", "2:0-2:3"), state.ranges(syntax))
	}

	@Test
	fun `decorations never enter undo or redo`() {
		val state = editor("val x")
		state.setDecorations(syntax, listOf(span(0, 0, 3, keyword)))
		assertFalse(state.canUndo)

		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.insertCharacterAtCursor('y')
		state.setDecorations(syntax, listOf(span(0, 4, 6, keyword)))
		state.clearDecorations(syntax)
		state.setDecorations(syntax, listOf(span(0, 0, 3, keyword)))

		state.undo()
		assertEquals("val x", state.getAllText().text)
		assertFalse(state.canUndo, "one undo step for the one edit")
		assertEquals(listOf("0:0-0:3"), state.ranges(syntax))

		state.redo()
		assertEquals("val xy", state.getAllText().text)
		assertFalse(state.canRedo)
		assertEquals(listOf("0:0-0:3"), state.ranges(syntax))
	}

	@Test
	fun `decorations are no edit and leave the text revision`() = runTest(UnconfinedTestDispatcher()) {
		val state = editor("val x\nval y", scope = TestScope(UnconfinedTestDispatcher()))
		val operations = mutableListOf<TextEditOperation>()
		backgroundScope.launch { state.editOperations.collect { operations += it } }
		val revision = state.textRevision

		state.setDecorations(syntax, listOf(span(0, 0, 3, keyword), span(1, 0, 3, keyword)))
		state.replaceDecorations(syntax, 0..0, emptyList())
		state.clearDecorations(syntax)

		assertEquals(revision, state.textRevision)
		assertTrue(operations.isEmpty(), "edit stream got $operations")
	}

	@Test
	fun `copies, drags, saves and exports leave decorations out`() {
		val state = editor("val x = 1\nval y = 2")
		val html = state.withHtml().exportAsHtml()
		state.setDecorations(syntax, listOf(span(0, 0, 3, keyword), span(1, 0, 3, keyword)))
		val all = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 9))

		assertTrue(state.preservedRichSpans(all).isEmpty(), "a copy or drag would carry them")
		assertTrue(state.getAllText().spanStyles.isEmpty())
		assertEquals(html, state.withHtml().exportAsHtml())

		val saver = textEditorStateSaver(TestScope(), mockk(relaxed = true), null)
		val saved = with(saver) { SaverScope { true }.save(state) }!!
		val restored = saver.restore(saved)!!
		assertEquals("val x = 1\nval y = 2", restored.getAllText().text)
		assertTrue(restored.snapshot().richSpans.isEmpty())
	}

	@Test
	fun `a click passes through a ready-made decoration`() {
		val state = editor("val x")
		state.setDecorations(syntax, listOf(span(0, 0, 3, keyword)))
		assertFalse(keyword.isHitTestable)
		assertTrue(state.decorations(syntax).single().style.isDecoration)
	}

	@Test
	fun `a host style carrying data is a decoration of its layer`() {
		class Note(val text: String) : DecorationStyle {
			override val layer = lint
			override fun DrawScope.drawCustomStyle(layoutResult: TextLayoutResult, lineWrap: LineWrap, textRange: TextRange, state: TextEditorState) = Unit
		}
		val state = editor("val x")
		state.setDecorations(lint, listOf(span(0, 4, 5, Note("unused"))))
		assertEquals("unused", (state.decorations(lint).single().style as Note).text)
		assertFalse(state.canUndo)
	}
}
