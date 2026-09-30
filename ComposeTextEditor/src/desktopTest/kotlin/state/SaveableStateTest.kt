package state

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.LineWrap
import com.darkrockstudios.texteditor.markdown.withMarkdown
import com.darkrockstudios.texteditor.richstyle.RichSpanStyle
import com.darkrockstudios.texteditor.richstyle.SpellCheckStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberSaveableTextEditorState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [rememberSaveableTextEditorState] across a save and restore. The saved value goes
 * through Java serialization on the way, as an Android Bundle stores it.
 */
@OptIn(ExperimentalTestApi::class)
class SaveableStateTest {

	/** A host's own rich span style, which only a host saver knows how to keep. */
	private class NoteStyle(val note: String) : RichSpanStyle {
		override fun DrawScope.drawCustomStyle(
			layoutResult: TextLayoutResult,
			lineWrap: LineWrap,
			textRange: TextRange,
			state: TextEditorState,
		) = Unit

		override fun equals(other: Any?) = other is NoteStyle && other.note == note
		override fun hashCode() = note.hashCode()
	}

	private val noteSaver = Saver<RichSpanStyle, Any>(
		save = { (it as? NoteStyle)?.note },
		restore = { NoteStyle(it as String) },
	)

	/**
	 * Composes [content] under a registry that can be saved, serialized, and restored
	 * from, the way `StateRestorationTester` does (which does not run on desktop yet).
	 */
	private class Restorer(private val test: ComposeUiTest) : SaveableStateRegistry {
		private var registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { it is Serializable })
		private var shown by mutableStateOf(true)

		fun setContent(content: @Composable () -> Unit) = test.setContent {
			CompositionLocalProvider(LocalSaveableStateRegistry provides this) {
				if (shown) content()
			}
		}

		fun saveAndRestore() {
			test.runOnIdle {
				val saved = serializeRoundTrip(registry.performSave())
				shown = false
				registry = SaveableStateRegistry(restoredValues = saved, canBeSaved = { it is Serializable })
			}
			test.runOnIdle { shown = true }
			test.waitForIdle()
		}

		override fun canBeSaved(value: Any) = registry.canBeSaved(value)
		override fun consumeRestored(key: String) = registry.consumeRestored(key)
		override fun performSave() = registry.performSave()
		override fun registerProvider(key: String, valueProvider: () -> Any?) =
			registry.registerProvider(key, valueProvider)

