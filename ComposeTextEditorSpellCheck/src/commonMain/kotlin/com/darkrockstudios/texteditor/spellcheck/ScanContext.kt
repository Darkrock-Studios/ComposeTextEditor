package com.darkrockstudios.texteditor.spellcheck

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * The context [rememberSpellCheckState] and
 * [com.darkrockstudios.texteditor.spellcheck.diagnostics.rememberTextDiagnosticsState] give
 * their scans. UI tests provide one on the test's own dispatcher, so waiting for idle waits
 * for the scans too.
 */
internal val LocalScanContext = staticCompositionLocalOf<CoroutineContext> { Dispatchers.Default }
