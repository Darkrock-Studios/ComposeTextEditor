package com.darkrockstudios.texteditor.sample

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.darkrockstudios.texteditor.markdown.MarkdownStyles

const val alice_wounder_land = """
  **Down the Rabbit-Hole**

  Alice was beginning to get very tired of sitting by her sister
on the bank, and of having nothing to do:  once or twice she had
peeped into the book her sister was reading, but it had no
pictures or conversations in it, `and what is the use of a book,'
thought Alice `without pictures or conversation?'

  So she was considering in her own mind (as well as she could,
for the hot day made her feel very sleepy and stupid), whether
the pleasure of making a daisy-chain would be worth the trouble
of getting up and picking the daisies, when suddenly a White
Rabbit with pink eyes ran close by her.

  There was nothing so VERY remarkable in that; nor did Alice
think it so VERY much out of the way to hear the Rabbit say to
itself, `Oh dear!  Oh dear!  I shall be late!'  (when she thought
it over afterwards, it occurred to her that she ought to have
wondered at this, but at the time it all seemed quite natural);
but when the Rabbit actually TOOK A WATCH OUT OF ITS WAISTCOAT-
POCKET, and looked at it, and then hurried on, Alice started to
her feet, for it flashed across her mind that she had never
before seen a rabbit with either a waistcoat-pocket, or a watch to
take out of it, and burning with curiosity, she ran across the
field after it, and fortunately was just in time to see it pop
down a large rabbit-hole under the hedge.

  In another moment down went Alice after it, never once
considering how in the world she was to get out again.

  The rabbit-hole went straight on like a tunnel for some way,
and then dipped suddenly down, so suddenly that Alice had not a
moment to think about stopping herself before she found herself
falling down a very deep well.

  Either the well was very deep, or she fell very slowly, for she
had plenty of time as she went down to look about her and to
wonder what was going to happen next.  First, she tried to look
down and make out what she was coming to, but it was too dark to
see anything; then she looked at the sides of the well, and
noticed that they were filled with cupboards and book-shelves;
here and there she saw maps and pictures hung upon pegs.  She
took down a jar from one of the shelves as she passed; it was
labelled `ORANGE MARMALADE', but to her great disappointment it
was empty:  she did not like to drop the jar for fear of killing
somebody, so managed to put it into one of the cupboards as she
fell past it.

  `Well!' thought Alice to herself, `after such a fall as this, I
shall think nothing of tumbling down stairs!  How brave they'll
all think me at home!  Why, I wouldn't say anything about it,
even if I fell off the top of the house!' (Which was very likely
true.)

  Down, down, down.  Would the fall NEVER come to an end!  `I
wonder how many miles I've fallen by this time?' she said aloud.
`I must be getting somewhere near the centre of the earth.  Let
me see:  that would be four thousand miles down, I think--' (for,
you see, Alice had learnt several things of this sort in her
lessons in the schoolroom, and though this was not a VERY good
opportunity for showing off her knowledge, as there was no one to
listen to her, still it was good practice to say it over) `--yes,
that's about the right distance--but then I wonder what Latitude
or Longitude I've got to?'  (Alice had no idea what Latitude was,
or Longitude either, but thought they were nice grand words to
say.)

  Presently she began again.  `I wonder if I shall fall right
THROUGH the earth!  How funny it'll seem to come out among the
people that walk with their heads downward!  The Antipathies, I
think--' (she was rather glad there WAS no one listening, this
time, as it didn't sound at all the right word) `--but I shall
have to ask them what the name of the country is, you know.
Please, Ma'am, is this New Zealand or Australia?' (and she tried
to curtsey as she spoke--fancy CURTSEYING as you're falling
through the air!  Do you think you could manage it?)  `And what
an ignorant little girl she'll think me for asking!  No, it'll
never do to ask:  perhaps I shall see it written up somewhere.'

  Down, down, down.  There was nothing else to do, so Alice soon
began talking again.  `Dinah'll miss me very much to-night, I
should think!'  (Dinah was the cat.)  `I hope they'll remember
her saucer of milk at tea-time.  Dinah my dear!  I wish you were
down here with me!  There are no mice in the air, I'm afraid, but
you might catch a bat, and that's very like a mouse, you know.
But do cats eat bats, I wonder?'  And here Alice began to get
rather sleepy, and went on saying to herself, in a dreamy sort of
way, `Do cats eat bats?  Do cats eat bats?' and sometimes, `Do
bats eat cats?' for, you see, as she couldn't answer either
question, it didn't much matter which way she put it.  She felt
that she was dozing off, and had just begun to dream that she
was walking hand in hand with Dinah, and saying to her very
earnestly, `Now, Dinah, tell me the truth:  did you ever eat a
bat?' when suddenly, thump! thump! down she came upon a heap of
sticks and dry leaves, and the fall was over.

  Alice was not a bit hurt, and she jumped up on to her feet in a
moment:  she looked up, but it was all dark overhead; before her
was another long passage, and the White Rabbit was still in
sight, hurrying down it.  There was not a moment to be lost:
away went Alice like the wind, and was just in time to hear it
say, as it turned a corner, `Oh my ears and whiskers, how late
it's getting!'  She was close behind it when she turned the
corner, but the Rabbit was no longer to be seen:  she found
herself in a long, low hall, which was lit up by a row of lamps
hanging from the roof.

  There were doors all round the hall, but they were all locked;
and when Alice had been all the way down one side and up the
other, trying every door, she walked sadly down the middle,
wondering how she was ever to get out again.

  Suddenly she came upon a little three-legged table, all made of
solid glass; there was nothing on it except a tiny golden key,
and Alice's first thought was that it might belong to one of the
doors of the hall; but, alas! either the locks were too large, or
the key was too small, but at any rate it would not open any of
them.  However, on the second time round, she came upon a low
curtain she had not noticed before, and behind it was a little
door about fifteen inches high:  she tried the little golden key
in the lock, and to her great delight it fitted!

  Alice opened the door and found that it led into a small
passage, not much larger than a rat-hole:  she knelt down and
looked along the passage into the loveliest garden you ever saw.
How she longed to get out of that dark hall, and wander about
among those beds of bright flowers and those cool fountains, but
she could not even get her head though the doorway; `and even if
my head would go through,' thought poor Alice, `it would be of
very little use without my shoulders.  Oh, how I wish
I could shut up like a telescope!  I think I could, if I only
know how to begin.'  For, you see, so many out-of-the-way things
had happened lately, that Alice had begun to think that very few
things indeed were really impossible.
"""

