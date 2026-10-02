package com.darkrockstudios.texteditor.utils

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

@OptIn(ExperimentalContracts::class)
internal inline fun <T> measureAndReport(message: String, block: () -> T): T {
	contract {
		callsInPlace(block, InvocationKind.EXACTLY_ONCE)
	}
	val value = TimeSource.Monotonic.measureTimedValue(block)
	println("$message: ${value.duration}")

	return value.value
}

/**
 * Builds a string whose span styles [block] adds through `addSpan`: runs of one style
 * that overlap or touch are merged into one span, written where its first run was
 * added, so the styles keep the order they were added in.
 */
internal fun buildAnnotatedStringWithSpans(
	block: AnnotatedString.Builder.(addSpan: (SpanStyle, Int, Int) -> Unit) -> Unit
): AnnotatedString {
	return buildAnnotatedString {
		// Each merged run as start and end (exclusive), in the order first added; a
		// merged-away run is left null.
		val runs = ArrayList<Triple<Int, Int, SpanStyle>?>()
		val runsByStyle = HashMap<SpanStyle, MutableList<Int>>()

		fun addSpan(item: SpanStyle, start: Int, end: Int) {
			val indices = runsByStyle.getOrPut(item) { ArrayList(1) }
			var mergedStart = start
			var mergedEnd = end
			val touching = indices.filter { index ->
				val run = runs[index]!!
				run.first <= end && start <= run.second
			}
			touching.forEach { index ->
				val run = runs[index]!!
				mergedStart = minOf(mergedStart, run.first)
				mergedEnd = maxOf(mergedEnd, run.second)
			}
			val at = touching.firstOrNull()
			if (at == null) {
				indices += runs.size
				runs += Triple(start, end, item)
			} else {
				runs[at] = Triple(mergedStart, mergedEnd, item)
				touching.drop(1).forEach { runs[it] = null }
				indices.removeAll(touching.drop(1).toSet())
			}
		}

		block(::addSpan)
		runs.forEach { run -> run?.let { (start, end, item) -> addStyle(item, start, end) } }
	}
}
