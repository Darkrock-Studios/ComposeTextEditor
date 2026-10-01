package com.darkrockstudios.texteditor.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent

actual val KeyEvent.layoutKey: Key
	get() = layoutKeyFromCodePoint()
