package com.encore.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Shimmering dust for hidden (view-once) media. Put it over a blurred thumbnail:
 *
 *   Box {
 *       Image(..., modifier = Modifier.blur(24.dp))      // blur() works on Android 12+
 *       SpoilerDust(Modifier.matchParentSize(), revealed = opened)
 *   }
 *
 * On Android 11 and older, load a tiny (about 24px) bitmap and scale it up instead of blur().
 */
@Composable
fun SpoilerDust(
    modifier: Modifier = Modifier,
    revealed: Boolean = false,
    color: Color = Color.White,
    density: Float = 0.0022f,
    dotSize: Dp = 1.2.dp,
    speed: Dp = 10.dp
) {
    val d = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val field = remember(size, density) { DustField(size.width, size.height, density, with(d) { speed.toPx() }) }
    var frame by remember { mutableLongStateOf(0L) }
    val fade by animateFloatAsState(if (revealed) 0f else 1f, label = "dust")

    LaunchedEffect(field, revealed) {
        if (revealed) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                field.step(dt)
                frame = now
            }
        }
    }

    val stroke = with(d) { dotSize.toPx() }
    val paints = remember {
        Array(3) { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap = android.graphics.Paint.Cap.ROUND } }
    }
    Canvas(
        modifier
            .onSizeChanged { size = it }
            .graphicsLayer { alpha = fade }
    ) {
        @Suppress("UNUSED_VARIABLE") val tick = frame // reading it redraws every frame
        if (fade > 0f) drawIntoCanvas { field.draw(it.nativeCanvas, paints, color.toArgb(), stroke) }
    }
}

private class DustField(w: Int, h: Int, density: Float, private val speedPx: Float) {
    private val w = w.toFloat()
    private val h = h.toFloat()
    private val groups = 3
    private val per = if (w <= 0 || h <= 0) 0 else (w.toFloat() * h * density / groups).toInt().coerceIn(0, 900)
    private val n = per * groups
    private val pts = FloatArray(n * 2)
    private val vel = FloatArray(n * 2)
    private val life = FloatArray(n)
    private val rnd = Random(System.nanoTime())
    private var time = 0f

    init {
        for (i in 0 until n) spawn(i, anywhere = true)
    }

    private fun spawn(i: Int, anywhere: Boolean) {
        pts[i * 2] = rnd.nextFloat() * w
        pts[i * 2 + 1] = rnd.nextFloat() * h
        val a = rnd.nextFloat() * 2f * PI.toFloat()
        val v = speedPx * (0.4f + rnd.nextFloat() * 0.9f)
        vel[i * 2] = cos(a) * v
        vel[i * 2 + 1] = sin(a) * v
        life[i] = 0.6f + rnd.nextFloat() * 1.8f
        if (anywhere) life[i] *= rnd.nextFloat()
    }

    fun step(dt: Float) {
        time += dt
        for (i in 0 until n) {
            pts[i * 2] += vel[i * 2] * dt
            pts[i * 2 + 1] += vel[i * 2 + 1] * dt
            life[i] -= dt
            val x = pts[i * 2]
            val y = pts[i * 2 + 1]
            if (life[i] <= 0f || x < 0f || x > w || y < 0f || y > h) spawn(i, anywhere = false)
        }
    }

    fun draw(c: android.graphics.Canvas, paints: Array<android.graphics.Paint>, argb: Int, stroke: Float) {
        for (g in 0 until groups) {
            val p = paints[g]
            p.color = argb
            p.strokeWidth = stroke
            val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(time * 3.2f + g * 2.1f))
            p.alpha = (twinkle * 255).toInt()
            c.drawPoints(pts, g * per * 2, per * 2, p)
        }
    }
}
