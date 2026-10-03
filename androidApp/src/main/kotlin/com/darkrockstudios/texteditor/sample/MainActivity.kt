package com.darkrockstudios.texteditor.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.darkrockstudios.texteditor.sample.ui.theme.TextEditorTheme

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			TextEditorTheme {
				Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
					Box(modifier = Modifier
						.padding(innerPadding)
						// The scaffold's padding already holds the navigation bar, which the
						// keyboard's inset includes.
						.consumeWindowInsets(innerPadding)
						.imePadding()
						.background(color = Color.White)) {
						AndroidApp()
					}
				}
			}
		}
	}
}