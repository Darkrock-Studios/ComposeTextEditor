package com.darkrockstudios.texteditor.dragdrop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropSourceModifierNode
import androidx.compose.ui.draganddrop.DragAndDropStartTransferScope
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTargetModifierNode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.cursor.caretRect
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.random.Random

/**
 * Drag and drop of text for one editor: dragging its selection out (a move, or a copy
 * with the platform's modifier), and dropping text in, its own selection included.
 *
 * The pointer handling starts a drag ([startSelectionDrag]) when a mouse press inside
 * the selection moves past the slop, as native editors do.
 */
internal class TextDragAndDrop(private val state: TextEditorState) {
	/** Whether drops edit this editor; a read-only one only lets its text be dragged out as a copy. */
	var enabled: Boolean = true

	/** Where a drag over the editor would drop, drawn as a caret while it hovers. */
	var dropPosition: CharLineOffset? by mutableStateOf(null)
		private set

	private class OutgoingDrag(val id: Long, val range: TextEditorRange, val text: String) {
		var droppedHere = false
	}

	private var outgoing: OutgoingDrag? = null

	internal var requestTransfer: ((Offset) -> Unit)? = null

	/**
	 * Starts a platform drag of the selection, [offset] being where the pointer is in the
	 * canvas node. False where this platform cannot drag text, so the press selects instead.
	 */
	fun startSelectionDrag(offset: Offset): Boolean {
		val request = requestTransfer?.takeIf { platformDragsText } ?: return false
		request(offset)
		return true
	}

	internal fun startTransfer(scope: DragAndDropStartTransferScope) {
		val selection = state.selector.selection ?: return
		val text = state.selector.getSelectedText()
		val id = Random.nextLong()
		val data = textDragTransferData(
			text = text,
			html = state.selectionAsHtml(selection),
			dragId = id,
			styles = state.richTextStyles,
			allowMove = enabled,
			onEnded = ::onSourceEnded,
		) ?: return
		val drag = OutgoingDrag(id, selection, text.text)
		outgoing = drag
		val started = scope.startDragAndDropTransfer(data, DECORATION_SIZE) {}
		if (!started && outgoing === drag) outgoing = null
	}

	/** A move dropped somewhere else takes the text from here; one dropped here already did. */
	private fun onSourceEnded(moved: Boolean) {
		val drag = outgoing ?: return
		outgoing = null
		if (!moved || drag.droppedHere || !enabled) return
		if (state.holds(drag.range, drag.text)) state.delete(drag.range)
	}

	internal fun accepts(event: DragAndDropEvent): Boolean = enabled && event.carriesText()

	internal fun hover(event: DragAndDropEvent, positionInRoot: Offset?) {
		dropPosition = positionInRoot?.let(::offsetAt)
	}

	internal fun endHover() {
		dropPosition = null
	}

	internal fun drop(event: DragAndDropEvent, positionInRoot: Offset?): Boolean {
		val at = positionInRoot?.let(::offsetAt) ?: dropPosition ?: return false
		dropPosition = null
		val content = event.droppedText(state.richTextStyles) ?: return false
		return dropAt(at, content, event.dragId(), event.requestsCopy())
	}

	/**
	 * Drops [content] at [at]. A drag of this editor's own text ([dragId]) takes the rich
	 * spans its markup cannot carry from its source, which it still holds, as a paste
	 * takes them from the copy.
	 */
	internal fun dropAt(at: CharLineOffset, content: DroppedText, dragId: Long?, copy: Boolean): Boolean {
		val ours = outgoing?.takeIf { it.id == dragId }
		ours?.droppedHere = true
		val source = ours?.takeIf { state.holds(it.range, it.text) }
		val moveFrom = source?.takeIf { !copy }?.range
		// A copy of whole lines carries their markers and formats; a drop leaves them to
		// the markup, which restores them.
		val richSpans = source?.takeIf { content.text.text == it.text }
			?.let { state.preservedRichSpans(it.range) }
			?.filter { !it.style.stickyAtStart && it.style !is BlockSpanStyle }
		// Refused, the drop is not taken, so a move leaves its source where it was.
		return state.dropText(content.text, content.html, at, moveFrom, whole = !copy, richSpans) != null
	}

