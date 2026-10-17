package com.encore.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Same red / green Telegram uses for decline / accept
private val CallRed = Color(0xFFFF3846)
private val CallGreen = Color(0xFF4DD156)

// The same yellow the app already uses (chat-list badge, login button); move into Ui with the global theme pass.
private val CallAccent = Color(0xFFFFC400)

/* ------------------------------------------------------------------------------------------
 * Call color language
 *
 * Every palette has 4 colors (one per light in the scene). The call always sits on ONE palette
 * and slides smoothly to the next:
 *   - while calling / ringing / connecting: slowly and continuously, glowing from the bottom
 *   - once answered: the glow rises and fills the screen, and a new palette arrives every minute
 *   - while anyone speaks: the lights swell and drift faster, following the real voice level
 * Gold is first because it is Encore's signature; the call always starts on it.
 * ---------------------------------------------------------------------------------------- */
private val CallPalettes: List<List<Color>> = listOf(
    listOf(Color(0xFFFFC400), Color(0xFFFF8F00), Color(0xFFB26A00), Color(0xFFFFE082)), // Gold
    listOf(Color(0xFF7C4DFF), Color(0xFF3D5AFE), Color(0xFFD500F9), Color(0xFF536DFE)), // Violet
    listOf(Color(0xFF0091EA), Color(0xFF00BFA5), Color(0xFF2962FF), Color(0xFF18FFFF)), // Ocean
    listOf(Color(0xFF00C853), Color(0xFF00897B), Color(0xFF64DD17), Color(0xFF1DE9B6)), // Emerald
    listOf(Color(0xFFFF6D00), Color(0xFFFF1744), Color(0xFFFFC400), Color(0xFFD50000)), // Sunset
    listOf(Color(0xFFF50057), Color(0xFFAA00FF), Color(0xFFFF4081), Color(0xFFC51162))  // Rose
)

/** Color [k] of the palette scale at position [pos] (whole numbers are pure palettes, in between is a smooth blend). */
private fun paletteColor(pos: Float, k: Int): Color {
    val n = CallPalettes.size
    val i = floor(pos).toInt()
    val f = pos - floor(pos)
    val s = f * f * (3f - 2f * f)
    val a = CallPalettes[((i % n) + n) % n][k]
    val b = CallPalettes[(((i + 1) % n) + n) % n][k]
    return lerp(a, b, s)
}

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * The call background, built the way Telegram builds it: a tiny colour field (4 moving colour points blended
 * smoothly) that is stretched over the whole screen with bilinear filtering, so there are no visible shapes
 * or edges, only a soft flowing colour. It is always drawn on the same deep base, never on the app theme,
 * so white text and waves read identically in light mode and dark mode.
 */
private const val FieldW = 48
private const val FieldH = 80
private const val BaseR = 0x12
private const val BaseG = 0x14
private const val BaseB = 0x1A

private class CallField {
    val bmp: android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(FieldW, FieldH, android.graphics.Bitmap.Config.ARGB_8888)
    val px = IntArray(FieldW * FieldH)
    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG)
    val src = android.graphics.Rect(0, 0, FieldW, FieldH)
    val dst = android.graphics.Rect()
}

private class CallPoint(
    val bx: Float, val by: Float,           // where the colour point lives
    val ax: Float, val ay: Float,           // how far it wanders
    val sx: Float, val sy: Float,           // how fast it wanders
    val offset: Float
)

private val CallPoints = listOf(
    CallPoint(0.25f, 0.20f, 0.22f, 0.15f, 0.31f, 0.23f, 0.0f),
    CallPoint(0.80f, 0.30f, 0.18f, 0.18f, 0.27f, 0.35f, 1.7f),
    CallPoint(0.30f, 0.80f, 0.22f, 0.16f, 0.19f, 0.29f, 3.1f),
    CallPoint(0.78f, 0.85f, 0.20f, 0.14f, 0.23f, 0.17f, 4.6f)
)

