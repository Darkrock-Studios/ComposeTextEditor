package state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.HeaderSpanStyle
import com.darkrockstudios.texteditor.richstyle.LineBlockEditBehavior
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.richstyle.blockStylesRepair
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import utils.blockLines
import utils.setBlockLines

/** A joined line carries the text style its kept markers bake, over all of it, and no other block's (6.35, 7.72). */
class JoinBlockTextStyleTest {

	private val styles = RichTextStyles.DEFAULT
	private val body = styles.defaultTextStyle
	private val monospace = SpanStyle(fontFamily = FontFamily.Monospace)

	private fun TestScope.editor(blockLines: String): TextEditorState {
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setBlockLines(blockLines)
		return state
	}

	/** The style each character resolves to, its spans merged in order. */
	private fun AnnotatedString.looks(): List<SpanStyle> = text.indices.map { index ->
		spanStyles.filter { it.start <= index && index < it.end }.fold(SpanStyle()) { acc, run -> acc.merge(run.item) }
	}

	private fun TextEditorState.assertLook(line: Int, look: SpanStyle) {
		textLines[line].looks().forEachIndexed { index, resolved ->
			assertEquals(look, resolved, "line $line '${textLines[line].text}' at $index")
		}
	}

	private fun TextEditorState.carries(line: Int, style: SpanStyle) = textLines[line].spanStyles.any { it.item == style }

	@Test
	fun `a heading's tail joined onto a plain line's head is body text`() = runTest {
		val state = editor("seed\n# Heading")

		state.delete(TextEditorRange(CharLineOffset(0, 4), CharLineOffset(1, 1)))

		assertEquals("seedeading", state.blockLines())
		assertFalse(state.carries(0, styles.header1Style))
		state.assertLook(0, body)
	}

	@Test
	fun `a body line joined onto a heading takes the heading's look over all of it`() = runTest {
		val state = editor("## Title\nbody")

		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.deleteAtCursor()

		assertEquals("## Titlebody", state.blockLines())
		state.assertLook(0, body.merge(styles.header2Style))
	}

	@Test
	fun `another heading joined onto a heading loses its look`() = runTest {
		val state = editor("## Title\n### Sub")

		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.deleteAtCursor()

		assertEquals("## TitleSub", state.blockLines())
		assertFalse(state.carries(0, styles.header3Style))
		state.assertLook(0, body.merge(styles.header2Style))
	}

