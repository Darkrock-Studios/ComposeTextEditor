package state

import com.darkrockstudios.texteditor.state.TextEditOperation
import com.darkrockstudios.texteditor.state.TextEditorState
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
}
