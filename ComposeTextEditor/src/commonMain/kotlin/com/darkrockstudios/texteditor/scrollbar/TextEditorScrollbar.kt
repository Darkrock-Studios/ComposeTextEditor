package com.darkrockstudios.texteditor.scrollbar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.darkrockstudios.texteditor.state.TextEditorScrollState

/**
 * Wraps editor [content] with a platform-appropriate vertical scrollbar driven by
 * [scrollState]. The platform implementation draws a scrollbar where the host
 * conventionally expects one (e.g. desktop) and passes a [Modifier] to [content]
 * so it can reserve room for it.
 *
 * @param modifier Applied to the scrollbar container.
 * @param scrollState Scroll position the scrollbar reflects and controls.
 * @param content The editor, receiving a [Modifier] to apply to its root.
 */
@Composable
expect fun TextEditorScrollbar(
    modifier: Modifier = Modifier,
    scrollState: TextEditorScrollState,
    content: @Composable (modifier: Modifier) -> Unit,
)
/**
 * The platform's sideways scrollbar for [scrollState] (7.41), laid over the bottom edge of
 * the text: Compose's own on desktop and the web, none on Android and iOS.
 */
@Composable
internal expect fun EditorHorizontalScrollbar(scrollState: TextEditorScrollState, modifier: Modifier)
