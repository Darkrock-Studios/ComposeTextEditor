package state

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.HorizontalRuleSpanStyle
import com.darkrockstudios.texteditor.richstyle.InMemoryImageProvider
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SetDocumentTest {

	private val scope = TestScope()
	private val provider = InMemoryImageProvider()

	private fun newState() = TextEditorState(
		scope = scope.backgroundScope,
		measurer = mockk(relaxed = true),
	)

	private fun newMarkdown() =
		MarkdownExtension(newState(), MarkdownConfiguration.DEFAULT, imageProvider = provider)

	private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
		TextEditorRange(CharLineOffset(startLine, startChar), CharLineOffset(endLine, endChar))

	@Test
	fun `copies rich blocks between editors`() {
		val markdown = listOf(
			"# Title",
			"",
			"Some **bold** text with a [link](https://example.com).",
			"",
			"---",
			"",
			"![alt](image.png)",
			"",
			"> quoted",
			"",
			"- bullet one",
			"- bullet two",
			"",
			"1. first",
			"2. second",
			"",
			"```",
			"val x = 1",
			"```",
		).joinToString("\n")

		val source = newMarkdown()
		source.importMarkdown(markdown)
		val target = newMarkdown()

		target.editorState.setDocument(source.editorState.snapshot())

		assertEquals(source.exportAsMarkdown(), target.exportAsMarkdown())
		assertEquals(source.editorState.textLines, target.editorState.textLines)
		assertEquals(
			source.editorState.snapshot().richSpans,
			target.editorState.snapshot().richSpans,
		)
	}

	@Test
	fun `clean snapshot is published as is`() {
		val source = newMarkdown()
		source.importMarkdown("para\n\n---\n\n- item")
		val snapshot = source.editorState.snapshot()
		val target = newState()

		target.setDocument(snapshot)

		assertSame(snapshot, target.snapshot())
	}

	@Test
	fun `decoration spans are dropped`() {
		val source = newState()
		source.setText("hello world")
		source.addRichSpan(range(0, 0, 0, 5), SpellCheckStyle)
		source.addRichSpan(range(0, 6, 0, 11), HighlightSpanStyle(Color.Yellow))
		source.addRichSpan(range(0, 0, 0, 5), LinkSpanStyle("https://example.com"))
		val target = newState()

		target.setDocument(source.snapshot())

		val styles = target.snapshot().richSpans.map { it.style }
		assertEquals(2, styles.size)
		assertTrue(styles.any { it is LinkSpanStyle })
		assertTrue(styles.any { it is HighlightSpanStyle })
	}

	@Test
	fun `out of range spans are clamped onto the incoming lines`() {
		val link = LinkSpanStyle("https://example.com")
		val snapshot = DocumentSnapshot(
			lines = listOf(AnnotatedString("abc"), AnnotatedString("de")),
			richSpans = setOf(
				RichSpan(range(1, 1, 7, 40), link),
				// Collapses to zero width past the line end; a link does not render empty.
				RichSpan(range(1, 5, 1, 9), LinkSpanStyle("https://gone.example")),
				// A sticky marker survives at zero width.
				RichSpan(range(5, 0, 5, 0), BulletListSpanStyle),
			),
		)
		val state = newState()

		state.setDocument(snapshot)

		assertEquals(
			setOf(
				RichSpan(range(1, 1, 1, 2), link),
				RichSpan(range(1, 0, 1, 0), BulletListSpanStyle),
			),
			state.snapshot().richSpans,
		)
	}

	@Test
	fun `empty snapshot yields a single empty line`() {
		val state = newState()
		state.setText("something")

		state.setDocument(DocumentSnapshot(emptyList()))

		assertTrue(state.isEmpty())
		assertTrue(state.snapshot().richSpans.isEmpty())
	}

	@Test
	fun `load resets history cursor and selection`() {
		val state = newState()
		state.setText("a long first line\nsecond line")
		state.editManager.applyOperation(
			TextEditOperation.Insert(
				position = CharLineOffset(1, 11),
				text = AnnotatedString("!"),
				cursorBefore = CharLineOffset(1, 11),
				cursorAfter = CharLineOffset(1, 12),
			)
		)
		state.selector.updateSelection(CharLineOffset(0, 2), CharLineOffset(1, 4))
		state.cursor.updatePosition(CharLineOffset(1, 12))
		assertTrue(state.editManager.history.hasUndoLevels())

		state.setDocument(DocumentSnapshot(listOf(AnnotatedString("short"))))

		assertFalse(state.editManager.history.hasUndoLevels())
		assertFalse(state.canUndo)
		assertFalse(state.canRedo)
		assertNull(state.selector.selection)
		assertEquals(CharLineOffset(0, 5), state.cursorPosition)
		state.undo()
		assertEquals("short", state.textLines.single().text)
	}

	@Test
	fun `load announces a replacement instead of an edit`() {
		val state = newState()
		val emitted = mutableListOf<TextEditOperation>()
		val edits = scope.launch(start = CoroutineStart.UNDISPATCHED) {
			state.editOperations.collect { emitted.add(it) }
		}
		val source = newMarkdown()
		source.importMarkdown("text\n\n---")
		val generation = state.documentGeneration.value

		state.setDocument(source.editorState.snapshot())
		scope.testScheduler.advanceUntilIdle()
		edits.cancel()

		assertTrue(emitted.isEmpty())
		assertEquals(generation + 1, state.documentGeneration.value)
	}

	@Test
	fun `failed load is not announced`() {
		val state = newState()
		val generation = state.documentGeneration.value

		assertFailsWith<IllegalStateException> {
			state.withAtomicEdit {
				state.setDocument(DocumentSnapshot(listOf(AnnotatedString("after"))))
				state.setText("also after")
				error("load failed")
			}
		}

		assertEquals(generation, state.documentGeneration.value)
	}

	@Test
	fun `restored rule is still a rule`() {
		val source = newMarkdown()
		source.importMarkdown("above\n\n---\n\nbelow")
		val target = newState()

		target.setDocument(source.editorState.snapshot())

		assertTrue(target.snapshot().richSpans.any { it.style is HorizontalRuleSpanStyle })
	}

	@Test
	fun `inside a transaction the load commits with it`() {
		val state = newState()
		state.setText("before")
		val incoming = DocumentSnapshot(listOf(AnnotatedString("after")))

		state.withAtomicEdit {
			state.setDocument(incoming)
			assertEquals("before", state.snapshot().lines.single().text)
		}

		assertEquals("after", state.snapshot().lines.single().text)
	}

	@Test
	fun `failed transaction restores history and selection`() {
		val state = newState()
		state.setText("before")
		state.editManager.applyOperation(
			TextEditOperation.Insert(
				position = CharLineOffset(0, 6),
				text = AnnotatedString("!"),
				cursorBefore = CharLineOffset(0, 6),
				cursorAfter = CharLineOffset(0, 7),
			)
		)
		val selection = range(0, 1, 0, 4)
		state.selector.updateSelection(selection.start, selection.end)

		assertFailsWith<IllegalStateException> {
			state.withAtomicEdit {
				state.setDocument(DocumentSnapshot(listOf(AnnotatedString("after"))))
				error("load failed")
			}
		}

		assertEquals("before!", state.textLines.single().text)
		assertEquals(selection, state.selector.selection)
		assertTrue(state.editManager.history.hasUndoLevels())
	}
}
