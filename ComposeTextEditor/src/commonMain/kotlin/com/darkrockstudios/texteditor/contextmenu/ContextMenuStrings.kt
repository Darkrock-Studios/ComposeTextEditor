package com.darkrockstudios.texteditor.contextmenu

/**
 * Localizable strings for the context menu.
 * Provide a custom implementation to localize the context menu UI. The later labels
 * default to English so that existing hosts compile; a host that localizes sets them all.
 */
data class ContextMenuStrings(
	val cut: String,
	val copy: String,
	val paste: String,
	val selectAll: String,
	val undo: String = "Undo",
	val redo: String = "Redo",
	val pasteAsPlainText: String = "Paste as Plain Text",
) {
	companion object {
		/**
		 * Default English strings for the context menu.
		 */
		val Default = ContextMenuStrings(
			cut = "Cut",
			copy = "Copy",
			paste = "Paste",
			selectAll = "Select All",
		)
	}
}
