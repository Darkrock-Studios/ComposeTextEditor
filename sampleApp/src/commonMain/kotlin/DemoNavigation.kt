package com.darkrockstudios.texteditor.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The list of demos. With a [selected] demo it is the wide layout's side panel and marks the
 * open one; without, it is the narrow layout's start screen and each item opens its demo.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DemoNavigation(
	selected: Demo?,
	onSelect: (Demo) -> Unit,
	isDark: Boolean,
	onToggleDark: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
		Column(
			modifier = Modifier
				.widthIn(max = 560.dp)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp, vertical = 20.dp),
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
					Text(
						"Compose",
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.primary,
					)
					Text(
						"Text Editor",
						style = MaterialTheme.typography.headlineMedium,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
				DarkModeToggle(isDark = isDark, onToggle = onToggleDark)
			}

			DemoSection.entries.forEach { section ->
				val demos = Demo.entries.filter { it.section == section }
				Text(
					section.title,
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
				)
				Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
					demos.forEachIndexed { index, demo ->
						DemoListItem(
							demo = demo,
							index = index,
							count = demos.size,
							selected = selected?.let { it == demo },
							onClick = { onSelect(demo) },
						)
					}
				}
			}
			Spacer(modifier = Modifier.height(16.dp))
		}
	}
}

/** [selected] is null where the item navigates rather than selects. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DemoListItem(
	demo: Demo,
	index: Int,
	count: Int,
	selected: Boolean?,
	onClick: () -> Unit,
) {
	val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
	val colors = ListItemDefaults.segmentedColors(
		containerColor = MaterialTheme.colorScheme.surfaceBright,
		selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
		selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
		selectedSupportingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
	)
	val leading = @Composable { DemoIcon(demo, highlighted = selected == true) }
	val supporting = @Composable { Text(demo.description) }

	if (selected != null) {
		SegmentedListItem(
			selected = selected,
			onClick = onClick,
			shapes = shapes,
			colors = colors,
			leadingContent = leading,
			supportingContent = supporting,
		) { Text(demo.title) }
	} else {
		SegmentedListItem(
			onClick = onClick,
			shapes = shapes,
			colors = colors,
			modifier = Modifier.semantics { role = Role.Button },
			leadingContent = leading,
			supportingContent = supporting,
			trailingContent = {
				Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
			},
		) { Text(demo.title) }
	}
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DemoIcon(demo: Demo, highlighted: Boolean) {
	Surface(
		shape = if (highlighted) MaterialShapes.Cookie9Sided.toShape() else MaterialShapes.Circle.toShape(),
		color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
		contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
		modifier = Modifier.size(40.dp),
	) {
		Box(contentAlignment = Alignment.Center) {
			Icon(demo.icon, contentDescription = null, modifier = Modifier.size(22.dp))
		}
	}
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DarkModeToggle(
	isDark: Boolean,
	onToggle: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	FilledTonalIconToggleButton(
		checked = isDark,
		onCheckedChange = onToggle,
		shapes = IconButtonDefaults.toggleableShapes(),
		modifier = modifier,
	) {
		Icon(
			imageVector = if (isDark) Icons.Default.DarkMode else Icons.Default.LightMode,
			contentDescription = if (isDark) "Switch to light mode" else "Switch to dark mode",
		)
	}
}
