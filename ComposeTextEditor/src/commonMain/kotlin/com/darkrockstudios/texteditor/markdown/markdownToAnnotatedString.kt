package com.darkrockstudios.texteditor.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * A hyperlink found during a parse: [start] until [end] are flat character
 * offsets into the produced [AnnotatedString]'s text, [url] the destination.
 */
internal data class ParsedLink(val start: Int, val end: Int, val url: String)

/** A parse's styled text together with the links found inside it. */
internal class MarkdownParseResult(
	val annotatedString: AnnotatedString,
	val links: List<ParsedLink>,
)

/**
 * Parses this string as GitHub Flavored Markdown and renders it into a styled
 * [AnnotatedString].
 *
 * @param configuration Styling (fonts, colors, weights) applied to the parsed
 * markdown elements.
 */
fun String.toAnnotatedStringFromMarkdown(
	configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
): AnnotatedString = parseMarkdownWithLinks(configuration).annotatedString

/**
 * Parses like [toAnnotatedStringFromMarkdown] but also reports every inline
 * link's text range and destination, so an importer can attach the semantic
 * link spans the [AnnotatedString] itself cannot carry.
 */
internal fun String.parseMarkdownWithLinks(
	configuration: MarkdownConfiguration = MarkdownConfiguration.DEFAULT
): MarkdownParseResult {
	val styles = MarkdownStyles(configuration)

	val flavour = GFMFlavourDescriptor()
	val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(this)
	val links = mutableListOf<ParsedLink>()
	val annotated = buildAnnotatedString {
		appendMarkdownChildren(this@parseMarkdownWithLinks, parsedTree, 0, styles, links)
	}
	return MarkdownParseResult(annotated, links)
}

internal fun AnnotatedString.Builder.appendMarkdownChildren(
	original: String,
	node: ASTNode,
	startOffset: Int,
	styles: MarkdownStyles,
	links: MutableList<ParsedLink> = mutableListOf(),
) {
	var childOffset = startOffset
	node.children.forEach { child ->
		appendMarkdownNode(original, child, childOffset, styles, links)
		childOffset += child.getTextInNode(original).length
	}
}

private fun AnnotatedString.Builder.appendMarkdownNode(
	original: String,
	node: ASTNode,
	startOffset: Int,
	styles: MarkdownStyles,
	links: MutableList<ParsedLink>,
) {
	val nodeText = node.getTextInNode(original).toString()

	when (node.type) {
		MarkdownElementTypes.PARAGRAPH -> {
			pushStyle(styles.BASE_TEXT)
			appendMarkdownChildren(original, node, startOffset, styles, links)
			pop()
		}

		MarkdownTokenTypes.WHITE_SPACE -> {
			// Only keep newlines and spaces between words
			if (nodeText.contains("\n") || startOffset > 0) {
				append(nodeText)
			}
		}

		MarkdownElementTypes.EMPH -> {
			pushStyle(styles.ITALICS)
			appendStyledContent(node, original, startOffset, styles, links)
			pop()
		}

		MarkdownElementTypes.STRONG -> {
			pushStyle(styles.BOLD)
			appendStyledContent(node, original, startOffset, styles, links)
			pop()
		}

		GFMElementTypes.STRIKETHROUGH -> {
			pushStyle(styles.STRIKETHROUGH)
			appendStyledContent(node, original, startOffset, styles, links)
			pop()
		}

		MarkdownElementTypes.CODE_SPAN -> {
			pushStyle(styles.CODE)
			val codeText = nodeText.removeSurrounding("`")
			append(codeText)
			pop()
		}

		MarkdownTokenTypes.ESCAPED_BACKTICKS -> {
			append(nodeText.removeMarkdownEscapes())
		}

		MarkdownElementTypes.CODE_FENCE -> {
			pushStyle(styles.CODE)

			// Get the lines and strip fence markers
			val lines = nodeText.lines()
				.dropWhile { it.trim().startsWith("```") } // Drop opening fence
				.dropLastWhile { it.trim().startsWith("```") } // Drop closing fence
				.filter { it.isNotEmpty() } // Remove empty lines

			if (lines.isNotEmpty()) {
				// Calculate minimum indentation from non-empty lines
				val minIndent = lines
					.filter { it.isNotBlank() }
					.map { it.indexOfFirst { char -> !char.isWhitespace() } }
					.filter { it != -1 }
					.minOrNull() ?: 0

				// Process and append each line with proper indentation
				lines.joinToString("\n") { line ->
					if (line.length >= minIndent) {
						line.substring(minIndent)
					} else {
						line
					}
				}.let { processedContent ->
					append(processedContent.trim())
					append('\n')
				}
			}
			pop()
		}

		MarkdownElementTypes.ATX_1 -> handleHeader(original, node, startOffset, 1, styles, links)
		MarkdownElementTypes.ATX_2 -> handleHeader(original, node, startOffset, 2, styles, links)
		MarkdownElementTypes.ATX_3 -> handleHeader(original, node, startOffset, 3, styles, links)
		MarkdownElementTypes.ATX_4 -> handleHeader(original, node, startOffset, 4, styles, links)
		MarkdownElementTypes.ATX_5 -> handleHeader(original, node, startOffset, 5, styles, links)
		MarkdownElementTypes.ATX_6 -> handleHeader(original, node, startOffset, 6, styles, links)

		MarkdownElementTypes.INLINE_LINK -> {
			pushStyle(styles.LINK)
			val textStart = length
			var childOffset = startOffset
			node.children.forEach { child ->
				if (child.type == MarkdownElementTypes.LINK_TEXT) {
					// The first and last children are the bracket tokens; the
					// nodes between them are the link text, styles and all.
					var gcOffset = childOffset
					child.children.forEachIndexed { i, gc ->
						if (i != 0 && i != child.children.lastIndex) {
							appendMarkdownNode(original, gc, gcOffset, styles, links)
						}
						gcOffset += gc.getTextInNode(original).length
					}
				}
				childOffset += child.getTextInNode(original).length
			}
			val textEnd = length
			pop()
			// A bare destination parses as LINK_DESTINATION; the GFM flavour
			// reads an angle-bracketed one as an AUTOLINK child instead. Both
			// carry any angle brackets in the node text; the URL itself is
			// what round-trips.
			val url = node.children
				.firstOrNull {
					it.type == MarkdownElementTypes.LINK_DESTINATION ||
						it.type == MarkdownElementTypes.AUTOLINK
				}
				?.getTextInNode(original)?.toString()
				?.removeSurrounding("<", ">")
			if (url != null && textEnd > textStart) {
				links += ParsedLink(textStart, textEnd, url)
			}
		}

		MarkdownElementTypes.ORDERED_LIST,
		MarkdownElementTypes.UNORDERED_LIST -> {
			// MarkdownExtension's pre-pass strips bullet markers (`-`, `*`, `+`) from
			// unordered list lines before parsing, so this branch only fires for
			// ordered lists or list-like markup that bypassed the pre-pass. We just
			// recurse into children — no glyph injection — so the body text survives
			// without spurious bullet characters leaking into the AnnotatedString.
			// Ordered list numbering is a follow-up.
			appendMarkdownChildren(original, node, startOffset, styles, links)
		}

		MarkdownElementTypes.LIST_ITEM -> {
			appendMarkdownChildren(original, node, startOffset, styles, links)
		}

		MarkdownElementTypes.BLOCK_QUOTE -> {
			// MarkdownExtension's pre-pass strips `> ` line prefixes before parsing, so
			// this branch only fires for blockquotes outside that pipeline (e.g. callers
			// of toAnnotatedStringFromMarkdown directly). Recurse without injecting a
			// literal `> ` marker so the body text isn't visually corrupted.
			appendMarkdownChildren(original, node, startOffset, styles, links)
		}

		MarkdownTokenTypes.TEXT -> {
			// Remove escape sequences from text content
			append(nodeText.removeMarkdownEscapes())
		}

		MarkdownTokenTypes.EOL -> {
			append(nodeText)
		}

		MarkdownElementTypes.MARKDOWN_FILE -> {
			appendMarkdownChildren(original, node, startOffset, styles, links)
		}

		else -> {
			// For any unhandled node types, append text with escapes removed
			if (nodeText.isNotEmpty()) {
				append(nodeText.removeMarkdownEscapes())
			} else {
				appendMarkdownChildren(original, node, startOffset, styles, links)
			}
		}
	}
}