		@Suppress("UNCHECKED_CAST")
		private fun serializeRoundTrip(map: Map<String, List<Any?>>): Map<String, List<Any?>> {
			val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(HashMap(map)) } }
			return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() } as Map<String, List<Any?>>
		}
	}

	private val bold = SpanStyle(fontWeight = FontWeight.Bold)
	private val rich = SpanStyle(
		color = Color.Red,
		fontSize = 20.sp,
		fontStyle = FontStyle.Italic,
		textDecoration = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)),
		background = Color(0x3300FF00),
		fontFamily = FontFamily.Monospace,
	)

	@Test
	fun `text, styles, blocks, caret and selection survive a restore`() = runComposeUiTest {
		val restorer = Restorer(this)
		lateinit var state: TextEditorState
		restorer.setContent {
			state = rememberSaveableTextEditorState(richSpanStyleSaver = noteSaver)
			BasicTextEditor(state = state, modifier = Modifier.size(400.dp, 300.dp))
		}
		lateinit var linesBefore: List<AnnotatedString>
		lateinit var spansBefore: Set<Any>
		runOnIdle {
			state.withMarkdown().importMarkdown(
				"# Title\n\nSome **bold** text with a [link](https://example.com).\n\n" +
					"- one\n- two\n\n> quoted\n\n```\ncode\n```\n\n---\n\nlast"
			)
			state.insertStringAtCursor(buildAnnotatedString { withStyle(rich) { append("styled ") } })
			state.addRichSpan(0, 2, NoteStyle("a note"))
			state.addRichSpan(10, 14, SpellCheckStyle)
			state.selector.updateSelection(CharLineOffset(2, 0), CharLineOffset(2, 4))
			linesBefore = state.snapshot().lines
			spansBefore = state.snapshot().richSpans.filterNot { it.style.isDecoration }.toSet()
		}
		val caretBefore = state.cursorPosition
		val selectionBefore = state.selector.selection
		val old = state

		restorer.saveAndRestore()

		assertNotSame(old, state, "a new state was restored")
		assertEquals(linesBefore.map { it.text }, state.textLines.map { it.text })
		assertEquals(linesBefore.map { it.spanStyles }, state.textLines.map { it.spanStyles })
		assertEquals(linesBefore.map { it.paragraphStyles }, state.textLines.map { it.paragraphStyles })
		assertEquals(spansBefore, state.snapshot().richSpans)
		assertEquals(caretBefore, state.cursorPosition)
		assertEquals(selectionBefore, state.selector.selection)
		assertFalse(state.canUndo, "the undo history is not kept")
	}

	@Test
	fun `stacked blocks keep both indents`() = runComposeUiTest {
		val restorer = Restorer(this)
		lateinit var state: TextEditorState
		restorer.setContent {
			state = rememberSaveableTextEditorState()
			BasicTextEditor(state = state, modifier = Modifier.size(400.dp, 300.dp))
		}
		lateinit var before: List<AnnotatedString>
		runOnIdle {
			state.withMarkdown().importMarkdown("> - quoted item\n> # quoted heading\n\nplain")
			before = state.snapshot().lines
		}
		assertTrue(before.any { it.paragraphStyles.size > 1 }, "precondition: a line with two blocks")

		restorer.saveAndRestore()

		assertEquals(before.map { it.paragraphStyles }, state.textLines.map { it.paragraphStyles })
		assertEquals(before.map { it.spanStyles }, state.textLines.map { it.spanStyles })
	}

	@Test
	fun `nested list levels and a fence language survive a restore`() = runComposeUiTest {
		val restorer = Restorer(this)
		lateinit var state: TextEditorState
		restorer.setContent {
			state = rememberSaveableTextEditorState()
			BasicTextEditor(state = state, modifier = Modifier.size(400.dp, 300.dp))
		}
		val markdown = "1. a\n   - b\n     1. c\n2. d\n\n```kotlin\nfun f() = 1\n```"
		lateinit var linesBefore: List<AnnotatedString>
		lateinit var spansBefore: Set<Any>
		runOnIdle {
			state.withMarkdown().importMarkdown(markdown)
			linesBefore = state.snapshot().lines
			spansBefore = state.snapshot().richSpans
		}

		restorer.saveAndRestore()

		assertEquals(spansBefore, state.snapshot().richSpans)
		// The nested indents come back equal to the ones their levels strip.
		assertEquals(linesBefore.map { it.paragraphStyles }, state.textLines.map { it.paragraphStyles })
		assertEquals(markdown, state.withMarkdown().exportAsMarkdown())
	}

	@Test
	fun `a saver that saves what cannot be saved says so`() = runComposeUiTest {
		val restorer = Restorer(this)
		lateinit var state: TextEditorState
		val badSaver = Saver<RichSpanStyle, Any>(save = { Any() }, restore = { null })
		restorer.setContent {
			state = rememberSaveableTextEditorState(AnnotatedString("Hello"), richSpanStyleSaver = badSaver)
		}
		runOnIdle { state.addRichSpan(0, 5, NoteStyle("x")) }
		val failure = kotlin.runCatching { restorer.saveAndRestore() }.exceptionOrNull()
		assertTrue(failure?.message.orEmpty().contains("richSpanStyleSaver"), "got $failure")
	}

	@Test
	fun `a style no saver knows is dropped`() = runComposeUiTest {
		val restorer = Restorer(this)
		lateinit var state: TextEditorState
		restorer.setContent {
			state = rememberSaveableTextEditorState(AnnotatedString("Hello world"))
			BasicTextEditor(state = state, modifier = Modifier.size(400.dp, 300.dp))
		}
		runOnIdle {
			state.addRichSpan(0, 5, NoteStyle("gone"))
			state.addStyleSpan(com.darkrockstudios.texteditor.TextEditorRange(CharLineOffset(0, 6), CharLineOffset(0, 11)), bold)
		}
		restorer.saveAndRestore()
		assertEquals("Hello world", state.getAllText().text)
		assertTrue(state.snapshot().richSpans.isEmpty())
		assertTrue(state.getAllText().spanStyles.any { it.item == bold && it.start == 6 && it.end == 11 })
	}

	@Test
	fun `the first visible line is scrolled back to`() = runComposeUiTest {
		val restorer = Restorer(this)
		lateinit var state: TextEditorState
		restorer.setContent {
			state = rememberSaveableTextEditorState(AnnotatedString((1..200).joinToString("\n") { "line $it" }))
			BasicTextEditor(state = state, modifier = Modifier.size(400.dp, 200.dp))
		}
		runOnIdle { state.scrollManager.scrollToPosition(CharLineOffset(120, 0), top = true, animated = false) }
		waitForIdle()
		val topBefore = state.scrollManager.firstVisibleOffset
		assertTrue(topBefore.line > 100, "precondition: scrolled down, at $topBefore")

		restorer.saveAndRestore()

		assertEquals(topBefore, state.scrollManager.firstVisibleOffset)
		assertNull(state.restoredFirstVisible)
	}

	@Test
	fun `a state saved again before it is laid out keeps its top line`() = runComposeUiTest {
		val restorer = Restorer(this)
		lateinit var state: TextEditorState
		var shown by mutableStateOf(true)
		restorer.setContent {
			state = rememberSaveableTextEditorState(AnnotatedString((1..200).joinToString("\n") { "line $it" }))
			if (shown) BasicTextEditor(state = state, modifier = Modifier.size(400.dp, 200.dp))
		}
		runOnIdle { state.scrollManager.scrollToPosition(CharLineOffset(120, 0), top = true, animated = false) }
		waitForIdle()
		val topBefore = state.scrollManager.firstVisibleOffset

		runOnIdle { shown = false }
		restorer.saveAndRestore()
		assertEquals(topBefore, state.restoredFirstVisible, "not laid out, so still owed")
		restorer.saveAndRestore()
		runOnIdle { shown = true }
		waitForIdle()

		assertEquals(topBefore, state.scrollManager.firstVisibleOffset)
	}
}