private fun smooth01(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun DrawScope.drawCallGlow(field: CallField, phase: Float, pos: Float, energy: Float, spread: Float) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val aspect = h / w

    // the four colour points and their colours at this moment
    val n = CallPoints.size
    val ptX = FloatArray(n)
    val ptY = FloatArray(n)
    val cr = FloatArray(n)
    val cg = FloatArray(n)
    val cb = FloatArray(n)
    for (k in 0 until n) {
        val p = CallPoints[k]
        val baseY = lerpF(0.62f + 0.38f * p.by, p.by, spread) // while calling the colours sit low
        ptX[k] = p.bx + p.ax * sin(phase * p.sx + p.offset)
        ptY[k] = baseY + p.ay * (0.4f + 0.6f * spread) * cos(phase * p.sy + p.offset * 1.3f)
        val c = paletteColor(pos, k)
        cr[k] = c.red * 255f
        cg[k] = c.green * 255f
        cb[k] = c.blue * 255f
    }

    // a little less bright overall, and the colour no longer brightens when someone speaks
    val lift = 0.92f
    val px = field.px
    for (j in 0 until FieldH) {
        val v = (j + 0.5f) / FieldH
        // calling / ringing: colour rises from the bottom edge; live: colour fills the whole screen
        val cover = lerpF(0.94f * smooth01(0.30f, 1.0f, v), 1f, spread)
        for (i in 0 until FieldW) {
            val u = (i + 0.5f) / FieldW
            var sw = 0f
            var r = 0f
            var g = 0f
            var b = 0f
            for (k in 0 until n) {
                val dx = u - ptX[k]
                val dy = (v - ptY[k]) * aspect
                val d = dx * dx + dy * dy + 0.05f
                val wt = 1f / (d * d)
                sw += wt
                r += cr[k] * wt
                g += cg[k] * wt
                b += cb[k] * wt
            }
            r = (r / sw) * lift
            g = (g / sw) * lift
            b = (b / sw) * lift
            // blending several colours dulls them; give back just a little richness so the mix is never muddy
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            r = luma + (r - luma) * 1.08f
            g = luma + (g - luma) * 1.08f
            b = luma + (b - luma) * 1.08f
            val rr = (BaseR + (r - BaseR) * cover).coerceIn(0f, 255f).toInt()
            val gg = (BaseG + (g - BaseG) * cover).coerceIn(0f, 255f).toInt()
            val bb = (BaseB + (b - BaseB) * cover).coerceIn(0f, 255f).toInt()
            px[j * FieldW + i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }
    field.bmp.setPixels(px, 0, FieldW, 0, 0, FieldW, FieldH)
    field.dst.set(0, 0, w.toInt(), h.toInt())
    drawIntoCanvas { it.nativeCanvas.drawBitmap(field.bmp, field.src, field.dst, field.paint) }

    // soft shadows at top and bottom (Telegram uses 160dp) keep the name and buttons readable on any color
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent),
            startY = 0f,
            endY = 160.dp.toPx()
        )
    )
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.20f)),
            startY = h - 160.dp.toPx(),
            endY = h
        )
    )
}

/** Frosted-glass surface: a light translucent fill with a bright-to-faint edge. */
private fun Modifier.callGlass(shape: Shape, fill: Float = 0.12f): Modifier =
    this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = fill + 0.06f), Color.White.copy(alpha = (fill - 0.04f).coerceAtLeast(0.02f)))
            ),
            shape
        )
        .border(
            1.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.06f))),
            shape
        )

private fun callIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = addPathNodes(path),
        fill = SolidColor(Color.Black)
    ).build()

private val MicIcon: ImageVector by lazy {
    callIcon(
        "callMic",
        "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"
    )
}

private val MicOffIcon: ImageVector by lazy {
    callIcon(
        "callMicOff",
        "M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28zM14.98,11.17c0,-0.06 0.02,-0.11 0.02,-0.17L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v0.18l5.98,5.99zM4.27,3L3,4.27l6.01,6.01L9.01,11c0,1.66 1.33,3 2.99,3 0.22,0 0.44,-0.03 0.65,-0.08l1.66,1.66c-0.71,0.33 -1.5,0.52 -2.31,0.52 -2.76,0 -5.3,-2.1 -5.3,-5.1L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c0.91,-0.13 1.77,-0.45 2.54,-0.9L19.73,21 21,19.73 4.27,3z"
    )
}

private val SpeakerIcon: ImageVector by lazy {
    callIcon(
        "callSpeaker",
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
    )
}

