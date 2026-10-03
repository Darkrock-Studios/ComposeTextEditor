package com.darkrockstudios.texteditor.sample

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.sanitizeLinkUrl
import com.darkrockstudios.texteditor.richstyle.*
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.getRichSpansAtPosition
import com.darkrockstudios.texteditor.state.getRichSpansInRange
import com.darkrockstudios.texteditor.state.hasStyleThroughout
import com.darkrockstudios.texteditor.state.headerLevel
import com.darkrockstudios.texteditor.state.setLink
import com.darkrockstudios.texteditor.state.toggleBlockquote
import com.darkrockstudios.texteditor.state.toggleBulletList
import com.darkrockstudios.texteditor.state.toggleCodeFence
import com.darkrockstudios.texteditor.state.toggleHeader
import com.darkrockstudios.texteditor.state.toggleOrderedList
import com.darkrockstudios.texteditor.state.toggleSpanStyle
import com.darkrockstudios.texteditor.state.unlink
import markdown.decreaseFontSize
import markdown.increaseFontSize

@Composable
fun TextEditorToolbar(
	state: TextEditorState,
	markdownControls: Boolean,
	modifier: Modifier = Modifier,
) {

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
			// Active exactly when the toggle would remove the style.
			fun isActive(style: SpanStyle) =
				if (selection != null && selection.start != selection.end) {
					state.hasStyleThroughout(selection, style)
				} else {
					style in cursorStyles
				}

			val richSpans = if (selection != null) {
				state.getRichSpansInRange(selection)
			} else {
				state.getRichSpansAtPosition(position)
			}

			isBoldActive = isActive(state.richTextStyles.boldStyle)
			isItalicActive = isActive(state.richTextStyles.italicStyle)
			isCodeActive = isActive(state.richTextStyles.codeStyle)
			isStrikethroughActive = isActive(state.richTextStyles.strikethroughStyle)
			// The span lookup counts edges; a selection ending where a link starts does not touch it.
			val selected = selection?.takeIf { it.start != it.end }
			existingLinkSpan = richSpans.firstOrNull {
				it.style is LinkSpanStyle &&
					(selected == null || (it.range.start < selected.end && selected.start < it.range.end))
			}
			isBlockquoteActive = richSpans.any { it.style === BlockquoteSpanStyle }
			isBulletListActive = richSpans.any { it.style is BulletListSpanStyle }
			isOrderedListActive = richSpans.any { it.style is OrderedListSpanStyle }
			isCodeFenceActive = richSpans.any { it.style === CodeFenceSpanStyle }
			currentHeaderLevel = state.headerLevel(position.line) ?: 0
			isHighlightActive = isActive(state.richTextStyles.highlightStyle)
		}
	}

	LaunchedEffect(Unit) {
		state.editOperations.collect { reconcileHorizontalRules(state) }
	}

	Surface(
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		modifier = modifier,
	) {
		Row(
			modifier = Modifier
				.horizontalScroll(rememberScrollState())
				.padding(horizontal = 8.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			// History Controls Group
			Row(verticalAlignment = Alignment.CenterVertically) {
				ToolbarButton(
					onClick = state::undo,
					icon = Icons.AutoMirrored.Filled.Undo,
					contentDescription = "Undo",
					enabled = state.canUndo
				)

				Spacer(modifier = Modifier.width(2.dp))

				ToolbarButton(
					onClick = state::redo,
					icon = Icons.AutoMirrored.Filled.Redo,
					contentDescription = "Redo",
					enabled = state.canRedo
				)
			}

			ToolbarDivider()

			// Formatting Controls Group
			Row(verticalAlignment = Alignment.CenterVertically) {
				FormatButton(
					onClick = { state.toggleSpanStyle(state.richTextStyles.boldStyle) },
					icon = Icons.Default.FormatBold,
					contentDescription = "Bold",
					isActive = isBoldActive,
				)

				Spacer(modifier = Modifier.width(2.dp))

				FormatButton(
					onClick = { state.toggleSpanStyle(state.richTextStyles.italicStyle) },
					icon = Icons.Default.FormatItalic,
					contentDescription = "Italic",
					isActive = isItalicActive,
				)

				if (markdownControls) {
					Spacer(modifier = Modifier.width(2.dp))

					FormatButton(
						onClick = { state.toggleSpanStyle(state.richTextStyles.codeStyle) },
						icon = Icons.Default.Code,
						contentDescription = "Inline Code",
						isActive = isCodeActive,
					)

					Spacer(modifier = Modifier.width(2.dp))

					FormatButton(
						onClick = { state.toggleSpanStyle(state.richTextStyles.strikethroughStyle) },
						icon = Icons.Default.FormatStrikethrough,
						contentDescription = "Strikethrough",
						isActive = isStrikethroughActive,
					)

					Spacer(modifier = Modifier.width(2.dp))

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

					Spacer(modifier = Modifier.width(2.dp))

					TextLabelButton(
						onClick = {
							cycleHeader(state, currentHeaderLevel)
						},
						label = if (currentHeaderLevel == 0) "H" else "H$currentHeaderLevel",
						contentDescription = if (currentHeaderLevel == 0)
							"Header (none) — click to cycle"
						else
							"Header H$currentHeaderLevel — click to cycle",
						isActive = currentHeaderLevel != 0,
					)

					Spacer(modifier = Modifier.width(2.dp))

					FormatButton(
						onClick = { toggleBlockquote(state) },
						icon = Icons.Default.FormatQuote,
						contentDescription = "Blockquote",
						isActive = isBlockquoteActive,
					)

					Spacer(modifier = Modifier.width(2.dp))

					FormatButton(
						onClick = { toggleBulletList(state) },
						icon = Icons.Default.FormatListBulleted,
						contentDescription = "Bullet list",
						isActive = isBulletListActive,
					)

					Spacer(modifier = Modifier.width(2.dp))

					FormatButton(
						onClick = { toggleOrderedList(state) },
						icon = Icons.Default.FormatListNumbered,
						contentDescription = "Ordered list",
						isActive = isOrderedListActive,
					)

					Spacer(modifier = Modifier.width(2.dp))

					FormatButton(
						onClick = { toggleCodeFence(state) },
						icon = Icons.Default.Terminal,
						contentDescription = "Code block",
						isActive = isCodeFenceActive,
					)

					Spacer(modifier = Modifier.width(2.dp))

					ToolbarButton(
						onClick = { insertHorizontalRule(state) },
						icon = Icons.Default.HorizontalRule,
						contentDescription = "Horizontal rule",
					)

					ToolbarDivider()

					// Font size decrease button
					ToolbarButton(
						onClick = { decreaseFontSize(state) },
						icon = Icons.Default.Remove,
						contentDescription = "Decrease Font Size"
					)

					Icon(
						imageVector = Icons.Default.FormatSize,
						contentDescription = null,
						modifier = Modifier
							.padding(horizontal = 4.dp)
							.size(20.dp),
						tint = MaterialTheme.colorScheme.onSurfaceVariant
					)

					// Font size increase button
					ToolbarButton(
						onClick = { increaseFontSize(state) },
						icon = Icons.Default.Add,
						contentDescription = "Increase Font Size"
					)
				} else {
					Spacer(modifier = Modifier.width(2.dp))

					FormatButton(
						onClick = { state.toggleSpanStyle(state.richTextStyles.highlightStyle) },
						icon = Icons.Default.Highlight,
						contentDescription = "Highlight",
						isActive = isHighlightActive,
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
			isAllowed = { sanitizeLinkUrl(it, state.allowedLinkSchemes) != null },
			onConfirm = { url ->
				applyLink(state, request, url)
				linkDialogState = null
			},
			onRemove = if (isEditing) {
				{
					applyLink(state, request, url = "")
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
	isAllowed: (String) -> Boolean,
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
				isError = url.isNotBlank() && !isAllowed(url),
				supportingText = if (url.isNotBlank() && !isAllowed(url)) {
					{ Text("Not a link the editor allows") }
				} else null,
			)
		},
		confirmButton = {
			TextButton(
				onClick = { onConfirm(url) },
				enabled = url.isNotBlank() && isAllowed(url),
			) { Text(if (isEditing) "Save" else "Add") }
		},
		dismissButton = {
			Row(verticalAlignment = Alignment.CenterVertically) {
				if (onRemove != null) {
					TextButton(onClick = onRemove) { Text("Remove") }
					Spacer(modifier = Modifier.width(2.dp))
				}
				TextButton(onClick = onDismiss) { Text("Cancel") }
			}
		},
	)
}

private fun applyLink(
	state: TextEditorState,
	request: LinkDialogRequest,
	url: String,
) {
	state.editGroup {
		state.unlink()
		if (url.isNotBlank()) state.setLink(request.range, url)
	}
}

private fun toggleBlockquote(state: TextEditorState) {
	val selection = state.selector.selection
	val lines = if (selection != null) {
		selection.start.line..selection.end.line
	} else {
		state.cursorPosition.line..state.cursorPosition.line
	}
	state.toggleBlockquote(lines)
}

private fun toggleBulletList(state: TextEditorState) {
	val selection = state.selector.selection
	val lines = if (selection != null) {
		selection.start.line..selection.end.line
	} else {
		state.cursorPosition.line..state.cursorPosition.line
	}
	state.toggleBulletList(lines)
}

private fun toggleOrderedList(state: TextEditorState) {
	val selection = state.selector.selection
	val lines = if (selection != null) {
		selection.start.line..selection.end.line
	} else {
		state.cursorPosition.line..state.cursorPosition.line
	}
	state.toggleOrderedList(lines)
}

private fun toggleCodeFence(state: TextEditorState) {
	val selection = state.selector.selection
	val lines = if (selection != null) {
		selection.start.line..selection.end.line
	} else {
		state.cursorPosition.line..state.cursorPosition.line
	}
	state.toggleCodeFence(lines)
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

// Cycles none -> H1 -> H2 -> ... -> H6 -> none over the selected lines (or the
// cursor's line). Headings are semantic line blocks: toggling the same level
// removes it, toggling a new level swaps it.
private fun cycleHeader(
	state: TextEditorState,
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
		state.toggleHeader(lines, nextLevel)
	} else if (currentLevel != 0) {
		state.toggleHeader(lines, currentLevel)
	}
}

@Composable
private fun ToolbarDivider() {
	VerticalDivider(
		modifier = Modifier.padding(horizontal = 8.dp).height(24.dp),
		color = MaterialTheme.colorScheme.outlineVariant,
	)
}

// The toolbar's controls never take focus, so the editor keeps its caret and selection.
private val Unfocusable = Modifier.focusProperties { canFocus = false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolbarTooltip(label: String, content: @Composable () -> Unit) {
	TooltipBox(
		positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
		tooltip = { PlainTooltip { Text(label) } },
		state = rememberTooltipState(),
		content = content,
	)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolbarButton(
	onClick: () -> Unit,
	icon: ImageVector,
	contentDescription: String,
	enabled: Boolean = true,
) {
	ToolbarTooltip(contentDescription) {
		IconButton(
			onClick = onClick,
			enabled = enabled,
			shapes = IconButtonDefaults.shapes(),
			modifier = Unfocusable,
		) {
			Icon(imageVector = icon, contentDescription = contentDescription)
		}
	}
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FormatButton(
	onClick: () -> Unit,
	icon: ImageVector,
	contentDescription: String,
	isActive: Boolean,
	enabled: Boolean = true
) {
	ToolbarTooltip(contentDescription) {
		IconToggleButton(
			checked = isActive,
			onCheckedChange = { onClick() },
			enabled = enabled,
			shapes = IconButtonDefaults.toggleableShapes(),
			colors = toolbarToggleColors(),
			modifier = Unfocusable,
		) {
			Icon(imageVector = icon, contentDescription = contentDescription)
		}
	}
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TextLabelButton(
	onClick: () -> Unit,
	label: String,
	contentDescription: String,
	isActive: Boolean,
) {
	ToolbarTooltip(contentDescription) {
		IconToggleButton(
			checked = isActive,
			onCheckedChange = { onClick() },
			shapes = IconButtonDefaults.toggleableShapes(),
			colors = toolbarToggleColors(),
			modifier = Unfocusable.semantics { this.contentDescription = contentDescription },
		) {
			Text(text = label, style = MaterialTheme.typography.labelLarge)
		}
	}
}

@Composable
private fun toolbarToggleColors() = IconButtonDefaults.iconToggleButtonColors(
	checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
	checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
)
