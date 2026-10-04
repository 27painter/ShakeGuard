package com.shakeguard.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.shakeguard.app.core.SensorState
import com.shakeguard.app.core.TrackState

val OkColor = Color(0xFF1B7F4B)
val WarnColor = Color(0xFFB26A00)
val BadColor = Color(0xFFC0392B)
val MutedColor = Color(0xFF7A8683)

private val Light = lightColorScheme(
    primary = Color(0xFF0B5F4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBEE7D8),
    onPrimaryContainer = Color(0xFF00281C),
    secondary = Color(0xFF1B7F63),
    onSecondary = Color.White,
    background = Color(0xFFF5F8F6),
    surface = Color.White,
    onSurface = Color(0xFF15201C),
    surfaceVariant = Color(0xFFE3EAE7),
    onSurfaceVariant = Color(0xFF47524E)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD9BA),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513C),
    onPrimaryContainer = Color(0xFF9BF6D6),
    secondary = Color(0xFF9BD3C0),
    background = Color(0xFF0F1413),
    surface = Color(0xFF181D1B),
    onSurface = Color(0xFFE2E9E6),
    surfaceVariant = Color(0xFF2A322F),
    onSurfaceVariant = Color(0xFFBAC5C0)
)

@Composable
fun ShakeGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content
    )
}

fun sensorColor(state: SensorState): Color = when (state) {
    SensorState.DENIED -> OkColor
    SensorState.SPLASH_ONLY -> WarnColor
    SensorState.ALLOWED -> BadColor
    SensorState.UNSUPPORTED -> MutedColor
    SensorState.UNKNOWN -> MutedColor
}

fun trackColor(state: TrackState): Color = when (state) {
    TrackState.DENIED -> OkColor
    TrackState.DEFAULT -> WarnColor
    TrackState.ALLOWED -> BadColor
    TrackState.UNSUPPORTED -> MutedColor
    TrackState.UNKNOWN -> MutedColor
}