/** Returns a function that runs the given action only once the microphone permission is granted. */
@Composable
fun rememberMicGate(): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending
        pending = null
        if (ok) p?.invoke()
        else Toast.makeText(ctx, "Microphone permission is needed for calls", Toast.LENGTH_SHORT).show()
    }
    return { action ->
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pending = action
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
fun CallHost() {
    OnRealtime { CallManager.onEvent(it) }
    if (CallManager.phase != CallPhase.Idle) CallScreen()
}

@Composable
private fun CallScreen() {
    val phase = CallManager.phase
    val gate = rememberMicGate()
    BackHandler { }

    // Answer was pressed on the call notification: accept as soon as this screen is up
    LaunchedEffect(phase, CallManager.acceptCallId) {
        if (phase == CallPhase.Ringing && CallManager.acceptRequested) {
            CallManager.clearAcceptRequest()
            gate { CallManager.accept() }
        }
    }

    val status = when (phase) {
        CallPhase.Calling -> "Calling…"
        CallPhase.Ringing -> "Incoming voice call"
        CallPhase.Connecting -> "Connecting…"
        CallPhase.Active -> {
            val s = CallManager.seconds
            (s / 60).toString().padStart(2, '0') + ":" + (s % 60).toString().padStart(2, '0')
        }
        else -> ""
    }

    val live = phase == CallPhase.Active
    val minute = if (live) CallManager.seconds / 60 else 0

    // Which palette we are on. Before the answer it glides on slowly and forever; after the answer it
    // settles onto the next palette and then moves to a new one at every full minute.
    val pos = remember { Animatable(0f) }
    LaunchedEffect(live, minute) {
        if (live) {
            pos.animateTo(floor(pos.value) + 1f, tween(4200, easing = FastOutSlowInEasing))
        } else {
            while (true) pos.animateTo(pos.value + 1f, tween(9000, easing = LinearEasing))
        }
    }

    // The glow climbs from the bottom edge to the whole screen when the call becomes live
    val spread by animateFloatAsState(if (live) 1f else 0f, tween(1400, easing = FastOutSlowInEasing), label = "callSpread")

    // Voice energy 0..1 from the real loudness of both sides (WebRTC levels are small, so lift them a little)
    val raw = max(CallManager.level, CallManager.myLevel)
    val energyTarget = if (live) sqrt((raw * 3.5f).coerceIn(0f, 1f)) else 0f
    val energy by animateFloatAsState(energyTarget, tween(180), label = "callEnergy")
    val energyNow by rememberUpdatedState(energy)

    // Motion clock: runs faster the louder the voice is, so colors drift calmly when quiet and move when speaking
    var motion by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) motion += (now - last) / 1_000_000_000f * (0.45f + 1.7f * energyNow)
                last = now
            }
        }
    }

    // The call is always colour on a deep base (like Telegram), so the system bars must always use light icons
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = ctrl?.isAppearanceLightStatusBars
        val prevNav = ctrl?.isAppearanceLightNavigationBars
        ctrl?.isAppearanceLightStatusBars = false
        ctrl?.isAppearanceLightNavigationBars = false
        onDispose {
            if (prevStatus != null) ctrl?.isAppearanceLightStatusBars = prevStatus
            if (prevNav != null) ctrl?.isAppearanceLightNavigationBars = prevNav
        }
    }

    val field = remember { CallField() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF12141A))
            .drawBehind { drawCallGlow(field, motion, pos.value, energy, spread) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Telegram: top block is 93dp, the small pill sits 17dp from the top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(93.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(Modifier.padding(top = 17.dp)) { CallBadge(live = live) }
            }
            // Telegram: 204dp avatar view, 135dp photo
            AvatarHalo(
                pulsing = phase == CallPhase.Calling || phase == CallPhase.Ringing || phase == CallPhase.Connecting,
                energy = { energy },
                motion = { motion }
            ) {
                Avatar(CallManager.peerId, CallManager.peerName, 135.dp)
            }
            // Telegram: name (28sp) starts 282dp below the status bar, which is 15dp up into the avatar view
            Column(
                modifier = Modifier.offset(y = (-15).dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    CallManager.peerName,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(status, fontSize = 15.sp, color = Color.White.copy(alpha = 0.78f))
            }
            Spacer(Modifier.weight(1f))

            if (phase == CallPhase.Ringing) {
                // Telegram accept/decline: 186dp tall, 60dp buttons scaled up by 1.15
                IncomingButtons(
                    onDecline = { CallManager.hangup() },
                    onAccept = { gate { CallManager.accept() } }
                )
            } else {
                ControlSheet { slot ->
                    ToggleButton(if (CallManager.muted) MicOffIcon else MicIcon, if (CallManager.muted) "Unmute" else "Mute", CallManager.muted, slot) { CallManager.toggleMute() }
                    RoundButton(CallRed, "End", hangup = true, size = 52.dp, slot = slot) { CallManager.hangup() }
                    ToggleButton(SpeakerIcon, "Speaker", CallManager.speaker, slot) { CallManager.toggleSpeaker() }
                }
            }
        }
    }
}

