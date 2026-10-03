package com.darkrockstudios.texteditor.sample

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.darkrockstudios.texteditor.TextEditorStyle
import com.darkrockstudios.texteditor.rememberTextEditorStyle
import androidx.compose.ui.unit.dp

/** A demo's frame: its title bar, with a way back where the list is not on screen, over [content]. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DemoScaffold(
	demo: Demo,
	onBack: (() -> Unit)?,
	modifier: Modifier = Modifier,
	actions: @Composable RowScope.() -> Unit = {},
	content: @Composable ColumnScope.() -> Unit,
) {
	Column(modifier = modifier.fillMaxSize()) {
		TopAppBar(
			title = { Text(demo.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
			subtitle = { Text(demo.description, maxLines = 1, overflow = TextOverflow.Ellipsis) },
			navigationIcon = {
				if (onBack != null) {
					ActionButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
				}
			},
			actions = actions,
			windowInsets = WindowInsets(0),
			colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
		)
		content()
	}
}

/** An icon button with its [label] as a tooltip and as what a screen reader says. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActionButton(
	icon: ImageVector,
	label: String,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	onClick: () -> Unit,
) {
	TooltipBox(
		positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
		tooltip = { PlainTooltip { Text(label) } },
		state = rememberTooltipState(),
	) {
		IconButton(
			onClick = onClick,
			enabled = enabled,
			shapes = IconButtonDefaults.shapes(),
			modifier = modifier,
		) {
			Icon(icon, contentDescription = label)
		}
	}
}

class DemoOption(
	val label: String,
	val checked: Boolean,
	val supportingText: String? = null,
	val enabled: Boolean = true,
	val onCheckedChange: (Boolean) -> Unit,
)

class DemoOptionGroup(val title: String, val options: List<DemoOption>)

/**
 * The demo's switches behind one button, as a menu that stays open while they are flipped so
 * each change shows on the editor behind it. The button carries a dot while any differs from
 * its default, which [onReset] restores.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DemoOptionsButton(
	groups: List<DemoOptionGroup>,
	modified: Boolean,
	onReset: () -> Unit,
) {
	var open by remember { mutableStateOf(false) }
	Box {
		BadgedBox(badge = { if (modified) Badge() }) {
			ActionButton(Icons.Default.Tune, "Options") { open = true }
		}
		DropdownMenuPopup(expanded = open, onDismissRequest = { open = false }) {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				val groupCount = groups.size + 1
				groups.forEachIndexed { groupIndex, group ->
					DropdownMenuGroup(shapes = MenuDefaults.groupShape(groupIndex, groupCount)) {
						MenuDefaults.Label { Text(group.title) }
						group.options.forEachIndexed { index, option ->
							DropdownMenuItem(
								checked = option.checked,
								onCheckedChange = option.onCheckedChange,
								text = { Text(option.label) },
								supportingText = option.supportingText?.let { { Text(it) } },
								shapes = MenuDefaults.itemShape(index, group.options.size),
								checkedLeadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
								enabled = option.enabled,
							)
						}
					}
					Spacer(modifier = Modifier.height(MenuDefaults.GroupSpacing))
				}
				DropdownMenuGroup(shapes = MenuDefaults.groupShape(groupCount - 1, groupCount)) {
					DropdownMenuItem(
						onClick = onReset,
						text = { Text("Reset to defaults") },
						shape = MenuDefaults.standaloneItemShape,
						leadingIcon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
						enabled = modified,
					)
				}
			}
		}
	}
}

/** A quiet line of facts about the document under the editor. */
@Composable
fun StatusBar(text: String, modifier: Modifier = Modifier) {
	Text(
		text,
		style = MaterialTheme.typography.labelMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
	)
}

/**
 * A rounded sheet the editor sits on, its outline turning the primary colour while it has
 * focus. The editor inside draws no border or background of its own; see
 * [rememberFramedEditorStyle].
 */
@Composable
fun EditorFrame(focused: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
	val outline by animateColorAsState(
		if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
	)
	val width by animateDpAsState(if (focused) 2.dp else 1.dp)
	Surface(
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.surfaceContainerLowest,
		border = BorderStroke(width, outline),
		modifier = modifier,
		content = content,
	)
}

@Composable
fun rememberFramedEditorStyle(
	placeholderText: String = "",
	textStyle: TextStyle = TextStyle.Default,
): TextEditorStyle = rememberTextEditorStyle(
	placeholderText = placeholderText,
	textColor = MaterialTheme.colorScheme.onSurface,
	backgroundColor = Color.Transparent,
	focusedBorderColor = Color.Transparent,
	unfocusedBorderColor = Color.Transparent,
	textStyle = textStyle,
)
