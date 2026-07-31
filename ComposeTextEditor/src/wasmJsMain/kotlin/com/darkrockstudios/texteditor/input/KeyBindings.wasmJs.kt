package com.darkrockstudios.texteditor.input

/** Browsers on macOS deliver Cmd as the meta modifier, so the page follows the macOS conventions. */
private fun isMacHost(): Boolean =
	js("/Mac|iPhone|iPad|iPod/.test(navigator.platform || navigator.userAgent || '')")

internal actual fun platformKeyBindings(): KeyBindings =
	if (isMacHost()) MacKeyBindings else CtrlKeyBindings
