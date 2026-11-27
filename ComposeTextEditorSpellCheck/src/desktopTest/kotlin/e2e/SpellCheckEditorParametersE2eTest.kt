package e2e

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.EditorLineLimits
import com.darkrockstudios.texteditor.RichSpanClick
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.contextmenu.ContextMenuStrings
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.input.MacKeyBindings
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.spellcheck.SpellCheckMode
import com.darkrockstudios.texteditor.spellcheck.SpellCheckStrings
import com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor
import com.darkrockstudios.texteditor.spellcheck.api.Correction
import com.darkrockstudios.texteditor.spellcheck.api.Suggestion
import com.darkrockstudios.texteditor.spellcheck.diagnostics.LineCheck
import com.darkrockstudios.texteditor.spellcheck.diagnostics.LineDiagnostic
import com.darkrockstudios.texteditor.spellcheck.diagnostics.TextDiagnosticsChecker
import com.darkrockstudios.texteditor.state.SpanClickType
import com.darkrockstudios.texteditor.state.TextEditorState
import utils.CountingSpellChecker
import utils.GatedSuggestions
import utils.setScanningContent
import utils.spellCheckUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The editor parameters [com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor] passes on. */
@OptIn(ExperimentalTestApi::class)
class SpellCheckEditorParametersE2eTest {

	private val document = "see the docs here"
	private val url = "https://example.com/docs"
	private val checker = CountingSpellChecker(correctWords = setOf("see", "the", "docs", "here"))

	@Test
	fun `ctrl+click opens a link and a plain click does not`() {
		val opened = mutableListOf<String>()
		spellCheckUiTest(spellChecker = checker, initialText = document, onLinkClick = { opened += it }) {
			// "docs" is characters 8 until 12.
			state.textState.addRichSpan(8, 12, LinkSpanStyle(url))
			waitForIdle()

			clickAtCharacter(9)
			assertTrue(opened.isEmpty(), "a plain click places the caret")

			clickAtCharacter(9, ctrl = true)
			assertEquals(listOf(url), opened)
		}
	}

	@Test
	fun `a click on a host span reaches the event listener with its modifier keys`() {
		val clicks = mutableListOf<RichSpanClick>()
		spellCheckUiTest(spellChecker = checker, initialText = document, onRichSpanClickEvent = { clicks += it; true }) {
			state.textState.addRichSpan(8, 12, HighlightSpanStyle(Color.Yellow))
			waitForIdle()

			clickAtCharacter(9, ctrl = true)

			val click = clicks.single()
			assertEquals(SpanClickType.PRIMARY_CLICK, click.type)
			assertTrue(click.keyboardModifiers.isCtrlPressed)
		}
	}

	@Test
	fun `both listeners hear a host span click once`() {
		val events = mutableListOf<String>()
		spellCheckUiTest(
			spellChecker = checker,
			initialText = document,
			onRichSpanClick = { _, _, _ -> events += "listener"; true },
			onRichSpanClickEvent = { events += "event"; true },
		) {
			state.textState.addRichSpan(8, 12, HighlightSpanStyle(Color.Yellow))
			waitForIdle()

			clickAtCharacter(9)

			assertEquals(listOf("listener", "event"), events)
		}
	}

