package com.darkrockstudios.texteditor.state

/**
 * The facts a line's neighbours decide, walked in line order: every [BlockKind]'s walk
 * at once. A walk starts at the document or [resume]s from the facts a [LineLayout]
 * kept.
 */
internal class LineFacts(spans: SpanIndex) {
	@Suppress("UNCHECKED_CAST")
	private val walks = BLOCK_KINDS.map { it.walk(spans) as FactsWalk<Any> }
	private val values = arrayOfNulls<Any>(walks.size)

	/** The facts of the line [next] was last given. */
	var facts: BlockFacts = BlockFacts.NONE
		private set

	/** Continues the walk after a line laid out as [after]. */
	fun resume(after: LineLayout) {
		@Suppress("UNCHECKED_CAST")
		walks.forEachIndexed { index, walk -> walk.resume(after.facts[BLOCK_KINDS[index] as BlockKind<Any>]) }
	}

	/**
	 * Derives the facts of [line], which must follow the line last given, or start the
	 * walk; [was], the facts the line had, when they are the same, so a walk past
	 * unchanged lines allocates none.
	 */
	fun next(line: Int, was: BlockFacts? = null) {
		walks.forEachIndexed { index, walk -> values[index] = walk.next(line) }
		facts = if (was != null && was.holds(values)) was else BlockFacts.of(values)
	}
}
