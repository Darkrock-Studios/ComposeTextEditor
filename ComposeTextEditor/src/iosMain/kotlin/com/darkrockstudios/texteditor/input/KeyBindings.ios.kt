package com.darkrockstudios.texteditor.input

/** Hardware keyboards on iPadOS follow the macOS conventions. */
actual fun platformKeyBindings(): KeyBindings = MacKeyBindings
