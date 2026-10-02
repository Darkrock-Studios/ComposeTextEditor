package utils

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * A [TextToolbar] that records what the editor asked it to show, standing in for the
 * platform's floating toolbar, which the desktop test scene does not have.
 */
class RecordingTextToolbar : TextToolbar {

	/** One `showMenu` call: the rect and the items offered, null where an item was withheld. */
	class Menu(
		val rect: Rect,
		val onCopy: (() -> Unit)?,
		val onPaste: (() -> Unit)?,
		val onCut: (() -> Unit)?,
		val onSelectAll: (() -> Unit)?,
	)

	/** The menu showing now, or null. */
	var menu: Menu? = null
		private set

	var showCount = 0
		private set

	var hideCount = 0
		private set

	override var status: TextToolbarStatus = TextToolbarStatus.Hidden
		private set

	override fun showMenu(
		rect: Rect,
		onCopyRequested: (() -> Unit)?,
		onPasteRequested: (() -> Unit)?,
		onCutRequested: (() -> Unit)?,
		onSelectAllRequested: (() -> Unit)?,
	) {
		menu = Menu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
		showCount++
		status = TextToolbarStatus.Shown
	}

	override fun hide() {
		if (menu != null) hideCount++
		menu = null
		status = TextToolbarStatus.Hidden
	}

	/**
	 * Taps [item] as the platform would: Android's action mode finishes itself once an
	 * item's callback returns, so the toolbar is gone unless the editor brings it back.
	 */
	fun click(item: (() -> Unit)?) {
		checkNotNull(item) { "the item is not offered" }()
		hide()
	}
}
