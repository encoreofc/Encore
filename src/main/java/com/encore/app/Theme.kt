package com.encore.app

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

class Palette(
    val dark: Boolean,
    val bg: Color,
    val ink: Color,
    val onInk: Color,
    val muted: Color,
    val line: Color,
    val soft: Color,
    val mine: Color,
    val onMine: Color,
    val theirs: Color
)

private val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFFFFFFF),
    ink = Color(0xFF111111),
    onInk = Color(0xFFFFFFFF),
    muted = Color(0xFF8A8F98),
    line = Color(0xFFEFF0F2),
    soft = Color(0xFFF3F4F6),
    mine = Color(0xFF111111),
    onMine = Color(0xFFFFFFFF),
    theirs = Color(0xFFF3F4F6)
)

private val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF0F1114),
    ink = Color(0xFFF2F3F5),
    onInk = Color(0xFF111111),
    muted = Color(0xFF8D939C),
    line = Color(0xFF23262B),
    soft = Color(0xFF1C1F24),
    mine = Color(0xFF3A3F47),
    onMine = Color(0xFFFFFFFF),
    theirs = Color(0xFF1C1F24)
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

object Ui {
    val bg: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
    val ink: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
    val onInk: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.onInk
    val muted: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
    val line: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.line
    val soft: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.soft
    val mine: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.mine
    val onMine: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.onMine
    val theirs: Color
        @Composable @ReadOnlyComposable get() = LocalPalette.current.theirs
}

object ThemeStore {
    var mode by mutableStateOf("system")
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        mode = ctx.getSharedPreferences("encore_ui", Context.MODE_PRIVATE)
            .getString("theme", "system") ?: "system"
    }

    fun set(ctx: Context, value: String) {
        mode = value
        ctx.getSharedPreferences("encore_ui", Context.MODE_PRIVATE)
            .edit().putString("theme", value).apply()
    }
}

@Composable
fun EncoreTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    ThemeStore.load(ctx)
    val dark = when (ThemeStore.mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.ink, onPrimary = p.onInk,
            background = p.bg, onBackground = p.ink,
            surface = p.bg, onSurface = p.ink,
            surfaceVariant = p.soft, onSurfaceVariant = p.muted,
            surfaceContainer = p.soft, surfaceContainerHigh = p.soft, surfaceContainerLow = p.bg,
            outline = Color(0xFF3A3F47), error = Color(0xFFFF6B60)
        )
    } else {
        lightColorScheme(
            primary = p.ink, onPrimary = p.onInk,
            background = p.bg, onBackground = p.ink,
            surface = p.bg, onSurface = p.ink,
            surfaceVariant = p.soft, onSurfaceVariant = p.muted,
            surfaceContainer = p.soft, surfaceContainerHigh = p.soft, surfaceContainerLow = p.bg,
            outline = Color(0xFFD5D7DB), error = Color(0xFFD93025)
        )
    }

    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

fun ComponentActivity.edgeToEdgeLight() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
    )
}
