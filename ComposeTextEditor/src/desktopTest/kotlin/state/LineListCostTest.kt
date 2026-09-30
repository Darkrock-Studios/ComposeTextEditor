package state

import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.state.LineList
import com.darkrockstudios.texteditor.state.MAX_CHUNK_SIZE
import com.darkrockstudios.texteditor.state.MIN_CHUNK_SIZE
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The line list is chunked, so an edit copies the chunks it touches and the chunk
 * directory, never every line, and the flat character index of a line is a prefix
 * total rather than a table rebuilt per revision.
 */
class LineListCostTest {

	private fun lines(count: Int, from: Int = 0) = List(count) { AnnotatedString("line ${from + it}") }

	private fun LineList.assertChunkSizes() {
		if (size < MIN_CHUNK_SIZE) {
			assertTrue(chunks.size <= 1, "a document under $MIN_CHUNK_SIZE lines is one chunk, got ${chunks.size}")
			return
		}
		chunks.forEach { chunk ->
			assertTrue(chunk.size in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE, "chunk of ${chunk.size} lines in $size")
		}
	}

	private fun LineList.assertMatches(expected: List<AnnotatedString>) {
		assertEquals(expected.size, size)
		assertEquals(expected, toList())
		var offset = 0
		for (line in expected.indices) {
			assertEquals(offset, charStart(line), "start of line $line")
			assertEquals(line, lineOf(offset), "line at $offset")
			if (expected[line].length > 0) assertEquals(line, lineOf(offset + expected[line].length), "line at the end of $line")
			offset += expected[line].length + 1
		}
		assertEquals(offset, charStart(expected.size))
		assertEquals(maxOf(0, offset - 1), textLength)
	}

	@Test
	fun `chunks hold between the minimum and the maximum`() {
		for (count in listOf(0, 1, 31, 32, 33, 63, 64, 65, 100, 129, 2_000)) {
			val list = LineList.of(lines(count))
			list.assertChunkSizes()
			list.assertMatches(lines(count))
		}
	}

	@Test
	fun `wrapping a line list returns it`() {
		val list = LineList.of(lines(100))
		assertSame(list, LineList.of(list))
	}

	@Test
	fun `a one-line edit copies at most two chunks and shares the rest`() {
		val old = LineList.of(lines(2_000))
		val edited = old.splice(1_000, 1_001, listOf(AnnotatedString("edited")))

		val shared = edited.chunks.count { chunk -> old.chunks.any { it === chunk } }
		assertTrue(edited.chunks.size - shared <= 2, "a keystroke rebuilt ${edited.chunks.size - shared} chunks")
		assertTrue(shared >= old.chunks.size - 2, "a keystroke dropped ${old.chunks.size - shared} of the old chunks")
		assertEquals("edited", edited[1_000].text)
		assertEquals(old.charStart(1_001) - "line 1000".length + "edited".length, edited.charStart(1_001))
		edited.assertChunkSizes()
	}

	@Test
	fun `a splice copies only the touched region`() {
		val old = LineList.of(lines(2_000))
		val pasted = lines(10, from = 5_000)
		val spliced = old.splice(700, 703, pasted)

		val copied = spliced.chunks.filter { chunk -> old.chunks.none { it === chunk } }.sumOf { it.size }
		assertTrue(copied <= 2 * MAX_CHUNK_SIZE + pasted.size, "a paste of ${pasted.size} lines copied $copied lines")
		spliced.assertMatches(lines(700) + pasted + lines(2_000 - 703, from = 703))
	}

	@Test
	fun `splices agree with a plain list under random edits`() {
		val random = Random(11)
		var expected = lines(90)
		var list = LineList.of(expected)
		repeat(400) { step ->
			val from = random.nextInt(expected.size + 1)
			val to = from + random.nextInt(minOf(expected.size - from, 70) + 1)
			val replacement = lines(random.nextInt(if (random.nextInt(4) == 0) 150 else 3), from = 10_000 + step * 200)
			expected = expected.subList(0, from) + replacement + expected.subList(to, expected.size)
			list = list.splice(from, to, replacement)
			list.assertChunkSizes()
			list.assertMatches(expected)
		}
	}

	@Test
	fun `emptying the list and refilling it`() {
		val list = LineList.of(lines(100)).splice(0, 100, emptyList())
		assertEquals(0, list.size)
		assertEquals(0, list.textLength)
		val refilled = list.splice(0, 0, lines(3))
		refilled.assertMatches(lines(3))
	}
}
