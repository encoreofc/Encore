package com.encore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Frosted-glass look: translucent fill plus a light rim. Works in light and dark. */
@Composable
fun Modifier.glassSurface(shape: Shape, strong: Boolean = false): Modifier {
    val dark = LocalPalette.current.dark
    val fill = if (dark) Color.White.copy(alpha = if (strong) 0.14f else 0.09f)
    else Color.Black.copy(alpha = if (strong) 0.08f else 0.05f)
    val rim = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.28f else 0.9f),
            Color.White.copy(alpha = if (dark) 0.06f else 0.3f)
        )
    )
    return this
        .clip(shape)
        .background(fill)
        .border(0.8.dp, rim, shape)
}
