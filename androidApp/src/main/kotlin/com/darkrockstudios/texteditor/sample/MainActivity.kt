package com.darkrockstudios.texteditor.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			// The app pads for the system bars itself; the keyboard's inset, consumed here,
			// already holds the navigation bar, so the two never add up.
			Box(modifier = Modifier.imePadding()) {
				AndroidApp()
			}
		}
	}
}
