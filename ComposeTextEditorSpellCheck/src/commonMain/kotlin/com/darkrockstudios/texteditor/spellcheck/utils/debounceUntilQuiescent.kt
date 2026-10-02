package com.darkrockstudios.texteditor.spellcheck.utils

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration

internal fun <T> Flow<T>.debounceUntilQuiescent(duration: Duration): Flow<T> = channelFlow {
	var job: Job? = null
	collect { value ->
		job?.cancel()
		job = launch {
			delay(duration)
			send(value)
			job = null
		}
	}
}

/**
 * The values of each burst that [duration] of quiet ends, with what [read] returned as the
 * burst's last value arrived. A collector still busy with an earlier burst takes it later, by
 * when more may have happened; and a value can arrive after what it reports already happened,
 * so reading as the quiet ends could take in a change of the next burst.
 */
internal fun <T, S> Flow<T>.debounceUntilQuiescentWithBatch(
	duration: Duration,
	read: () -> S,
): Flow<Pair<List<T>, S>> = channelFlow {
	val operations = mutableListOf<T>()
	var job: Job? = null

	collect { value ->
		operations.add(value)
		val state = read()
		job?.cancel()
		job = launch {
			delay(duration)
			val batch = operations.toList() to state
			operations.clear()
			job = null
			send(batch)
		}
	}
}
