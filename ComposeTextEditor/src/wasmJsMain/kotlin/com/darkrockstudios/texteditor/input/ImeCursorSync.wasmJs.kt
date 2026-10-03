package com.darkrockstudios.texteditor.input

import com.darkrockstudios.texteditor.state.TextEditorState

/**
 * WebAssembly implementation of IME cursor synchronization.
 * Compose's web session watches the shared request's value through `snapshotFlow`
 * and mirrors it into the hidden textarea itself, so nothing is pushed from here.
 */
@Suppress("UNUSED_PARAMETER")
actual class ImeCursorSync actual constructor(
	private val state: TextEditorState
) {
	actual fun startSync() {
		// No-op on WASM
	}

	actual fun stopSync() {
		// No-op on WASM
	}
}
