@file:OptIn(ExperimentalComposeUiApi::class)

package com.darkrockstudios.texteditor.input

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.TextEditingScope
import androidx.compose.ui.text.input.TextFieldValue
import com.darkrockstudios.texteditor.state.TextEditorState
import androidx.compose.ui.text.input.TextEditorState as ComposeTextEditorState

/**
 * Desktop implementation of [TextEditorTextInputService].
 *
 * Unlike the previous version (which suspended indefinitely and relied solely on
 * AWT `KEY_TYPED` events), this establishes a real platform input-method session
 * via [PlatformTextInputSession.startInputMethod]. Compose's
 * `DesktopTextInputService2` then attaches an AWT `InputMethodRequests` to the
 * window and routes `InputMethodEvent`s — dead-key / accent composition, CJK
 * input, the macOS press-and-hold accent popup, the Windows emoji picker — into
 * our [PlatformTextInputMethodRequest.editText] callback.
 *
 * Plain ASCII typing still arrives as `KEY_TYPED` and is handled by
 * [TextEditorKeyCommandHandler.handleCharacterInput]; the two paths coexist
 * exactly as they do in Compose's own `BasicTextField`. AWT delivers a given
 * keystroke through one path or the other, not both, and the press-and-hold case
 * (where a base char is typed and later replaced by the accented form) is handled
 * by Compose calling `deleteSurroundingTextInCodePoints` into [editText].
 *
 * This fixes dead keys not working in the editor on Linux (issue #561).
 */
actual class TextEditorTextInputService actual constructor(
	private val state: TextEditorState
) {
	actual suspend fun startInput(session: PlatformTextInputSession): Nothing {
		session.startInputMethod(DesktopTextEditorInputMethodRequest(state))
	}
}

private class DesktopTextEditorInputMethodRequest(
	private val editorState: TextEditorState
) : PlatformTextInputMethodRequest {

	/** Live view of the editor as the CharSequence + selection + composition Compose expects. */
	override val state: ComposeTextEditorState = ImeComposeStateAdapter(editorState)

	override val value: () -> TextFieldValue = {
		TextFieldValue(
			text = editorState.getAllText().text,
			selection = editorState.selectionAsTextRange()
		)
	}

	override val imeOptions: ImeOptions = ImeOptions.Default

	// The desktop input path (DesktopTextInputService2) drives every edit through
	// `editText`. `onEditCommand` is the legacy BTF1 hook and is never invoked on
	// this path, so a no-op satisfies the interface.
	override val onEditCommand: (List<EditCommand>) -> Unit = {}

	override val onImeAction: ((ImeAction) -> Unit)? = null

	// We render text with our own Canvas, not a Compose TextLayoutResult, so there
	// is nothing to expose. Null is explicitly allowed ("not laid out yet").
	override val textLayoutResult: () -> TextLayoutResult? = { null }

	/** Cursor rectangle in root coordinates — positions the IME candidate window. */
	override val focusedRectInRoot: () -> Rect? = {
		val coords = editorState.canvasLayoutCoordinates
		val metrics = editorState.lastCursorMetrics
		if (coords != null && coords.isAttached && metrics != null) {
			val origin = coords.positionInRoot()
			Rect(
				left = origin.x + metrics.position.x,
				top = origin.y + metrics.lineTop,
				right = origin.x + metrics.position.x,
				bottom = origin.y + metrics.lineBottom
			)
		} else {
			null
		}
	}

	override val textFieldRectInRoot: () -> Rect? = { editorBoundsInRoot() }

	override val textClippingRectInRoot: () -> Rect? = { editorBoundsInRoot() }

	/** Top-left of the (unclipped) text content in root coordinates. */
	override val unclippedTextOffsetInRoot: () -> Offset? = {
		val coords = editorState.canvasLayoutCoordinates
		if (coords != null && coords.isAttached) coords.positionInRoot() else null
	}

	override val editText: (TextEditingScope.() -> Unit) -> Unit = { block ->
		DesktopTextEditingScope(editorState).block()
	}

	private fun editorBoundsInRoot(): Rect? {
		val coords = editorState.canvasLayoutCoordinates ?: return null
		if (!coords.isAttached) return null
		val origin = coords.positionInRoot()
		val size = coords.size
		return Rect(
			left = origin.x,
			top = origin.y,
			right = origin.x + size.width,
			bottom = origin.y + size.height
		)
	}
}

/**
 * Adapts the library's [TextEditorState] to Compose's skiko `TextEditorState`
 * (CharSequence + selection + composition), read live each time the AWT
 * input-method framework queries it. Mapping logic is shared via the
 * `*AsTextRange` / `imeSubSequence` helpers in commonMain.
 */
private class ImeComposeStateAdapter(
	private val editorState: TextEditorState
) : ComposeTextEditorState {
	override val length: Int get() = editorState.getTextLength()
	override fun get(index: Int): Char = editorState.imeCharAt(index)
	override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
		editorState.imeSubSequence(startIndex, endIndex)
	override val text: String get() = editorState.getAllText().text
	override fun toString(): String = text
	override val selection get() = editorState.selectionAsTextRange()
	override val composition get() = editorState.composingAsTextRange()
}

/**
 * Bridges Compose's [TextEditingScope] IME edit commands to the shared
 * [ImeEditLogic] operations on [TextEditorState].
 */
private class DesktopTextEditingScope(
	private val state: TextEditorState
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
