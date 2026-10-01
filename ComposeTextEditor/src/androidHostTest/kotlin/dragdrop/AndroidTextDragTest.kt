package dragdrop

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.net.Uri
import android.view.DragAndDropPermissions
import android.view.DragEvent
import android.view.View
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropStartTransferScope
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.dragdrop.MAX_DROPPED_FILE_BYTES
import com.darkrockstudios.texteditor.dragdrop.TextDragAndDrop
import com.darkrockstudios.texteditor.dragdrop.carriesText
import com.darkrockstudios.texteditor.dragdrop.dragId
import com.darkrockstudios.texteditor.dragdrop.droppedText
import com.darkrockstudios.texteditor.dragdrop.platformDragsText
import com.darkrockstudios.texteditor.dragdrop.pointerInRoot
import com.darkrockstudios.texteditor.dragdrop.requestsCopy
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.FileNotFoundException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android drags text as `TextView` does: a global drag of `ClipData.newHtmlText`, the
 * drag id riding as the local state, which only this process sees. Drops read the
 * clip's markup and text at the drop, and the position from the `DragEvent`.
 */
class AndroidTextDragTest {

	private val styles = RichTextStyles.DEFAULT
	private val schemes = setOf("https")

	@AfterTest
	fun tearDown() = unmockkAll()

	/** Starts every drag but the first [refusals]. */
	private class RecordingScope(private var refusals: Int = 0) : DragAndDropStartTransferScope {
		var data: DragAndDropTransferData? = null
		override fun startDragAndDropTransfer(
			transferData: DragAndDropTransferData,
			decorationSize: Size,
			drawDragDecoration: DrawScope.() -> Unit,
		): Boolean {
			if (refusals-- > 0) return false
			data = transferData
			return true
		}
	}

	private fun item(text: String?, html: String?): ClipData.Item = mockk {
		every { this@mockk.text } returns text
		every { htmlText } returns html
	}

	private fun clip(vararg items: ClipData.Item): ClipData = mockk {
		every { itemCount } returns items.size
		every { getItemAt(any()) } answers { items[firstArg()] }
	}

	private fun event(
		clip: ClipData? = null,
		localState: Any? = null,
		mimeTypes: List<String> = listOf(ClipDescription.MIMETYPE_TEXT_PLAIN),
		x: Float = 0f,
		y: Float = 0f,
	): DragAndDropEvent {
		val description = mockk<ClipDescription> {
			every { hasMimeType(any()) } answers {
				val wanted = firstArg<String>()
				mimeTypes.any { it == wanted || (wanted.endsWith("/*") && it.startsWith(wanted.dropLast(1))) }
			}
		}
		val drag = mockk<DragEvent> {
			every { clipData } returns clip
			every { clipDescription } returns description
			every { this@mockk.localState } returns localState
			every { this@mockk.x } returns x
			every { this@mockk.y } returns y
		}
		return DragAndDropEvent(drag)
	}

	@Test
	fun `android drags text`() {
		assertTrue(platformDragsText)
	}

	@Test
	fun `a drag carries the selection as html beside its text, global, with its id`() = runTest {
		mockkStatic(ClipData::class)
		val clip = mockk<ClipData>()
		val html = slot<String>()
		every { ClipData.newHtmlText(any(), "two", capture(html)) } returns clip
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString("one two three"))
		state.selector.updateSelection(CharLineOffset(0, 4), CharLineOffset(0, 7))

		val scope = RecordingScope()
		TextDragAndDrop(state).startTransfer(scope)

