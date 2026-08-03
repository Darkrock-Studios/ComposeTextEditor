package com.darkrockstudios.texteditor.sample

import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.*
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getRichSpansAtPosition
import com.darkrockstudios.texteditor.state.getRichSpansInRange
import com.darkrockstudios.texteditor.state.getSpanStylesInRange
import markdown.decreaseFontSize
import markdown.increaseFontSize

@Composable
fun TextEditorToolbar(
	mardkown: MarkdownExtension,
	markdownControls: Boolean,
	modifier: Modifier = Modifier,
) {
	val state = remember(mardkown) { mardkown.editorState }

	var isBoldActive by remember { mutableStateOf(false) }
	var isItalicActive by remember { mutableStateOf(false) }
	var isCodeActive by remember { mutableStateOf(false) }
	var isStrikethroughActive by remember { mutableStateOf(false) }
	var existingLinkSpan by remember { mutableStateOf<RichSpan?>(null) }
	var isBlockquoteActive by remember { mutableStateOf(false) }
	var isBulletListActive by remember { mutableStateOf(false) }
	var isOrderedListActive by remember { mutableStateOf(false) }
	var isCodeFenceActive by remember { mutableStateOf(false) }
	var currentHeaderLevel by remember { mutableStateOf(0) }
	var isHighlightActive by remember { mutableStateOf(false) }
	var linkDialogState by remember { mutableStateOf<LinkDialogRequest?>(null) }
	val isLinkActive = existingLinkSpan != null

	LaunchedEffect(Unit) {
		state.cursorDataFlow.collect { (position, cursorStyles, selection) ->
			val styles = if (selection != null) {
				state.getSpanStylesInRange(selection)
			} else {
				cursorStyles
			}

			val richSpans = if (selection != null) {
				state.getRichSpansInRange(selection)
			} else {
				state.getRichSpansAtPosition(position)
			}

			isBoldActive = styles.contains(mardkown.markdownStyles.BOLD)
			isItalicActive = styles.contains(mardkown.markdownStyles.ITALICS)
			isCodeActive = styles.contains(mardkown.markdownStyles.CODE)
			isStrikethroughActive = styles.contains(mardkown.markdownStyles.STRIKETHROUGH)
			existingLinkSpan = richSpans.firstOrNull { it.style is LinkSpanStyle }
			isBlockquoteActive = richSpans.any { it.style === BlockquoteSpanStyle }
			isBulletListActive = richSpans.any { it.style === BulletListSpanStyle }
			isOrderedListActive = richSpans.any { it.style === OrderedListSpanStyle }
			isCodeFenceActive = richSpans.any { it.style === CodeFenceSpanStyle }
			currentHeaderLevel = mardkown.headerLevel(position.line) ?: 0
			isHighlightActive = richSpans.any { it.style == HIGHLIGHT }
		}
	}

	LaunchedEffect(Unit) {
		state.editOperations.collect { reconcileHorizontalRules(state) }
	}

	Surface(modifier = modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier
				.horizontalScroll(rememberScrollState())
				.padding(horizontal = 16.dp, vertical = 2.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			// History Controls Group
			Row {
				ToolbarButton(
					onClick = state::undo,
					icon = Icons.AutoMirrored.Filled.Undo,
					contentDescription = "Undo",
					enabled = state.canUndo
				)

				Spacer(modifier = Modifier.width(4.dp))

				ToolbarButton(
					onClick = state::redo,
					icon = Icons.AutoMirrored.Filled.Redo,
					contentDescription = "Redo",
					enabled = state.canRedo
				)
			}

			Spacer(modifier = Modifier.width(12.dp))

			VerticalDivider(modifier = Modifier.height(24.dp))

			Spacer(modifier = Modifier.width(12.dp))

			// Formatting Controls Group
			Row {
				FormatButton(
					onClick = { state.toggleBold(mardkown) },
					icon = Icons.Default.FormatBold,
					contentDescription = "Bold",
					isActive = isBoldActive,
				)

				Spacer(modifier = Modifier.width(4.dp))

				FormatButton(
					onClick = {
						toggleStyle(state, isItalicActive, mardkown.markdownStyles.ITALICS)
					},
					icon = Icons.Default.FormatItalic,
					contentDescription = "Italic",
					isActive = isItalicActive,
				)

				if (markdownControls) {
					Spacer(modifier = Modifier.width(4.dp))

					FormatButton(
						onClick = {
							toggleStyle(state, isCodeActive, mardkown.markdownStyles.CODE)
						},
						icon = Icons.Default.Code,
						contentDescription = "Inline Code",
						isActive = isCodeActive,
					)

					Spacer(modifier = Modifier.width(4.dp))

					FormatButton(
						onClick = {
							toggleStyle(
								state,
								isStrikethroughActive,
								mardkown.markdownStyles.STRIKETHROUGH
							)
						},
						icon = Icons.Default.FormatStrikethrough,
						contentDescription = "Strikethrough",
						isActive = isStrikethroughActive,
					)

					Spacer(modifier = Modifier.width(4.dp))

					FormatButton(
						onClick = {
							val targetRange =
								state.selector.selection ?: existingLinkSpan?.range
							if (targetRange != null) {
								linkDialogState = LinkDialogRequest(
									range = targetRange,
									existingSpan = existingLinkSpan,
								)
							}
						},
						icon = Icons.Default.Link,
						contentDescription = if (isLinkActive) "Edit link" else "Add link",
						isActive = isLinkActive,
						enabled = state.selector.hasSelection() || isLinkActive,
					)

					Spacer(modifier = Modifier.width(4.dp))

					TextLabelButton(
						onClick = {
							cycleHeader(state, mardkown, currentHeaderLevel)
						},
						label = if (currentHeaderLevel == 0) "H" else "H$currentHeaderLevel",
						contentDescription = if (currentHeaderLevel == 0)
							"Header (none) — click to cycle"
						else
							"Header H$currentHeaderLevel — click to cycle",
						isActive = currentHeaderLevel != 0,
					)

					Spacer(modifier = Modifier.width(4.dp))

					FormatButton(
						onClick = { toggleBlockquote(state, mardkown) },
						icon = Icons.Default.FormatQuote,
						contentDescription = "Blockquote",
						isActive = isBlockquoteActive,
					)

					Spacer(modifier = Modifier.width(4.dp))

					FormatButton(
						onClick = { toggleBulletList(state, mardkown) },
						icon = Icons.Default.FormatListBulleted,
						contentDescription = "Bullet list",
						isActive = isBulletListActive,
					)

					Spacer(modifier = Modifier.width(4.dp))

					FormatButton(
						onClick = { toggleOrderedList(state, mardkown) },
						icon = Icons.Default.FormatListNumbered,
						contentDescription = "Ordered list",
						isActive = isOrderedListActive,
					)

					Spacer(modifier = Modifier.width(4.dp))

					FormatButton(
						onClick = { toggleCodeFence(state, mardkown) },
						icon = Icons.Default.Terminal,
						contentDescription = "Code block",
						isActive = isCodeFenceActive,
					)

					Spacer(modifier = Modifier.width(4.dp))

					FormatButton(
						onClick = { insertHorizontalRule(state) },
						icon = Icons.Default.HorizontalRule,
						contentDescription = "Horizontal rule",
						isActive = false,
					)

					Spacer(modifier = Modifier.width(12.dp))

					// Font size control group
					VerticalDivider(modifier = Modifier.height(24.dp))

					Spacer(modifier = Modifier.width(12.dp))

					// Font size decrease button
					ToolbarButton(
						onClick = { decreaseFontSize(mardkown) },
						icon = Icons.Default.Remove,
						contentDescription = "Decrease Font Size"
					)

					Icon(
						imageVector = Icons.Default.FormatSize,
						contentDescription = null,
						modifier = Modifier
							.size(20.dp)
							.padding(horizontal = 4.dp),
						tint = MaterialTheme.colorScheme.onSurfaceVariant
					)

					// Font size increase button
					ToolbarButton(
						onClick = { increaseFontSize(mardkown) },
						icon = Icons.Default.Add,
						contentDescription = "Increase Font Size"
					)
				} else {
					Spacer(modifier = Modifier.width(4.dp))

					FormatButton(
						onClick = {
							state.selector.selection?.let { range ->
								if (isHighlightActive) {
									state.removeRichSpan(range.start, range.end, HIGHLIGHT)
								} else {
									state.addRichSpan(range.start, range.end, HIGHLIGHT)
								}
							}
						},
						icon = Icons.Default.Highlight,
						contentDescription = "Highlight",
						isActive = isHighlightActive,
						enabled = state.selector.hasSelection()
					)
				}
			}
		}
	}

	linkDialogState?.let { request ->
		val isEditing = request.existingSpan != null
		LinkDialog(
			initialUrl = (request.existingSpan?.style as? LinkSpanStyle)?.url ?: "",
			isEditing = isEditing,
			onConfirm = { url ->
				applyLink(state, mardkown, request, url)
				linkDialogState = null
			},
			onRemove = if (isEditing) {
				{
					applyLink(state, mardkown, request, url = "")
					linkDialogState = null
				}
			} else null,
			onDismiss = { linkDialogState = null },
		)
	}
}

