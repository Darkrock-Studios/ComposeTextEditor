package com.darkrockstudios.texteditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.contextmenu.ContextMenuActions
import com.darkrockstudios.texteditor.contextmenu.ContextMenuStrings
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuProvider
import com.darkrockstudios.texteditor.contextmenu.TextEditorContextMenuState
import com.darkrockstudios.texteditor.input.LocalKeyBindings
import com.darkrockstudios.texteditor.input.TextEditorInputModifierElement
import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * Read-only view of a [TextEditorState]'s content using the editor's full rendering pipeline
 * (text, span styles, rich spans). No surface, scrollbar, focus border, IME, gestures, or
 * cursor — height wraps to content. Useful for showing rich content in detail screens or list
 * cards without the editor chrome.
 *
 * When [isSelectable] is true, users can select text (mouse drag / double-click word /
 * triple-click line / long-press on touch), copy to the clipboard, select all, and open a
 * right-click "Copy / Select All" context menu. The copy and select-all shortcuts follow the
 * host platform: Ctrl+C / Ctrl+A on Windows and Linux, Cmd+C / Cmd+A on macOS and iOS. The
 * text cursor caret is still never drawn, only selection.
 *
 * The caller owns the [TextEditorState] and is responsible for seeding it with content
 * (e.g. via `rememberTextEditorState(initialText)` or `withMarkdown(...)`).
 */
@Composable
fun RichTextView(
	state: TextEditorState,
	modifier: Modifier = Modifier,
	contentPadding: PaddingValues = PaddingValues(0.dp),
	style: TextEditorStyle = rememberTextEditorStyle(),
	isSelectable: Boolean = false,
) {
	LaunchedEffect(style.textStyle) {
		state.textStyle = style.textStyle
	}

	val density = LocalDensity.current

	LaunchedEffect(density) {
		state.density = density
	}

	val spanTapGate = remember { SpanTapGate() }

	if (isSelectable) {
		val clipboard = LocalClipboard.current
		val focusRequester = remember { FocusRequester() }
		val interactionSource = remember { MutableInteractionSource() }
		val contextMenuState = remember { TextEditorContextMenuState() }
		val keyBindings = LocalKeyBindings.current
		val inputModifierElement = remember(state, clipboard, keyBindings) {
			TextEditorInputModifierElement(state, clipboard, enabled = false, keyBindings = keyBindings)
		}
		val contextMenuActions = remember(state, clipboard) {
			ContextMenuActions(state, clipboard, state.scope)
		}

		TextEditorContextMenuProvider(
			menuState = contextMenuState,
			actions = contextMenuActions,
			strings = ContextMenuStrings.Default,
			enabled = false,
		) {
			RichTextViewBody(
				state = state,
				modifier = modifier
					.focusRequester(focusRequester)
					.requestFocusOnPress(focusRequester, spanTapGate)
					.then(inputModifierElement)
					.focusable(enabled = true, interactionSource = interactionSource),
				contentPadding = contentPadding,
				style = style,
				isSelectable = true,
				onContextMenuRequest = { offset -> contextMenuState.showMenu(offset) },
				spanTapGate = spanTapGate,
			)
		}
	} else {
		RichTextViewBody(
			state = state,
			modifier = modifier,
			contentPadding = contentPadding,
			style = style,
			isSelectable = false,
			onContextMenuRequest = null,
			spanTapGate = spanTapGate,
		)
	}
}

@Composable
private fun RichTextViewBody(
	state: TextEditorState,
	modifier: Modifier,
	contentPadding: PaddingValues,
	style: TextEditorStyle,
	isSelectable: Boolean,
	onContextMenuRequest: ((Offset) -> Unit)?,
	spanTapGate: SpanTapGate,
) {
	val density = LocalDensity.current
	val layoutDirection = LocalLayoutDirection.current

	BoxWithConstraints(modifier = modifier) {
		val totalWidthPx = with(density) { maxWidth.toPx() }
		val startPx = with(density) { contentPadding.calculateStartPadding(layoutDirection).toPx() }
		val endPx = with(density) { contentPadding.calculateEndPadding(layoutDirection).toPx() }
		val contentWidthPx = (totalWidthPx - startPx - endPx).coerceAtLeast(0f)

		// Drive line layout from the available content width. Height is set arbitrarily large
		// — RichTextView never scrolls, so the viewport height only affects DrawEditorText's
		// visible-range culling, which we want to be a no-op.
		LaunchedEffect(contentWidthPx) {
			if (contentWidthPx > 0f) {
				state.onViewportSizeChange(Size(contentWidthPx, Float.MAX_VALUE / 2))
			}
		}

		val contentHeightPx = state.lineOffsets.lastOrNull()?.let { last ->
			last.offset.y + last.effectiveHeight
		} ?: 0f

		Box(modifier = Modifier.padding(contentPadding)) {
			val selectionModifier = if (isSelectable) {
				Modifier
					.pointerHoverIcon(PointerIcon.Text)
					.textEditorPointerInputHandling(
						state = state,
						onContextMenuRequest = onContextMenuRequest,
						readOnly = true,
						spanTapGate = spanTapGate,
					)
			} else {
				Modifier
			}

			Canvas(
				modifier = Modifier
					.fillMaxWidth()
					.height(with(density) { contentHeightPx.toDp() })
					.graphicsLayer { clip = false }
					.then(selectionModifier)
			) {
				try {
					DrawEditorText(state, style, decorateLine = null)
				} catch (_: IllegalArgumentException) {
					// Mid-resize layout race; the next frame will recover, mirrors BasicTextEditor.
				}

				if (isSelectable) {
					DrawSelection(state, style.selectionColor)
					DrawSelectionHandles(state)
				}
			}
		}
	}
}

