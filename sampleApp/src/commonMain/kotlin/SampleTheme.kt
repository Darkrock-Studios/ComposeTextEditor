package com.darkrockstudios.texteditor.sample

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

private val SeedColor = Color(0xFF1EB980)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SampleTheme(isDark: Boolean, content: @Composable () -> Unit) {
	val colorScheme = rememberDynamicColorScheme(
		seedColor = SeedColor,
		isDark = isDark,
		style = PaletteStyle.TonalSpot,
		specVersion = ColorSpec.SpecVersion.SPEC_2025,
	)
	MaterialExpressiveTheme(
		colorScheme = colorScheme,
		motionScheme = MotionScheme.expressive(),
		content = content,
	)
}