	@Test
	fun `a replace across headings leaves the kept heading's look alone`() = runTest {
		val state = editor("## Title\n### Sub line")

		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(1, 4)), "x")

		assertEquals("## Tixline", state.blockLines())
		assertFalse(state.carries(0, styles.header3Style))
		state.assertLook(0, body.merge(styles.header2Style))
	}

	@Test
	fun `a fenced line's tail joined onto a plain line is not monospace`() = runTest {
		val state = editor("plain\n``` code")

		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.deleteAtCursor()

		assertEquals("plaincode", state.blockLines())
		state.assertLook(0, body)
	}

	@Test
	fun `a plain line joined onto a fenced line is monospace over all of it`() = runTest {
		val state = editor("``` code\nplain")

		state.cursor.updatePosition(CharLineOffset(0, 4))
		state.deleteAtCursor()

		assertEquals("``` codeplain", state.blockLines())
		state.assertLook(0, body.merge(monospace))
	}

	@Test
	fun `undo of a join gives both lines back their own looks`() = runTest {
		val state = editor("## Title\n### Sub")
		val origin = state.textLines.toList()

		state.cursor.updatePosition(CharLineOffset(0, 5))
		state.deleteAtCursor()
		state.undo()

		assertEquals(origin, state.textLines.toList())
		state.redo()
		assertEquals("## TitleSub", state.blockLines())
		state.assertLook(0, body.merge(styles.header2Style))
	}

	@Test
	fun `a size of the user's own inside a heading still wins after a join`() = runTest {
		val state = editor("## a BIG b\nmore")
		val big = SpanStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold)
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 5)), big)

		state.cursor.updatePosition(CharLineOffset(0, 7))
		state.deleteAtCursor()

		assertEquals("## a BIG bmore", state.blockLines())
		val looks = state.textLines[0].looks()
		assertEquals(body.merge(styles.header2Style).merge(big), looks[3])
		assertEquals(body.merge(styles.header2Style), looks[10])
	}

	@Test
	fun `a heading's look applied by hand to a plain line stays`() = runTest {
		val state = editor("Title\nnext")
		state.addStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)), styles.header1Style)

		state.cursor.updatePosition(CharLineOffset(1, 0))
		state.backspaceAtCursor()

		assertEquals("Titlenext", state.blockLines())
		assertTrue(state.carries(0, styles.header1Style))
	}

	@Test
	fun `a heading's text joined up onto an empty head keeps the user's size inside it`() = runTest {
		val state = editor("intro\n## a BIG b")
		val big = SpanStyle(fontSize = 40.sp)
		state.addStyleSpan(TextEditorRange(CharLineOffset(1, 2), CharLineOffset(1, 5)), big)

		state.delete(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(1, 0)))

		assertEquals("## a BIG b", state.blockLines())
		val looks = state.textLines[0].looks()
		assertEquals(body.merge(styles.header2Style).merge(big), looks[3])
		assertEquals(body.merge(styles.header2Style), looks[0])
	}

	@Test
	fun `the bake goes after a body run and before the user's own`() {
		val big = SpanStyle(fontSize = 40.sp)
		val line = AnnotatedString(
			"a BIG bmore",
			listOf(
				AnnotatedString.Range(styles.header2Style, 0, 7),
				AnnotatedString.Range(big, 2, 5),
				AnnotatedString.Range(body, 7, 11),
			),
		)
		val heading = RichSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 11)), HeaderSpanStyle.of(2))

		val repaired = blockStylesRepair(styles)(line, listOf(heading))!!

		assertEquals(listOf(body, styles.header2Style, big), repaired.spanStyles.map { it.item })
		assertEquals(body.merge(styles.header2Style).merge(big), repaired.looks()[3])
		assertEquals(body.merge(styles.header2Style), repaired.looks()[8])
		assertNull(blockStylesRepair(styles)(repaired, listOf(heading)))
	}

	@Test
	fun `a heading look equal to the body style is baked told apart from it`() {
		val config = styles.copy(header4Style = body)
		val line = AnnotatedString(
			"Title",
			listOf(AnnotatedString.Range(body, 0, 5)),
			listOf(AnnotatedString.Range(ParagraphStyle(), 0, 5)),
		)
		val heading = RichSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 5)), HeaderSpanStyle.of(4))

		val repaired = blockStylesRepair(config)(line, listOf(heading))!!

		assertEquals(listOf(body, config.headingLook(4)), repaired.spanStyles.map { it.item })
		assertNull(blockStylesRepair(config)(repaired, listOf(heading)))
	}

	@Test
	fun `a replace across headings that inherits its styles leaves the other heading's look`() = runTest {
		val state = editor("## Title\n### Sub line")

		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(1, 4)), "abcdefgh", inheritStyle = true)

		assertEquals("## Tiabcdefghline", state.blockLines())
		assertFalse(state.carries(0, styles.header3Style))
		state.assertLook(0, body.merge(styles.header2Style))
	}

	@Test
	fun `a heading's tail split onto a plain line leaves its look behind`() = runTest {
		val state = editor("## Title")
		state.editBehaviors.remove(LineBlockEditBehavior)

		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 4)), "a\nb")

		assertEquals("## Tia\nbe", state.blockLines())
		state.assertLook(0, body.merge(styles.header2Style))
		assertFalse(state.carries(1, styles.header2Style))
	}

	@Test
	fun `a heading split by Enter continues with its look`() = runTest {
		val state = editor("## Title")

		state.cursor.updatePosition(CharLineOffset(0, 2))
		state.insertNewlineAtCursor()

		assertEquals("## Ti\n## tle", state.blockLines())
		state.assertLook(0, body.merge(styles.header2Style))
		state.assertLook(1, body.merge(styles.header2Style))
	}

	@Test
	fun `a replace that breaks a heading inherits no look onto the plain line`() = runTest {
		val state = editor("## Title")
		state.editBehaviors.remove(LineBlockEditBehavior)

		state.replace(TextEditorRange(CharLineOffset(0, 2), CharLineOffset(0, 4)), "a\nb", inheritStyle = true)

		assertEquals("## Tia\nbe", state.blockLines())
		state.assertLook(0, body.merge(styles.header2Style))
		assertFalse(state.carries(1, styles.header2Style))
	}

	@Test
	fun `unbolding a word in a heading whose look is bold leaves the heading's look`() = runTest {
		val config = styles.copy(header4Style = styles.boldStyle)
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.richTextStyles = config
		state.setBlockLines("#### one two")

		state.removeStyleSpan(TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 3)), config.boldStyle)
		state.cursor.updatePosition(CharLineOffset(0, 7))
		state.insertStringAtCursor("x")

		val looks = state.textLines[0].looks()
		assertEquals(FontWeight.Bold, looks[0].fontWeight)
		assertEquals(FontWeight.Bold, looks[5].fontWeight)
	}

	@Test
	fun `a heading look equal to bold stays behind when a join takes the tail`() = runTest {
		val config = styles.copy(header4Style = styles.boldStyle)
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.richTextStyles = config
		state.setBlockLines("plain\n#### bold tail")

		state.delete(TextEditorRange(CharLineOffset(0, 5), CharLineOffset(1, 0)))

		assertEquals("plainbold tail", state.blockLines())
		assertNull(state.textLines[0].looks()[6].fontWeight)
	}
}
