@file:OptIn(ExperimentalComposeUiApi::class)

package com.darkrockstudios.texteditor.input

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
import com.darkrockstudios.texteditor.state.precedingGraphemeBoundary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.TextEditorState as ComposeTextEditorState

/**
 * The keyboard options iOS and the web ask for: the editor's [KeyboardSettings], with a
 * single line asking for single-line text and its action key (Done by default), as
 * Android's `EditorInfo` does.
 */
internal fun TextEditorState.skikoImeOptions(): ImeOptions {
	val settings = keyboardSettings
	return ImeOptions(
		singleLine = isSingleLine,
		capitalization = settings.capitalization,
		autoCorrect = settings.autoCorrect,
		keyboardType = settings.keyboardType,
		imeAction = effectiveImeAction(),
	)
}

/**
 * Starts a Compose skiko input-method session bound to this editor. A platform's
 * `TextEditorTextInputService` hands it that platform's [imeOptions], read as snapshot
 * state: when they change, the input method starts again with the new ones, as Android
 * restarts its input for new settings, calling [onRun] with them for each run, just
 * before the run starts the platform's input method and in the same dispatch. It also
 * hands whether the
 * platform needs a text layout to hit-test ([exposeTextLayout]), whether it acts on a
 * hardware key itself as well ([echoesKeys]), and [imeResync]; every
 * edit the platform delivers lands in [ImeEditLogic] through
 * [SkikoTextEditorInputMethodRequest].
 *
 * The frameworks watch the request's text through `snapshotFlow`, but the document
 * is deliberately not snapshot state. The request's text reads fold in
 * [TextEditorState.textRevision], snapshot state that advances with every text
 * change, so an edit that moves no cursor (a forward delete) still reaches the
 * platform's mirror, a keystroke's caret move and edit land in one apply, and a
 * rich-span change (a spell-check pass) wakes no observer.
 *
 * A resync ([TextEditorState.requestImeResync]) is handed to [imeResync], which says how
 * this platform makes its input method drop what it assumed. It defaults to
 * [SkikoImeResync.None], which iOS relies on until the device pass decides (roadmap 4.29).
 */
internal suspend fun TextEditorState.startSkikoInputSession(
	session: PlatformTextInputSession,
	imeOptions: () -> ImeOptions,
	exposeTextLayout: Boolean = false,
	echoesKeys: Boolean = false,
	imeResync: SkikoImeResync = SkikoImeResync.None,
	onRun: CoroutineScope.(ImeOptions) -> Unit = {},
): Nothing = coroutineScope {
	snapshotFlow(imeOptions).collectLatest { options ->
		coroutineScope {
			onRun(options)
			runSkikoInputMethod(session, options, exposeTextLayout, echoesKeys, imeResync)
		}
	}
	throw CancellationException("The keyboard options stopped")
}

/** One run of the platform's input method with [imeOptions]; see [startSkikoInputSession]. */
private suspend fun TextEditorState.runSkikoInputMethod(
	session: PlatformTextInputSession,
	imeOptions: ImeOptions,
	exposeTextLayout: Boolean,
	echoesKeys: Boolean,
	imeResync: SkikoImeResync,
): Nothing = coroutineScope {
	val request = SkikoTextEditorInputMethodRequest(
		this@runSkikoInputMethod,
		imeOptions,
		exposeTextLayout,
		echoesKeys,
	)
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
 * @param echoesKeys The platform acts on a hardware key after the editor has, moving
 *   the caret for a caret key and typing a tab for Tab, and repeats a held one itself:
 *   UIKit does. Its edits while such a key is held go to [TextEditorState.heldKey]
 *   instead of the document.
 */
internal class SkikoTextEditorInputMethodRequest(
	private val editorState: TextEditorState,
	override val imeOptions: ImeOptions,
	exposeTextLayout: Boolean = false,
	private val echoesKeys: Boolean = false,
) : PlatformTextInputMethodRequest {

	/** Live view of the editor as the CharSequence + selection + composition Compose expects. */
	override val state: ComposeTextEditorState = ImeComposeStateAdapter()

	override val value: () -> TextFieldValue = {
		editorState.textRevision
		TextFieldValue(
			text = editorState.getAllPlainText(),
			selection = editorState.selectionAsTextRange(),
		)
	}

	override val onEditCommand: (List<EditCommand>) -> Unit = { commands ->
		commands.forEach { editorState.applyImeEditCommand(it) }
	}

	/**
	 * The keyboard's action key, or Return in a single line: iOS calls this rather than
	 * typing a line break. An action that starts a line is not one to run.
	 */
	override val onImeAction: ((ImeAction) -> Unit)? = { action ->
		if (!action.startsLine) editorState.performImeAction(action)
	}

	private val documentLayout = if (exposeTextLayout) DocumentTextLayout(editorState) else null

	/**
	 * The editor draws its own lines, so there is no Compose layout to expose. A platform
	 * that hit-tests the text itself asks for [exposeTextLayout] and gets a whole-document
	 * layout built on demand ([DocumentTextLayout]); the others get null, the documented
	 * "not laid out yet" answer every framework tolerates.
	 */
	override val textLayoutResult: () -> TextLayoutResult? = {
		editorState.textRevision
		documentLayout?.get()
	}

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

	private val keyboardBackspace = KeyboardBackspace()

	override val editText: (TextEditingScope.() -> Unit) -> Unit = { block ->
		SkikoTextEditingScope(editorState, keyboardBackspace, echoesKeys).block()
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
		override val length: Int
			get() {
				editorState.textRevision
				return editorState.getTextLength()
			}

		override fun get(index: Int): Char {
			editorState.textRevision
			return editorState.imeCharAt(index)
		}

		override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
			editorState.textRevision
			return editorState.imeSubSequence(startIndex, endIndex)
		}

		override val text: String
			get() {
				editorState.textRevision
				return editorState.getAllPlainText()
			}

		override fun toString(): String = text

		// Flat indices depend on the lines before the caret as well as the caret itself,
		// so an edit elsewhere in the document must re-run an observer of these too.
		override val selection: TextRange
			get() {
				editorState.textRevision
				return editorState.selectionAsTextRange()
			}

		override val composition: TextRange?
			get() {
				editorState.textRevision
				return editorState.composingAsTextRange()
			}
	}
}

