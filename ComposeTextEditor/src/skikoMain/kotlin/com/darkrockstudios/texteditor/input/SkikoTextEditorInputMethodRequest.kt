@file:OptIn(ExperimentalComposeUiApi::class)

package com.darkrockstudios.texteditor.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.TextEditingScope
import androidx.compose.ui.text.input.TextFieldValue
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.TextEditorState as ComposeTextEditorState

/**
 * Starts a Compose skiko input-method session bound to this editor. A platform's
 * `TextEditorTextInputService` hands it that platform's [imeOptions] and nothing else;
 * every edit the platform delivers lands in [ImeEditLogic] through
 * [SkikoTextEditorInputMethodRequest].
 *
 * The frameworks watch the request's text through `snapshotFlow`, but the document
 * is deliberately not snapshot state. The session therefore bumps a snapshot-backed
 * revision on every content edit, and the request's text reads fold it in, so an edit
 * that moves no cursor (a forward delete) still reaches the platform's mirror.
 */
internal suspend fun TextEditorState.startSkikoInputSession(
	session: PlatformTextInputSession,
	imeOptions: ImeOptions,
): Nothing = coroutineScope {
	val request = SkikoTextEditorInputMethodRequest(this@startSkikoInputSession, imeOptions)
	launch { editOperations.collect { request.contentRevision++ } }
	launch { documentGeneration.collect { request.contentRevision++ } }
	session.startInputMethod(request)
}

/**
 * The one [PlatformTextInputMethodRequest] for the skiko platforms. Desktop drives it
 * through [editText], iOS through [editText] as well, and web (still on the command
 * list API) through [onEditCommand]; both routes end in [ImeEditLogic].
 */
internal class SkikoTextEditorInputMethodRequest(
	private val editorState: TextEditorState,
	override val imeOptions: ImeOptions,
) : PlatformTextInputMethodRequest {

	/** Advanced on every content edit; see [startSkikoInputSession]. */
	internal var contentRevision by mutableIntStateOf(0)

	/** Live view of the editor as the CharSequence + selection + composition Compose expects. */
	override val state: ComposeTextEditorState = ImeComposeStateAdapter()

	override val value: () -> TextFieldValue = {
		contentRevision
		TextFieldValue(
			text = editorState.getAllText().text,
			selection = editorState.selectionAsTextRange(),
		)
	}

	override val onEditCommand: (List<EditCommand>) -> Unit = { commands ->
		commands.forEach { editorState.applyImeEditCommand(it) }
	}

	override val onImeAction: ((ImeAction) -> Unit)? = null

	// The editor draws its own lines, so there is no Compose layout to expose. Null is
	// the documented "not laid out yet" answer and every framework tolerates it.
	override val textLayoutResult: () -> TextLayoutResult? = { null }

	/** Caret rectangle in root coordinates; positions candidate windows and the web backing input. */
	override val focusedRectInRoot: () -> Rect? = {
		// The metrics are plain fields written at draw time. Reading the caret position
		// makes a snapshot observer re-run on every caret move and pick up the fresh ones.
		editorState.cursorPosition
		val coords = attachedCoordinates()
		val metrics = editorState.lastCursorMetrics
		if (coords != null && metrics != null) {
			val origin = coords.positionInRoot()
			Rect(
				left = origin.x + metrics.position.x,
				top = origin.y + metrics.lineTop,
				right = origin.x + metrics.position.x,
				bottom = origin.y + metrics.lineBottom,
			)
		} else {
			null
		}
	}

	override val textFieldRectInRoot: () -> Rect? = { editorBoundsInRoot() }

	override val textClippingRectInRoot: () -> Rect? = { editorBoundsInRoot() }

	/** Where the document's first line starts, in root coordinates: the viewport origin less the scroll. */
	override val unclippedTextOffsetInRoot: () -> Offset? = {
		attachedCoordinates()?.let { coords ->
			val origin = coords.positionInRoot()
			Offset(origin.x, origin.y - editorState.scrollState.value)
		}
	}

	override val editText: (TextEditingScope.() -> Unit) -> Unit = { block ->
		SkikoTextEditingScope(editorState).block()
	}

	private fun attachedCoordinates(): LayoutCoordinates? {
		// The coordinates are a plain field. The viewport size is snapshot state that
		// changes with every resize (a soft keyboard appearing, a rotation), so reading
		// it lets an observer of the rectangles follow those relayouts.
		editorState.viewportSize
		return editorState.canvasLayoutCoordinates?.takeIf { it.isAttached }
	}

	private fun editorBoundsInRoot(): Rect? {
		val coords = attachedCoordinates() ?: return null
		val origin = coords.positionInRoot()
		val size = coords.size
		return Rect(
			left = origin.x,
			top = origin.y,
			right = origin.x + size.width,
			bottom = origin.y + size.height,
		)
	}

	/**
	 * Adapts the editor to Compose's skiko `TextEditorState`, read live each time the
	 * platform queries it. Reads are served from the requested range only, so IME
	 * queries stay cheap on large documents.
	 */
	private inner class ImeComposeStateAdapter : ComposeTextEditorState {
		override val length: Int get() = editorState.getTextLength()
		override fun get(index: Int): Char = editorState.imeCharAt(index)
		override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
			editorState.imeSubSequence(startIndex, endIndex)

		override val text: String
			get() {
				contentRevision
				return editorState.getAllText().text
			}

		override fun toString(): String = text

		// Flat indices depend on the lines before the caret as well as the caret itself,
		// so an edit elsewhere in the document must re-run an observer of these too.
		override val selection: TextRange
			get() {
				contentRevision
				return editorState.selectionAsTextRange()
			}

		override val composition: TextRange?
			get() {
				contentRevision
				return editorState.composingAsTextRange()
			}
	}
}

/** Bridges Compose's [TextEditingScope] to the shared [ImeEditLogic] operations. */
private class SkikoTextEditingScope(
	private val state: TextEditorState,
) : TextEditingScope {

	override fun deleteSurroundingTextInCodePoints(lengthBeforeCursor: Int, lengthAfterCursor: Int) =
		state.imeDeleteSurroundingTextInCodePoints(lengthBeforeCursor, lengthAfterCursor)

	override fun setSelection(start: Int, end: Int) = state.imeSetSelection(start, end)

	override fun commitText(text: CharSequence, newCursorPosition: Int) =
		state.imeCommitText(text.toString(), newCursorPosition)

	override fun setComposingRegion(start: Int, end: Int) = state.imeSetComposingRegion(start, end)

	override fun setComposingText(text: CharSequence, newCursorPosition: Int) =
		state.imeSetComposingText(text.toString(), newCursorPosition)

	override fun finishComposingText() = state.imeFinishComposing()
}
