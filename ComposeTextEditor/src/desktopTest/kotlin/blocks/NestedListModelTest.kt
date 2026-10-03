package blocks

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.richstyle.BULLET_LISTS
import com.darkrockstudios.texteditor.richstyle.BULLET_LIST_PARAGRAPH_STYLE
import com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle
import com.darkrockstudios.texteditor.richstyle.BulletList
import com.darkrockstudios.texteditor.richstyle.BulletListSpanStyle
import com.darkrockstudios.texteditor.richstyle.MAX_LIST_LEVEL
import com.darkrockstudios.texteditor.richstyle.ORDERED_LISTS
import com.darkrockstudios.texteditor.richstyle.OrderedListSpanStyle
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.richstyle.atListLevel
import com.darkrockstudios.texteditor.richstyle.lineBlocksConflict
import com.darkrockstudios.texteditor.richstyle.listBlockAt
import com.darkrockstudios.texteditor.richstyle.listLevel
import com.darkrockstudios.texteditor.richstyle.listParagraphStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The nested list model: per-level singletons, one list block per line, levels clamped to what a predecessor allows. */
class NestedListModelTest {

	@Test
	fun `the bare style names are level zero and levels are singletons`() {
		assertSame(BulletListSpanStyle, BulletListSpanStyle.of(0))
		assertSame(OrderedListSpanStyle, OrderedListSpanStyle.of(0))
		assertSame(BulletListSpanStyle.of(2), BulletListSpanStyle.of(2))
		assertNotSame<Any>(BulletListSpanStyle.of(1), BulletListSpanStyle.of(2))
		assertSame(BulletListSpanStyle.of(MAX_LIST_LEVEL), BulletListSpanStyle.of(MAX_LIST_LEVEL + 5))
		assertSame(BulletListSpanStyle, BulletListSpanStyle.of(-1))
		assertEquals(2, BulletListSpanStyle.of(2).level)
	}

	@Test
	fun `list blocks at any level exclude each other and stack with a quote`() {
		assertTrue(lineBlocksConflict(BulletListSpanStyle.of(1), BulletListSpanStyle.of(2)))
		assertTrue(lineBlocksConflict(BulletListSpanStyle.of(1), OrderedListSpanStyle.of(1)))
		assertFalse(lineBlocksConflict(BulletListSpanStyle.of(3), BulletListSpanStyle.of(3)))
		assertFalse(lineBlocksConflict(BulletListSpanStyle.of(3), com.darkrockstudios.texteditor.richstyle.BlockquoteSpanStyle))
	}

	@Test
	fun `a block knows its level and kind`() {
		assertEquals(0, BulletList.listLevel)
		assertEquals(3, ORDERED_LISTS[3].listLevel)
		assertSame(ORDERED_LISTS[2], ORDERED_LISTS[0].atListLevel(2))
		assertSame(BULLET_LISTS[MAX_LIST_LEVEL], BULLET_LISTS[1].atListLevel(99))
		assertSame(BULLET_LIST_PARAGRAPH_STYLE, listParagraphStyle(0))
		assertEquals(listParagraphStyle(2), BULLET_LISTS[2].paragraphStyle)
	}

	@Test
	fun `the model keeps an orphaned level and gives each level its own indent`() = runTest {
		// Deleting a parent leaves its children where they are, as Google Docs
		// does; export writes an orphan at the level its predecessor allows.
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true))
		state.setText(AnnotatedString("a\nb\nc"))
		state.applyDocumentBlocks(
			blockLines = mapOf(BulletListSpanStyle.of(0) to listOf(0), BulletListSpanStyle.of(3) to listOf(1), OrderedListSpanStyle.of(2) to listOf(2)),
		)
		assertSame(BULLET_LISTS[0], state.listBlockAt(0))
		assertSame(BULLET_LISTS[3], state.listBlockAt(1))
		assertSame(ORDERED_LISTS[2], state.listBlockAt(2))
		assertEquals(listOf(listParagraphStyle(3)), state.textLines[1].paragraphStyles.map { it.item })
		assertEquals(listOf(listParagraphStyle(2)), state.textLines[2].paragraphStyles.map { it.item })
	}
}
