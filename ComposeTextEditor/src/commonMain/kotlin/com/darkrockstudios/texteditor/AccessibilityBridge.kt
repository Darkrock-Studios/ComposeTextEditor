package com.darkrockstudios.texteditor

import androidx.compose.runtime.Composable

/**
 * Lets the platform's accessibility bridge answer character bounds from an editor's
 * [CharacterBounds] where it has a seam for it: on Android, a wrapper around the view's
 * accessibility delegate, installed once per view. Elsewhere nothing.
 */
@Composable
internal expect fun PlatformAccessibilityBridge()
