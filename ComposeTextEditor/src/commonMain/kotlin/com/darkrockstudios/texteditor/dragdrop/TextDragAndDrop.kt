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
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.cursor.calculateCursorPosition
import com.darkrockstudios.texteditor.cursor.caretRect
import com.darkrockstudios.texteditor.cursor.drawCaretRect
import com.darkrockstudios.texteditor.html.selectionAsHtml
import com.darkrockstudios.texteditor.richstyle.BlockSpanStyle
import com.darkrockstudios.texteditor.state.FocusedEditor
import com.darkrockstudios.texteditor.state.PointerHit
import com.darkrockstudios.texteditor.state.TextEditorState
import kotlin.random.Random

/** What the pointer handling hands a drag of the selection. */
internal interface SelectionDrag {
	/**
	 * Starts a platform drag of the selection, [offset] being where the pointer is in the
	 * canvas node; a drag a finger starts ([byFinger]) shows a picture of the text. False
	 * where none started, so the press selects instead.
	 */
	fun start(offset: Offset, byFinger: Boolean = false): Boolean

	/**
	 * A press inside the selection is held. Where the platform starts drags itself (the
	 * web, as the mouse moves), only such a press gives it one.
	 */
	fun holdPress()

	/** The held press ended. True when a drag the platform started took it. */
	fun releasePress(): Boolean

	/**
	 * Whether the platform starts drags itself (the web) rather than on request. Its own
	 * threshold must come before the held press gives up for a selection.
	 */
	val platformStartsDrags: Boolean
}

/**
 * Drag and drop of text for one editor: dragging its selection out (a move, or a copy
 * with the platform's modifier), and dropping text in, its own selection included.
 *
 * The pointer handling starts a drag ([start]) when a mouse press inside the selection
 * moves past the slop, or a finger long-presses inside it, as native editors do. A drop
 * follows the line limit of [editor], the editor it lands on, which need not hold focus.
 */