const val SIMPLE_MARKDOWN = """# Understanding *RichSpan Management* in Compose Multiplatform

The `RichSpanManager` plays a **critical role** in our text editor by efficiently managing **custom text styles** known as `RichSpanStyle`. These styles are applied dynamically to the text, enhancing flexibility. A **notable feature** of this manager is its ability to handle spans gracefully during operations like **insertion**, **deletion**, or **replacement**, ensuring text integrity.

![Highway at night](sample.jpg)

> Blockquotes render with a left bar and an indent. The text inside still supports **bold**, *italic*, and `code` markdown — anything you'd write in a normal paragraph works here too.

Bullet lists exercise the same gutter-marker pattern with a hanging indent so wrapped lines stay aligned under the text:

- First item with **bold** content
- Second item — toggle me with the bullet button or hit backspace at the start to demote
- A longer third item that should wrap onto a second visual line; the wrap should hang under the text rather than under the bullet glyph

Ordered lists number themselves automatically — add or remove items and the numbers re-flow on the next layout pass:

1. First numbered step
2. Second step with **bold** and *italic*
3. A third item that wraps onto a second visual line so we can confirm the hanging indent lines up under the text rather than the numeral

Fenced code blocks render with monospace text on a tinted card with a hairline border on all four sides, so they stand apart from the body without being mistaken for a blockquote:

```
fun greet(name: String): String {
    return "Hello, ${'$'}name!"
}
```

Markdown specials inside a fence — `*foo*`, `# heading`, `> quote` — render as literal text rather than being reinterpreted by the parser.

## Escapes & Literal Syntax

Every markdown special character can appear as literal text by escaping it with a backslash. This sentence contains all of them — \* \_ \` \# \+ \- \! \[ \] \( \) \{ \} \< \> \| \\ — and they all survive a save-and-reload round trip unchanged.

Inside code spans the same characters appear verbatim because span contents are taken literally: `* _ # + - ! [ ] ( ) { } < > |`. Combine that with **bold containing a literal \* asterisk** and *italic containing a literal \_ underscore* to confirm the editor distinguishes syntax from content.

Numeric edges round-trip too: version 2.0, Room 1-A, and "Step 1. do this" all stay as written rather than collapsing into an ordered list.

---

When a text edit occurs, spans are adjusted to maintain consistency. If the operation is an *insertion*, spans may **expand** or **shift** depending on the location relative to the insertion point. For *deletions*, spans **shrink** or **merge** based on the removed range. Replacement operations are treated as a **combination** of deletion and insertion, ensuring spans adapt seamlessly.

Span deduplication plays a significant role by merging overlapping spans to reduce redundancy. Dynamic updates ensure that any changes in text instantly reflect on spans, keeping the document visually consistent. Efficient and robust, this approach simplifies complex text operations while maintaining **visual coherence**."""

