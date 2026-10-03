package com.darkrockstudios.texteditor.sample

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application

fun main() = application {
	Window(
		onCloseRequest = ::exitApplication,
		title = "Compose Text Editor",
		state = remember { WindowState(size = DpSize(width = 1200.dp, height = 800.dp)) }
	) {
		window.minimumSize = java.awt.Dimension(360, 480)
		App()
	}
}
