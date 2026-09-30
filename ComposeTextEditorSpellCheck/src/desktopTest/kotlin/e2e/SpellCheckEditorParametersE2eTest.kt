package e2e

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.isCtrlPressed
import com.darkrockstudios.texteditor.RichSpanClick
import com.darkrockstudios.texteditor.contextmenu.ContextMenuStrings
import com.darkrockstudios.texteditor.richstyle.HighlightSpanStyle
import com.darkrockstudios.texteditor.richstyle.LinkSpanStyle
import com.darkrockstudios.texteditor.state.SpanClickType
import utils.CountingSpellChecker
import utils.spellCheckUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The editor parameters [com.darkrockstudios.texteditor.spellcheck.SpellCheckingTextEditor] passes on. */
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
}
