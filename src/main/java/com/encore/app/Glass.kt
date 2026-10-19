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

/** Slightly see-through container for dialogs and bottom sheets. */
@Composable
fun glassDialogColor(): Color = Ui.bg.copy(alpha = 0.94f)

@Composable
fun glassSheetColor(): Color = Ui.bg.copy(alpha = 0.92f)

/**
 * Glass for panels that sit over the aura: a fill that is lighter at the top-left,
 * and a rim that catches light on one corner. The aura shows through it.
 */
@Composable
fun Modifier.glassPanel(shape: Shape): Modifier {
    val dark = LocalPalette.current.dark
    val fill = Brush.linearGradient(
        if (dark) listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.05f))
        else listOf(Color.White.copy(alpha = 0.72f), Color.White.copy(alpha = 0.38f))
    )
    val rim = Brush.linearGradient(
        if (dark) listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.2f))
        else listOf(Color.White, Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.75f))
    )
    return this
        .clip(shape)
        .background(fill)
        .border(1.dp, rim, shape)
}
