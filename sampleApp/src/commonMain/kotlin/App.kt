package com.darkrockstudios.texteditor.sample

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.darkrockstudios.texteditor.RichTextStyles
import org.jetbrains.compose.ui.tooling.preview.Preview

/** At this width and above the demo list and the open demo sit side by side. */
private val WideLayoutMinWidth = 720.dp

@Composable
@Preview
fun App() {
	val systemDark = isSystemInDarkTheme()
	var isDark by rememberSaveable { mutableStateOf(systemDark) }

	SampleTheme(isDark = isDark) {
		// Null until a demo is picked; the wide layout shows the first one meanwhile.
		var selected by rememberSaveable { mutableStateOf<Demo?>(null) }
		val richTextStyles = if (isDark) RichTextStyles.DEFAULT_DARK else RichTextStyles.DEFAULT

		Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
			BoxWithConstraints(
				modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
			) {
				if (maxWidth >= WideLayoutMinWidth) {
					WideLayout(
						demo = selected ?: Demo.entries.first(),
						onSelect = { selected = it },
						isDark = isDark,
						onToggleDark = { isDark = it },
						richTextStyles = richTextStyles,
					)
				} else {
					NarrowLayout(
						demo = selected,
						onSelect = { selected = it },
						isDark = isDark,
						onToggleDark = { isDark = it },
						richTextStyles = richTextStyles,
					)
				}
			}
		}
	}
}

@Composable
private fun WideLayout(
	demo: Demo,
	onSelect: (Demo) -> Unit,
	isDark: Boolean,
	onToggleDark: (Boolean) -> Unit,
	richTextStyles: RichTextStyles,
) {
	Row(modifier = Modifier.fillMaxSize()) {
		DemoNavigation(
			selected = demo,
			onSelect = onSelect,
			isDark = isDark,
			onToggleDark = onToggleDark,
			modifier = Modifier.width(300.dp).fillMaxHeight(),
		)
		Surface(
			shape = MaterialTheme.shapes.extraLarge,
			color = MaterialTheme.colorScheme.surface,
			modifier = Modifier
				.weight(1f)
				.fillMaxHeight()
				.padding(top = 12.dp, end = 12.dp, bottom = 12.dp),
		) {
			AnimatedContent(
				targetState = demo,
				transitionSpec = { fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
			) { shown ->
				DemoHost(
					demo = shown,
					onBack = null,
					isDark = isDark,
					richTextStyles = richTextStyles,
					modifier = Modifier.hiddenWhileLeaving(transition.targetState),
				)
			}
		}
	}
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NarrowLayout(
	demo: Demo?,
	onSelect: (Demo?) -> Unit,
	isDark: Boolean,
	onToggleDark: (Boolean) -> Unit,
	richTextStyles: RichTextStyles,
) {
	BackHandler(enabled = demo != null) { onSelect(null) }

	AnimatedContent(
		targetState = demo,
		transitionSpec = { slideBetweenListAndDemo() },
	) { shown ->
		val modifier = Modifier.fillMaxSize().hiddenWhileLeaving(transition.targetState)
		if (shown == null) {
			DemoNavigation(
				selected = null,
				onSelect = onSelect,
				isDark = isDark,
				onToggleDark = onToggleDark,
				modifier = modifier,
			)
		} else {
			Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
				DemoHost(
					demo = shown,
					onBack = { onSelect(null) },
					isDark = isDark,
					richTextStyles = richTextStyles,
				)
			}
		}
	}
}

private fun AnimatedContentTransitionScope<Demo?>.slideBetweenListAndDemo(): ContentTransform {
	val opening = targetState != null
	val direction = if (opening) 1 else -1
	return (slideInHorizontally { width -> direction * width / 4 } + fadeIn())
		.togetherWith(slideOutHorizontally { width -> -direction * width / 4 } + fadeOut())
		.using(SizeTransform(clip = false))
}

/** Content on its way out leaves the semantics tree at once, so two demos never show up in it together. */
private fun Modifier.hiddenWhileLeaving(state: EnterExitState): Modifier =
	if (state == EnterExitState.PostExit) clearAndSetSemantics {} else this

@Composable
private fun DemoHost(
	demo: Demo,
	onBack: (() -> Unit)?,
	isDark: Boolean,
	richTextStyles: RichTextStyles,
	modifier: Modifier = Modifier,
) {
	// Spans keep the styles they were made with, so the demos that use them start over in the new theme.
	val usesRichTextStyles = demo != Demo.Code && demo != Demo.Find
	key(if (usesRichTextStyles) isDark else null) {
		DemoContentFor(demo, onBack, isDark, richTextStyles, modifier)
	}
}

@Composable
private fun DemoContentFor(
	demo: Demo,
	onBack: (() -> Unit)?,
	isDark: Boolean,
	richTextStyles: RichTextStyles,
	modifier: Modifier,
) {
	when (demo) {
		Demo.RichText -> TextEditorDemoUi(demo, onBack, DemoContent.Rich, richTextStyles, modifier)
		Demo.Markdown -> TextEditorDemoUi(demo, onBack, DemoContent.Markdown, richTextStyles, modifier)
		Demo.BlankMarkdown -> TextEditorDemoUi(demo, onBack, DemoContent.Empty, richTextStyles, modifier)
		Demo.Code -> CodeEditorDemoUi(demo, onBack, isDark, modifier)
		Demo.SpellCheck -> SpellCheckingTextEditorDemoUi(demo, onBack, richTextStyles, modifier)
		Demo.Find -> FindTextEditorDemoUi(demo, onBack, modifier)
		Demo.RichTextView -> RichTextViewDemoUi(demo, onBack, richTextStyles, modifier)
	}
}
