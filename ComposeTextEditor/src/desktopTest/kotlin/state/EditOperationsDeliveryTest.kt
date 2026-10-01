package state

import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Consumers such as spell check act on each edit, so none may be lost when several
 * commit before a collector next runs.
 */
class EditOperationsDeliveryTest {

	@Test
	fun `a collector sees every edit in a burst`() = runTest {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		val seen = mutableListOf<TextEditOperation>()
		val collector = launch { state.editOperations.collect { seen += it } }
		runCurrent()

		repeat(3) { state.insertStringAtCursor("x") }
		runCurrent()

		assertEquals(3, seen.size)
		collector.cancel()
	}

	@Test
	fun `edits that land before a collector runs come as one burst`() = runTest {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		state.insertStringAtCursor("before")
		val bursts = mutableListOf<List<TextEditOperation>>()
		val collector = launch { state.editOperationBursts.collect { bursts += it } }
		runCurrent()

		repeat(2) { state.insertStringAtCursor("x") }
		runCurrent()
		state.insertStringAtCursor("y")
		runCurrent()

		assertEquals(listOf(2, 1), bursts.map { it.size })
		collector.cancel()
	}

	@Test
	fun `a collector run as a group's first edit is announced waits for the rest`() = runTest(UnconfinedTestDispatcher()) {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		val bursts = mutableListOf<Pair<Int, String>>()
		val collector = launch { state.editOperationBursts.collect { bursts += it.size to state.getAllText().text } }

		state.editGroup {
			state.insertStringAtCursor("a")
			state.insertStringAtCursor("b")
		}
		state.insertStringAtCursor("c")

		assertEquals(listOf(2 to "ab", 1 to "abc"), bursts)
		collector.cancel()
	}

	@Test
	fun `a group that throws leaves no burst waiting for its edits`() = runTest(UnconfinedTestDispatcher()) {
		val state = TextEditorState(scope = TestScope(), measurer = mockk(relaxed = true))
		val bursts = mutableListOf<Int>()
		val collector = launch { state.editOperationBursts.collect { bursts += it.size } }

		runCatching {
			state.editGroup {
				state.insertStringAtCursor("a")
				error("refused")
			}
		}
		state.insertStringAtCursor("c")

		assertEquals(listOf(1), bursts)
		collector.cancel()
	}
}