	@Test
	fun `a right-click on a flagged word is the spell checker's, not the host's`() {
		val clicks = mutableListOf<RichSpanClick>()
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("fine")),
			initialText = "fine zorp",
			onRichSpanClickEvent = { clicks += it; true },
		) {
			rightClickAtCharacter(6)
			awaitMenuItem("Ignore")

			assertTrue(clicks.isEmpty())
		}
	}

	@Test
	fun `a tap on a host span reaches the event listener`() {
		val clicks = mutableListOf<RichSpanClick>()
		spellCheckUiTest(spellChecker = checker, initialText = document, onRichSpanClickEvent = { clicks += it; true }) {
			state.textState.addRichSpan(8, 12, HighlightSpanStyle(Color.Yellow))
			waitForIdle()

			tapAtCharacter(9)

			assertEquals(listOf(SpanClickType.TAP), clicks.map { it.type })
		}
	}

	@Test
	fun `a right-click on a host span opens the standard menu and reaches the host`() {
		val clicks = mutableListOf<RichSpanClick>()
		spellCheckUiTest(spellChecker = checker, initialText = document, onRichSpanClickEvent = { clicks += it; true }) {
			state.textState.addRichSpan(8, 12, HighlightSpanStyle(Color.Yellow))
			waitForIdle()

			rightClickAtCharacter(9)

			assertTrue(hasMenuItem(ContextMenuStrings.Default.selectAll))
			assertEquals(listOf(SpanClickType.SECONDARY_CLICK), clicks.map { it.type })
		}
	}

	@Test
	fun `a click on a flagged word is not passed on`() {
		val clicks = mutableListOf<RichSpanClick>()
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("fine")),
			initialText = "fine zorp",
			onRichSpanClickEvent = { clicks += it; true },
		) {
			assertEquals(1, spellCheckSpanCount)

			clickAtCharacter(6)

			assertTrue(clicks.isEmpty())
		}
	}

	@Test
	fun `a right-click on a misspelled link offers its suggestions`() {
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("see", "the", "here"), suggestions = listOf("docs")),
			initialText = "see the dosc here",
		) {
			state.textState.addRichSpan(8, 12, LinkSpanStyle(url))
			waitForIdle()
			assertEquals(1, spellCheckSpanCount)

			rightClickAtCharacter(9)

			awaitMenuItem("docs")
		}
	}

	@Test
	fun `the key bindings choose the link chord`() {
		val opened = mutableListOf<String>()
		spellCheckUiTest(
			spellChecker = checker,
			initialText = document,
			onLinkClick = { opened += it },
			keyBindings = MacKeyBindings,
		) {
			state.textState.addRichSpan(8, 12, LinkSpanStyle(url))
			waitForIdle()

			clickAtCharacter(9, ctrl = true)
			assertTrue(opened.isEmpty(), "Ctrl+click is not the macOS chord")

			clickAtCharacter(9, meta = true)
			assertEquals(listOf(url), opened)
		}
	}

	@Test
	fun `the content description labels the editor`() {
		spellCheckUiTest(spellChecker = checker, initialText = document, contentDescription = "Notes") {
			test.onNodeWithContentDescription("Notes").assertExists()
		}
	}

	@Test
	fun `a single-line editor keeps its text to one line`() {
		spellCheckUiTest(spellChecker = checker, lineLimits = EditorLineLimits.SingleLine) {
			typeText("see\nthe")

			assertEquals("seethe", state.textState.getAllText().text)
		}
	}

	// --- read-only ------------------------------------------------------------

	private val flagging = CountingSpellChecker(correctWords = setOf("fine"), suggestions = listOf("brokenwork"))

	@Test
	fun `a read-only editor takes no typing`() {
		spellCheckUiTest(spellChecker = flagging, initialText = "fine brokenword", readOnly = true) {
			typeText("x")

			assertEquals("fine brokenword", state.textState.getAllText().text)
		}
	}

	@Test
	fun `a read-only editor offers Ignore on a flagged word but no corrections`() {
		val added = mutableListOf<String>()
		spellCheckUiTest(
			spellChecker = flagging,
			initialText = "fine brokenword",
			readOnly = true,
			onAddToDictionary = { added += it },
		) {
			rightClickAtCharacter(7)
			awaitMenuItem("Ignore")

			assertTrue(hasMenuItem("Add to dictionary"))
			assertFalse(hasMenuItem("brokenwork"))
			assertFalse(hasMenuItem("Loading..."))
			assertFalse(hasMenuItem("No suggestions"))

			clickMenuItem("Ignore")
			assertEquals(0, spellCheckSpanCount)
			assertEquals("fine brokenword", state.textState.getAllText().text)
		}
	}

	private fun sentenceIssueMenu(readOnly: Boolean, block: (offersCorrection: Boolean) -> Unit) {
		val brokenRange = TextEditorRange(CharLineOffset(0, 5), CharLineOffset(0, 15))
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(
				sentenceCorrections = { _, _ -> listOf(Correction(brokenRange, "brokenword", listOf(Suggestion("broken word")))) },
			),
			initialText = "fine brokenword fine",
			spellCheckMode = SpellCheckMode.Sentence,
			readOnly = readOnly,
		) {
			rightClickAtCharacter(7)
			awaitMenuItem("Ignore")
			block(hasMenuItem("broken word"))
		}
	}

	@Test
	fun `a read-only editor offers no correction for a sentence issue`() {
		sentenceIssueMenu(readOnly = false) { assertTrue(it) }
		sentenceIssueMenu(readOnly = true) { assertFalse(it) }
	}

	private fun diagnosticMenu(readOnly: Boolean, block: (offersFix: Boolean) -> Unit) {
		val repeats = TextDiagnosticsChecker { lines ->
			lines.map { line ->
				Regex("the the").findAll(line).map { LineDiagnostic(it.range.first, it.range.last + 1, "Repeated word", listOf("the")) }.toList().let(::LineCheck)
			}
		}
		spellCheckUiTest(
			spellChecker = CountingSpellChecker(correctWords = setOf("over", "the", "hill")),
			initialText = "over the the hill",
			diagnosticsChecker = repeats,
			readOnly = readOnly,
		) {
			rightClickAtCharacter(7)
			awaitMenuItem("Repeated word")
			block(hasMenuItem("the"))
		}
	}

	@Test
	fun `a read-only editor shows a diagnostic's message but not its fixes`() {
		diagnosticMenu(readOnly = false) { assertTrue(it) }
		diagnosticMenu(readOnly = true) { assertFalse(it) }
	}

	// --- a host's context menu state ----------------------------------------------

	private val misspelled = "fine brokenword fine"
	private fun suggesting() = CountingSpellChecker(correctWords = setOf("fine"), suggestions = listOf("brokenwork"))

	@Test
	fun `a host's menu state sees the spell check menu open and can close it`() {
		val menu = TextEditorContextMenuState()
		spellCheckUiTest(spellChecker = suggesting(), initialText = misspelled, contextMenuState = menu) {
			assertFalse(menu.isVisible)

			rightClickAtCharacter(7)
			awaitMenuItem("brokenwork")
			assertTrue(menu.isVisible)

			test.runOnIdle { menu.dismissMenu() }
			test.waitForIdle()
			assertFalse(menu.isVisible)
			assertFalse(hasMenuItem("brokenwork"))
			assertFalse(hasMenuItem("Ignore"))
		}
	}

	@Test
	fun `shift+f10 fills a host's menu state with suggestions`() {
		val menu = TextEditorContextMenuState()
		spellCheckUiTest(spellChecker = suggesting(), initialText = misspelled, contextMenuState = menu) {
			state.textState.cursor.updatePosition(CharLineOffset(0, 7))
			press(Key.F10, shift = true)

			awaitMenuItem("brokenwork")
			assertTrue(menu.isVisible)
		}
	}

	@Test
	fun `suggestions that arrive after a host closed the menu do not reopen it`() {
		val menu = TextEditorContextMenuState()
		val checker = GatedSuggestions(suggesting())
		spellCheckUiTest(spellChecker = checker, initialText = misspelled, contextMenuState = menu) {
			rightClickAtCharacter(7)
			awaitMenuItem(SpellCheckStrings.Default.loading)

			test.runOnIdle { menu.dismissMenu() }
			checker.release()
			test.waitForIdle()

			assertFalse(menu.isVisible)
			assertFalse(hasMenuItem("brokenwork"))
		}
	}

	@Test
	fun `an editor leaving composition closes the menu on a host's state`() = runSkikoComposeUiTest {
		val menu = TextEditorContextMenuState()
		var shown by mutableStateOf(true)
		val checker = suggesting()
		setScanningContent {
			if (shown) SpellCheckingTextEditor(spellChecker = checker, contextMenuState = menu)
		}
		waitForIdle()
		assertNotNull(menu.onOpenedAtCaret)
		runOnIdle { menu.showMenu(Offset(1f, 1f)) }
		waitForIdle()

		shown = false
		waitForIdle()

		assertFalse(menu.isVisible)
		assertNull(menu.onOpenedAtCaret)
	}

	@Test
	fun `a state the editor is no longer given is closed and released`() = runSkikoComposeUiTest {
		val first = TextEditorContextMenuState()
		val second = TextEditorContextMenuState()
		var menu by mutableStateOf(first)
		val checker = suggesting()
		setScanningContent {
			SpellCheckingTextEditor(spellChecker = checker, contextMenuState = menu)
		}
		waitForIdle()
		runOnIdle { first.showMenu(Offset(1f, 1f)) }
		waitForIdle()

		menu = second
		waitForIdle()

		assertFalse(first.isVisible)
		assertNull(first.onOpenedAtCaret)
		assertNotNull(second.onOpenedAtCaret)
	}

	@Test
	fun `an editor leaving composition keeps a caret handler the host set since`() = runSkikoComposeUiTest {
		val menu = TextEditorContextMenuState()
		val hostHandler: (TextEditorState) -> Unit = {}
		var shown by mutableStateOf(true)
		val checker = suggesting()
		setScanningContent {
			if (shown) SpellCheckingTextEditor(spellChecker = checker, contextMenuState = menu)
		}
		waitForIdle()
		runOnIdle { menu.onOpenedAtCaret = hostHandler }

		shown = false
		waitForIdle()

		assertSame(hostHandler, menu.onOpenedAtCaret)
	}
}
