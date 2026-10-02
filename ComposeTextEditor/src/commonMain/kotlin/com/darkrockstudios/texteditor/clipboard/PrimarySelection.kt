package com.darkrockstudios.texteditor.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.state.DocumentSnapshot
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.concurrent.Volatile

/**
 * The X11 primary selection: whatever the user last selected, pasted elsewhere with a
 * middle click. Separate from the clipboard, and plain text only.
 */
internal interface PrimarySelection {
	/**
	 * Makes [source] the primary selection, unless it already is. Its text is read only
	 * when something pastes it, so offering again as a selection grows costs nothing.
	 */
	fun offer(source: PrimarySelectionSource)

	/** The primary selection's plain text, or null when it holds none or only an empty string. */
	suspend fun readText(): String?
}

internal fun interface PrimarySelectionSource {
	/** The text on offer, read on whichever thread asks. */
	fun text(): String?
}

/** The platform's primary selection, or null where it has none (all but desktop Linux). */
internal expect fun platformPrimarySelection(): PrimarySelection?

internal val LocalPrimarySelection = staticCompositionLocalOf { platformPrimarySelection() }

/**
 * Offers [state]'s selection as the primary selection whenever the selection changes to
 * a new one. An edit that leaves the selection alone offers nothing, so a view whose
 * text grows under an old selection never takes the primary selection back from
 * another application. A cleared selection leaves the last one on offer, as Qt does, so
 * text can be selected, the caret placed elsewhere, and the text middle-click pasted
 * there; leaving composition keeps it on offer as text, without the document.
 */
@Composable
internal fun PrimarySelectionEffect(state: TextEditorState) {
	val primary = LocalPrimarySelection.current ?: return
	val source = remember(state) { SelectedText() }
	DisposableEffect(source) { onDispose { source.freeze() } }
	LaunchedEffect(state, primary) {
		var previous = state.selector.selection
		snapshotFlow { state.selector.selection to state.textRevision }.collect { (selection, _) ->
			val changed = selection != previous
			previous = selection
			if (selection == null || selection.start == selection.end) return@collect
			source.select(state.snapshot(), selection)
			if (changed) primary.offer(source)
		}
	}
}

/** A selection as of one revision, which no later edit changes. */
private class SelectedText : PrimarySelectionSource {
	@Volatile
	private var held: (() -> String)? = null

	fun select(document: DocumentSnapshot, range: TextEditorRange) {
		held = { document.plainTextIn(range) }
	}

	/** Keeps the selected text and lets go of the document. */
	fun freeze() {
		val text = held?.invoke() ?: return
		held = { text }
	}

	override fun text(): String? = held?.invoke()
}

private fun DocumentSnapshot.plainTextIn(range: TextEditorRange): String =
	chars.subSequence(
		lineStart(range.start.line) + range.start.char,
		lineStart(range.end.line) + range.end.char,
	).toString()
