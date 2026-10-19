package com.encore.app

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * The aura: every person has one colour. It comes from their photo; with no photo it is
 * picked from their id, so it is always the same for the same person.
 */

/** A steady colour for a person, from their id. */
fun auraSeed(id: String): Color {
    var h = 0
    for (c in id) h = h * 31 + c.code
    val hue = (((h % 360) + 360) % 360).toFloat()
    return Color.hsv(220f, 0.12f, 0.62f)
}

/** The most colourful hue in the photo, or null when the photo is grey. */
private fun dominantColor(bmp: Bitmap): Color? {
    val size = 24
    val s = Bitmap.createScaledBitmap(bmp, size, size, true)
    val hsv = FloatArray(3)
    var sx = 0.0
    var sy = 0.0
    var sat = 0.0
    var total = 0.0
    for (y in 0 until size) {
        for (x in 0 until size) {
            android.graphics.Color.colorToHSV(s.getPixel(x, y), hsv)
            val w = (hsv[1] * hsv[2]).toDouble()
            val rad = Math.toRadians(hsv[0].toDouble())
            sx += cos(rad) * w
            sy += sin(rad) * w
            sat += hsv[1] * w
            total += w
        }
    }
    if (total < 8.0) return null
    val hue = ((Math.toDegrees(atan2(sy, sx)) + 360.0) % 360.0).toFloat()
    val saturation = (sat / total).toFloat().coerceIn(0.25f, 0.50f)
    return Color.hsv(hue, saturation, 0.80f)
}

private fun hueShift(c: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    return Color.hsv((hsv[0] + degrees + 360f) % 360f, hsv[1], hsv[2])
}

/** The person's colour. Starts at once from the id, then moves to the photo's colour. */
@Composable
fun rememberAura(id: String, url: String?): State<Color> {
    val ctx = LocalContext.current
    var target by remember(id) { mutableStateOf(auraSeed(id)) }
    LaunchedEffect(id, url) {
        if (url == null) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(ctx)
                    .data(url)
                    .addHeader("Authorization", "Bearer ${Session.token}")
                    .allowHardware(false)
                    .size(96)
                    .build()
                val res = Coil.imageLoader(ctx).execute(req)
                val bmp = ((res as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                bmp?.let { dominantColor(it) }
            }.getOrNull()
        }
        if (found != null) target = found
    }
    return animateColorAsState(target, tween(900), label = "aura")
}

/** One quiet wash of the person's colour from the top, fading to the normal background. No motion. */
@Composable
fun AuraBackground(color: Color, modifier: Modifier = Modifier) {
    val dark = LocalPalette.current.dark
    Box(
        modifier
            .background(Ui.bg)
            .drawBehind {
                val w = size.width
                val h = size.height
                val a = if (dark) 0.20f else 0.13f
                drawRect(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = a), Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.06f),
                        radius = w * 1.15f
                    )
                )
            }
    )
}