internal class TextDragAndDrop(
	private val state: TextEditorState,
	private val editor: () -> FocusedEditor? = { null },
) : SelectionDrag {
	/** Read here, as the web's actual installs its listeners when first read. */
	private val dragsText = platformDragsText

	private var pressHeld = false

	/** Whether a drag the platform started took the held press. */
	private var pressDragged = false

	override fun holdPress() {
		pressHeld = true
		pressDragged = false
	}

	override fun releasePress(): Boolean {
		pressHeld = false
		return pressDragged.also { pressDragged = false }
	}

	override val platformStartsDrags: Boolean get() = dragsText && requestTransfer == null

	/** Whether drops edit this editor; a read-only one only lets its text be dragged out as a copy. */
	var enabled: Boolean = true

	/** The editor's text colour, which a finger drag's picture of the text is drawn in. */
	var textColor: Color = Color.Unspecified

	/**
	 * Where a drag over the editor would drop, drawn as a caret while it hovers, with the
	 * row it is drawn on: past a wrapped row's end, that row's end rather than the next
	 * row's start.
	 */
	var dropHit: PointerHit? by mutableStateOf(null)
		private set

	/** Where the drag last hovered, for a drop that reports no position of its own. */
	private var hoverAt: Offset? = null

	private class OutgoingDrag(val id: Long, val range: TextEditorRange, val styled: AnnotatedString) {
		val text: String get() = styled.text

		var droppedHere = false
	}

	private var outgoing: OutgoingDrag? = null

	/** Whether the last [startTransfer] started a drag. */
	private var started = false

	/** Whether [start] is asking the platform for a drag. */
	private var requesting = false

	/** Whether the drag [start] asks for is a finger's. */
	private var byFinger = false

	internal var requestTransfer: ((Offset) -> Unit)? = null

	override fun start(offset: Offset, byFinger: Boolean): Boolean {
		val request = requestTransfer?.takeIf { dragsText } ?: return false
		// The platforms that take a request (desktop, Android) start the drag inside it.
		started = false
		requesting = true
		this.byFinger = byFinger
		try {
			request(offset)
		} finally {
			requesting = false
			this.byFinger = false
		}
		return started
	}

	/** A drag [start] asked for, or one the platform starts itself from a held press. */
	internal fun platformStartsTransfer(scope: DragAndDropStartTransferScope) {
		when {
			requesting -> startTransfer(scope)
			dragsText && pressHeld -> {
				startTransfer(scope)
				pressDragged = started
			}
		}
	}

	internal fun startTransfer(scope: DragAndDropStartTransferScope) {
		val selection = state.selector.selection ?: return
		val text = state.selector.getSelectedText()
		val id = Random.nextLong()
		fun data(html: String?) = textDragTransferData(
			text = text,
			html = html,
			dragId = id,
			styles = state.richTextStyles,
			allowMove = enabled,
			onEnded = ::onSourceEnded,
		)
		val rich = data(state.selectionAsHtml(selection)) ?: return
		val drag = OutgoingDrag(id, selection, text)
		outgoing = drag
		val picture = (if (byFinger) state.dragPicture(text, textColor) else null) ?: POINTER_PICTURE
		// A drag too large to carry its markup to another process (Android's binder
		// limit) still drags its text.
		started = scope.startDragAndDropTransfer(rich, picture.size, picture.draw) ||
				data(null)?.let { scope.startDragAndDropTransfer(it, picture.size, picture.draw) } == true
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

	internal fun hover(positionInRoot: Offset?) {
		hoverAt = positionInRoot
		dropHit = positionInRoot?.let(::hitAt)
		if (dropHit != null) DropCarets.shown(this) else DropCarets.hidden(this)
	}

	internal fun endHover() {
		hoverAt = null
		dropHit = null
		DropCarets.hidden(this)
	}

	internal fun drop(event: DragAndDropEvent, positionInRoot: Offset?, target: DelegatableNode?): Boolean {
		val hit = positionInRoot?.let(::hitAt) ?: dropHit ?: return false
		val pointer = positionInRoot ?: hoverAt
		endHover()
		val dragId = event.dragId()
		val content = event.droppedText(
			state.richTextStyles,
			state.allowedLinkSchemes,
			ownDrag = dragId != null && dragId == outgoing?.id,
			target = target,
		) ?: return false
		val revision = state.revision
		val at = {
			// A behavior's edit of the composition moved the text under the pointer.
			if (state.revision == revision) hit.position else pointer?.let(::hitAt)?.position
		}
		return dropAt(at, content, dragId, event.requestsCopy())
	}

	/**
	 * Drops [content] where [at] reads. The pointer owns the caret, as for a tap: a
	 * composition is finished before [at] is read, so the behaviors' edit of it lands
	 * first and the drop goes where the pointer is on the substituted text. A drag of
	 * this editor's own text ([dragId]) takes what its markup cannot carry from its
	 * source, which it still holds, as a paste takes them from the copy: the text as it
	 * was styled (markup has no font size) and its rich spans.
	 */
	internal fun dropAt(at: () -> CharLineOffset?, content: DroppedText, dragId: Long?, copy: Boolean): Boolean =
		state.asEditor(editor()) {
			state.finishCompositionBeforeInsert()
			val position = at() ?: return@asEditor false
			dropHere(position, content, dragId, copy)
		}

	private fun dropHere(at: CharLineOffset, content: DroppedText, dragId: Long?, copy: Boolean): Boolean {
		val ours = outgoing?.takeIf { it.id == dragId }
		ours?.droppedHere = true
		val source = ours?.takeIf { state.holds(it.range, it.text) }
		val moveFrom = source?.takeIf { !copy }?.range
		// A move onto its own text or either edge is taken, and changes nothing.
		if (moveFrom != null && at >= moveFrom.start && at <= moveFrom.end) return true
		// A copy of whole lines carries their markers and formats; a drop leaves them to
		// the markup, which restores them.
		val richSpans = source?.let { state.preservedRichSpans(it.range) }
			?.filter { !it.style.stickyAtStart && it.style !is BlockSpanStyle }
		// The text as it was dragged, which the markup does not carry exactly (it has no font size).
		val text = source?.styled ?: content.text
		// Refused, the drop is not taken, so a move leaves its source where it was.
		return state.dropText(
			text, content.html, at, moveFrom, whole = !copy, richSpans, content.document, asItWas = source != null,
		) != null
	}

	private fun hitAt(positionInRoot: Offset): PointerHit? {
		val canvas = state.canvasPositionInRoot
		if (canvas == Offset.Unspecified) return null
		return state.pointerHitAt(positionInRoot - canvas)
	}

	/** Whether [range] still holds [text]: the drag's source is as it was dragged. */
	private fun TextEditorState.holds(range: TextEditorRange, text: String): Boolean {
		val lines = textLines
		if (range.end.line > lines.lastIndex) return false
		if (range.start.char > lines[range.start.line].length || range.end.char > lines[range.end.line].length) return false
		return getStringInRange(range) == text
	}

	private companion object {
		/** A pointer's drag shows the platform's cursor, not a picture of the text. */
		val POINTER_PICTURE = DragPicture(Size(1f, 1f)) {}
	}
}

/** Draws the drop caret while a drag of text hovers over the editor. */
internal fun DrawScope.DrawDropCaret(dragAndDrop: TextDragAndDrop, state: TextEditorState, color: Color, width: Dp) {
	val hit = dragAndDrop.dropHit ?: return
	drawCaretRect(state, state.caretRect(state.calculateCursorPosition(hit.position, hit.affinity), width.toPx(), size.width), color)
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
			if (isAttached) value.requestTransfer = transferRequest()
		}

	private val target = object : DragAndDropTarget {
		override fun onEntered(event: DragAndDropEvent) = dragAndDrop.hover(event.pointerInRoot(requireDensity()))
		override fun onMoved(event: DragAndDropEvent) = dragAndDrop.hover(event.pointerInRoot(requireDensity()))
		override fun onExited(event: DragAndDropEvent) = dragAndDrop.endHover()
		override fun onEnded(event: DragAndDropEvent) = dragAndDrop.endHover()
		override fun onDrop(event: DragAndDropEvent): Boolean =
			dragAndDrop.drop(event, event.pointerInRoot(requireDensity()), this@TextDragAndDropNode)
	}

	private val source = delegate(DragAndDropSourceModifierNode { _ -> dragAndDrop.platformStartsTransfer(this) })

	private val targetNode = delegate(DragAndDropTargetModifierNode({ dragAndDrop.accepts(it) }, target))

	override fun onAttach() {
		dragAndDrop.requestTransfer = transferRequest()
	}

	/** Null where the platform starts drags itself (web, iOS) and refuses a request. */
	private fun transferRequest(): ((Offset) -> Unit)? =
		source.takeIf { it.isRequestDragAndDropTransferRequired }?.let { it::requestDragAndDropTransfer }

	override fun onDetach() {
		dragAndDrop.requestTransfer = null
		dragAndDrop.endHover()
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