private data class LinkDialogRequest(
	val range: TextEditorRange,
	val existingSpan: RichSpan?,
)

@Composable
private fun LinkDialog(
	initialUrl: String,
	isEditing: Boolean,
	onConfirm: (String) -> Unit,
	onRemove: (() -> Unit)?,
	onDismiss: () -> Unit,
) {
	var url by remember(initialUrl) { mutableStateOf(initialUrl) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(if (isEditing) "Edit link" else "Add link") },
		text = {
			OutlinedTextField(
				value = url,
				onValueChange = { url = it },
				label = { Text("URL") },
				placeholder = { Text("https://example.com") },
				singleLine = true,
			)
		},
		confirmButton = {
			TextButton(
				onClick = { onConfirm(url) },
				enabled = url.isNotBlank(),
			) { Text(if (isEditing) "Save" else "Add") }
		},
		dismissButton = {
			Row {
				if (onRemove != null) {
					TextButton(onClick = onRemove) { Text("Remove") }
					Spacer(modifier = Modifier.width(4.dp))
				}
				TextButton(onClick = onDismiss) { Text("Cancel") }
			}
		},
	)
}

private fun applyLink(
	state: TextEditorState,
	markdown: MarkdownExtension,
	request: LinkDialogRequest,
	url: String,
) {
	request.existingSpan?.let { state.removeRichSpan(it) }
	state.removeStyleSpan(request.range, markdown.markdownStyles.LINK)
	if (url.isNotBlank()) {
		state.addStyleSpan(request.range, markdown.markdownStyles.LINK)
		state.addRichSpan(request.range.start, request.range.end, LinkSpanStyle(url))
	}
}

