package com.darkrockstudios.texteditor.input

// The skiko platforms compose dead keys in their input method, so no key event carries one.
internal actual fun deadChar(accent: Int, codePoint: Int): Int =
	if (codePoint == accent || codePoint == ' '.code) accent else 0