/** Small glass pill at the top: brand + live state. */
@Composable
private fun CallBadge(live: Boolean) {
    Row(
        modifier = Modifier
            .callGlass(RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (live) CallAccent else Color.White.copy(alpha = 0.55f))
        )
        Spacer(Modifier.width(8.dp))
        Text("Encore voice call", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

/** Avatar (Telegram: 204dp view) with the two soft voice waves; they breathe gently while calling and follow the voice once connected. */
@Composable
private fun AvatarHalo(
    pulsing: Boolean,
    energy: () -> Float,
    motion: () -> Float,
    content: @Composable BoxScope.() -> Unit
) {
    val t = rememberInfiniteTransition(label = "callHalo")
    val breath by t.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "callHaloBreath"
    )
    Box(
        modifier = Modifier
            .size(204.dp)
            .drawBehind { drawVoiceWaves(if (pulsing) breath else energy(), motion()) },
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** One soft filled blob with 8 gentle lobes, like Telegram's avatar waves. */
private fun DrawScope.drawWaveBlob(color: Color, radius: Float, wobble: Float, phase: Float) {
    val path = androidx.compose.ui.graphics.Path()
    val steps = 72
    for (i in 0..steps) {
        val a = (i.toFloat() / steps) * 2f * Math.PI.toFloat()
        val r = radius * (1f + wobble * sin(8f * a + phase))
        val x = center.x + r * cos(a)
        val y = center.y + r * sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

/**
 * Two soft filled waves around the avatar that follow the voice level.
 * Telegram: outer wave 104..111dp at 8% white, inner wave 12dp smaller at 14% white, scale 0.8 + 0.4 * level.
 */
private fun DrawScope.drawVoiceWaves(e: Float, motion: Float) {
    val scale = 0.8f + 0.4f * e
    val wobble = 0.02f + 0.035f * e
    drawWaveBlob(Color.White.copy(alpha = 20f / 255f), (104.dp.toPx() + 7.dp.toPx() * e) * scale, wobble, motion * 1.1f)
    drawWaveBlob(Color.White.copy(alpha = 36f / 255f), (92.dp.toPx() + 7.dp.toPx() * e) * scale, wobble, -motion * 1.4f + 1.2f)
}

/** Incoming call buttons, placed like Telegram's accept/decline view. */
@Composable
private fun IncomingButtons(onDecline: () -> Unit, onAccept: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(186.dp)
    ) {
        // Telegram: 27dp view margin + 46dp inner padding, 60dp buttons, whole view scaled 1.15 around its center
        val scale = 1.15f
        val half = maxWidth / 2
        val inset = half - (half - (27.dp + 46.dp + 30.dp)) * scale - 30.dp * scale
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = inset, end = inset, top = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            RoundButton(CallRed, "Decline", hangup = true, size = 69.dp, labelSp = 12.65f, slot = 69.dp, onClick = onDecline)
            RoundButton(CallGreen, "Accept", hangup = false, size = 69.dp, labelSp = 12.65f, slot = 69.dp, onClick = onAccept)
        }
    }
}

/**
 * Holds the in-call controls. Telegram lays four 68dp slots across the screen and centers the visible ones;
 * the row is at least 80dp tall and sits directly on the bottom inset.
 */
@Composable
private fun ControlSheet(content: @Composable RowScope.(slot: Dp) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        val slot = maxWidth / 4
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Top
        ) {
            content(slot)
        }
    }
}

@Composable
private fun RoundButton(
    color: Color,
    label: String,
    hangup: Boolean,
    size: Dp = 60.dp,
    labelSp: Float = 11f,
    slot: Dp = 68.dp,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.width(slot), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color)
                .background(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.Transparent)),
                    CircleShape
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.04f))),
                    CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Call,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier
                    .size(size * (28f / 60f))
                    .rotate(if (hangup) 135f else 0f)
            )
        }
        Spacer(Modifier.height(4.5.dp))
        Text(label, fontSize = labelSp.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, on: Boolean, slot: Dp = 68.dp, onClick: () -> Unit) {
    Column(modifier = Modifier.width(slot), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(53.5.dp)
                .then(
                    if (on) Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.94f))
                    else Modifier.callGlass(CircleShape, fill = 0.14f)
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (on) Color(0xFF14161B) else Color.White,
                modifier = Modifier.size(25.dp)
            )
        }
        Spacer(Modifier.height(4.5.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}
