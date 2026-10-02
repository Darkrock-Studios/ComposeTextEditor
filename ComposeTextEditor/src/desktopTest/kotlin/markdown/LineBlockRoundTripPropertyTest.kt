package markdown

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.Blockquote
import com.darkrockstudios.texteditor.richstyle.BulletList
import com.darkrockstudios.texteditor.richstyle.HR_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.IMAGE_PLACEHOLDER
import com.darkrockstudios.texteditor.richstyle.ImageBlockSpanStyle
import com.darkrockstudios.texteditor.richstyle.InMemoryImageProvider
import com.darkrockstudios.texteditor.richstyle.LineBlockStyle
import com.darkrockstudios.texteditor.richstyle.OrderedList
import com.darkrockstudios.texteditor.richstyle.applyDocumentBlocks
import com.darkrockstudios.texteditor.richstyle.atListLevel
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The serialization contract, checked over generated documents instead of
 * hand-picked examples: exporting a valid document and importing the result
 * preserves the text and the block placement, and a second export equals the
 * first. Line bodies are drawn from a pool that includes marker lookalikes
 * (`1990. plans`, `- dash lead`, `---`) precisely because those are the shapes
 * that corrupt when escaping or marker peeling has a hole.
 */
class LineBlockRoundTripPropertyTest {

	private data class GeneratedLine(
		val text: String,
		val isRule: Boolean = false,
		val isImage: Boolean = false,
		val quote: Boolean,
		val list: LineBlockStyle?,
	)

	private val bodyPool = listOf(
		"plain prose line",
		"she walked into the room",
		"",
		"1990. The year everything changed",
		"1. Introduction",
		"- dash lead",
		"* star lead",
		"+ plus lead",
		"> angle lead",
		"---",
		"a - b in the middle",
		"trailing marker -",
	)

	private fun generateLine(random: Random): GeneratedLine {
		val list = when (random.nextInt(4)) {
			0 -> BulletList
			1 -> OrderedList
			else -> null
		}
		return when (random.nextInt(10)) {
			0 -> GeneratedLine(
				text = HR_PLACEHOLDER,
				isRule = true,
				quote = random.nextBoolean(),
				list = null,
			)

			1 -> GeneratedLine(
				text = IMAGE_PLACEHOLDER,
				isImage = true,
				quote = random.nextBoolean(),
				list = list,
			)

			else -> GeneratedLine(
				text = bodyPool.random(random),
				quote = random.nextInt(3) == 0,
				list = list,
			)
		}
	}

	private fun TestScope.buildDocument(lines: List<GeneratedLine>): MarkdownExtension {
		val state = TextEditorState(
			scope = this,
			measurer = mockk(relaxed = true),
			initialText = null as AnnotatedString?,
		)
		val provider = InMemoryImageProvider()
		val extension = MarkdownExtension(state, MarkdownConfiguration.DEFAULT, imageProvider = provider)
		state.setText(AnnotatedString(lines.joinToString("\n") { it.text }))
		state.applyDocumentBlocks(
			horizontalRuleLines = lines.withIndex().filter { it.value.isRule }.map { it.index },
			imageLines = lines.withIndex()
				.filter { it.value.isImage }
				.associate { (index, _) ->
					index to ImageBlockSpanStyle(source = "img.png", alt = "alt", provider = provider)
				},
			blockLines = mapOf(
				Blockquote to lines.withIndex().filter { it.value.quote }.map { it.index },
			) + lines.withIndex()
				.filter { it.value.list != null }
				.groupBy({ it.value.list!! }, { it.index }),
		)
		return extension
	}

	private fun MarkdownExtension.blockPlacement(): Map<String, List<Int>> {
		val spans = editorState.richSpanManager.getAllRichSpans()
		// By the style's own name, which carries a list's level; the level-0 list
		// styles are companion objects whose class name is only "Companion".
		return spans.groupBy { it.style.toString() }
			.mapValues { (_, group) -> group.map { it.range.start.line }.sorted() }
	}

	/**
	 * Gives each list line a nesting level the text form can hold: at most one
	 * deeper than the list line before it, blank lines transparent, a quote
	 * change or any other line resetting to the top. An orphaned deeper level
	 * is the one model state export rewrites (see `docs/design/line-blocks.md`,
	 * "Nested lists"), so the generator does not produce it.
	 */
	private fun withValidLevels(lines: List<GeneratedLine>, random: Random): List<GeneratedLine> {
		var allowed = 0
		var previousQuote = false
		return lines.map { line ->
			val blank = line.list == null && !line.isRule && !line.isImage && line.text.isBlank()
			if (line.quote != previousQuote) allowed = 0
			val result = if (line.list != null) {
				val level = random.nextInt(0, minOf(allowed, 3) + 1)
				allowed = level + 1
				line.copy(list = line.list.atListLevel(level))
			} else {
				if (!blank) allowed = 0
				line
			}
			previousQuote = line.quote
			result
		}
	}

	@Test
	fun `export then import preserves text and blocks over generated documents`() = runTest {
		val random = Random(20260801)
		repeat(200) { iteration ->
			val lines = withValidLevels(List(random.nextInt(1, 10)) { generateLine(random) }, random)
			val extension = buildDocument(lines)

			val expectedText = extension.editorState.getAllText().text
			val expectedBlocks = extension.blockPlacement()
			val exported = extension.exportAsMarkdown()

			extension.importMarkdown(exported)

			val context = "iteration $iteration\n--- exported ---\n$exported"
			assertEquals(
				expectedText,
				extension.editorState.getAllText().text,
				"text drifted: $context",
			)
			assertEquals(
				expectedBlocks,
				extension.blockPlacement(),
				"blocks drifted: $context",
			)
			assertEquals(
				exported,
				extension.exportAsMarkdown(),
				"second export drifted: $context",
			)
		}
	}
}
