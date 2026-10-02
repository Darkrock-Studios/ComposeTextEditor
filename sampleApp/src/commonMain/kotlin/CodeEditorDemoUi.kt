package com.darkrockstudios.texteditor.sample

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import codeeditor.CodeEditor
import codeeditor.SyntaxHighlighting
import codeeditor.displayName
import codeeditor.rememberCodeEditorStyle
import codeeditor.syntaxLanguages
import codeeditor.syntaxTheme
import com.darkrockstudios.texteditor.annotatedstring.toAnnotatedString
import com.darkrockstudios.texteditor.decoration.DecorationLayer
import com.darkrockstudios.texteditor.input.KeyboardSettings
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import dev.snipme.highlights.model.SyntaxLanguage

@Composable
fun CodeEditorDemoUi(
	modifier: Modifier = Modifier,
	navigateTo: (Destination) -> Unit,
	isDarkMode: Boolean = false,
) {
	val style = rememberCodeEditorStyle(
		placeholderText = "Enter code here",
	)
	val state: TextEditorState =
		rememberTextEditorState(SAMPLE_CODE.toAnnotatedString(FontFamily.Monospace))
	val syntax = remember { DecorationLayer("syntax") }
	var language by remember { mutableStateOf(SyntaxLanguage.KOTLIN) }
	var highlight by remember { mutableStateOf(true) }
	var softWrap by remember { mutableStateOf(false) }
	var large by remember { mutableStateOf(false) }

	LaunchedEffect(state) {
		state.keyboardSettings = KeyboardSettings(capitalization = KeyboardCapitalization.None, autoCorrect = false)
	}

	LaunchedEffect(Unit) {
		state.editOperations.collect { operation ->
			println("Applying Operation: $operation")
		}
	}

	SyntaxHighlighting(state, syntax, language, syntaxTheme(isDarkMode), enabled = highlight)

	Column(modifier = modifier) {
		Row {
			Text(
				"Code Editor",
				modifier = Modifier.padding(8.dp),
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold
			)
			Spacer(modifier = Modifier.weight(1f))
			Button(onClick = { navigateTo(Destination.Menu) }) {
				Text("X")
			}
		}

		Row(
			modifier = Modifier.horizontalScroll(rememberScrollState()),
			verticalAlignment = Alignment.CenterVertically,
		) {
			LanguagePicker(language) { language = it }
			LabeledSwitch("Highlight", highlight) { highlight = it }
			LabeledSwitch("Soft wrap", softWrap) { softWrap = it }
			TextButton(onClick = {
				large = !large
				state.setText((if (large) largeSample() else SAMPLE_CODE).toAnnotatedString(FontFamily.Monospace))
			}) {
				Text(if (large) "Short sample" else "5,000 lines")
			}
		}

		CodeEditor(
			state = state,
			modifier = Modifier.fillMaxSize(),
			style = style,
			softWrap = softWrap,
		)
	}
}

@Composable
private fun LanguagePicker(language: SyntaxLanguage, onPick: (SyntaxLanguage) -> Unit) {
	var open by remember { mutableStateOf(false) }
	Box(modifier = Modifier.padding(horizontal = 8.dp)) {
		OutlinedButton(onClick = { open = true }) {
			Text(language.displayName())
		}
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			syntaxLanguages.forEach { option ->
				DropdownMenuItem(
					text = { Text(option.displayName()) },
					onClick = {
						onPick(option)
						open = false
					},
				)
			}
		}
	}
}

/** The sample's declarations after its imports repeated, renamed, to about 5,000 lines. */
private fun largeSample(): String {
	val declarations = SAMPLE_CODE.indexOf("/**")
	val body = SAMPLE_CODE.substring(declarations).trimEnd()
	val lines = body.count { it == '\n' } + 1
	return buildString {
		append(SAMPLE_CODE.substring(0, declarations).trimEnd())
		var copy = 0
		while (copy * lines < 5_000) {
			append("\n\n")
			append(listOf("Inventory", "Item", "Sku", "fun main").fold(body) { text, name -> text.replace(name, "$name$copy") })
			copy++
		}
	}
}

private val SAMPLE_CODE = """
package com.example.inventory

import kotlin.math.roundToInt

/**
 * A shelf of items, counted and priced.
 * Multi-line comments, strings and numbers each get a colour.
 */
@JvmInline
value class Sku(val code: String)

data class Item(val sku: Sku, val name: String, val price: Double, val count: Int = 0)

class Inventory(private val items: MutableList<Item> = mutableListOf()) {
    fun add(item: Item): Boolean = items.add(item)

    // Everything on the shelf, at its price.
    fun total(): Double = items.sumOf { it.price * it.count }

    fun report(): String {
        val lines = items.map { "${'$'}{it.name}: ${'$'}{it.count} at ${'$'}{it.price}" }
        return lines.joinToString(separator = "\n")
    }
}

fun main() {
    val inventory = Inventory()
    inventory.add(Item(Sku("A-100"), "Kettle", 24.99, count = 3))
    inventory.add(Item(Sku("B-200"), "Teapot 🫖", 18.5, count = 2))
    for (i in 1..3) {
        println("Pass ${'$'}i: total ${'$'}{inventory.total().roundToInt()}")
    }
    if (inventory.total() > 100.0) println(inventory.report()) else println("Low stock")
}
""".trim()
