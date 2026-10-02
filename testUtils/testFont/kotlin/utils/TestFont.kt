package utils

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

private const val TEST_FONT_RESOURCE = "fonts/NotoSans-Regular.ttf"

private object TestFontResources

/**
 * The font the UI test harnesses lay text out in, so wrapping and widths are the same
 * on every machine rather than following the host's default sans-serif. A subset of
 * Noto Sans Regular (Latin, Greek, basic Cyrillic, punctuation; see `fonts/OFL.txt`).
 * Characters outside it (CJK, Hebrew, Arabic, emoji) fall back to the system's fonts,
 * and bold and italic are synthesized.
 */
val TestFontFamily: FontFamily by lazy {
	val data = checkNotNull(TestFontResources::class.java.classLoader.getResourceAsStream(TEST_FONT_RESOURCE)) {
		"$TEST_FONT_RESOURCE is not on the test classpath"
	}.use { it.readBytes() }
	FontFamily(Font(identity = "ComposeTextEditorTestFont", data = data))
}

/** This style in [TestFontFamily], unless it already names a font family. */
fun TextStyle.withTestFont(): TextStyle = if (fontFamily == null) copy(fontFamily = TestFontFamily) else this

/** One font resolver for measuring outside a composition, so the test font is loaded once. */
val testFontFamilyResolver: FontFamily.Resolver by lazy { createFontFamilyResolver() }

/**
 * The advance of one unwrapped left-to-right line of [text] in [style] at [density], trailing
 * spaces included, outside any composition.
 */
fun measureLineWidth(text: String, style: TextStyle, density: Density = Density(1f)): Float =
	TextMeasurer(testFontFamilyResolver, density, LayoutDirection.Ltr, cacheSize = 0)
		.measure(text, style)
		.getHorizontalPosition(text.length, usePrimaryDirection = true)
