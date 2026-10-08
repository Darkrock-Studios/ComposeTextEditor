package markdown

import com.darkrockstudios.texteditor.html.HtmlExtension
import com.darkrockstudios.texteditor.markdown.MarkdownExtension
import com.darkrockstudios.texteditor.richstyle.InMemoryImageProvider
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URI
import kotlin.test.Test

/**
 * Scores import against the CommonMark spec's examples and GFM's extension examples, and
 * redraws the README's support charts in `docs/images`. Skipped unless `CTE_SPEC` is 1,
 * since it fetches the specs (pinned below, cached in `build/spec-cache`):
 *
 * `CTE_SPEC=1 ./gradlew :ComposeTextEditorMarkdown:desktopTest --tests 'markdown.SpecComplianceTest' --rerun`
 *
 * An example is supported when importing its markdown gives the document importing its
 * expected HTML does, that HTML also read with a line per soft line break, since an editor
 * line is a paragraph. A failing example is raw HTML, a non-goal, when it is in the spec's
 * HTML sections or a tag of its markdown is in its expected HTML as written. The totals and
 * what is missing go to standard output (the test report's).
 */
class SpecComplianceTest {

	private class Example(val number: Int, val section: String, val label: String, val markdown: String, val html: String)

	private enum class Result { SUPPORTED, NOT_YET, RAW_HTML }

	@Test
	fun scoreAndDraw() {
		assumeTrue("set CTE_SPEC=1 to run", System.getenv("CTE_SPEC") == "1")
		val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

		val commonMark = examples(fetch(COMMONMARK_SPEC, "commonmark-$COMMONMARK_VERSION.txt"))
		val commonMarkResults = commonMark.associateWith { example ->
			when {
				supported(example) -> Result.SUPPORTED
				example.section in HTML_SECTIONS || HTML_CONSTRUCT.findAll(example.markdown).any { it.value in example.html } -> Result.RAW_HTML
				else -> Result.NOT_YET
			}
		}
		report("CommonMark $COMMONMARK_VERSION", commonMarkResults) { COMMONMARK_GROUPS[it.section] ?: "Smaller edge cases" }
		drawBar(File(root, "docs/images/commonmark-support.svg"), "CommonMark", commonMarkResults.values)

		val gfm = examples(fetch(GFM_SPEC, "gfm-$GFM_VERSION.txt")).filter { it.label.isNotEmpty() }
		val gfmResults = gfm.associateWith { example ->
			when {
				example.label == "tagfilter" -> Result.RAW_HTML
				supported(example) -> Result.SUPPORTED
				else -> Result.NOT_YET
			}
		}
		report("GFM $GFM_VERSION extensions", gfmResults) { GFM_GROUPS[it.label] ?: it.label }
		drawBar(File(root, "docs/images/gfm-support.svg"), "GFM extensions", gfmResults.values)
	}

	private fun fetch(url: String, name: String): String {
		val cached = File("build/spec-cache", name)
		if (!cached.exists()) {
			cached.parentFile.mkdirs()
			cached.writeText(URI(url).toURL().readText())
		}
		return cached.readText()
	}

	/** The examples of a spec in the CommonMark spec's text format, each with the section it is under. */
	private fun examples(spec: String): List<Example> {
		val lines = spec.lines()
		val out = ArrayList<Example>()
		var section = ""
		var index = 0
		while (index < lines.size) {
			val line = lines[index]
			HEADING.matchEntire(line)?.let { section = it.groupValues[1].trim() }
			if (line.startsWith(EXAMPLE_FENCE)) {
				fun block(from: Int, end: (String) -> Boolean): Pair<String, Int> {
					var at = from
					val text = StringBuilder()
					while (!end(lines[at])) text.append(lines[at++]).append('\n')
					return text.toString().replace('→', '\t') to at
				}
				val (markdown, dot) = block(index + 1) { it == "." }
				val (html, close) = block(dot + 1) { it.startsWith(FENCE) }
				out += Example(out.size + 1, section, line.removePrefix(EXAMPLE_FENCE).trim(), markdown, html)
				index = close
			}
			index++
		}
		return out
	}

