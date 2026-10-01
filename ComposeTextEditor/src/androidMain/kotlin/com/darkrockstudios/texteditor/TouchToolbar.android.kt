package com.darkrockstudios.texteditor

/** The floating action mode toolbar. */
internal actual fun hasNativeTextToolbar(): Boolean = true

// A mouse's right-click opens the context menu, as Android's text fields do.
internal actual fun pointerMenuIsTextToolbar(): Boolean = false
