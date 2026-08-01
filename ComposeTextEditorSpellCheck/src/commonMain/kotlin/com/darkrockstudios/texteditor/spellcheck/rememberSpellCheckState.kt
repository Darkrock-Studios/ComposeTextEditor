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
 * Pass a STABLE [spellChecker] instance — `remember` it across recompositions and
 * only create a new one when the underlying dictionary actually changes. A fresh
 * instance every recomposition re-keys the full-rescan effect below and re-scans
 * the whole document on each frame. (Re-scans are cancellation-safe and won't lose
 * spans, but the churn is pure waste.)
 *
 * @param guard Sanity limits that suspend checking when its results stop looking plausible;
 *   see [SpellCheckGuard] and [SpellCheckState.suspension]. A new [spellChecker] clears any
 *   suspension, since swapping the checker is how a wrong-language one gets fixed.
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

	// Run SpellCheck as soon as it is ready. A different checker is a fresh chance for
	// the guard, so `resumeSpellChecking` rather than a plain re-check: the new checker
	// may well be the right-language one the previous suspension was asking for.
	LaunchedEffect(spellChecker) {
		if (spellChecker != null) {
			state.spellChecker = spellChecker
			state.resumeSpellChecking()
		}
	}

	// Propagate enableSpellChecking changes to the state so callers can toggle it on recomposition
	LaunchedEffect(enableSpellChecking) {
		state.setSpellCheckingEnabled(enableSpellChecking)
	}

	return state
}