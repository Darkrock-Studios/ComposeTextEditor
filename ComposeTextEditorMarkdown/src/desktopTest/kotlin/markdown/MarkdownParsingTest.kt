package markdown

import com.darkrockstudios.texteditor.markdown.toAnnotatedStringFromMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownParsingTest {
	@Test
	fun `Parse markdown to Annotated String`() {
		val markdownText = """
		    # Hello World
		    This is **bold** and *italic* text.
			
			Here's a [link](https://example.com) in the text.
		    
		    Here's some `inline code` and a code block:
		    ```
		    fun hello() {
		        println("Hello!")
		    }
		    ```
		""".trimIndent()

		val annotatedString = markdownText.toAnnotatedStringFromMarkdown()
		//println(annotatedString.text)

		val expectedString = """Hello World
   This is bold and italic text.

Here's a link in the text.
   
   Here's some inline code and a code block:
fun hello() {
    println("Hello!")
}

""".trimIndent()

		assertEquals(expectedString, annotatedString.text)
		assertEquals(9, annotatedString.spanStyles.size)
	}
}