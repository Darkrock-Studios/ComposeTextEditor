package com.darkrockstudios.texteditor.input

/** Hardware keyboards on iPadOS follow the macOS conventions. */
internal actual fun platformKeyBindings(): KeyBindings = MacKeyBindings
