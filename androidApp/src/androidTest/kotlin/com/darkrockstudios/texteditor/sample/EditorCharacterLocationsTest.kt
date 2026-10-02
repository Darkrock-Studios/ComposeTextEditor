package com.darkrockstudios.texteditor.sample

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX
import android.view.accessibility.AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.darkrockstudios.texteditor.BasicTextEditor
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.state.TextEditorState
import com.darkrockstudios.texteditor.state.rememberTextEditorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The character locations an accessibility service asks the editor for, through
 * the platform's own request on a real device: the glyphs as drawn, past the content
 * padding and less the scroll, where Compose alone answers from a layout that has
 * neither. The editor's other semantics still answer through the wrapped delegate.
 */
@RunWith(AndroidJUnit4::class)
class EditorCharacterLocationsTest {
	@get:Rule
	val compose = createAndroidComposeRule<ComponentActivity>()

	private lateinit var state: TextEditorState
	private val document = (1..60).joinToString("\n") { "Line number $it of the document" }

	private fun composeEditor(paddingStart: Int, paddingTop: Int) {
		compose.setContent {
			state = rememberTextEditorState(initialText = AnnotatedString(document))
			BasicTextEditor(
				state = state,
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(start = paddingStart.dp, top = paddingTop.dp),
				autoFocus = false,
			)
		}
		compose.waitForIdle()
	}

	private fun editorNode(): AccessibilityNodeInfo {
		val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
		var found: AccessibilityNodeInfo? = null
		compose.waitUntil(timeoutMillis = 10_000) {
			found = automation.rootInActiveWindow?.let(::findEditable)
			found != null
		}
		return found!!
	}

	private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
		if (node.isEditable) return node
		for (i in 0 until node.childCount) {
			node.getChild(i)?.let(::findEditable)?.let { return it }
		}
		return null
	}

	private fun locations(node: AccessibilityNodeInfo, start: Int, length: Int): Array<RectF?> {
		val args = Bundle().apply {
			putInt(EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
			putInt(EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
		}
		assertTrue(node.refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args))
		// The typed getter is API 33.
		@Suppress("DEPRECATION")
		val boxes = checkNotNull(node.extras.getParcelableArray(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)) { "no character locations" }
		return Array(boxes.size) { boxes[it] as RectF? }
	}

	@Test
	fun characterLocationsAreTheDrawnGlyphs() {
		val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
		composeEditor(paddingStart = 24, paddingTop = 16)
		val scroll = compose.runOnIdle {
			state.scrollState.scrollTo((state.lineOffsets.first { it.line == 20 }.offset.y - 10f).roundToInt())
			state.scrollState.value
		}
		compose.waitForIdle()
		val node = editorNode()
		assertTrue(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY in node.availableExtraData)
		val nodeOnScreen = Rect().also(node::getBoundsInScreen)

		val line = 21
		val start = compose.runOnIdle { state.getCharacterIndex(CharLineOffset(line, 0)) }
		val boxes = locations(node, start, 6)
		assertEquals(6, boxes.size)
		compose.runOnIdle {
			val row = state.lineOffsets.first { it.line == line }
			for (i in 0 until 6) {
				val glyph = row.textLayoutResult.getBoundingBox(i)
				val left = nodeOnScreen.left + (24 * density).roundToInt() + glyph.left
				val top = nodeOnScreen.top - scroll + row.paragraphTop + glyph.top
				val box = checkNotNull(boxes[i]) { "character $i has no box" }
				assertTrue("left of $i: ${box.left}, drawn at $left", abs(box.left - left) < 1f)
				assertTrue("top of $i: ${box.top}, drawn at $top", abs(box.top - top) < 1f)
				assertTrue("width of $i", abs(box.width() - glyph.width) < 1f)
			}
		}

		// The first line is scrolled out of view, so it has no visible box.
		assertTrue(locations(node, 0, 4).all { it == null })
		// Past the text, nothing.
		assertNull(locations(node, document.length - 1, 3)[2])
	}

	@Test
	fun theEditorsOtherSemanticsStillAnswer() {
		composeEditor(paddingStart = 0, paddingTop = 0)
		val node = editorNode()
		assertEquals(document, node.text.toString())
		val select = Bundle().apply {
			putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 5)
			putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 11)
		}
		assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, select))
		compose.waitUntil(timeoutMillis = 5_000) { state.selector.getSelectedText().text == "number" }
		// A line move reads Compose's own iterator over getTextLayoutResult's layout: from
		// inside the first line, the next line is the second.
		val byLine = Bundle().apply {
			putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE)
		}
		assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, byLine))
		val secondLineEnd = document.indexOf('\n', document.indexOf('\n') + 1)
		compose.waitUntil(timeoutMillis = 5_000) {
			!state.selector.hasSelection() && state.getCharacterIndex(state.cursorPosition) in secondLineEnd..secondLineEnd + 1
		}
		val setText = Bundle().apply {
			putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Replaced")
		}
		assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText))
		compose.waitUntil(timeoutMillis = 5_000) { state.getAllText().text == "Replaced" }
	}
}
