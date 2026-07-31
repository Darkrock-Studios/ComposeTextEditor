package com.darkrockstudios.texteditor.input

private val isMacOs: Boolean =
	System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

internal actual fun platformKeyBindings(): KeyBindings =
	if (isMacOs) MacKeyBindings else CtrlKeyBindings