	private fun offsetAt(positionInRoot: Offset): CharLineOffset? {
		val canvas = state.canvasPositionInRoot
		if (canvas == Offset.Unspecified) return null
		return state.getOffsetAtPosition(positionInRoot - canvas)
	}

	/** Whether [range] still holds [text]: the drag's source is as it was dragged. */
	private fun TextEditorState.holds(range: TextEditorRange, text: String): Boolean {
		val lines = textLines
		if (range.end.line > lines.lastIndex) return false
		if (range.start.char > lines[range.start.line].length || range.end.char > lines[range.end.line].length) return false
		return getStringInRange(range) == text
	}

	private companion object {
		/** The drag shows the platform's cursor, not a picture of the text. */
		val DECORATION_SIZE = Size(1f, 1f)
	}
}

/** Draws the drop caret while a drag of text hovers over the editor. */
internal fun DrawScope.DrawDropCaret(dragAndDrop: TextDragAndDrop, state: TextEditorState, color: Color, width: Dp) {
	val position = dragAndDrop.dropPosition ?: return
	val rect = caretRect(state.getPositionForOffset(position), width.toPx(), size.width)
	if (rect.bottom >= 0f && rect.top <= size.height) {
		drawRect(color = color, topLeft = rect.topLeft, size = rect.size)
	}
}

/** Makes the node this sits on the source and the target of [dragAndDrop]'s drags. */
internal fun Modifier.textDragAndDrop(dragAndDrop: TextDragAndDrop): Modifier =
	this then TextDragAndDropElement(dragAndDrop)

private data class TextDragAndDropElement(val dragAndDrop: TextDragAndDrop) :
	ModifierNodeElement<TextDragAndDropNode>() {
	override fun create() = TextDragAndDropNode(dragAndDrop)
	override fun update(node: TextDragAndDropNode) {
		node.dragAndDrop = dragAndDrop
	}
}

private class TextDragAndDropNode(dragAndDrop: TextDragAndDrop) : DelegatingNode(), LayoutAwareModifierNode {

	var dragAndDrop: TextDragAndDrop = dragAndDrop
		set(value) {
			if (value === field) return
			field.requestTransfer = null
			field = value
			if (isAttached) value.requestTransfer = source::requestDragAndDropTransfer
		}

	private val target = object : DragAndDropTarget {
		override fun onEntered(event: DragAndDropEvent) = dragAndDrop.hover(event, event.pointerInRoot(requireDensity()))
		override fun onMoved(event: DragAndDropEvent) = dragAndDrop.hover(event, event.pointerInRoot(requireDensity()))
		override fun onExited(event: DragAndDropEvent) = dragAndDrop.endHover()
		override fun onEnded(event: DragAndDropEvent) = dragAndDrop.endHover()
		override fun onDrop(event: DragAndDropEvent): Boolean =
			dragAndDrop.drop(event, event.pointerInRoot(requireDensity()))
	}

	private val source = delegate(DragAndDropSourceModifierNode { _ -> dragAndDrop.startTransfer(this) })

	private val targetNode = delegate(DragAndDropTargetModifierNode({ dragAndDrop.accepts(it) }, target))

	override fun onAttach() {
		dragAndDrop.requestTransfer = source::requestDragAndDropTransfer
	}

	override fun onDetach() {
		dragAndDrop.requestTransfer = null
	}

	override fun onPlaced(coordinates: LayoutCoordinates) {
		source.onPlaced(coordinates)
		targetNode.onPlaced(coordinates)
	}

	override fun onRemeasured(size: IntSize) {
		source.onRemeasured(size)
		targetNode.onRemeasured(size)
	}
}
