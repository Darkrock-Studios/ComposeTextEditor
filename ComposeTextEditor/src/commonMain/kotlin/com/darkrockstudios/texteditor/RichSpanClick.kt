package com.darkrockstudios.texteditor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.SpanClickType

/**
 * One click or tap on a [RichSpan].
 *
 * A left-click or tap is reported on release, and only when the press and the
 * release land on the same span with no drag in between, so placing the caret or
 * starting a selection never reads as a click. The second and third press of a
 * multi-click are selection gestures and are not reported. A right-click is
 * reported on press, alongside the context menu it opens.
 *
 * @property span The span under the pointer.
 * @property type Whether this was a tap, a left-click, or a right-click.
 * @property offset Where the click landed, in the text's coordinates: inside the content
 *   padding, as the state's layout queries take them. To open a menu there, see
 *   [com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState.showMenuAtText].
 * @property keyboardModifiers The modifier keys held when the click was reported.
 */
data class RichSpanClick(
	val span: RichSpan,
	val type: SpanClickType,
	val offset: Offset,
	val keyboardModifiers: PointerKeyboardModifiers,
)

/**
 * Handles a [RichSpanClick]. Receives the same clicks as [RichSpanClickListener],
 * with the modifier keys added. The return value is the same chaining protocol and
 * has no effect on the editor.
 */
typealias RichSpanClickEventListener = (RichSpanClick) -> Boolean
