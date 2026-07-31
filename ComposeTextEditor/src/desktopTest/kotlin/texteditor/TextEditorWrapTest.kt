package texteditor

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.IntSize
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.richstyle.RichSpan
import com.darkrockstudios.texteditor.state.RichSpanManager
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.toCharacterIndex
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import utils.TestStyle
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextEditorWrapTest {
	private val testScope = TestScope()

	@Test
	fun `test rich span rendering with line wrap`() {
		println("\n=== Test Start ===")

		val state = TextEditorState(
			scope = testScope,
			measurer = createMockTextMeasurer()
		)

		state.onViewportSizeChange(Size(100f, 500f))

		println("\n=== Setting Initial Text ===")
		state.setText("Initial")

		println("\n=== Adding Initial Span ===")
		val startPos = CharLineOffset(0, 0)
		val endPos = CharLineOffset(0, 7)
		state.addRichSpan(
			start = startPos.toCharacterIndex(state),
			end = endPos.toCharacterIndex(state),
			style = TestStyle()
		)

		println("\n=== Initial State ===")
		println("Initial text: ${state.textLines[0].text}")
		println("Line offsets size: ${state.lineOffsets.size}")
		state.richSpanManager.debugPrintSpans()

		state.lineOffsets.forEachIndexed { index, lineWrap ->
			println("\nLine wrap $index:")
			println("  Line: ${lineWrap.line}")
			println("  Wrap starts at: ${lineWrap.wrapStartsAtIndex}")
			println("  Number of spans: ${lineWrap.richSpans.size}")
			lineWrap.richSpans.forEach { span ->
				println("  Span: ${span.range.start.line},${span.range.start.char} -> ${span.range.end.line},${span.range.end.char}")
				// Test intersection explicitly
				val intersects = span.intersectsWith(lineWrap)
				println("  Explicitly testing intersection: $intersects")
			}
		}

		println("\n=== Inserting Text ===")
		val insertText = "1234567890ABCDEF"
		state.insertStringAtCursor(insertText)

		println("\n=== Final State ===")
		println("Final text: ${state.textLines[0].text}")
		state.richSpanManager.debugPrintSpans()

		state.lineOffsets.forEachIndexed { index, lineWrap ->
			println("\nLine wrap $index:")
			println("  Line: ${lineWrap.line}")
			println("  Wrap starts at: ${lineWrap.wrapStartsAtIndex}")
			println(
				"  Virtual line range: ${lineWrap.wrapStartsAtIndex} -> ${
					lineWrap.textLayoutResult.getLineEnd(
						0,
						false
					)
				}"
			)
			println("  Number of spans: ${lineWrap.richSpans.size}")
			// Print all available spans
			state.richSpanManager.debugPrintSpans("  Available spans:")
			// Test intersection explicitly with each span
			state.richSpanManager.getSpans().forEach { span ->
				val intersects = span.intersectsWith(lineWrap)
				println("  Testing intersection with span ${span.range.start.line},${span.range.start.char} -> ${span.range.end.line},${span.range.end.char}: $intersects")
				if (!intersects) {
					println(
						"    Wrap range: ${lineWrap.wrapStartsAtIndex} -> ${
							lineWrap.textLayoutResult.getLineEnd(
								0,
								true
							)
						}"
					)
				}
			}
		}

		val wrappedLineOffsets = state.lineOffsets
		assertTrue(wrappedLineOffsets.size > 1, "Text should have wrapped to multiple lines")

		wrappedLineOffsets.forEachIndexed { index, lineWrap ->
			val expectedSpans = when (index) {
				0 -> 0
				1 -> 1
				2 -> 1
				else -> error("Unhandled index")
			}
			val spans = lineWrap.richSpans
			assertEquals(
				expectedSpans,
				spans.size,
				"Wrapped line should have had $expectedSpans spans. Line Wrap $index, " +
						"wrap at ${lineWrap.wrapStartsAtIndex} had ${spans.size} spans"
			)
		}
	}
}

// Extension function to help debug RichSpanManager
fun RichSpanManager.debugPrintSpans(prefix: String = "Spans:") {
	println(prefix)
	getSpans().forEachIndexed { index, span ->
		println("    $index: $span")
	}
}

// Extension function to get spans from RichSpanManager for testing
fun RichSpanManager.getSpans(): Collection<RichSpan> = getAllRichSpans()

private fun createMockTextMeasurer(): TextMeasurer {
	val measurer = mockk<TextMeasurer>()

	fun createLayoutResult(text: AnnotatedString): TextLayoutResult {
		val mockLayoutResult = mockk<TextLayoutResult>()
		val lineCount = (text.length + 9) / 10 // Will create a new line every 10 chars

		every { mockLayoutResult.size } returns IntSize(100, 20)
		every { mockLayoutResult.lineCount } returns lineCount
		every { mockLayoutResult.multiParagraph.lineCount } returns lineCount

		// Mock getLineStart
		every { mockLayoutResult.getLineStart(any()) } answers { call ->
			val lineIndex = call.invocation.args[0] as Int
			val result = lineIndex * 10
			println("Getting line start for line $lineIndex: returning $result")
			result
		}

		// Debug print for line end calculations
		every { mockLayoutResult.getLineEnd(any(), any()) } answers { call ->
			val lineIndex = call.invocation.args[0] as Int
			val visibleEnd = call.invocation.args[1] as Boolean
			val result = minOf((lineIndex + 1) * 10, text.length).let { end ->
				// For wrapped lines, we need to return the actual wrapped positions
				when (lineIndex) {
					0 -> minOf(10, text.length)
					1 -> minOf(20, text.length)
					2 -> minOf(30, text.length)
					else -> text.length
				}
			}
			println("Getting line end for line $lineIndex (visibleEnd=$visibleEnd): returning $result")
			result
		}

		every { mockLayoutResult.multiParagraph.getLineHeight(any()) } returns 20f

		return mockLayoutResult
	}

	// Handle all possible measure calls
	every {
		measurer.measure(
			text = any<AnnotatedString>(),
			style = any(),
			constraints = any(),
			density = any()
		)
	} answers { call ->
		val text = call.invocation.args[0] as AnnotatedString
		println("Measuring text: '${text.text}'")
		createLayoutResult(text)
	}

	every {
		measurer.measure(
			text = any<AnnotatedString>(),
			constraints = any(),
			style = any(),
			maxLines = any(),
			density = any()
		)
	} answers { call ->
		val text = call.invocation.args[0] as AnnotatedString
		println("Measuring text (with maxLines): '${text.text}'")
		createLayoutResult(text)
	}

	every {
		measurer.measure(
			text = any<AnnotatedString>(),
			constraints = any(),
		)
	} answers { call ->
		val text = call.invocation.args[0] as AnnotatedString
		println("Measuring text (simple): '${text.text}'")
		createLayoutResult(text)
	}

	return measurer
}