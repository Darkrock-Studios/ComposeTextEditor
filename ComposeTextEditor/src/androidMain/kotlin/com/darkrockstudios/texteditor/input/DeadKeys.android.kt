package com.darkrockstudios.texteditor.input

import android.view.KeyCharacterMap

internal actual fun deadChar(accent: Int, codePoint: Int): Int = KeyCharacterMap.getDeadChar(accent, codePoint)
