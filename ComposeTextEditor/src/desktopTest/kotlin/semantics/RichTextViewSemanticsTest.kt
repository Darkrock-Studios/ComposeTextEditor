package semantics

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isNotFocusable
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.RichTextView
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import utils.InMemoryClipboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A read-only view publishes its text as a text view does: to read, and when selectable, to select and copy. */
@OptIn(ExperimentalTestApi::class)
class RichTextViewSemanticsTest {

	private val document = "Hello world, a read-only view that wraps across a few rows at this width."

	private fun viewTest(
		isSelectable: Boolean,
		onLinkClick: ((String) -> Unit)? = null,
		block: ComposeUiTest.(state: TextEditorState, clipboard: InMemoryClipboard) -> Unit,
	) = runComposeUiTest {
		val clipboard = InMemoryClipboard()
		lateinit var state: TextEditorState
		setContent {
			state = rememberTextEditorState(initialText = AnnotatedString(document))
			CompositionLocalProvider(LocalClipboard provides clipboard) {
				RichTextView(
					state = state,
					modifier = Modifier.width(200.dp),
					contentPadding = PaddingValues(12.dp),
					isSelectable = isSelectable,
					onLinkClick = onLinkClick,
				)
			}
		}
		waitForIdle()
		block(state, clipboard)
	}

	private fun ComposeUiTest.viewNode(): SemanticsNodeInteraction =
		onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)

	@Test
	fun `a view publishes its text and layout`() = viewTest(isSelectable = false) { state, _ ->
		viewNode()
			.assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(document))))
			.assert(isNotFocusable())
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetSelection))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CopyText))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.EditableText))

		val layouts = mutableListOf<TextLayoutResult>()
		assertTrue(viewNode().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts))
		val layout = layouts.single()
		assertTrue(state.lineOffsets.size > 1, "precondition: the text wraps")
		assertEquals(
			state.lineOffsets.map { state.wrapStartToCharacterIndex(it) },
			(0 until layout.lineCount).map { layout.getLineStart(it) },
		)
	}

	@Test
	fun `a selectable view selects and copies but never edits`() = viewTest(isSelectable = true) { state, clipboard ->
		viewNode()
			.assert(isFocusable())
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.PasteText))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CopyText))

		viewNode().performSemanticsAction(SemanticsActions.SetSelection) { it(0, 5, false) }
		waitForIdle()
		assertEquals("Hello", state.selector.getSelectedText().text)
		viewNode()
			.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 5)))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CutText))

		viewNode().performSemanticsAction(SemanticsActions.CopyText)
		waitForIdle()
		assertEquals("Hello", clipboard.plainText())
	}

	@Test
	fun `links in a view open through the host`() {
		var opened: String? = null
		viewTest(isSelectable = false, onLinkClick = { opened = it }) { state, _ ->
			state.addRichSpan(6, 11, LinkSpanStyle("https://example.com"))
			waitForIdle()
			val text = viewNode().fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.single()
			assertNotNull(text)
			val link = text.getLinkAnnotations(0, text.length).single()
			assertEquals(6 to 11, link.start to link.end)
			val url = link.item as LinkAnnotation.Url
			url.linkInteractionListener!!.onClick(url)
		}
		assertEquals("https://example.com", opened)
	}
}
