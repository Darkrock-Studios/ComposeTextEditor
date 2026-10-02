package input

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays every keyboard trace in `resources/keyboard-traces`. A trace that
 * parts from the editor fails with each place it does; `docs/TESTING.md` ("Keyboard
 * traces") says how to add one.
 */
class KeyboardTraceCorpusTest {

	private fun corpus(): List<File> {
		val directory = javaClass.classLoader!!.getResource("keyboard-traces")?.toURI()?.let(::File)
		return directory?.listFiles { file -> file.extension == "trace" }?.sortedBy { it.name }.orEmpty()
	}

	@Test
	fun `the corpus is found`() {
		assertTrue(corpus().any { it.name == "gboard-japanese-930.trace" }, corpus().toString())
	}

	@Test
	fun `every trace in the corpus replays as recorded`() {
		val failures = corpus().flatMap { trace ->
			KeyboardTraceReplayer(trace.readText()).replay().map { "${trace.name} $it" }
		}
		assertEquals(emptyList(), failures, failures.joinToString("\n"))
	}
}
