package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import com.darkrockstudios.texteditor.spellcheck.api.EditorSpellChecker
import com.darkrockstudios.texteditor.state.rememberTextEditorState

/**
 * Remembers a [SpellCheckState] for the editor.
 *
 * Pass a STABLE [spellChecker] instance: `remember` it across recompositions and
 * only create a new one when the underlying dictionary actually changes. A fresh
 * instance every recomposition re-keys the full-rescan effect below and re-scans
 * the whole document on each frame. Re-scans are cancellation-safe and won't lose
 * spans, but the churn is pure waste, and it also clears any guard suspension each
 * frame, defeating the suspension's stickiness.
 *
 * @param guard Sanity limits that suspend checking when its results stop looking plausible;
 *   see [SpellCheckGuard] and [SpellCheckState.suspension]. Changing [guard] or [spellChecker]
 *   on recomposition clears any standing suspension, since swapping the checker (or loosening
 *   the limits) is how a wrong-language suspension gets fixed.
 */
@Composable
fun rememberSpellCheckState(
	spellChecker: EditorSpellChecker?,
	initialText: AnnotatedString? = null,
	enableSpellChecking: Boolean = true,
	spellCheckMode: SpellCheckMode = SpellCheckMode.Word,
	guard: SpellCheckGuard = SpellCheckGuard.Default,
): SpellCheckState {
	val richTextState = rememberTextEditorState(initialText)
	val state = remember {
		SpellCheckState(richTextState, spellChecker, enableSpellChecking, spellCheckMode, guard)
	}

	// Run SpellCheck as soon as it is ready. A full check clears any guard suspension,
	// so a replacement checker gets a fresh chance: it may well be the right-language
	// one the previous suspension was asking for.
	LaunchedEffect(spellChecker) {
		if (spellChecker != null) {
			state.spellChecker = spellChecker
			state.runFullSpellCheck()
		}
	}

	// Propagate guard changes to the remembered state. A laxer guard can make a
	// standing suspension obsolete, so re-check under the new limits.
	LaunchedEffect(guard) {
		if (state.guard != guard) {
			state.guard = guard
			if (state.suspension != null) {
				state.resumeSpellChecking()
			}
		}
	}

	// Propagate enableSpellChecking changes to the state so callers can toggle it on
	// recomposition. The initial value was applied by the constructor; skipping it here
	// keeps this from re-running the full check the checker effect already started.
	LaunchedEffect(enableSpellChecking) {
		if (state.spellCheckingEnabled != enableSpellChecking) {
			state.setSpellCheckingEnabled(enableSpellChecking)
		}
	}

	return state
}