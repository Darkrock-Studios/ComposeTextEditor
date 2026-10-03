package com.darkrockstudios.texteditor

/** UIKit's edit menu, hosted by the editor's text input connection. */
internal actual fun hasNativeTextToolbar(): Boolean = true

// UIKit answers a secondary click in text with the edit menu.
internal actual fun pointerMenuIsTextToolbar(): Boolean = true
