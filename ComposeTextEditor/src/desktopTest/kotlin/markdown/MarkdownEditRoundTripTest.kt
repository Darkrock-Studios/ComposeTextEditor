package markdown

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end regression tests for markdown corruption that accumulates as a
 * user edits styled text. The defect: editing inside a bold/italic run leaves
 * unmerged span fragments, and re-exporting multiplies emphasis markers. Each
 * edit + save + reload cycle compounds the damage.
 */
class MarkdownEditRoundTripTest {

	private fun TestScope.ext(initial: String? = null): MarkdownExtension {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = initial?.let { AnnotatedString(it) },
		)
		return MarkdownExtension(state)
	}

	private fun TestScope.exportAfterImport(markdown: String): String {
		val e = ext()
		e.importMarkdown(markdown)
		return e.exportAsMarkdown()
	}

	@Test
	fun `typing a character inside a bold word does not multiply markers`() = runTest {
		val e = ext()
		e.importMarkdown("**End of chapter**")
		e.editorState.cursor.moveRight(1) // after "E"
		e.editorState.insertCharacterAtCursor('X')
		assertEquals("**EXnd of chapter**", e.exportAsMarkdown())
	}

	@Test
	fun `typing inside an italic word does not multiply markers`() = runTest {
		val e = ext()
		e.importMarkdown("*weak*")
		e.editorState.cursor.moveRight(2) // after "we"
		e.editorState.insertCharacterAtCursor('e')
		assertEquals("*weeak*", e.exportAsMarkdown())
	}

	@Test
	fun `repeated edits inside bold stay clean`() = runTest {
		val e = ext()
		e.importMarkdown("**End**")
		repeat(3) {
			e.editorState.cursor.moveRight(1)
			e.editorState.insertCharacterAtCursor('x')
		}
		// Three x's inserted at successive interior positions; exact text aside,
		// the result must remain a single bold run with no interior markers.
		val md = e.exportAsMarkdown()
		assertEquals(0, interiorMarkerCount(md), "interior emphasis markers in [$md]")
	}

	@Test
	fun `editor state does not accumulate duplicate bold spans after an insert`() = runTest {
		val e = ext()
		e.importMarkdown("**End of chapter**")
		e.editorState.cursor.moveRight(1)
		e.editorState.insertCharacterAtCursor('X')
		// The line should hold a single contiguous bold run, not fragments.
		val boldSpans = e.editorState.getAllText().spanStyles
			.filter { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold }
		assertEquals(1, boldSpans.size, "bold spans: $boldSpans")
	}

	@Test
	fun `editing does not merge bold runs across an intervening italic word`() = runTest {
		val e = ext()
		e.importMarkdown("**ab**_c_**de**")
		e.editorState.cursor.moveRight(100) // end of line
		e.editorState.insertCharacterAtCursor('Z')
		// "c" must stay italic-only; the two bold runs must not swallow it.
		val md = e.exportAsMarkdown()
		assertEquals("**ab***c***deZ**", md)
	}

	@Test
	fun `editing does not bold a deliberately unbolded separator`() = runTest {
		val e = ext()
		e.importMarkdown("**He**-**lo**")
		e.editorState.cursor.moveRight(100)
		e.editorState.insertCharacterAtCursor('Z')
		val md = e.exportAsMarkdown()
		// The "-" between the two bold runs must remain unbolded; it needs no
		// escape, since both runs flank it correctly.
		assertEquals("**He**-**loZ**", md)
	}

	@Test
	fun `clean markdown is idempotent across many cycles`() = runTest {
		val input = "*I'm weak.* And now **I lied** to her. ***End of chapter***"
		var current = input
		repeat(5) { current = exportAfterImport(current) }
		assertEquals(input, current)
	}

	/** Counts `*` runs that sit between two word characters (corruption markers). */
	private fun interiorMarkerCount(md: String): Int =
		Regex("(?<=\\w)\\*+(?=\\w)").findAll(md).count()
}
