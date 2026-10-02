package com.darkrockstudios.texteditor

import androidx.compose.runtime.Composable

/** Desktop's, iOS's and the web's bridges have no seam for character bounds. */
@Composable
internal actual fun PlatformAccessibilityBridge() = Unit
