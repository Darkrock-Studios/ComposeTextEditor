package com.darkrockstudios.texteditor.spellcheck.utils

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class DebounceUntilQuiescentTest {
	@Test
	fun `a batch is paired with what was read as its last value arrived`() = runTest {
		var text = 0
		val source = MutableSharedFlow<Int>(extraBufferCapacity = 8)
		val batches = Channel<Pair<List<Int>, Int>>(Channel.UNLIMITED)
		val collector = launch {
			source.debounceUntilQuiescentWithBatch(500.milliseconds) { text }.collect { batches.send(it) }
		}
		runCurrent()

		text = 1
		source.emit(1)
		runCurrent()
		// An edit committed, its value not yet delivered when the quiet period ends.
		text = 2
		advanceTimeBy(600)
		runCurrent()

		assertEquals(listOf(1) to 1, batches.tryReceive().getOrNull())
		collector.cancel()
	}
}
