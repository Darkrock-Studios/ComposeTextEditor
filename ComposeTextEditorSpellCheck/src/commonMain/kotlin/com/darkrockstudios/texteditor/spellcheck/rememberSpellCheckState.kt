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
 */
@Composable
fun rememberSpellCheckState(
	spellChecker: EditorSpellChecker?,
	initialText: AnnotatedString? = null,
	enableSpellChecking: Boolean = true,
	spellCheckMode: SpellCheckMode = SpellCheckMode.Word,
): SpellCheckState {
	val richTextState = rememberTextEditorState(initialText)
	val state = remember { SpellCheckState(richTextState, spellChecker, enableSpellChecking, spellCheckMode) }

	// Run SpellCheck as soon as it is ready
	LaunchedEffect(spellChecker) {
		if (spellChecker != null) {
			state.spellChecker = spellChecker
			state.runFullSpellCheck()
		}
	}

	// Propagate enableSpellChecking changes to the state so callers can toggle it on recomposition
	LaunchedEffect(enableSpellChecking) {
		state.setSpellCheckingEnabled(enableSpellChecking)
	}

	return state
}