private fun toggleBlockquote(state: TextEditorState, markdown: MarkdownExtension) {
	val selection = state.selector.selection
	val lines = if (selection != null) {
		selection.start.line..selection.end.line
	} else {
		state.cursorPosition.line..state.cursorPosition.line
	}
	markdown.toggleBlockquote(lines)
}

private fun toggleBulletList(state: TextEditorState, markdown: MarkdownExtension) {
	val selection = state.selector.selection
	val lines = if (selection != null) {
		selection.start.line..selection.end.line
	} else {
		state.cursorPosition.line..state.cursorPosition.line
	}
	markdown.toggleBulletList(lines)
}

private fun toggleOrderedList(state: TextEditorState, markdown: MarkdownExtension) {
	val selection = state.selector.selection
	val lines = if (selection != null) {
		selection.start.line..selection.end.line
	} else {
		state.cursorPosition.line..state.cursorPosition.line
	}
	markdown.toggleOrderedList(lines)
}

private fun toggleCodeFence(state: TextEditorState, markdown: MarkdownExtension) {
	val selection = state.selector.selection
	val lines = if (selection != null) {
		selection.start.line..selection.end.line
	} else {
		state.cursorPosition.line..state.cursorPosition.line
	}
	markdown.toggleCodeFence(lines)
}

private fun insertHorizontalRule(state: TextEditorState) {
	state.insertNewlineAtCursor()
	val hrLine = state.cursorPosition.line
	state.insertStringAtCursor(HR_PLACEHOLDER)
	state.insertNewlineAtCursor()
	state.addRichSpan(
		start = CharLineOffset(hrLine, 0),
		end = CharLineOffset(hrLine, HR_PLACEHOLDER.length),
		style = HorizontalRuleSpanStyle,
	)
}

