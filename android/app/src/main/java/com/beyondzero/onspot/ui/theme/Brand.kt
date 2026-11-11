package com.beyondzero.onspot.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** The OnSpot palette, shared with the desktop app: a calm blue accent on soft, pastel surfaces. */
object Brand {
    val Blue = Color(0xFF3B6FF5)
    val BlueDeep = Color(0xFF2A5AE6)
    val BlueLight = Color(0xFF6B9BFF)
    val Violet = Color(0xFF7457E0)
    val VioletLight = Color(0xFFB7A5FF)
    val Pink = Color(0xFFD6456B)
    val Mint = Color(0xFF22C55E)
    val Amber = Color(0xFFF59E0B)
    val Rose = Color(0xFFEF4444)
    val Cyan = Color(0xFF22D3EE)
    val Surface = Color(0xFF12142A)
    val GradientColors = listOf(BlueLight, Blue, BlueDeep)
    val Gradient = Brush.linearGradient(GradientColors)
}