private fun AnnotatedString.Builder.appendStyledContent(
	node: ASTNode,
	original: String,
	startOffset: Int,
	styles: MarkdownStyles,
	links: MutableList<ParsedLink>,
) {
	var currentText = StringBuilder()

	node.children.forEach { child ->
		// At this level we should only be dealing with tokens, not elements
		when (child.type) {
			// Accumulate actual content
			MarkdownTokenTypes.TEXT,
			MarkdownTokenTypes.WHITE_SPACE -> {
				currentText.append(child.getTextInNode(original))
			}
			// Skip markdown syntax tokens
			MarkdownTokenTypes.EMPH,
			MarkdownTokenTypes.BACKTICK,
			GFMTokenTypes.TILDE -> {
			}
			// Handle any nested elements by recursing
			else -> {
				// Flush accumulated text first
				if (currentText.isNotEmpty()) {
					append(currentText.toString().removeMarkdownEscapes())
					currentText.clear()
				}
				appendMarkdownNode(original, child, startOffset, styles, links)
			}
		}
	}

	// Flush any remaining text
	if (currentText.isNotEmpty()) {
		append(currentText.toString().removeMarkdownEscapes())
	}
}

private fun AnnotatedString.Builder.handleHeader(
	original: String,
	node: ASTNode,
	startOffset: Int,
	level: Int,
	styles: MarkdownStyles,
	links: MutableList<ParsedLink>,
) {
	// Apply the header style
	pushStyle(styles.header(level))

	// Process the child nodes, ignoring `#` markers but supporting nested spans
	node.children.forEach { child ->
		when (child.type) {
			MarkdownTokenTypes.ATX_HEADER -> {
				// Skip processing the actual `#` markers
			}

			MarkdownTokenTypes.WHITE_SPACE -> {
				// Direct WHITE_SPACE children of an ATX_n element are the syntactic
				// separator between `##` markers and content — never content itself.
			}

			MarkdownTokenTypes.ATX_CONTENT -> {
				// The first child of ATX_CONTENT is typically a WHITE_SPACE token
				// holding the syntactic space between `##` and the text. Skip leading
				// whitespace tokens here so the styled header text doesn't accumulate
				// a leading space on each export round-trip — the serializer already
				// emits `## ` with its own trailing space.
				var contentOffset = startOffset
				var seenContent = false
				child.children.forEach { gc ->
					if (!seenContent && gc.type == MarkdownTokenTypes.WHITE_SPACE) {
						contentOffset += gc.getTextInNode(original).length
						return@forEach
					}
					seenContent = true
					appendMarkdownNode(original, gc, contentOffset, styles, links)
					contentOffset += gc.getTextInNode(original).length
				}
			}

			else -> {
				// Process any other nested styles or text
				appendMarkdownNode(original, child, startOffset, styles, links)
			}
		}
	}

	// Pop the header style
	pop()
}
