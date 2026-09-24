package com.darkrockstudios.texteditor.find

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A find bar UI component that provides search and replace functionality.
 *
 * @param state The FindState managing the search
 * @param onClose Called when the user closes the find bar
 * @param modifier Modifier for the find bar
 * @param strings Localizable strings for the UI. Defaults to English.
 * @param requestFocus Whether to request focus on the search field when shown
 */
@Composable
fun FindBar(
	state: FindState,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
	strings: FindBarStrings = FindBarStrings.Default,
	requestFocus: Boolean = true
) {
	var searchText by remember { mutableStateOf(state.query) }
	var replaceText by remember { mutableStateOf("") }
	var showReplace by remember { mutableStateOf(false) }
	val focusRequester = remember { FocusRequester() }

	// Request focus when shown
	LaunchedEffect(requestFocus) {
		if (requestFocus) {
			focusRequester.requestFocus()
		}
	}

	// Sizes
	val buttonSize = 32.dp
	val iconSize = 20.dp
	val clearButtonSize = 18.dp
	val clearIconSize = 14.dp
	val fontSize = 14.sp
	val borderWidth = 1.dp
	val borderRadius = 4.dp
	val fieldPaddingHorizontal = 8.dp
	val fieldPaddingVertical = 6.dp
	val barPaddingHorizontal = 8.dp
	val rowSpacing = 4.dp

	// Colors
	val textColor = MaterialTheme.colorScheme.onSurface
	val borderColor = MaterialTheme.colorScheme.outline
	val cursorColor = MaterialTheme.colorScheme.primary
	val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant

	// Styles
	val textStyle = TextStyle(fontSize = fontSize, color = textColor)

	Surface(
		modifier = modifier.fillMaxWidth(),
		tonalElevation = 2.dp,
		shadowElevation = 2.dp
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = barPaddingHorizontal)
		) {
			// First row: Find
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(rowSpacing)
			) {
				// Search input with custom styling
				Box(
					modifier = Modifier
						.weight(1f)
						.border(borderWidth, borderColor, RoundedCornerShape(borderRadius))
						.padding(horizontal = fieldPaddingHorizontal, vertical = fieldPaddingVertical),
					contentAlignment = Alignment.CenterStart
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						modifier = Modifier.fillMaxWidth()
					) {
						Box(modifier = Modifier.weight(1f)) {
							if (searchText.isEmpty()) {
								Text(
									text = strings.placeholder,
									style = textStyle.copy(color = placeholderColor)
								)
							}
							BasicTextField(
								value = searchText,
								onValueChange = { newValue ->
									searchText = newValue
									state.search(newValue)
								},
								modifier = Modifier
									.fillMaxWidth()
									.focusRequester(focusRequester)
									.findShortcut { onClose() }
									.onPreviewKeyEvent { event ->
										if (event.type == KeyEventType.KeyDown) {
											when {
												event.isEnter && event.isShiftPressed -> {
													state.findPrevious()
													true
												}

												event.isEnter -> {
													state.findNext()
													true
												}

												event.key == Key.Escape -> {
													onClose()
													true
												}

												else -> false
											}
										} else false
									},
								singleLine = true,
								textStyle = textStyle,
								cursorBrush = SolidColor(cursorColor),
								keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
								keyboardActions = KeyboardActions(
									onSearch = { state.findNext() }
								)
							)
						}

						if (searchText.isNotEmpty()) {
							IconButton(
								onClick = {
									searchText = ""
									state.clearSearch()
								},
								modifier = Modifier.size(clearButtonSize)
							) {
								Icon(
									imageVector = Icons.Default.Clear,
									contentDescription = strings.clearSearch,
									modifier = Modifier.size(clearIconSize)
								)
							}
						}
					}
				}

				// Match count
				if (state.query.isNotEmpty()) {
					Text(
						text = if (state.matchCount > 0) {
							strings.matchCount(state.currentMatchIndex + 1, state.matchCount)
						} else {
							strings.noMatches
						},
						style = MaterialTheme.typography.bodySmall,
						color = if (state.matchCount == 0) {
							MaterialTheme.colorScheme.error
						} else {
							MaterialTheme.colorScheme.onSurface
						}
					)
				}

				// Previous button
				IconButton(
					onClick = { state.findPrevious() },
					enabled = state.matchCount > 0,
					modifier = Modifier.size(buttonSize)
				) {
					Icon(
						imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
						contentDescription = strings.previousMatch,
						modifier = Modifier.size(iconSize)
					)
				}

				// Next button
				IconButton(
					onClick = { state.findNext() },
					enabled = state.matchCount > 0,
					modifier = Modifier.size(buttonSize)
				) {
					Icon(
						imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
						contentDescription = strings.nextMatch,
						modifier = Modifier.size(iconSize)
					)
				}

				// Replace toggle button
				IconButton(
					onClick = { showReplace = !showReplace },
					modifier = Modifier.size(buttonSize)
				) {
					Icon(
						imageVector = Icons.Default.FindReplace,
						contentDescription = if (showReplace) strings.hideReplace else strings.showReplace,
						modifier = Modifier.size(iconSize),
						tint = if (showReplace) MaterialTheme.colorScheme.primary else LocalContentColor.current
					)
				}

				// Close button
				IconButton(
					onClick = {
						state.clearSearch()
						onClose()
					},
					modifier = Modifier.size(buttonSize)
				) {
					Icon(
						imageVector = Icons.Default.Close,
						contentDescription = strings.close,
						modifier = Modifier.size(iconSize)
					)
				}
			}

			// Second row: Replace (animated visibility)
			AnimatedVisibility(visible = showReplace) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = rowSpacing),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(rowSpacing)
				) {
					// Replace input with custom styling
					Box(
						modifier = Modifier
							.weight(1f)
							.border(borderWidth, borderColor, RoundedCornerShape(borderRadius))
							.padding(horizontal = fieldPaddingHorizontal, vertical = fieldPaddingVertical),
						contentAlignment = Alignment.CenterStart
					) {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							modifier = Modifier.fillMaxWidth()
						) {
							Box(modifier = Modifier.weight(1f)) {
								if (replaceText.isEmpty()) {
									Text(
										text = strings.replacePlaceholder,
										style = textStyle.copy(color = placeholderColor)
									)
								}
								BasicTextField(
									value = replaceText,
									onValueChange = { replaceText = it },
									modifier = Modifier
										.fillMaxWidth()
										.onPreviewKeyEvent { event ->
											if (event.type == KeyEventType.KeyDown) {
												when {
													event.isEnter -> {
														state.replaceCurrent(replaceText)
														true
													}

													event.key == Key.Escape -> {
														onClose()
														true
													}

													else -> false
												}
											} else false
										},
									singleLine = true,
									textStyle = textStyle,
									cursorBrush = SolidColor(cursorColor),
									keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
									keyboardActions = KeyboardActions(
										onDone = { state.replaceCurrent(replaceText) }
									)
								)
							}

							if (replaceText.isNotEmpty()) {
								IconButton(
									onClick = { replaceText = "" },
									modifier = Modifier.size(clearButtonSize)
								) {
									Icon(
										imageVector = Icons.Default.Clear,
										contentDescription = strings.clearSearch,
										modifier = Modifier.size(clearIconSize)
									)
								}
							}
						}
					}

					// Replace button
					IconButton(
						onClick = { state.replaceCurrent(replaceText) },
						enabled = state.matchCount > 0,
						modifier = Modifier.size(buttonSize)
					) {
						Icon(
							imageVector = Icons.Default.Done,
							contentDescription = strings.replace,
							modifier = Modifier.size(iconSize)
						)
					}

					// Replace All button
					IconButton(
						onClick = { state.replaceAll(replaceText) },
						enabled = state.matchCount > 0,
						modifier = Modifier.size(buttonSize)
					) {
						Icon(
							imageVector = Icons.Default.DoneAll,
							contentDescription = strings.replaceAll,
							modifier = Modifier.size(iconSize)
						)
					}
				}
			}
		}
	}
}

private val KeyEvent.isEnter: Boolean
	get() = key == Key.Enter || key == Key.NumPadEnter
