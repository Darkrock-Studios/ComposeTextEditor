package clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.darkrockstudios.texteditor.clipboard.ClipboardHelper
import com.darkrockstudios.texteditor.clipboard.readClipboardHtml
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Android's clipboard carries an HTML flavor beside the text (`ClipData.newHtmlText`)
 * and the copy id in the description's extras, so bold and italic survive a copy and
 * paste between editors and to and from other apps.
 */
class AndroidRichClipboardTest {

	private val config = MarkdownConfiguration.DEFAULT

	@AfterTest
	fun tearDown() = unmockkAll()

	private fun item(text: String?, html: String?): ClipData.Item = mockk {
		every { this@mockk.text } returns text
		every { htmlText } returns html
	}

	private fun clipboardHolding(vararg items: ClipData.Item, copyId: Long? = null): Clipboard {
		val extras = mockk<PersistableBundle> {
			every { containsKey(any()) } returns (copyId != null)
			every { getLong(any(), any()) } returns (copyId ?: 0L)
		}
		val description = mockk<ClipDescription> { every { this@mockk.extras } returns extras }
		val clip = mockk<ClipData> {
			every { itemCount } returns items.size
			every { getItemAt(any()) } answers { items[firstArg()] }
			every { this@mockk.description } returns description
		}
		return mockk { coEvery { getClipEntry() } returns ClipEntry(clip) }
	}

	@Test
	fun `a paste reads the html flavor`() = runTest {
		val clipboard = clipboardHolding(item("plain bold", "plain <b>bold</b>"))
		val text = ClipboardHelper.getText(clipboard, config)!!
		assertEquals("plain bold", text.text)
		assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 6 && it.end == 10 })
		assertEquals("plain <b>bold</b>", readClipboardHtml(clipboard))
	}

	@Test
	fun `a clip without markup pastes its text`() = runTest {
		val clipboard = clipboardHolding(item("just text", null))
		assertEquals(AnnotatedString("just text"), ClipboardHelper.getText(clipboard, config))
		assertNull(readClipboardHtml(clipboard))
	}

	@Test
	fun `a plain paste reads the text flavor`() = runTest {
		val clipboard = clipboardHolding(item("a | b", "<table><tr><td>a</td><td>b</td></tr></table>"))
		assertEquals("a | b", ClipboardHelper.getPlainText(clipboard))
	}

	@Test
	fun `several items paste one per line`() = runTest {
		val clipboard = clipboardHolding(item("one", "<i>one</i>"), item("two", null))
		assertEquals("one\ntwo", ClipboardHelper.getText(clipboard, config)!!.text)
	}

	@Test
	fun `the copy id rides in the description extras`() = runTest {
		// As a paste reads them: the text, then the markup, then the copy id, from one read.
		val ours = clipboardHolding(item("x", null), copyId = 42L)
		ClipboardHelper.getText(ours, config)
		readClipboardHtml(ours)
		assertEquals(42L, ClipboardHelper.readCopyId(ours))
		coVerify(exactly = 1) { ours.getClipEntry() }

		val foreign = clipboardHolding(item("x", null))
		ClipboardHelper.getText(foreign, config)
		assertNull(ClipboardHelper.readCopyId(foreign))
		assertTrue(ClipboardHelper.supportsCopyProvenance)
	}

	@Test
	fun `this editor's own copy pastes the characters it copied`() = runTest {
		// Markup that re-parses to other text (here, collapsing the double space).
		val clipboard = clipboardHolding(item("a  b", "a  <b>b</b>"), copyId = 3L)
		assertEquals(AnnotatedString("a  b"), ClipboardHelper.getText(clipboard, config))
		ClipboardHelper.readCopyId(clipboard)
	}

	@Test
	fun `a copy too large for markup copies its text`() = runTest {
		mockkStatic(ClipData::class)
		mockkStatic(android.util.Log::class)
		every { android.util.Log.w(any(), any<String>(), any()) } returns 0
		val rich = mockk<ClipData>(relaxed = true)
		val plain = mockk<ClipData>(relaxed = true)
		every { ClipData.newHtmlText(any(), any(), any()) } returns rich
		every { ClipData.newPlainText(any(), any()) } returns plain
		val clipboard = mockk<Clipboard>()
		coEvery { clipboard.setClipEntry(match { it?.clipData === rich }) } throws RuntimeException("too large")
		coEvery { clipboard.setClipEntry(match { it?.clipData === plain }) } returns Unit

		assertTrue(ClipboardHelper.setText(clipboard, AnnotatedString("big"), config, copyId = null, html = null))

		coVerify { clipboard.setClipEntry(match { it?.clipData === plain }) }
	}

	@Test
	fun `a copy the clipboard refuses reports it`() = runTest {
		mockkStatic(ClipData::class)
		mockkStatic(android.util.Log::class)
		every { android.util.Log.w(any(), any<String>(), any()) } returns 0
		every { ClipData.newHtmlText(any(), any(), any()) } returns mockk(relaxed = true)
		every { ClipData.newPlainText(any(), any()) } returns mockk(relaxed = true)
		val clipboard = mockk<Clipboard>()
		coEvery { clipboard.setClipEntry(any()) } throws RuntimeException("refused")

		assertFalse(ClipboardHelper.setText(clipboard, AnnotatedString("x"), config, copyId = null, html = null))
	}

	@Test
	fun `a copy writes html beside the text and the copy id`() = runTest {
		mockkStatic(ClipData::class)
		mockkConstructor(PersistableBundle::class)
		every { anyConstructed<PersistableBundle>().putLong(any(), any()) } returns Unit
		val description = mockk<ClipDescription>(relaxed = true)
		val clip = mockk<ClipData> { every { this@mockk.description } returns description }
		val html = slot<String>()
		every { ClipData.newHtmlText(any(), any(), capture(html)) } returns clip
		val clipboard = mockk<Clipboard>(relaxed = true)

		val text = buildAnnotatedString {
			append("a ")
			pushStyle(config.boldStyle)
			append("b")
			pop()
		}
		ClipboardHelper.setText(clipboard, text, config, copyId = 7L, html = null)

		assertEquals("a <strong>b</strong>", html.captured)
		verify { ClipData.newHtmlText(any(), "a b", any()) }
		verify { anyConstructed<PersistableBundle>().putLong(any(), 7L) }
		verify { description.extras = any() }
		coVerify { clipboard.setClipEntry(match { it.clipData === clip }) }
	}
}
