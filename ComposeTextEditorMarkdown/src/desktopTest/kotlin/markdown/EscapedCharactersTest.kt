package markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown
import com.darkrockstudios.texteditor.markdown.toMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals

class EscapedCharactersTest {
	@Test
	fun `Test escaped special characters`() {
		val markdownText = """
            This is not \*bold\* and not \_italic\_ and not \`code\`.
            
            This is a literal backslash: \\
            
            This is a literal \# hash.
        """.trimIndent()

		val annotatedString = markdownText.toAnnotatedStringFromMarkdown()
		println("[DEBUG_LOG] Annotated string text: ${annotatedString.text}")

		val expectedString = """
            This is not *bold* and not _italic_ and not `code`.
            
            This is a literal backslash: \
            
            This is a literal # hash.
        """.trimIndent()

		assertEquals(expectedString, annotatedString.text)

		// The emphasis and code delimiters would be read as markup and stay
		// escaped, as does a backslash ending a line (a hard break); the hash
		// mid-line is prose.
		val canonical = """
            This is not \*bold\* and not \_italic\_ and not \`code\`.
            
            This is a literal backslash: \\
            
            This is a literal # hash.
        """.trimIndent()
		val reconverted = annotatedString.toMarkdown()
		assertEquals(canonical, reconverted)
		assertEquals(expectedString, reconverted.toAnnotatedStringFromMarkdown().text)
	}

	@Test
	fun `Test inner escaped special characters`() {
		val markdownText = """
            This is *bold with \\ escaped \- characters* and not \_italic\_ and not \`code\`.
            
            This is a **literal backslash: \\ inside italics**
            
            This is a literal \# hash.
        """.trimIndent()

		val annotatedString = markdownText.toAnnotatedStringFromMarkdown()
		println("[DEBUG_LOG] Annotated string text: ${annotatedString.text}")

		val expectedString = """
            This is bold with \ escaped - characters and not _italic_ and not `code`.
            
            This is a literal backslash: \ inside italics
            
            This is a literal # hash.
        """.trimIndent()

		assertEquals(expectedString, annotatedString.text)

		// A backslash before a space, a hyphen and a hash mid-line are prose.
		val canonical = """
            This is *bold with \ escaped - characters* and not \_italic\_ and not \`code\`.
            
            This is a **literal backslash: \ inside italics**
            
            This is a literal # hash.
        """.trimIndent()
		val reconverted = annotatedString.toMarkdown()
		assertEquals(canonical, reconverted)
		assertEquals(expectedString, reconverted.toAnnotatedStringFromMarkdown().text)
	}

	@Test
	fun `Test escaped angle brackets and pipe round-trip`() {
		val markdownText = "This has \\< angle \\> brackets and a \\| pipe."

		val annotatedString = markdownText.toAnnotatedStringFromMarkdown()
		val expectedString = "This has < angle > brackets and a | pipe."
		assertEquals(expectedString, annotatedString.text, "Unescape failed for <, >, |")

		// Beside spaces none of them starts syntax, so none is written escaped.
		val reconverted = annotatedString.toMarkdown()
		assertEquals(expectedString, reconverted, "Round-trip failed for <, >, |")
		assertEquals(expectedString, reconverted.toAnnotatedStringFromMarkdown().text)
	}

	@Test
	fun `Test all special characters round-trip from plain text`() {
		val specialChars = listOf('*', '_', '`', '#', '+', '-', '!', '[', ']', '(', ')', '{', '}', '<', '>', '|', '\\')

		specialChars.forEach { char ->
			val originalText = "Text with $char here"
			val original = AnnotatedString(originalText)
			val markdown = original.toMarkdown()
			val roundTripped = markdown.toAnnotatedStringFromMarkdown()
			assertEquals(
				originalText,
				roundTripped.text,
				"Round-trip failed for character: $char (markdown was: $markdown)"
			)
		}
	}

	@Test
	fun `Test hyphen in plain text round-trip`() {
		val originalText = "hello-world and some-other-hyphenated-words"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(originalText, roundTripped.text, "Hyphen round-trip failed. Markdown was: $markdown")
	}

