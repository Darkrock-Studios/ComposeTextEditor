package markdown

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.sequentialparsers.DelimiterParser
import org.intellij.markdown.parser.sequentialparsers.EmphasisLikeParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The importer's GFM flavour rebuilds upstream's emphasis-like pass as emphasis and
 * strikethrough alone, leaving out its `==` highlight. A delimiter syntax a later
 * release adds to that pass would be dropped with it, so this pins the pass it replaces.
 */
class GfmFlavourGuardTest {

	@Test
	fun `upstream's emphasis-like pass is emphasis, strikethrough and highlight`() {
		val passes = GFMFlavourDescriptor().sequentialParserManager.getParserSequence().filterIsInstance<EmphasisLikeParser>()
		assertEquals(1, passes.size)
		val field = EmphasisLikeParser::class.java.getDeclaredField("parsers").apply { isAccessible = true }
		@Suppress("UNCHECKED_CAST")
		val parsers = (field.get(passes.single()) as Array<DelimiterParser>).map { it::class.simpleName }.toSet()
		assertEquals(
			setOf("EmphStrongDelimiterParser", "StrikeThroughDelimiterParser", "HighlightDelimiterParser"),
			parsers,
			"Upstream's emphasis-like pass changed: update EditorGfmFlavour in markdownToAnnotatedString.kt",
		)
	}
}
