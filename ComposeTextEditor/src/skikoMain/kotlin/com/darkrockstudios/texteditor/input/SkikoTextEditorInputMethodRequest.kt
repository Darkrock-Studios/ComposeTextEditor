@file:OptIn(ExperimentalComposeUiApi::class)

package com.darkrockstudios.texteditor.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.TextEditorState as ComposeTextEditorState

/**
 * Starts a Compose skiko input-method session bound to this editor. A platform's
 * `TextEditorTextInputService` hands it that platform's [imeOptions], whether the
 * platform needs a text layout to hit-test ([exposeTextLayout]), and [imeResync]; every
 * edit the platform delivers lands in [ImeEditLogic] through
 * [SkikoTextEditorInputMethodRequest].
 *
 * The frameworks watch the request's text through `snapshotFlow`, but the document
 * is deliberately not snapshot state. The session therefore bumps a snapshot-backed
 * revision on every content edit, and the request's text reads fold it in, so an edit
 * that moves no cursor (a forward delete) still reaches the platform's mirror.
 *
 * A resync ([TextEditorState.requestImeResync]) is handed to [imeResync], which says how
 * this platform makes its input method drop what it assumed. It defaults to
 * [SkikoImeResync.None], which iOS relies on until the device pass decides (roadmap 4.29).
 */
internal suspend fun TextEditorState.startSkikoInputSession(
	session: PlatformTextInputSession,
	imeOptions: ImeOptions,
	exposeTextLayout: Boolean = false,
	imeResync: SkikoImeResync = SkikoImeResync.None,
): Nothing = coroutineScope {
	val request = SkikoTextEditorInputMethodRequest(this@startSkikoInputSession, imeOptions, exposeTextLayout)
	launch { editOperations.collect { request.contentRevision++ } }
	launch { documentGeneration.collect { request.contentRevision++ } }
	when (imeResync) {
		SkikoImeResync.None -> Unit
		is SkikoImeResync.Rewrite -> launch { onImeResync { imeResync.rewrite(request.value()) } }
		SkikoImeResync.RestartInput -> return@coroutineScope restartingInputMethod(session, request)
	}
	session.startInputMethod(request)
}

/** Runs [action] for each advance of [TextEditorState.imeResyncGeneration] from now on. */
private suspend fun TextEditorState.onImeResync(action: suspend () -> Unit) {
	var handled = imeResyncGeneration
	snapshotFlow { imeResyncGeneration }.collect { generation ->
		if (generation == handled) return@collect
		handled = generation
		action()
	}
}

/**
 * Runs the platform's input method, starting it again on each resync once the previous
 * one has torn down, and ends when one ends without being replaced.
 */
private suspend fun TextEditorState.restartingInputMethod(
	session: PlatformTextInputSession,
	request: SkikoTextEditorInputMethodRequest,
): Nothing = coroutineScope {
	val scope = this
	var inputMethod = launch { session.startInputMethod(request) }
	launch {
		onImeResync {
			val previous = inputMethod
			inputMethod = scope.launch(start = CoroutineStart.LAZY) { session.startInputMethod(request) }
			previous.cancelAndJoin()
			inputMethod.start()
		}
	}
	do {
		val current = inputMethod
		current.join()
	} while (current !== inputMethod)
	throw CancellationException("The platform ended the input method")
}

/**
 * How a skiko platform makes its input method drop what it assumed about the text when
 * the editor answered a request without the edit the IME expected.
 */
internal sealed interface SkikoImeResync {
	/** The platform reads the editor live and keeps no copy of the text to correct. */
	data object None : SkikoImeResync

	/**
	 * Restarts the platform's input method, which then reads the editor afresh. Heavy:
	 * the keyboard resets its state, as Android's `restartInput` does.
	 */
	data object RestartInput : SkikoImeResync

	/** Writes the value the platform should hold into its own copy of the text. */
	class Rewrite(val rewrite: (TextFieldValue) -> Unit) : SkikoImeResync
}

/**
 * The one [PlatformTextInputMethodRequest] for the skiko platforms. Desktop drives it
 * through [editText], iOS through [editText] as well, and web (still on the command
 * list API) through [onEditCommand]; both routes end in [ImeEditLogic].
 *
 * @param exposeTextLayout Serve [textLayoutResult] from a whole-document layout. Only
 *   iOS reads it, for the spacebar trackpad; elsewhere it would be a cost for nothing.
 */
internal class SkikoTextEditorInputMethodRequest(
	private val editorState: TextEditorState,
	override val imeOptions: ImeOptions,
	exposeTextLayout: Boolean = false,
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

	private val documentLayout = if (exposeTextLayout) DocumentTextLayout(editorState) else null

	/**
	 * The editor draws its own lines, so there is no Compose layout to expose. A platform
	 * that hit-tests the text itself asks for [exposeTextLayout] and gets a whole-document
	 * layout built on demand ([DocumentTextLayout]); the others get null, the documented
	 * "not laid out yet" answer every framework tolerates.
	 */
	override val textLayoutResult: () -> TextLayoutResult? = { documentLayout?.get() }

	/** Caret rectangle in root coordinates; positions candidate windows and the web backing input. */
	override val focusedRectInRoot: () -> Rect? = {
		// Platforms ask as soon as the caret moves, before the next frame draws it, so the
		// caret is measured here. Observers re-run on a caret move only, as the platforms'
		// geometry tracking expects; a scroll alone does not move the rectangle.
		editorState.cursorPosition
		attachedCoordinates()?.let {
			Snapshot.withoutReadObservation { editorState.imeCaretInRoot() }
				?.let { caret -> Rect(left = caret.x, top = caret.top, right = caret.x, bottom = caret.bottom) }
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
		// The coordinates are a plain field. The viewport size and the canvas's position
		// are snapshot state that change with every resize (a rotation) and move (the
		// window shifting for a soft keyboard), so reading them lets an observer of the
		// rectangles follow both.
		editorState.viewportSize
		editorState.canvasPositionInRoot
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
