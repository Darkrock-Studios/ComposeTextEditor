package markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.markdown.MarkdownConfiguration
import com.darkrockstudios.texteditor.markdown.toMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownConverterTest {
	@Test
	fun `test empty string conversion`() {
		val input = AnnotatedString("")
		assertEquals("", input.toMarkdown())
	}

	@Test
	fun `test plain text conversion`() {
		val input = AnnotatedString("Hello World")
		assertEquals("Hello World", input.toMarkdown())
	}

	@Test
	fun `test bold text conversion`() {
		val input = buildAnnotatedString {
			append("Hello ")
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
				append("bold")
			}
			append(" world")
		}
		assertEquals("Hello **bold** world", input.toMarkdown())
	}

	@Test
	fun `test configurable bold style`() {
		val customConfig = MarkdownConfiguration(
			boldStyle = SpanStyle(fontWeight = FontWeight.Bold, color = Color.Red)
		)

		val input = buildAnnotatedString {
			append("Hello ")
			withStyle(customConfig.boldStyle) {
				append("bold")
			}
			append(" world")
		}

		assertEquals("Hello **bold** world", input.toMarkdown(customConfig))
	}

	@Test
	fun `test italic text conversion`() {
		val input = buildAnnotatedString {
			append("Hello ")
			withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
				append("italic")
			}
			append(" world")
		}
		assertEquals("Hello *italic* world", input.toMarkdown())
	}

	@Test
	fun `test code text conversion`() {
		val input = buildAnnotatedString {
			append("Here is some ")
			withStyle(
				SpanStyle(
					fontFamily = FontFamily.Monospace,
					background = Color(0xFFE0E0E0)
				)
			) {
				append("code")
			}
			append(" text")
		}
		assertEquals("Here is some `code` text", input.toMarkdown())
	}

	@Test
	fun `test nested styles conversion`() {
		val input = buildAnnotatedString {
			append("Hello ")
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
				append("bold ")
				withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
					append("and italic")
				}
			}
			append(" world")
		}
		assertEquals("Hello **bold *and italic*** world", input.toMarkdown())
	}

	@Test
	fun `test header conversion`() {
		val input = buildAnnotatedString {
			withStyle(SpanStyle(fontSize = 32f.sp, fontWeight = FontWeight.Bold)) {
				append("Header 1")
			}
			append("\n")
			withStyle(SpanStyle(fontSize = 24f.sp, fontWeight = FontWeight.Bold)) {
				append("Header 2")
			}
		}
		assertEquals("# Header 1\n## Header 2\n", input.toMarkdown())
	}

	@Test
	fun `test link conversion`() {
		val input = buildAnnotatedString {
			append("Click ")
			withStyle(SpanStyle(color = Color.Blue, textDecoration = TextDecoration.Underline)) {
				append("here")
			}
			append(" to continue")
		}
		assertEquals("Click [here]() to continue", input.toMarkdown())
	}

//	@Test
//	fun `test blockquote conversion`() {
//		val input = buildAnnotatedString {
//			withStyle(SpanStyle(color = Color.Gray, fontStyle = FontStyle.Italic)) {
//				append("This is a quote")
//			}
//		}
//		assertEquals("> This is a quote\n", input.toMarkdown())
//	}

	@Test
	fun `test strikethrough conversion`() {
		val input = buildAnnotatedString {
			append("Hello ")
			withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
				append("strikethrough")
			}
			append(" world")
		}
		assertEquals("Hello ~~strikethrough~~ world", input.toMarkdown())
	}

	@Test
	fun `test unsupported style is dropped`() {
		val input = buildAnnotatedString {
			append("Hello ")
			withStyle(SpanStyle(color = androidx.compose.ui.graphics.Color.Red)) {
				append("red")
			}
			append(" world")
		}
		assertEquals("Hello red world", input.toMarkdown())
	}

	@Test
	fun `test overlapping styles are properly nested`() {
		val input = buildAnnotatedString {
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
				append("Hello ")
				withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
					append("beautiful ")
				}
				append("world")
			}
			withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
				append(" and universe")
			}
		}
		// The italic ranges carry an edge space; the emphasis markers shrink onto the
		// text because delimiters touching whitespace are not emphasis in CommonMark.
		assertEquals("**Hello *beautiful* world** *and universe*", input.toMarkdown())
	}

	@Test
	fun `test escaping single special characters`() {
		val specialChars =
			listOf('*', '_', '`', '#', '+', '-', '!', '[', ']', '(', ')', '{', '}', '<', '>', '|', '\\')

		specialChars.forEach { char ->
			val input = AnnotatedString("Text with $char character")
			val expected = "Text with \\$char character"
			assertEquals(expected, input.toMarkdown(), "Failed to escape character: $char")
		}
	}

	@Test
	fun `test escaping multiple special characters`() {
		val input = AnnotatedString("Text with *bold* and _italic_ and `code` markers")
		val expected = "Text with \\*bold\\* and \\_italic\\_ and \\`code\\` markers"
		assertEquals(expected, input.toMarkdown())
	}

	@Test
	fun `test escaping special characters at different positions`() {
		val input1 = AnnotatedString("*At the beginning")
		assertEquals("\\*At the beginning", input1.toMarkdown())

		val input2 = AnnotatedString("In the middle * of text")
		assertEquals("In the middle \\* of text", input2.toMarkdown())

		val input3 = AnnotatedString("At the end*")
		assertEquals("At the end\\*", input3.toMarkdown())
	}

	@Test
	fun `test escaping special characters within styled text`() {
		val input = buildAnnotatedString {
			append("Text with ")
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
				append("bold *with* special chars")
			}
			append(" and ")
			withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
				append("italic [with] special chars")
			}
		}
		assertEquals(
			"Text with **bold \\*with\\* special chars** and *italic \\[with\\] special chars*",
			input.toMarkdown()
		)
	}
}