	private fun state() = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))

	private fun supported(example: Example): Boolean = runCatching {
		val imported = state().also { MarkdownExtension(it, imageProvider = InMemoryImageProvider()).importMarkdown(example.markdown) }
		val written = HtmlExtension(imported, InMemoryImageProvider()).exportAsHtml()
		listOf(example.html, linesAsBreaks(example.html)).any { html ->
			val reference = state().also { HtmlExtension(it, InMemoryImageProvider()).importHtml(html) }
			HtmlExtension(reference, InMemoryImageProvider()).exportAsHtml() == written
		}
	}.getOrDefault(false)

	/** [html] with each soft line break written as a `<br>`, the editor's line per source line. */
	private fun linesAsBreaks(html: String): String {
		val out = StringBuilder()
		var inPre = false
		for ((index, char) in html.withIndex()) {
			if (html.startsWith("<pre", index)) inPre = true
			if (html.startsWith("</pre>", index)) inPre = false
			if (char == '\n' && inPre && html.startsWith("</code></pre>", index + 1)) continue
			val softBreak = char == '\n' && !inPre && index + 1 < html.length &&
				!BLOCK_TAG_START.containsMatchIn(html.substring(index + 1)) && !BLOCK_TAG_END.containsMatchIn(out)
			if (softBreak) out.append("<br>") else out.append(char)
		}
		return out.toString()
	}

	private fun report(name: String, results: Map<Example, Result>, group: (Example) -> String) {
		val counts = Result.entries.associateWith { result -> results.values.count { it == result } }
		println("SPEC $name: ${counts[Result.SUPPORTED]} of ${results.size} supported, ${counts[Result.NOT_YET]} not yet, ${counts[Result.RAW_HTML]} raw HTML")
		results.filterValues { it == Result.NOT_YET }.keys.groupBy(group).entries.sortedByDescending { it.value.size }.forEach { (label, examples) ->
			println("SPEC   not yet: $label (${examples.size}): ${examples.joinToString(" ") { "#${it.number}" }}")
		}
	}

	/** A bar of the [results]: supported in green, not yet in yellow, raw HTML in grey, each labelled with its count where it fits. */
	private fun drawBar(file: File, name: String, results: Collection<Result>) {
		val width = 640
		val height = 30
		val parts = listOf(
			Triple(results.count { it == Result.SUPPORTED }, listOf("supported"), "#2da44e" to "#ffffff"),
			Triple(results.count { it == Result.NOT_YET }, listOf("not yet"), "#d4a72c" to "#1f2328"),
			Triple(results.count { it == Result.RAW_HTML }, listOf("raw HTML", "HTML"), "#8c959f" to "#ffffff"),
		)
		val title = "$name: " + parts.joinToString(", ") { (count, words, _) -> "$count ${words.first()}" } + " (a non-goal)"
		val rects = StringBuilder()
		val labels = StringBuilder()
		var x = 0.0
		parts.forEachIndexed { index, (count, words, colours) ->
			val w = if (index == parts.lastIndex) width - x else width * count.toDouble() / results.size
			if (w <= 0.0) return@forEachIndexed
			rects.append("<rect x=\"%.1f\" y=\"0\" width=\"%.1f\" height=\"$height\" fill=\"${colours.first}\"/>\n".format(x, w))
			val label = words.map { "$count $it" }.firstOrNull { it.length * 6.6 + 12 <= w } ?: "$count"
			labels.append("<text x=\"%.1f\" y=\"%.1f\" fill=\"${colours.second}\" text-anchor=\"middle\">$label</text>\n".format(x + w / 2, height / 2 + 4.5))
			x += w
		}
		file.writeText(
			"""
			|<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height" role="img" aria-label="$title">
			|<title>$title</title>
			|<clipPath id="r"><rect width="$width" height="$height" rx="6"/></clipPath>
			|<g clip-path="url(#r)">
			|${rects.trimEnd()}
			|</g>
			|<g font-family="-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif" font-size="12" font-weight="600">
			|${labels.trimEnd()}
			|</g>
			|</svg>
			|""".trimMargin(),
		)
	}

	private companion object {
		const val COMMONMARK_VERSION = "0.31.2"
		const val COMMONMARK_SPEC = "https://raw.githubusercontent.com/commonmark/commonmark-spec/$COMMONMARK_VERSION/spec.txt"
		const val GFM_VERSION = "0.29.0.gfm.13"
		const val GFM_SPEC = "https://raw.githubusercontent.com/github/cmark-gfm/$GFM_VERSION/test/spec.txt"

		const val FENCE = "````````````````````````````````"
		const val EXAMPLE_FENCE = "$FENCE example"
		val HEADING = Regex("""#{1,6} (.*)""")

		val HTML_SECTIONS = setOf("HTML blocks", "Raw HTML")
		val HTML_CONSTRUCT = Regex("""(?<!\\)(<[A-Za-z][A-Za-z0-9-]*(?:\s[^<>]*)?/?>|</[A-Za-z][A-Za-z0-9-]*\s*>|<!--|<\?|<![A-Z]|<!\[CDATA\[)""")

		val BLOCK_TAG_START = Regex("""^</?(p|li|ul|ol|blockquote|h[1-6]|hr|pre|table|thead|tbody|tr|th|td|div)\b""", RegexOption.IGNORE_CASE)
		val BLOCK_TAG_END = Regex("""(</?(p|li|ul|ol|blockquote|h[1-6]|pre|table|thead|tbody|tr|th|td|div)\b[^>]*>|<hr\s*/?>|<br\s*/?>)$""", RegexOption.IGNORE_CASE)

		/** The README's names for what each CommonMark section's unsupported examples are missing. */
		val COMMONMARK_GROUPS = mapOf(
			"List items" to "List items holding more than one paragraph or block",
			"Lists" to "List items holding more than one paragraph or block",
			"Links" to "Link titles and unusual link destinations",
			"Link reference definitions" to "Link titles and unusual link destinations",
			"Indented code blocks" to "Indented code blocks and tab indentation",
			"Tabs" to "Indented code blocks and tab indentation",
			"Code spans" to "Code span spacing, and line breaks inside a paragraph",
			"Hard line breaks" to "Code span spacing, and line breaks inside a paragraph",
			"Soft line breaks" to "Code span spacing, and line breaks inside a paragraph",
			"Images" to "Images with a title, inside text, or by reference",
			"Block quotes" to "Block quotes holding other blocks",
		)

		val GFM_GROUPS = mapOf(
			"table" to "Tables",
			"disabled" to "Task lists",
			"strikethrough" to "Strikethrough",
			"autolink" to "Bare links",
		)
	}
}
