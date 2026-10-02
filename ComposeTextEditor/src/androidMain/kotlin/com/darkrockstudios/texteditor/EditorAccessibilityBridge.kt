package com.darkrockstudios.texteditor

import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat

@Composable
internal actual fun PlatformAccessibilityBridge() {
	val view = LocalView.current
	// Installed for the view's lifetime, never taken off.
	LaunchedEffect(view) { EditorAccessibilityBridge.installOn(view) }
}

/**
 * Wraps Compose's accessibility delegate on an `AndroidComposeView` to answer
 * [EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY], the character boxes TalkBack's magnifier,
 * a braille display or Select to Speak ask for, from [characterLocations], where Compose
 * would answer from `getTextLayoutResult`'s layout, which misses the editor's padding
 * and scroll. Every other call, and the key for a node [characterLocations] does not
 * answer, goes to Compose's delegate unchanged.
 *
 * It relies on Compose installing its delegate once, through `ViewCompat`, when the
 * view is created, and never again.
 */
internal class EditorAccessibilityBridge(
	private val compose: AccessibilityDelegateCompat,
	/** The boxes, in screen coordinates, for a request on a virtual view, or null to leave it to Compose. */
	private val characterLocations: (virtualViewId: Int, start: Int, length: Int) -> Array<RectF?>?,
) : AccessibilityDelegateCompat() {

	private var provider: Provider? = null

	override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat? {
		val inner = compose.getAccessibilityNodeProvider(host) ?: return null
		return provider?.takeIf { it.inner === inner } ?: Provider(inner).also { provider = it }
	}

	override fun sendAccessibilityEvent(host: View, eventType: Int) =
		compose.sendAccessibilityEvent(host, eventType)

	override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) =
		compose.sendAccessibilityEventUnchecked(host, event)

	override fun dispatchPopulateAccessibilityEvent(host: View, event: AccessibilityEvent): Boolean =
		compose.dispatchPopulateAccessibilityEvent(host, event)

	override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) =
		compose.onPopulateAccessibilityEvent(host, event)

	override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) =
		compose.onInitializeAccessibilityEvent(host, event)

	override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) =
		compose.onInitializeAccessibilityNodeInfo(host, info)

	override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean =
		compose.onRequestSendAccessibilityEvent(host, child, event)

	override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
		compose.performAccessibilityAction(host, action, args)

	private inner class Provider(val inner: AccessibilityNodeProviderCompat) : AccessibilityNodeProviderCompat() {
		override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfoCompat? =
			inner.createAccessibilityNodeInfo(virtualViewId)

		override fun findAccessibilityNodeInfosByText(text: String, virtualViewId: Int): List<AccessibilityNodeInfoCompat>? =
			inner.findAccessibilityNodeInfosByText(text, virtualViewId)

		override fun findFocus(focus: Int): AccessibilityNodeInfoCompat? = inner.findFocus(focus)

		override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean =
			inner.performAction(virtualViewId, action, arguments)

		override fun addExtraDataToAccessibilityNodeInfo(
			virtualViewId: Int,
			info: AccessibilityNodeInfoCompat,
			extraDataKey: String,
			arguments: Bundle?,
		) {
			if (extraDataKey == EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY && arguments != null) {
				val start = arguments.getInt(EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, -1)
				val length = arguments.getInt(EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, -1)
				val boxes = characterLocations(virtualViewId, start, length)
				if (boxes != null) {
					info.extras.putParcelableArray(extraDataKey, boxes)
					return
				}
			}
			inner.addExtraDataToAccessibilityNodeInfo(virtualViewId, info, extraDataKey, arguments)
		}
	}

	companion object {
		/** Wraps [view]'s delegate, unless it is not a Compose root, has none, or is wrapped already. */
		fun installOn(
			view: View,
			delegateOf: (View) -> AccessibilityDelegateCompat? = ViewCompat::getAccessibilityDelegate,
			setDelegate: (View, AccessibilityDelegateCompat) -> Unit = ViewCompat::setAccessibilityDelegate,
		) {
			val root = view as? RootForTest ?: return
			val compose = delegateOf(view) ?: return
			if (compose is EditorAccessibilityBridge) return
			setDelegate(view, EditorAccessibilityBridge(compose) { id, start, length ->
				screenCharacterLocations(root, id, start, length)
			})
		}

		private fun screenCharacterLocations(root: RootForTest, virtualViewId: Int, start: Int, length: Int): Array<RectF?>? {
			val semantics = root.semanticsOwner.unmergedRootSemanticsNode
			// Compose publishes the root node as the host view.
			val id = if (virtualViewId == AccessibilityNodeProviderCompat.HOST_VIEW_ID) semantics.id else virtualViewId
			val boxes = semantics.characterLocations(id, start, length) ?: return null
			val toScreen = Matrix()
			semantics.layoutInfo.coordinates.findRootCoordinates().transformToScreen(toScreen)
			// The mapped box is the bounds of its mapped corners, as Compose maps its own.
			return Array(boxes.size) { i -> boxes[i]?.let { toScreen.map(it).run { RectF(left, top, right, bottom) } } }
		}
	}
}
