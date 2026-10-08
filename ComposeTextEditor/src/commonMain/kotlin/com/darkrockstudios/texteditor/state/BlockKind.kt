package com.darkrockstudios.texteditor.state

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import com.darkrockstudios.texteditor.CharLineOffset
import com.darkrockstudios.texteditor.RichTextStyles
import com.darkrockstudios.texteditor.TextEditorRange
import com.darkrockstudios.texteditor.html.HtmlDocument

/**
 * A kind of block that spans lines, as ordered lists, code fences and tables do: the
 * facts a line takes from its neighbours (an item's numeral, a fence line's card edges,
 * a cell's place in its table), how those facts lay the line out, and how edits keep
 * the block's structure. The layout pass walks every kind in [BLOCK_KINDS] down the
 * document together ([LineFacts]), and the edit pipeline asks each in turn, so a kind
 * plugs in here rather than into the passes. Every hook but [walk] has a default for a
 * kind that does not use it. See `docs/design/block-kinds.md`.
 *
 * Facts are values: a line's layout keeps them, compares them to decide whether a
 * later walk changed the line, and resumes the walk from them.
 */
internal interface BlockKind<F : Any> {

	/** A walk deriving each line's facts in line order from [spans]. */
	fun walk(spans: SpanIndex): FactsWalk<F>

	/**
	 * The first line a walk must restart from for the lines from [line] on to be derived
	 * afresh: [line] itself, unless a line before it has facts that depend on it, as a
	 * table row's cells depend on the row's last.
	 */
	fun walkStart(spans: SpanIndex, line: Int): Int = line

	/** Whether a line with facts [now] is shaped differently than with [was]; such a line is shaped again rather than given [now]. */
	fun shapesDifferently(now: F?, was: F?): Boolean = false

	/** [line] shaped for [facts] by [shaper], or null to shape it as any line. */
	fun shape(facts: F, line: AnnotatedString, shaper: LineShaping): TextLayoutResult? = null

	/** Where a line with [facts] whose rows are [textHeight] tall is laid out beside others, or null for the document's flow. */
	fun place(facts: F, inputs: LineInputs, textHeight: Float): LinePlacement? = null

	/**
	 * The space a line with [facts] in the document's flow takes above and below its
	 * rows besides its paragraph spacing, or null for none: room a style draws in, as a
	 * callout's title above its first line.
	 */
	fun spacing(facts: F, inputs: LineInputs): LineSpacing? = null

	/**
	 * How deleting [range] goes so the block keeps its structure: the ranges to delete,
	 * in document order, or null when the kind has no say in it.
	 */
	fun deletionPieces(state: TextEditorState, range: TextEditorRange): List<TextEditorRange>? = null

	/** Repairs [line], into which a deletion piece joined lines, recorded with the deletion. */
	fun afterJoin(state: TextEditorState, line: Int) {}

	/**
	 * Before markup [document] pasted at [landedAt] as [text] places its blocks, makes
	 * room for them, and returns where the paste starts then.
	 */
	fun beforePastedBlocks(state: TextEditorState, document: HtmlDocument, landedAt: CharLineOffset, text: AnnotatedString): CharLineOffset =
		landedAt

	/** Settles [lines], which a paste or drop put in place, recorded with it. */
	fun settlePasted(state: TextEditorState, lines: IntRange) {}

	/**
	 * Repairs the block's invariants on the lines in [changed] of a revision about to be
	 * published, outside the undo history; [spansChanged] when a span came or went (or a
	 * repair before this one changed the revision). See `normalizeLineBlocks`.
	 */
	fun repair(snapshot: DocumentSnapshot, config: RichTextStyles, changed: IntRange, spansChanged: Boolean): DocumentSnapshot = snapshot
}

/** Space above and below a line's rows, in pixels. */
internal data class LineSpacing(val before: Float, val after: Float) {
	companion object {
		val NONE = LineSpacing(0f, 0f)
	}
}

/** A walk deriving a [BlockKind]'s facts, one line after another. */
internal interface FactsWalk<F : Any> {
	/** Continues the walk after a line whose facts were [after]. */
	fun resume(after: F?)

	/** The facts of [line], which must follow the line last given, or start the walk. */
	fun next(line: Int): F?
}

/** What a [BlockKind] shapes a line with: the pass's inputs, and its text measured at a width. */
internal interface LineShaping {
	val inputs: LineInputs

	/** [line] measured wrapping at [width] pixels, under the editor's text style. */
	fun measureWrapped(line: AnnotatedString, width: Int): TextLayoutResult
}

/** The kinds the editor lays out and edits, in the order their hooks are asked. */
internal val BLOCK_KINDS: List<BlockKind<*>> = listOf(OrderedListKind, CodeFenceKind, TableKind, TaskKind)

/**
 * Every [BlockKind]'s facts for one line, by its place in [BLOCK_KINDS], which the
 * kinds' styles read from the line's rows ([com.darkrockstudios.texteditor.LineWrap.blockFacts]).
 * A line with none shares [NONE].
 */
class BlockFacts private constructor(internal val values: Array<Any?>) {

	@Suppress("UNCHECKED_CAST")
	internal operator fun <F : Any> get(kind: BlockKind<F>): F? = values[BLOCK_KINDS.indexOf(kind)] as F?

	/** Runs [action] for each kind with facts on the line. */
	@Suppress("UNCHECKED_CAST")
	internal inline fun forEach(action: (kind: BlockKind<Any>, facts: Any) -> Unit) {
		for (index in values.indices) action(BLOCK_KINDS[index] as BlockKind<Any>, values[index] ?: continue)
	}

	/** The first non-null answer of [hook] over the kinds with facts on the line. */
	internal inline fun <T : Any> firstOf(hook: (kind: BlockKind<Any>, facts: Any) -> T?): T? {
		forEach { kind, facts -> hook(kind, facts)?.let { return it } }
		return null
	}

	/** Whether a line with these facts is shaped differently than one with [was]. */
	@Suppress("UNCHECKED_CAST")
	internal fun shapesDifferentlyThan(was: BlockFacts): Boolean {
		if (this === was) return false
		for (index in values.indices) {
			val kind = BLOCK_KINDS[index] as BlockKind<Any>
			if (kind.shapesDifferently(values[index], was.values[index])) return true
		}
		return false
	}

	/** Whether these are the facts in [values], one per kind. */
	internal fun holds(values: Array<Any?>): Boolean = this.values.contentEquals(values)

	override fun equals(other: Any?): Boolean = other is BlockFacts && values.contentEquals(other.values)

	override fun hashCode(): Int = values.contentHashCode()

	companion object {
		/** No block's facts: a line in no multi-line block. */
		val NONE: BlockFacts = BlockFacts(arrayOfNulls(BLOCK_KINDS.size))

		/** The facts in [values], one per kind; [NONE] when every one is null. */
		internal fun of(values: Array<Any?>): BlockFacts = if (values.all { it == null }) NONE else BlockFacts(values.copyOf())
	}
}
