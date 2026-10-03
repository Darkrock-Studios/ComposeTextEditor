package semantics

import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
import androidx.compose.ui.node.RootForTest
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import com.darkrockstudios.texteditor.EditorAccessibilityBridge
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The bridge around Compose's accessibility delegate answers the character-location
 * key from the editor's rows and hands every other call to Compose unchanged.
 */
class EditorAccessibilityBridgeTest {

	@AfterTest
	fun tearDown() = unmockkAll()

	private val host = mockk<View>()
	private val inner = mockk<AccessibilityNodeProviderCompat>(relaxed = true)
	private val compose = mockk<AccessibilityDelegateCompat>(relaxed = true).also {
		every { it.getAccessibilityNodeProvider(host) } returns inner
	}

	private fun characterArgs(start: Int, length: Int): Bundle = mockk {
		every { getInt(EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, -1) } returns start
		every { getInt(EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, -1) } returns length
	}

	@Test
	fun `every delegate call goes to Compose's delegate`() {
		val bridge = EditorAccessibilityBridge(compose) { _, _, _ -> null }
		val event = mockk<AccessibilityEvent>()
		val info = mockk<AccessibilityNodeInfoCompat>()
		val parent = mockk<ViewGroup>()
		val args = mockk<Bundle>()
		every { compose.dispatchPopulateAccessibilityEvent(host, event) } returns true
		every { compose.onRequestSendAccessibilityEvent(parent, host, event) } returns true
		every { compose.performAccessibilityAction(host, 16, args) } returns true

		bridge.sendAccessibilityEvent(host, 8)
		bridge.sendAccessibilityEventUnchecked(host, event)
		assertTrue(bridge.dispatchPopulateAccessibilityEvent(host, event))
		bridge.onPopulateAccessibilityEvent(host, event)
		bridge.onInitializeAccessibilityEvent(host, event)
		bridge.onInitializeAccessibilityNodeInfo(host, info)
		assertTrue(bridge.onRequestSendAccessibilityEvent(parent, host, event))
		assertTrue(bridge.performAccessibilityAction(host, 16, args))

		verify {
			compose.sendAccessibilityEvent(host, 8)
			compose.sendAccessibilityEventUnchecked(host, event)
			compose.onPopulateAccessibilityEvent(host, event)
			compose.onInitializeAccessibilityEvent(host, event)
			compose.onInitializeAccessibilityNodeInfo(host, info)
		}
	}

	@Test
	fun `every provider call goes to Compose's provider, which stays the one wrapped`() {
		val bridge = EditorAccessibilityBridge(compose) { _, _, _ -> null }
		val provider = assertNotNull(bridge.getAccessibilityNodeProvider(host))
		assertSame(provider, bridge.getAccessibilityNodeProvider(host))
		val node = mockk<AccessibilityNodeInfoCompat>()
		val found = listOf(node)
		val args = mockk<Bundle>()
		every { inner.createAccessibilityNodeInfo(7) } returns node
		every { inner.findAccessibilityNodeInfosByText("word", 7) } returns found
		every { inner.findFocus(1) } returns node
		every { inner.performAction(7, 16, args) } returns true

		assertSame(node, provider.createAccessibilityNodeInfo(7))
		assertSame(found, provider.findAccessibilityNodeInfosByText("word", 7))
		assertSame(node, provider.findFocus(1))
		assertTrue(provider.performAction(7, 16, args))
		provider.addExtraDataToAccessibilityNodeInfo(7, node, "other", args)
		verify { inner.addExtraDataToAccessibilityNodeInfo(7, node, "other", args) }

		val replaced = mockk<AccessibilityNodeProviderCompat>()
		every { compose.getAccessibilityNodeProvider(host) } returns replaced
		val rewrapped = assertNotNull(bridge.getAccessibilityNodeProvider(host))
		assertFalse(rewrapped === provider)
	}

	@Test
	fun `no provider from Compose is none from the bridge`() {
		every { compose.getAccessibilityNodeProvider(host) } returns null
		assertNull(EditorAccessibilityBridge(compose) { _, _, _ -> null }.getAccessibilityNodeProvider(host))
	}

	@Test
	fun `the character-location key is answered from the editor's boxes`() {
		val boxes = arrayOf<RectF?>(mockk(), null)
		val asked = mutableListOf<Triple<Int, Int, Int>>()
		val bridge = EditorAccessibilityBridge(compose) { id, start, length ->
			asked += Triple(id, start, length)
			boxes
		}
		val extras = mockk<Bundle>(relaxed = true)
		val info = mockk<AccessibilityNodeInfoCompat> { every { this@mockk.extras } returns extras }

		bridge.getAccessibilityNodeProvider(host)!!
			.addExtraDataToAccessibilityNodeInfo(7, info, EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, characterArgs(3, 2))

		assertEquals(listOf(Triple(7, 3, 2)), asked)
		verify { extras.putParcelableArray(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, boxes) }
		verify(exactly = 0) { inner.addExtraDataToAccessibilityNodeInfo(any(), any(), any(), any()) }
	}

	@Test
	fun `the character-location key for a node without the editor's boxes goes to Compose`() {
		val bridge = EditorAccessibilityBridge(compose) { _, _, _ -> null }
		val info = mockk<AccessibilityNodeInfoCompat>()
		val args = characterArgs(0, 4)
		val provider = bridge.getAccessibilityNodeProvider(host)!!

		provider.addExtraDataToAccessibilityNodeInfo(7, info, EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
		provider.addExtraDataToAccessibilityNodeInfo(7, info, EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, null)

		verify {
			inner.addExtraDataToAccessibilityNodeInfo(7, info, EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)
			inner.addExtraDataToAccessibilityNodeInfo(7, info, EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, null)
		}
	}

	@Test
	fun `the bridge is installed once, around Compose's own delegate`() {
		val view = mockk<View>(moreInterfaces = arrayOf(RootForTest::class))
		var delegate: AccessibilityDelegateCompat = compose
		val installs = mutableListOf<AccessibilityDelegateCompat>()
		fun install() = EditorAccessibilityBridge.installOn(view, { delegate }) { _, set ->
			installs += set
			delegate = set
		}

		install()
		install()

		assertIs<EditorAccessibilityBridge>(installs.single())
	}

	@Test
	fun `a view that is no Compose root, or has no delegate, is left alone`() {
		val installs = mutableListOf<AccessibilityDelegateCompat>()
		EditorAccessibilityBridge.installOn(mockk<View>(), { compose }) { _, set -> installs += set }
		EditorAccessibilityBridge.installOn(mockk<View>(moreInterfaces = arrayOf(RootForTest::class)), { null }) { _, set -> installs += set }

		assertTrue(installs.isEmpty())
	}
}
