package com.encore.app

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Ui {
    val ink = Color(0xFF111111)
    val muted = Color(0xFF8A8F98)
    val line = Color(0xFFEFF0F2)
    val soft = Color(0xFFF3F4F6)
}

private val LightColors = lightColorScheme(
    primary = Ui.ink,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Ui.ink,
    surface = Color.White,
    onSurface = Ui.ink,
    surfaceVariant = Ui.soft,
    onSurfaceVariant = Ui.muted,
    outline = Color(0xFFD5D7DB),
    error = Color(0xFFD93025)
)

@Composable
fun EncoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}

fun ComponentActivity.edgeToEdgeLight() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
    )
}