/**
 * iOS's soft keyboard deletes backward in two edits: it selects the composed character
 * before the caret, then deletes the selection, which Compose sends as a commit of
 * nothing. Seen as edits, that is a selection replaced, which no backspace behavior
 * hears. This remembers a selection the keyboard took back from a collapsed caret, so
 * the commit that empties it next can be run as the backspace it is (roadmap 4.33).
 * Any other edit in between forgets it.
 */
private class KeyboardBackspace {
	private var selected: TextRange? = null

	fun selecting(state: TextEditorState, start: Int, end: Int) {
		val caret = state.selectionAsTextRange()
		selected = TextRange(start, end).takeIf {
			caret.collapsed && end == caret.start && state.composingRange == null &&
				start == state.clusterStartBefore(end)
		}
	}

	/**
	 * Where the grapheme cluster ending at [index] starts: the unit UIKit selects for one
	 * backspace. A wider selection is the user's own (a trackpad or Shift selection) and
	 * is deleted as a selection. At a line start the cluster is the line break.
	 */
	private fun TextEditorState.clusterStartBefore(index: Int): Int? {
		if (index <= 0) return null
		val at = getOffsetAtCharacter(index)
		if (at.char == 0) return index - 1
		return index - at.char + textLines[at.line].text.precedingGraphemeBoundary(at.char)
	}

	/** The range to backspace over when committing [text] now empties that selection, else null. */
	fun takeFor(state: TextEditorState, text: CharSequence): TextRange? {
		val range = selected
		selected = null
		return range?.takeIf {
			text.isEmpty() && state.composingRange == null && state.selectionAsTextRange() == it
		}
	}

	fun forget() {
		selected = null
	}
}

/** Bridges Compose's [TextEditingScope] to the shared [ImeEditLogic] operations. */
private class SkikoTextEditingScope(
	private val state: TextEditorState,
	private val keyboardBackspace: KeyboardBackspace,
	private val echoesKeys: Boolean,
) : TextEditingScope {

	override fun deleteSurroundingTextInCodePoints(lengthBeforeCursor: Int, lengthAfterCursor: Int) {
		platformEdited()
		state.imeDeleteSurroundingTextInCodePoints(lengthBeforeCursor, lengthAfterCursor)
	}

	/**
	 * Any other edit from the platform: it ends the keyboard's backspace, and a held caret
	 * key too, since the keys Compose consumes before the editor sees them (Backspace,
	 * Return) reach it only as edits.
	 */
	private fun platformEdited() {
		keyboardBackspace.forget()
		state.heldKey.clear()
	}

	override fun setSelection(start: Int, end: Int) {
		if (echoesKeys && state.heldKey.absorbSelection()) {
			keyboardBackspace.forget()
			return
		}
		keyboardBackspace.selecting(state, start, end)
		state.imeSetSelection(start, end)
	}

	override fun commitText(text: CharSequence, newCursorPosition: Int) {
		if (echoesKeys && state.heldKey.absorbText(text)) {
			keyboardBackspace.forget()
			return
		}
		state.heldKey.clear()
		val backspace = keyboardBackspace.takeFor(state, text)
		if (backspace != null) {
			state.imeBackspaceOver(backspace)
		} else {
			state.imeCommitText(text.toString(), newCursorPosition)
		}
	}

	override fun setComposingRegion(start: Int, end: Int) {
		platformEdited()
		state.imeSetComposingRegion(start, end)
	}

	override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
		platformEdited()
		state.imeSetComposingText(text.toString(), newCursorPosition)
	}

	override fun finishComposingText() {
		platformEdited()
		state.imeFinishComposing()
	}
}