// Once a user types on an HR line, the placeholder space is gone — drop the rule and
// strip the tracked placeholder so the line becomes plain text. A proper fix needs
// block-level support in the editor.
private fun reconcileHorizontalRules(state: TextEditorState) {
	val hrSpans = state.richSpanManager.getAllRichSpans()
		.filter { it.style === HorizontalRuleSpanStyle }
	if (hrSpans.isEmpty()) return
	hrSpans.forEach { span ->
		val lineIndex = span.range.start.line
		val lineText = state.textLines.getOrNull(lineIndex)?.text ?: return@forEach
		if (lineText == HR_PLACEHOLDER) return@forEach

		// Fallback to indexOf(' ') guards against paste/replace that didn't preserve the
		// tracked position.
		val placeholderChar = span.range.start.char
		val deleteAt = if (lineText.getOrNull(placeholderChar) == ' ') {
			placeholderChar
		} else {
			lineText.indexOf(' ').takeIf { it >= 0 }
		}
		if (deleteAt != null) {
			state.delete(
				TextEditorRange(
					start = CharLineOffset(lineIndex, deleteAt),
					end = CharLineOffset(lineIndex, deleteAt + 1),
				)
			)
		}
		state.removeRichSpan(span)
	}
}

private fun toggleStyle(
	state: TextEditorState,
	isActive: Boolean,
	spanStyle: SpanStyle
) {
	val selection = state.selector.selection
	if (selection != null) {
		if (isActive) {
			state.removeStyleSpan(selection, spanStyle)
		} else {
			state.addStyleSpan(selection, spanStyle)
		}
	} else {
		state.cursor.toggleStyle(spanStyle)
	}
}

// Cycles none -> H1 -> H2 -> ... -> H6 -> none over the selected lines (or the
// cursor's line). Headings are semantic line blocks: toggling the same level
// removes it, toggling a new level swaps it.
private fun cycleHeader(
	state: TextEditorState,
	markdown: MarkdownExtension,
	currentLevel: Int,
) {
	val nextLevel = (currentLevel + 1) % 7
	val selection = state.selector.selection
	val lines = if (selection != null) {
		selection.start.line..selection.end.line
	} else {
		state.cursorPosition.line..state.cursorPosition.line
	}
	if (nextLevel != 0) {
		markdown.toggleHeader(lines, nextLevel)
	} else if (currentLevel != 0) {
		markdown.toggleHeader(lines, currentLevel)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarButton(
	onClick: () -> Unit,
	icon: ImageVector,
	contentDescription: String,
	isActive: Boolean = false,
	enabled: Boolean = true,
	modifier: Modifier = Modifier
) {
	TooltipBox(
		positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
		tooltip = { PlainTooltip { Text(contentDescription) } },
		state = rememberTooltipState(),
	) {
		FilledTonalIconButton(
			onClick = onClick,
			enabled = enabled,
			modifier = modifier
				.size(32.dp)
				.focusable(false)
				.focusProperties {
					canFocus = false
				},
			colors = IconButtonDefaults.filledTonalIconButtonColors(
				containerColor = if (isActive)
					MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
				else
					MaterialTheme.colorScheme.surfaceVariant,
				contentColor = if (isActive)
					MaterialTheme.colorScheme.primary
				else
					MaterialTheme.colorScheme.onSurfaceVariant,
				disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
				disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
			)
		) {
			Icon(
				imageVector = icon,
				contentDescription = contentDescription,
				modifier = Modifier.size(20.dp)
			)
		}
	}
}

@Composable
private fun FormatButton(
	onClick: () -> Unit,
	icon: ImageVector,
	contentDescription: String,
	isActive: Boolean,
	enabled: Boolean = true
) {
	ToolbarButton(
		onClick = onClick,
		icon = icon,
		contentDescription = contentDescription,
		isActive = isActive,
		enabled = enabled
	)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextLabelButton(
	onClick: () -> Unit,
	label: String,
	contentDescription: String,
	isActive: Boolean,
	enabled: Boolean = true,
) {
	TooltipBox(
		positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
		tooltip = { PlainTooltip { Text(contentDescription) } },
		state = rememberTooltipState(),
	) {
		FilledTonalButton(
			onClick = onClick,
			enabled = enabled,
			modifier = Modifier
				.height(32.dp)
				.focusable(false)
				.focusProperties { canFocus = false },
			contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
			colors = ButtonDefaults.filledTonalButtonColors(
				containerColor = if (isActive)
					MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
				else
					MaterialTheme.colorScheme.surfaceVariant,
				contentColor = if (isActive)
					MaterialTheme.colorScheme.primary
				else
					MaterialTheme.colorScheme.onSurfaceVariant,
			)
		) {
			Text(
				text = label,
				style = MaterialTheme.typography.labelLarge,
			)
		}
	}
}