	@Test
	fun `Test hyphen in bold text round-trip`() {
		val original = buildAnnotatedString {
			append("Some ")
			withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
				append("bold-hyphenated-text")
			}
			append(" here")
		}
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			original.text,
			roundTripped.text,
			"Hyphen in bold round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test hyphen in italic text round-trip`() {
		val original = buildAnnotatedString {
			append("Some ")
			withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
				append("italic-hyphenated-text")
			}
			append(" here")
		}
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			original.text,
			roundTripped.text,
			"Hyphen in italic round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test hyphen at start of line round-trip`() {
		// A hyphen at line start could be parsed as an unordered list marker
		val originalText = "- this looks like a list item"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Hyphen at line start round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test multiple hyphens at start of line round-trip`() {
		// Three hyphens could be parsed as a thematic break / horizontal rule
		val originalText = "--- this starts with triple hyphens"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Triple hyphen at line start round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test standalone hyphen line round-trip`() {
		// A lone hyphen on a line
		val originalText = "-"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Standalone hyphen round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test hyphen after newline round-trip`() {
		// Hyphen at start of second line — could trigger list parsing
		val originalText = "First line\n- second line looks like a list"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Hyphen after newline round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test multiple hyphenated lines round-trip`() {
		val originalText = "key-value\nsome-thing\nanother-item"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Multiple hyphenated lines round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test hyphen with spaces round-trip`() {
		// "- " is the canonical unordered list marker in CommonMark
		val originalText = "- item one\n- item two"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Hyphen-space list-like lines round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test en-dash and em-dash round-trip`() {
		// Users might type double or triple hyphens for dashes
		val originalText = "value -- something --- else"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"En/em-dash round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test hyphen surrounded by special chars round-trip`() {
		val originalText = "*-* and _-_ and (-) and [-]"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Hyphen with surrounding special chars round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test plus at start of line round-trip`() {
		// Plus is also a list marker in CommonMark
		val originalText = "+ this looks like a list item"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Plus at line start round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test exclamation mark round-trip`() {
		// "![" starts an image in markdown
		val originalText = "Look at this ![not an image] thing"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Exclamation mark round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test digit-hyphen round-trip`() {
		// Reported bug: "1-" becomes "1\-" after round-trip
		val originalText = "1-"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Digit-hyphen round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test digit-hyphen variations round-trip`() {
		// Various digit-hyphen patterns that might trigger ordered list parsing
		val cases = listOf("1-", "1- ", "1-foo", "2-bar", "10-baz", "1-2-3", "Room 1-A")
		cases.forEach { text ->
			val original = AnnotatedString(text)
			val markdown = original.toMarkdown()
			val roundTripped = markdown.toAnnotatedStringFromMarkdown()
			assertEquals(
				text,
				roundTripped.text,
				"Digit-hyphen variation '$text' round-trip failed. Markdown was: $markdown"
			)
		}
	}

	@Test
	fun `Test digit-hyphen on its own line round-trip`() {
		val originalText = "some text\n1-\nmore text"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Digit-hyphen on own line round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test digit-period round-trip`() {
		// "1." at line start would trigger ordered list without escaping
		val cases = listOf("1.", "1. foo", "1.2.3", "version 2.0", "foo 1. bar")
		cases.forEach { text ->
			val original = AnnotatedString(text)
			val markdown = original.toMarkdown()
			val roundTripped = markdown.toAnnotatedStringFromMarkdown()
			assertEquals(
				text,
				roundTripped.text,
				"Digit-period variation '$text' round-trip failed. Markdown was: $markdown"
			)
		}
	}

	@Test
	fun `Test digit-period multiline round-trip`() {
		val originalText = "Step 1. do this\n2. then this\n10. finally this"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Digit-period multiline round-trip failed. Markdown was: $markdown"
		)
	}

	@Test
	fun `Test parsing markdown with literal backslash-hyphen`() {
		// Simulate what a user might see if markdown file contains literal \-
		val markdownText = "1\\-"
		val parsed = markdownText.toAnnotatedStringFromMarkdown()
		assertEquals("1-", parsed.text, "Parsing '1\\-' should produce '1-'")
	}

	@Test
	fun `Test double round-trip preserves text`() {
		// Ensure multiple round-trips don't accumulate escapes
		val originalText = "1- hello-world"
		val original = AnnotatedString(originalText)

		val markdown1 = original.toMarkdown()
		val roundTrip1 = markdown1.toAnnotatedStringFromMarkdown()
		assertEquals(originalText, roundTrip1.text, "First round-trip failed. Markdown: $markdown1")

		val markdown2 = roundTrip1.toMarkdown()
		val roundTrip2 = markdown2.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTrip2.text,
			"Second round-trip failed. Markdown1: $markdown1, Markdown2: $markdown2"
		)

		val markdown3 = roundTrip2.toMarkdown()
		val roundTrip3 = markdown3.toAnnotatedStringFromMarkdown()
		assertEquals(originalText, roundTrip3.text, "Third round-trip failed. Markdown: $markdown3")
	}

	@Test
	fun `Test header below first line does not accumulate leading space`() {
		// A header that isn't at file offset 0 used to keep the syntactic space
		// between `##` and the content as part of the styled text, so each export
		// round-trip added another leading space.
		val markdownText = "Some intro paragraph.\n\n## Escapes & Literal Syntax\n\nMore body text."

		val parsed1 = markdownText.toAnnotatedStringFromMarkdown()
		val markdown1 = parsed1.toMarkdown()
		val parsed2 = markdown1.toAnnotatedStringFromMarkdown()
		val markdown2 = parsed2.toMarkdown()

		assertEquals(parsed1.text, parsed2.text, "Header text drifted. Markdown1: $markdown1")
		assertEquals(markdown1, markdown2, "Header markdown drifted across round-trips")
	}

	@Test
	fun `Test special characters inside code span do not accumulate escapes`() {
		// CommonMark code spans take content literally — backslash escapes do not apply.
		// The serializer must not escape special chars inside a code span, or each
		// round-trip will double the backslashes.
		val markdownText = "Inline code: `* _ # + - ! [ ] ( ) { } < > |` here."

		val parsed1 = markdownText.toAnnotatedStringFromMarkdown()
		assertEquals(
			"Inline code: * _ # + - ! [ ] ( ) { } < > | here.",
			parsed1.text,
			"First parse: code span content should be literal"
		)

		val markdown1 = parsed1.toMarkdown()
		val parsed2 = markdown1.toAnnotatedStringFromMarkdown()
		assertEquals(
			parsed1.text,
			parsed2.text,
			"Second parse: code span text drifted. Markdown: $markdown1"
		)

		val markdown2 = parsed2.toMarkdown()
		assertEquals(
			markdown1,
			markdown2,
			"Markdown form should be stable across round-trips"
		)
	}

	@Test
	fun `Test hash at start of line round-trip`() {
		// "#" at line start is a header
		val originalText = "# not a header"
		val original = AnnotatedString(originalText)
		val markdown = original.toMarkdown()
		val roundTripped = markdown.toAnnotatedStringFromMarkdown()
		assertEquals(
			originalText,
			roundTripped.text,
			"Hash at line start round-trip failed. Markdown was: $markdown"
		)
	}
}