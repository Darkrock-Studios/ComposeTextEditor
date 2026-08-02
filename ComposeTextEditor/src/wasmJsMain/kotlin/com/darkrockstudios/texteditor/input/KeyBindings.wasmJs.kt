package com.darkrockstudios.texteditor.input

/**
 * Browsers on macOS deliver Cmd as the meta modifier, so the page follows the macOS conventions.
 * `navigator.platform` is deprecated and frozen or reduced by some browsers, so the userAgent is
 * searched as well rather than only as a fallback for an empty platform.
 */
private fun isMacHost(): Boolean =
	js("/Mac|iPhone|iPad|iPod/.test((navigator.platform || '') + ' ' + (navigator.userAgent || ''))")

actual fun platformKeyBindings(): KeyBindings =
	if (isMacHost()) MacKeyBindings else CtrlKeyBindings
