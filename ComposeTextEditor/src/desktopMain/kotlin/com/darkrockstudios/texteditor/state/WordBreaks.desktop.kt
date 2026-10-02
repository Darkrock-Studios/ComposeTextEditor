package com.darkrockstudios.texteditor.state

internal actual fun wordCursor(text: String): BreakCursor = skiaWordCursor(text)
