package semantics

import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.input.KeyboardSettings
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import utils.EditorUiTestScope
import utils.editorUiTest
import utils.setBlockLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The clipboard, layout, menu and structure semantics, compared with `BasicTextField`'s. */
@OptIn(ExperimentalTestApi::class)
class SemanticsActionsTest {

	private fun EditorUiTestScope.perform(action: SemanticsPropertyKey<AccessibilityAction<() -> Boolean>>) {
		editorNode().performSemanticsAction(action)
		waitForIdle()
	}

	@Test
	fun `without a selection there is nothing to copy or cut`() = editorUiTest(
		initialText = AnnotatedString("Hello world"),
	) {
		editorNode()
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CopyText))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CutText))
			.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.PasteText))
	}

	@Test
	fun `copy, cut and paste act on the selection and the clipboard`() = editorUiTest(
		initialText = AnnotatedString("Hello world"),
	) {
		dragSelect(0, 5)
		perform(SemanticsActions.CopyText)
		assertEquals("Hello", clipboard.plainText())

		perform(SemanticsActions.CutText)
		assertEquals(" world", text)

		clickAtCharacter(text.length)
		perform(SemanticsActions.PasteText)
		assertEquals(" worldHello", text)
	}

	@Test
	fun `a disabled editor offers copy only`() = editorUiTest(
		initialText = AnnotatedString("Hello world"),
		enabled = false,
	) {
		dragSelect(0, 5)
		editorNode()
			.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.CopyText))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CutText))
			.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.PasteText))
		perform(SemanticsActions.CopyText)
		assertEquals("Hello", clipboard.plainText())
	}

	@Test
	fun `the text layout covers the whole document`() = editorUiTest(
		initialText = AnnotatedString("one\ntwo\nthree"),
		width = 600.dp,
	) {
		val layouts = mutableListOf<TextLayoutResult>()
		val found = editorNode().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
		assertTrue(found)
		val layout = layouts.single()
		assertEquals("one\ntwo\nthree", layout.layoutInput.text.text)
		assertEquals(3, layout.lineCount)
	}

	@Test
	fun `the text layout breaks rows where the editor does`() = editorUiTest(
		width = 160.dp,
		textStyle = TextStyle(textIndent = TextIndent(firstLine = 30.sp)),
	) {
		state.setBlockLines(
			"A first paragraph long enough to wrap onto several rows here.\n" +
				"- a list item that also runs on for a few rows at this width"
		)
		waitForIdle()
		val layouts = mutableListOf<TextLayoutResult>()
		editorNode().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
		val layout = layouts.single()
		assertTrue(state.lineOffsets.size > state.textLines.size, "precondition: the text wraps")
		assertEquals(state.lineOffsets.size, layout.lineCount)
		val editorRowStarts = state.lineOffsets.map { state.wrapStartToCharacterIndex(it) }
		assertEquals(editorRowStarts, (0 until layout.lineCount).map { layout.getLineStart(it) })
	}

	@Test
	fun `a long press opens the context menu`() {
		val menu = TextEditorContextMenuState()
		editorUiTest(initialText = AnnotatedString("Hello"), contextMenuState = menu) {
			perform(SemanticsActions.OnLongClick)
			assertTrue(menu.isVisible)
		}
	}

	@Test
	fun `the composing region is published`() = editorUiTest(
		initialText = AnnotatedString("Hello world"),
	) {
		state.updateComposingRange(6, 11)
		waitForIdle()
		editorNode().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextCompositionRange, TextRange(6, 11)))
	}

	@Test
	fun `links are published as url links that open through the host`() {
		var opened: String? = null
		linksTest(onLinkClick = { opened = it })
		assertEquals("https://example.com", opened)
	}

	private fun linksTest(onLinkClick: (String) -> Unit) = editorUiTest(
		initialText = AnnotatedString("see the docs here"),
		onLinkClick = onLinkClick,
	) {
		state.addRichSpan(8, 12, LinkSpanStyle("https://example.com"))
		waitForIdle()
		val published = editorNode().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)
		assertNotNull(published)
		val link = published.getLinkAnnotations(0, published.length).single()
		assertEquals(8, link.start)
		assertEquals(12, link.end)
		val url = link.item as LinkAnnotation.Url
		assertEquals("https://example.com", url.url)
		url.linkInteractionListener!!.onClick(url)
	}

	@Test
	fun `the host's action key is offered as the IME action`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
	) {
		editorNode().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnImeAction))
		var performed: ImeAction? = null
		state.onImeAction = { performed = it }
		state.keyboardSettings = KeyboardSettings(imeAction = ImeAction.Send)
		waitForIdle()
		editorNode().performSemanticsAction(SemanticsActions.OnImeAction)
		assertEquals(ImeAction.Send, performed)
	}

	@Test
	fun `a content description labels the editable node`() = editorUiTest(
		initialText = AnnotatedString("Hello"),
		contentDescription = "Notes",
	) {
		editorNode().assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Notes")))
	}
}
