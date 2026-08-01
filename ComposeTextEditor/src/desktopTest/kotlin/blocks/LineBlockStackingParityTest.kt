package blocks

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.richstyle.ALL_BLOCK_STYLES
import com.darkrockstudios.texteditor.richstyle.Blockquote
import com.darkrockstudios.texteditor.richstyle.BulletList
import com.darkrockstudios.texteditor.richstyle.CodeFence
import com.darkrockstudios.texteditor.richstyle.LineBlockStyle
import com.darkrockstudios.texteditor.richstyle.OrderedList
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.richstyle.applyLineBlock
import com.darkrockstudios.texteditor.richstyle.lineBlockSpanStyles
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The per-line path (toolbar toggle, undo/redo, Enter on a block line) and the
 * batched path (markdown/HTML import, HTML paste) must resolve a stack of line
 * blocks to the same line.
 *
 * They are split along how a block arrived, so a divergence means the same source
 * produces different block structure depending on whether it was imported or typed.
 * Both resolve through `resolveLineBlock`; this pins that they still do.
 */
class LineBlockStackingParityTest {
	private val testScope = TestScope()
	private val density = Density(1f, 1f)

	private fun freshState(text: String): TextEditorState = TextEditorState(
		scope = testScope,
		measurer = TextMeasurer(
			defaultFontFamilyResolver = createFontFamilyResolver(),
			defaultDensity = density,
			defaultLayoutDirection = LayoutDirection.Ltr,
		),
	).also {
		it.density = density
		it.onViewportSizeChange(Size(500f, 200f))
		it.setText(text)
	}

	/** The document as the two paths have to agree on it: text, styles and block spans. */
	private fun TextEditorState.blockStructure(): List<Pair<AnnotatedString, List<RichSpanStyle>>> =
		textLines.mapIndexed { line, text -> text to lineBlockSpanStyles(line) }

	/**
	 * Applies [blocks] to line 0 of a one-line document down both paths and asserts
	 * they land on the same structure, and that the structure is [expected].
	 *
	 * Parity alone would also hold if both paths stopped placing blocks entirely, so
	 * [expected] pins the outcome as well as the agreement. [existing] is applied to
	 * both first, so the demotion rules are exercised against a line that already
	 * carries a block.
	 */
	private fun assertPathsAgree(
		vararg blocks: LineBlockStyle,
		expected: List<LineBlockStyle>,
		existing: List<LineBlockStyle> = emptyList(),
	) {
		val perLine = freshState("line text")
		existing.forEach { perLine.applyLineBlock(0, it) }
		// The batched path visits a line's blocks in ALL_BLOCK_STYLES order whatever
		// order they were requested in, so the per-line path has to be driven that way
		// for the comparison to be about the rules rather than about the ordering.
		ALL_BLOCK_STYLES.filter { it in blocks }.forEach { perLine.applyLineBlock(0, it) }

		val batched = freshState("line text")
		existing.forEach { batched.applyLineBlock(0, it) }
		batched.applyDocumentBlocks(blockLines = blocks.associateWith { listOf(0) })

		assertEquals(perLine.blockStructure(), batched.blockStructure())
		assertEquals(expected.map { it.spanStyle }, perLine.lineBlockSpanStyles(0))
	}

	@Test
	fun `blockquote stacks with bullet the same way on both paths`() {
		assertPathsAgree(Blockquote, BulletList, expected = listOf(Blockquote, BulletList))
	}

	@Test
	fun `blockquote stacks with an ordered list the same way on both paths`() {
		assertPathsAgree(Blockquote, OrderedList, expected = listOf(Blockquote, OrderedList))
	}

	@Test
	fun `mutually exclusive lists resolve the same way on both paths`() {
		// ALL_BLOCK_STYLES puts OrderedList first, so BulletList demotes it.
		assertPathsAgree(BulletList, OrderedList, expected = listOf(BulletList))
	}

	@Test
	fun `a fence beats every other block the same way on both paths`() {
		assertPathsAgree(
			CodeFence, Blockquote, BulletList, OrderedList,
			expected = listOf(CodeFence),
		)
	}

	@Test
	fun `a block already on the line is a no-op on both paths`() {
		assertPathsAgree(
			BulletList,
			expected = listOf(BulletList),
			existing = listOf(BulletList),
		)
	}

	@Test
	fun `a fence demotes a quoted list the same way on both paths`() {
		assertPathsAgree(
			CodeFence,
			expected = listOf(CodeFence),
			existing = listOf(Blockquote, BulletList),
		)
	}

	@Test
	fun `a list demotes an existing fence the same way on both paths`() {
		assertPathsAgree(
			OrderedList,
			expected = listOf(OrderedList),
			existing = listOf(CodeFence),
		)
	}

	@Test
	fun `an empty line carries the same zero-width span on both paths`() {
		val perLine = freshState("")
		perLine.applyLineBlock(0, BulletList)

		val batched = freshState("")
		batched.applyDocumentBlocks(blockLines = mapOf(BulletList to listOf(0)))

		assertEquals(perLine.blockStructure(), batched.blockStructure())
		assertEquals(listOf(BulletList.spanStyle), perLine.lineBlockSpanStyles(0))
		val zeroWidth = TextEditorRange(CharLineOffset(0, 0), CharLineOffset(0, 0))
		assertEquals(
			setOf(zeroWidth),
			perLine.richSpanManager.getAllRichSpans().mapTo(mutableSetOf()) { it.range },
		)
		assertEquals(
			perLine.richSpanManager.getAllRichSpans().mapTo(mutableSetOf()) { it.range },
			batched.richSpanManager.getAllRichSpans().mapTo(mutableSetOf()) { it.range },
		)
	}
}
