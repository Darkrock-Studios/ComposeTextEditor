package com.darkrockstudios.texteditor.input

/** Split out from [platformKeyBindings] so the host detection itself is testable. */
internal fun keyBindingsForOs(osName: String): KeyBindings =
	if (osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true)) {
		MacKeyBindings
	} else {
		CtrlKeyBindings
	}

internal actual fun platformKeyBindings(): KeyBindings =
	keyBindingsForOs(System.getProperty("os.name").orEmpty())
