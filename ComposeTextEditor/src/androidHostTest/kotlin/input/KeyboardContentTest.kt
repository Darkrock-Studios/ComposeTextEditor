package input

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.input.ImeCursorSync
import com.darkrockstudios.texteditor.input.ImeUpdateSink
import com.darkrockstudios.texteditor.input.KeyboardContentReceiver
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.input.keyboardContentReceiver
import com.darkrockstudios.texteditor.input.populate
import com.darkrockstudios.texteditor.state.CursorAnchor
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.TestScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A keyboard's GIFs and stickers reach the host only when it takes them. */
class KeyboardContentTest {

	private val state = TextEditorState(
		scope = TestScope(),
		measurer = mockk(relaxed = true),
		initialText = AnnotatedString("hello"),
	)
	private val connection = TextEditorInputConnection(state, mockk<View>(relaxed = true))
	private val content = mockk<InputContentInfo>(relaxed = true)
	private val received = mutableListOf<Pair<InputContentInfo, Bundle?>>()

	private fun receiver(takes: Boolean, vararg types: String = arrayOf("image/*")) =
		KeyboardContentReceiver(types.toList()) { info, extras ->
			received += info to extras
			takes
		}

	@BeforeTest
	fun mockCapsMode() {
		mockkStatic(TextUtils::class)
		every { TextUtils.getCapsMode(any(), any(), any()) } returns 0
	}

	@AfterTest
	fun unmockCapsMode() = unmockkStatic(TextUtils::class)

	@Test
	fun `without a receiver content is refused and no types are advertised`() {
		assertFalse(connection.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null))
		verify(exactly = 0) { content.requestPermission() }

		val info = mockk<EditorInfo>(relaxed = true)
		info.populate(state, connection)
		assertNull(info.contentMimeTypes)
	}

	@Test
	fun `a receiver's types are advertised`() {
		state.keyboardContentReceiver = receiver(takes = true, "image/gif", "image/png")

		val info = mockk<EditorInfo>(relaxed = true)
		info.populate(state, connection)

		assertContentEquals(arrayOf("image/gif", "image/png"), info.contentMimeTypes)
	}

	@Test
	fun `committed content reaches the receiver, which answers for it`() {
		val extras = mockk<Bundle>()
		state.keyboardContentReceiver = receiver(takes = true)

		assertTrue(connection.commitContent(content, 0, extras))
		assertEquals(1, received.size)
		assertSame(content, received[0].first)
		assertSame(extras, received[0].second)
		verify(exactly = 0) { content.requestPermission() }

		state.keyboardContentReceiver = receiver(takes = false)
		assertFalse(connection.commitContent(content, 0, null))
	}

	@Test
	fun `a grant the keyboard asks for is taken first and kept while the host has the content`() {
		state.keyboardContentReceiver = KeyboardContentReceiver(listOf("image/*")) { info, _ ->
			verify { info.requestPermission() }
			true
		}

		assertTrue(connection.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null))
		verify(exactly = 0) { content.releasePermission() }
	}

	@Test
	fun `refused content gives its grant back`() {
		state.keyboardContentReceiver = receiver(takes = false)

		assertFalse(connection.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null))
		verifyOrder {
			content.requestPermission()
			content.releasePermission()
		}
	}

	@Test
	fun `a grant that fails refuses the content without asking the host`() {
		every { content.requestPermission() } throws SecurityException("revoked")
		state.keyboardContentReceiver = receiver(takes = true)

		assertFalse(connection.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null))
		assertEquals(emptyList(), received)
	}

	@Test
	fun `a closed connection takes nothing`() {
		state.keyboardContentReceiver = receiver(takes = true)
		connection.closeConnection()

		assertFalse(connection.commitContent(content, 0, null))
		assertEquals(emptyList(), received)
	}

	@Test
	fun `a receiver coming, changing its types or going restarts input`() {
		val events = mutableListOf<String>()
		val sync = ImeCursorSync(state, object : ImeUpdateSink {
			override val isReady = true
			override fun restartInput() {
				events += "restart"
			}

			override fun updateSelection(selStart: Int, selEnd: Int, compStart: Int, compEnd: Int) = Unit
			override fun updateExtractedText(token: Int) = Unit
			override fun sendCursorAnchorInfo(anchor: CursorAnchor) = Unit
		}) {}
		sync.attach()
		sync.flush()

		state.keyboardContentReceiver = receiver(takes = true)
		sync.flush()
		state.keyboardContentReceiver = receiver(takes = false)
		sync.flush()
		assertEquals(listOf("restart"), events, "the same types need no restart")

		state.keyboardContentReceiver = receiver(takes = true, "image/gif")
		sync.flush()
		state.keyboardContentReceiver = null
		sync.flush()

		assertEquals(listOf("restart", "restart", "restart"), events)
	}
}
