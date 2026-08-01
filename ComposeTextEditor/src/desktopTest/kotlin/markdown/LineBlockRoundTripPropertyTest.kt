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
				BulletList to lines.withIndex()
					.filter { it.value.list === BulletList }.map { it.index },
				OrderedList to lines.withIndex()
					.filter { it.value.list === OrderedList }.map { it.index },
			),
		)
		return extension
	}

	private fun MarkdownExtension.blockPlacement(): Map<String, List<Int>> {
		val spans = editorState.richSpanManager.getAllRichSpans()
		return spans.groupBy { it.style::class.simpleName ?: "?" }
			.mapValues { (_, group) -> group.map { it.range.start.line }.sorted() }
	}

	@Test
	fun `export then import preserves text and blocks over generated documents`() = runTest {
		val random = Random(20260801)
		repeat(200) { iteration ->
			val lines = List(random.nextInt(1, 10)) { generateLine(random) }
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
