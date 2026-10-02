package input

import android.graphics.PointF
import android.graphics.RectF
import android.os.CancellationSignal
import android.view.View
import android.view.inputmethod.DeleteGesture
import android.view.inputmethod.DeleteRangeGesture
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.HandwritingGesture
import android.view.inputmethod.PreviewableHandwritingGesture
import android.view.inputmethod.SelectGesture
import android.view.inputmethod.SelectRangeGesture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextGranularity
import androidx.compose.ui.text.TextRange
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.input.GestureGeometry
import com.darkrockstudios.texteditor.input.TextEditorInputConnection
import com.darkrockstudios.texteditor.input.offerHandwritingGestures
import com.darkrockstudios.texteditor.input.performGesture
import com.darkrockstudios.texteditor.input.previewGesture
import com.darkrockstudios.texteditor.input.previewHandwriting
import com.darkrockstudios.texteditor.input.textRangeBetweenAreas
import com.darkrockstudios.texteditor.input.textRangeInArea
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.TestScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Roadmap 3.22: the keyboard's previews of select and delete gestures reach the editor's
 * highlight, mapped as the gestures are, and end when cancelled, when a gesture is
 * performed, or when the connection closes. The area mapping itself is
 * `HandwritingGestureLayoutTest`'s; here it answers "two" (4 to 7) for any area.
 */
class HandwritingPreviewGestureTest {

	private val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true), initialText = AnnotatedString("one two three"))
	private val connection = TextEditorInputConnection(state, mockk<View>(relaxed = true))
	private val two = TextEditorRange(CharLineOffset(0, 4), CharLineOffset(0, 7))

	private val geometry = object : GestureGeometry {
		override fun area(screen: RectF): Rect = Rect.Zero
		override fun point(screen: PointF): Offset = Offset.Zero
		override val lineMargin: Float = 0f
	}

	private val onMain: (Runnable) -> Unit = { it.run() }

	private fun mapsEveryAreaToTwo() {
		mockkStatic("com.darkrockstudios.texteditor.input.HandwritingGestureLayoutKt")
		every { any<TextEditorState>().textRangeInArea(any(), any(), any()) } returns TextRange(4, 7)
		every { any<TextEditorState>().textRangeBetweenAreas(any(), any(), any()) } returns TextRange(4, 7)
	}

	@AfterTest
	fun tearDown() = unmockkAll()

	@Test
	fun `the four select and delete gestures are offered for preview`() {
		val info = mockk<EditorInfo>(relaxed = true)

		info.offerHandwritingGestures()

		verify {
			info.supportedHandwritingGesturePreviews = setOf(
				SelectGesture::class.java,
				DeleteGesture::class.java,
				SelectRangeGesture::class.java,
				DeleteRangeGesture::class.java,
			)
		}
	}

	@Test
	fun `select gestures preview a select, delete gestures a delete`() {
		mapsEveryAreaToTwo()
		val granularity = HandwritingGesture.GRANULARITY_WORD
		val gestures = listOf(
			mockk<SelectGesture>(relaxed = true) { every { this@mockk.granularity } returns granularity } to false,
			mockk<SelectRangeGesture>(relaxed = true) { every { this@mockk.granularity } returns granularity } to false,
			mockk<DeleteGesture>(relaxed = true) { every { this@mockk.granularity } returns granularity } to true,
			mockk<DeleteRangeGesture>(relaxed = true) { every { this@mockk.granularity } returns granularity } to true,
		)
		for ((gesture, deletes) in gestures) {
			assertTrue(state.previewGesture(gesture, geometry, null, onMain))
			val preview = state.handwritingPreview!!
			assertEquals(two, preview.range, gesture.javaClass.simpleName)
			assertEquals(deletes, preview.deletes, gesture.javaClass.simpleName)
		}
		verify { any<TextEditorState>().textRangeInArea(any(), TextGranularity.Word, any()) }
	}

	@Test
	fun `a gesture the editor does not preview is refused and leaves the preview`() {
		val shown = state.previewHandwriting(TextRange(4, 7), deletes = false)

		assertFalse(state.previewGesture(mockk<PreviewableHandwritingGesture>(relaxed = true), geometry, null, onMain))
		assertSame(shown, state.handwritingPreview)
	}

	/** The keyboard's cancellation can come on a binder thread, so it ends the preview on the main one. */
	@Test
	fun `cancelling the preview ends it on the main thread`() {
		mapsEveryAreaToTwo()
		val signal = mockk<CancellationSignal>(relaxed = true)
		val listener = slot<CancellationSignal.OnCancelListener>()
		every { signal.setOnCancelListener(capture(listener)) } returns Unit
		val posted = mutableListOf<Runnable>()

		state.previewGesture(mockk<DeleteGesture>(relaxed = true), geometry, signal) { posted += it }
		assertTrue(state.handwritingPreview != null)

		listener.captured.onCancel()
		assertTrue(state.handwritingPreview != null, "not on the cancelling thread")
		posted.forEach { it.run() }
		assertNull(state.handwritingPreview)
	}

	@Test
	fun `a preview already cancelled shows nothing`() {
		mapsEveryAreaToTwo()
		val signal = mockk<CancellationSignal>(relaxed = true) { every { isCanceled } returns true }

		assertTrue(state.previewGesture(mockk<SelectGesture>(relaxed = true), geometry, signal, onMain))
		assertNull(state.handwritingPreview)
	}

	@Test
	fun `performing a gesture ends the preview`() {
		state.previewHandwriting(TextRange(4, 7), deletes = true)

		connection.performGesture(state, mockk<HandwritingGesture>(relaxed = true), geometry)

		assertNull(state.handwritingPreview)
	}

	@Test
	fun `closing the connection ends the preview`() {
		state.previewHandwriting(TextRange(4, 7), deletes = true)

		connection.closeConnection()

		assertNull(state.handwritingPreview)
	}
}