		val data = assertNotNull(scope.data)
		assertEquals(clip, data.clipData)
		assertTrue(html.captured.contains("two"))
		assertEquals(View.DRAG_FLAG_GLOBAL, data.flags and View.DRAG_FLAG_GLOBAL)
		val id = assertNotNull(data.localState as? Long)
		assertEquals(id, event(localState = id).dragId())
	}

	/** A clip past the binder transaction limit fails to start; the text alone is half the size. */
	@Test
	fun `a drag refused with its markup drags its text`() = runTest {
		mockkStatic(ClipData::class)
		every { ClipData.newHtmlText(any(), any(), any()) } returns mockk()
		val plain = mockk<ClipData>()
		every { ClipData.newPlainText(any(), "two") } returns plain
		val state = TextEditorState(scope = this, measurer = mockk(relaxed = true), initialText = AnnotatedString("one two three"))
		state.selector.updateSelection(CharLineOffset(0, 4), CharLineOffset(0, 7))

		val scope = RecordingScope(refusals = 1)
		TextDragAndDrop(state).startTransfer(scope)

		assertEquals(plain, assertNotNull(scope.data).clipData)
	}

	@Test
	fun `a drop reads the markup and the text`() {
		val dropped = event(clip(item("plain bold", "plain <b>bold</b>"))).droppedText(styles, schemes, ownDrag = false, target = null)!!
		assertEquals("plain bold", dropped.text.text)
		assertTrue(dropped.text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 6 })
		assertEquals("plain <b>bold</b>", dropped.html)
	}

	@Test
	fun `a drop without markup takes the text, several items one per line`() {
		val dropped = event(clip(item("one", null), item("two", null))).droppedText(styles, schemes, ownDrag = false, target = null)!!
		assertEquals("one\ntwo", dropped.text.text)
		assertNull(dropped.html)
	}

	@Test
	fun `this editor's own drag drops the characters it dragged`() {
		// Markup that re-parses to other text (collapsing the double space) is not taken.
		val drop = event(clip(item("a  b", "a  <b>b</b>")), localState = 5L)
		assertEquals(AnnotatedString("a  b"), drop.droppedText(styles, schemes, ownDrag = true, target = null)!!.text)
		// Another editor has no rich spans of its own to put back, so it takes the markup.
		assertEquals("a b", drop.droppedText(styles, schemes, ownDrag = false, target = null)!!.text.text)
	}

	private fun uriItem(uri: Uri): ClipData.Item = mockk {
		every { text } returns null
		every { htmlText } returns null
		every { this@mockk.uri } returns uri
	}

	private fun file(scheme: String = ContentResolver.SCHEME_CONTENT): Uri = mockk {
		every { this@mockk.scheme } returns scheme
	}

	/** An activity whose content resolver holds [files], each a type and its bytes. */
	private fun activityHolding(files: Map<Uri, Pair<String?, ByteArray>>, permissions: DragAndDropPermissions?): Activity {
		val resolver = mockk<ContentResolver> {
			every { getType(any()) } answers { files[firstArg()]?.first }
			every { openInputStream(any()) } answers { files[firstArg()]?.second?.inputStream() }
		}
		return mockk {
			every { contentResolver } returns resolver
			every { requestDragAndDropPermissions(any()) } returns permissions
		}
	}

	private fun quietLog() {
		mockkStatic(android.util.Log::class)
		every { android.util.Log.w(any(), any<String>(), any()) } returns 0
		every { android.util.Log.w(any(), any<String>()) } returns 0
	}

	/** A text file dragged from Files carries a content URI and no text (6.44). */
	@Test
	fun `a drop of a text file reads it with the drop's permissions`() {
		val notes = file()
		val page = file()
		val permissions = mockk<DragAndDropPermissions>(relaxed = true)
		val activity = activityHolding(
			mapOf(notes to ("text/plain" to "line one\nline two".encodeToByteArray()), page to ("text/html" to "<b>bold</b>".encodeToByteArray())),
			permissions,
		)
		val drag = event(clip(uriItem(notes), uriItem(page))).toAndroidDragEvent()

		val dropped = assertNotNull(drag.droppedText(styles, schemes, ownDrag = false) { activity })

		assertEquals("line one\nline two\nbold", dropped.text.text)
		assertTrue(dropped.text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 18 })
		verify(exactly = 1) { activity.requestDragAndDropPermissions(drag) }
		verify(exactly = 1) { permissions.release() }
	}

	@Test
	fun `a dropped html file brings its markup for its blocks`() {
		val page = file()
		val activity = activityHolding(mapOf(page to ("TEXT/HTML; charset=utf-8" to "<ul><li>one</li></ul>".encodeToByteArray())), mockk(relaxed = true))

		val dropped = assertNotNull(event(clip(uriItem(page))).toAndroidDragEvent().droppedText(styles, schemes, ownDrag = false) { activity })

		assertEquals("one", dropped.text.text)
		assertEquals("<ul><li>one</li></ul>", dropped.html)
	}

	@Test
	fun `a dropped file's byte order mark and charset decide how it reads`() {
		val bom = file()
		val utf16 = file()
		val latin = file()
		val activity = activityHolding(
			mapOf(
				bom to ("text/plain" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "caf\u00e9".encodeToByteArray()),
				utf16 to ("text/plain" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(Charsets.UTF_16LE)),
				latin to ("text/plain; charset=ISO-8859-1" to "caf\u00e9".toByteArray(Charsets.ISO_8859_1)),
			),
			mockk(relaxed = true),
		)

		val dropped = event(clip(uriItem(bom), uriItem(utf16), uriItem(latin))).toAndroidDragEvent()
			.droppedText(styles, schemes, ownDrag = false) { activity }

		assertEquals("caf\u00e9\nhi\ncaf\u00e9", dropped!!.text.text)
	}

	@Test
	fun `a dropped file the provider gives no type takes the drag's`() {
		val notes = file()
		val activity = activityHolding(mapOf(notes to (null to "notes".encodeToByteArray())), mockk(relaxed = true))
		val description = mockk<ClipDescription> {
			every { mimeTypeCount } returns 1
			every { getMimeType(0) } returns "text/plain"
		}
		val drag = mockk<DragEvent> {
			every { clipData } returns clip(uriItem(notes))
			every { clipDescription } returns description
		}

		assertEquals("notes", drag.droppedText(styles, schemes, ownDrag = false) { activity }!!.text.text)
	}

	@Test
	fun `a drop of a file that is not text, or unreadable, lands nothing`() {
		quietLog()
		val image = file()
		val gone = file()
		val activity = activityHolding(mapOf(image to ("image/png" to "png".encodeToByteArray())), mockk(relaxed = true))
		every { activity.contentResolver.getType(gone) } returns "text/plain"
		every { activity.contentResolver.openInputStream(gone) } throws FileNotFoundException()

		assertNull(
			event(clip(uriItem(image), uriItem(gone))).toAndroidDragEvent()
				.droppedText(styles, schemes, ownDrag = false) { activity }
		)
	}

	@Test
	fun `a drop reads its files up to a megabyte in all`() {
		quietLog()
		val half = MAX_DROPPED_FILE_BYTES / 2 + 1
		val first = file()
		val second = file()
		val activity = activityHolding(
			mapOf(
				first to ("text/plain" to ByteArray(half) { 'a'.code.toByte() }),
				second to ("text/plain" to ByteArray(half) { 'b'.code.toByte() }),
			),
			mockk(relaxed = true),
		)

		val dropped = event(clip(uriItem(first), uriItem(second))).toAndroidDragEvent()
			.droppedText(styles, schemes, ownDrag = false) { activity }

		assertEquals("a".repeat(half), dropped!!.text.text)
	}

	/** Read with this app's own access, another app's drag could have it read its private files. */
	@Test
	fun `a drop reads only what the drag grants`() {
		val granted = file()
		val ungranted = activityHolding(mapOf(granted to ("text/plain" to "secret".encodeToByteArray())), permissions = null)
		val resolver = ungranted.contentResolver
		val path = file(scheme = "file")
		val grants = activityHolding(mapOf(path to ("text/plain" to "secret".encodeToByteArray())), mockk(relaxed = true))

		assertNull(event(clip(uriItem(granted))).toAndroidDragEvent().droppedText(styles, schemes, ownDrag = false) { ungranted })
		assertNull(event(clip(uriItem(path))).toAndroidDragEvent().droppedText(styles, schemes, ownDrag = false) { grants })
		verify(exactly = 0) { resolver.openInputStream(any()) }
	}

	@Test
	fun `a drop of text asks no permissions`() {
		val activity = activityHolding(emptyMap(), mockk(relaxed = true))
		val drag = event(clip(item("plain", null))).toAndroidDragEvent()

		assertEquals("plain", drag.droppedText(styles, schemes, ownDrag = false) { activity }!!.text.text)
		verify(exactly = 0) { activity.requestDragAndDropPermissions(any()) }
	}

	@Test
	fun `only a drag of text is taken`() {
		assertTrue(event(mimeTypes = listOf(ClipDescription.MIMETYPE_TEXT_HTML)).carriesText())
		assertFalse(event(mimeTypes = listOf("image/png")).carriesText())
		assertNull(event(clip()).droppedText(styles, schemes, ownDrag = false, target = null))
	}

	@Test
	fun `a drag from elsewhere has no id and is never a copy request`() {
		assertNull(event(localState = "someone else's").dragId())
		assertFalse(event().requestsCopy())
	}

	@Test
	fun `the drop position is the event's, already in the root's pixels`() {
		assertEquals(Offset(30f, 40f), event(x = 30f, y = 40f).pointerInRoot(Density(2f)))
	}

	@Test
	fun `a drag within the editor moves the text`() = runTest {
		mockkStatic(ClipData::class)
		every { ClipData.newHtmlText(any(), any(), any()) } returns mockk()
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString("one two three"))
		state.selector.updateSelection(CharLineOffset(0, 0), CharLineOffset(0, 4))
		val dnd = TextDragAndDrop(state)
		val scope = RecordingScope()
		dnd.startTransfer(scope)
		val drop = event(clip(item("one ", null)), localState = scope.data!!.localState)

		val content = assertNotNull(drop.droppedText(styles, schemes, ownDrag = true, target = null))
		assertTrue(dnd.dropAt(CharLineOffset(0, 13), content, drop.dragId(), drop.requestsCopy()))

		assertEquals("two threeone ", state.getAllText().text)
	}
}