const val EXAMPLE_MARKDOWN = """# ComposeTextEditor

A **rich text** editor for *Compose Multiplatform* with `markdown` support — [try it online](https://Darkrock-Studios.github.io/ComposeTextEditorLibrary/).

![Highway at night](sample.jpg)

## Features at a glance

> Inline **bold**, *italic*, `code`, and ~~strike~~ all compose inside blockquotes, lists, and headings.

- Headings, blockquotes, and **horizontal rules**
- Bullet and ordered lists with hanging indents
- Fenced code blocks rendered on a tinted card

1. Type to edit
2. Toggle styles from the toolbar
3. Save round-trips through *CommonMark*

```kotlin
fun greet(name: String) =
    "Hello, ${'$'}name!"
```

---

Escapes survive a round trip: \*literal\* \_underscores\_ and version 2.0 stay as written."""

fun createRichTextDemo2(): AnnotatedString = buildAnnotatedString {
	append("1234567890123456789112345678921234567893")
}

private val DEFAULT = MarkdownStyles()
fun createRichTextDemo(): AnnotatedString = buildAnnotatedString {
	// Title
	withStyle(SpanStyle(fontSize = 24.sp, color = Color(0xFF1a73e8))) {
		append("Welcome to Rich Text Formatting\n\n")
	}

	// First major paragraph
	append("In the world of digital typography, formatting plays a crucial role in how we perceive and interact with text. ")

	// Bold section separated from other styles
	withStyle(DEFAULT.BOLD) {
		append("The careful application of styles can transform ordinary text into engaging, hierarchical content that guides the reader's eye and emphasizes key information. ")
	}

	append("Consider, for instance, how different formatting choices affect readability across long passages of text. When we examine documents that span multiple pages or screens, the importance of consistent and thoughtful styling becomes even more apparent. ")

	// Green and italic styles applied separately
	withStyle(SpanStyle(color = Color(0xFF0F9D58))) {
		withStyle(DEFAULT.ITALICS) {
			append("The interplay between various typographic elements – from font choices to spacing, from color to weight – creates a visual rhythm that can either enhance or detract from the reading experience.")
		}
	}
	append("\n\n")

	// Technical paragraph with monospace
	withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
		append("When we consider the technical aspects of text rendering, we must account for various factors such as word wrapping, line spacing, and character kerning. These elements work together to ensure optimal readability across different screen sizes and resolutions. The challenge lies in maintaining consistency while adapting to different viewing contexts. ")
	}

	append("This becomes particularly evident when we ")
	withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
		append("examine how text flows across multiple lines and pages, adapting to different container widths and heights while maintaining its fundamental styling attributes")
	}
	append(".\n\n")

	// Extended colored section with nested styles split apart
	withStyle(SpanStyle(color = Color(0xFF6200EE))) {
		append("Typography in the digital age presents unique challenges and opportunities. While traditional print media operated within fixed constraints, digital text must remain flexible and adaptable. ")

		// Applying bold separately within the colored section
		withStyle(DEFAULT.BOLD) {
			append("The way we style and present text can significantly impact how information is absorbed and retained. Consider how different formatting choices affect the reading experience: headlines draw attention, body text provides the core content, and highlighted elements guide the reader through key points. ")

			// Italic applied separately within bold
			withStyle(DEFAULT.ITALICS) {
				append("The interplay between these elements creates a visual hierarchy that helps readers navigate through complex information structures.")
			}
		}
		append(" This dynamic relationship between content and presentation forms the foundation of modern digital typography.")
	}
	append("\n\n")

	// Mixed text section
	append("In examining the history of typography, we find that many principles remain relevant even as technology evolves. ")
	withStyle(SpanStyle(color = Color(0xFFF4B400))) {
		append("The fundamental goals of clarity, readability, and visual appeal continue to guide design decisions in both print and digital media. Modern tools and technologies have expanded our capabilities, allowing for more sophisticated formatting options and dynamic adjustments based on viewing context. ")
	}

	withStyle(DEFAULT.BOLD) {
		append("Yet the core principles of good typography remain constant: text should be easy to read, visually pleasing, and structured in a way that supports the content's message and purpose.")
	}
	append("\n\n")

	// Technical considerations
	append("When implementing text rendering systems, developers must consider numerous factors: character encoding, font rendering, line breaking algorithms, and support for various writing systems. ")
	withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
		append("Each of these components plays a crucial role in ensuring text displays correctly across different platforms and devices. The complexity increases when dealing with rich text formatting, where multiple styles can be applied to different portions of the text, sometimes overlapping or nested within each other.")
	}
	append("\n\n")

	// Large styled paragraph
	withStyle(SpanStyle(letterSpacing = 0.5.sp)) {
		append("The impact of good typography extends beyond mere aesthetics. Studies have shown that well-formatted text can significantly improve reading comprehension and retention. When text is properly formatted, readers can more easily scan content, identify key points, and maintain focus over longer periods. This becomes particularly important in educational materials, technical documentation, and long-form content where reader engagement is crucial. The careful application of styling can help break up dense information into more manageable chunks, use emphasis to highlight important concepts, and create visual patterns that aid in navigation and comprehension.")
	}
	append("\n\n")

	// Conclusion with mixed styles separated
	append("As we continue to evolve in the digital age, ")

	// Bold and color applied separately
	withStyle(SpanStyle(color = Color(0xFF1a73e8))) {
		withStyle(DEFAULT.BOLD) {
			append("the importance of thoughtful text formatting cannot be overstated. ")
		}
	}

	append("From simple documents to complex applications, the way we style and present text plays a crucial role in communication effectiveness. ")

	withStyle(DEFAULT.ITALICS) {
		append("The challenge lies in balancing aesthetic appeal with functional requirements, ensuring that our text remains both beautiful and usable across all contexts.")
	}

	// Final styled statement with styles separated
	append("\n\n")
	withStyle(
		SpanStyle(
			fontSize = 18.sp,
			color = Color(0xFF1a73e8),
			letterSpacing = 2.sp,
			textDecoration = TextDecoration.Underline
		)
	) {
		withStyle(DEFAULT.BOLD) {
			append("Thank you for exploring this demonstration of rich text formatting!")
		}
	}
}