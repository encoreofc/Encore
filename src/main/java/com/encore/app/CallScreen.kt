package com.encore.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text as CText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/* ------------------------------------------------------------------------------------------
 * Encore call screen
 *
 *  - Two-voice colour field: the top of the screen belongs to the other person, the bottom to you.
 *    Whoever speaks makes their colours swell and drift toward the middle; both talking mixes them.
 *  - Glass everywhere: dock, cards, pills. One deep base, so it reads the same in light and dark mode.
 *  - Share while you talk: photos, videos and text float over the colour without leaving the call.
 * ---------------------------------------------------------------------------------------- */

private val CallEnd = Color(0xFFFF3B5C)
private val CallAnswer = Color(0xFF2EE6A6)

// The same yellow the app already uses (chat-list badge, login button); move into Ui with the global theme pass.
private val CallAccent = Color(0xFFFFC400)

private val CallInk = Color(0xFF12141A)

// Swap for your brand font, e.g. FontFamily(Font(R.font.encore_regular), Font(R.font.encore_bold, FontWeight.Bold)).
private val CallFont: FontFamily = FontFamily.Default

private const val TriggerFraction = 0.72f

/* ---- colour language ------------------------------------------------------------------- *
 * Every palette has 4 colours, one per light. Lights 0-1 are the other person's (top),
 * lights 2-3 are yours (bottom). While calling, only your side glows, on pure Gold.
 * When the call is answered the glow fills the screen and the other person's lights slide onto
 * the next palette, so the two voices have two different hues. A new palette arrives every minute.
 * ---------------------------------------------------------------------------------------- */
private val CallPalettes: List<List<Color>> = listOf(
    listOf(Color(0xFFFFC400), Color(0xFFFF8F00), Color(0xFFE07B00), Color(0xFFFFE082)), // Gold
    listOf(Color(0xFF7C4DFF), Color(0xFF3D5AFE), Color(0xFFD500F9), Color(0xFF536DFE)), // Violet
    listOf(Color(0xFF0091EA), Color(0xFF00BFA5), Color(0xFF2962FF), Color(0xFF18FFFF)), // Ocean
    listOf(Color(0xFF00C853), Color(0xFF00897B), Color(0xFF64DD17), Color(0xFF1DE9B6)), // Emerald
    listOf(Color(0xFFFF6D00), Color(0xFFFF1744), Color(0xFFFFC400), Color(0xFFD50000)), // Sunset
    listOf(Color(0xFFF50057), Color(0xFFAA00FF), Color(0xFFFF4081), Color(0xFFC51162))  // Rose
)

/** How far (in palettes) the other person's colours sit from yours once the call is live. */
private const val PeerPaletteShift = 1f

/** Colour [k] of the palette scale at position [pos] (whole numbers are pure palettes, in between is a smooth blend). */
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

private fun smooth01(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun voiceLevel(on: Boolean, raw: Float): Float =
    if (on) sqrt((raw * 3.5f).coerceIn(0f, 1f)) else 0f // WebRTC levels are small, lift them a little

/**
 * The background is a tiny colour field (4 moving colour points blended smoothly) stretched over the whole
 * screen with bilinear filtering, so there are no shapes or edges, only soft flowing colour.
 */
private const val FieldW = 48
private const val FieldH = 80
private const val PointCount = 4
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

    // per-frame scratch, allocated once
    val ptX = FloatArray(PointCount)
    val ptY = FloatArray(PointCount)
    val cr = FloatArray(PointCount)
    val cg = FloatArray(PointCount)
    val cb = FloatArray(PointCount)
    val mul = FloatArray(PointCount)
    val soft = FloatArray(PointCount)
}

private class CallPoint(
    val bx: Float, val by: Float,           // where the colour point lives
    val ax: Float, val ay: Float,           // how far it wanders
    val sx: Float, val sy: Float,           // how fast it wanders
    val offset: Float
)

// 0 and 1 live in the top half (the other person), 2 and 3 in the bottom half (you)
private val CallPoints = listOf(
    CallPoint(0.25f, 0.20f, 0.22f, 0.15f, 0.31f, 0.23f, 0.0f),
    CallPoint(0.80f, 0.30f, 0.18f, 0.18f, 0.27f, 0.35f, 1.7f),
    CallPoint(0.30f, 0.80f, 0.22f, 0.16f, 0.19f, 0.29f, 3.1f),
    CallPoint(0.78f, 0.85f, 0.20f, 0.14f, 0.23f, 0.17f, 4.6f)
)

private fun DrawScope.drawCallGlow(
    f: CallField,
    phase: Float,
    pos: Float,
    peerE: Float,
    meE: Float,
    spread: Float
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val aspect = h / w

    for (k in 0 until PointCount) {
        val p = CallPoints[k]
        val theirs = k < 2
        val e = if (theirs) peerE else meE
        val baseY = lerpF(0.62f + 0.38f * p.by, p.by, spread) // while calling the colours sit low
        var x = p.bx + p.ax * sin(phase * p.sx + p.offset)
        var y = baseY + p.ay * (0.4f + 0.6f * spread) * cos(phase * p.sy + p.offset * 1.3f)
        // the speaking side drifts toward the middle and gets a wider, heavier pool of colour
        x = lerpF(x, 0.5f, 0.28f * e)
        y = lerpF(y, 0.5f, 0.36f * e)
        f.ptX[k] = x
        f.ptY[k] = y
        f.mul[k] = 1f + 3.6f * e
        f.soft[k] = 0.05f + 0.06f * e
        val c = paletteColor(pos + if (theirs) PeerPaletteShift * spread else 0f, k)
        f.cr[k] = c.red * 255f
        f.cg[k] = c.green * 255f
        f.cb[k] = c.blue * 255f
    }

    val lift = 0.92f
    val px = f.px
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
            for (k in 0 until PointCount) {
                val dx = u - f.ptX[k]
                val dy = (v - f.ptY[k]) * aspect
                val d = dx * dx + dy * dy + f.soft[k]
                val wt = f.mul[k] / (d * d)
                sw += wt
                r += f.cr[k] * wt
                g += f.cg[k] * wt
                b += f.cb[k] * wt
            }
            r = (r / sw) * lift
            g = (g / sw) * lift
            b = (b / sw) * lift
            // blending several colours dulls them; give back a little richness so the mix is never muddy
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
    f.bmp.setPixels(px, 0, FieldW, 0, 0, FieldW, FieldH)
    f.dst.set(0, 0, w.toInt(), h.toInt())
    drawIntoCanvas { it.nativeCanvas.drawBitmap(f.bmp, f.src, f.dst, f.paint) }

    // soft shadows keep the name readable at the top and the glass dock readable at the bottom
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent),
            startY = 0f,
            endY = 190.dp.toPx()
        )
    )
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.30f)),
            startY = h - 220.dp.toPx(),
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

/* ---- icons ----------------------------------------------------------------------------- */

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

private val PhotoIcon: ImageVector by lazy {
    callIcon(
        "callPhoto",
        "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z"
    )
}

private val ChatIcon: ImageVector by lazy {
    callIcon("callChat", "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z")
}

private val SendIcon: ImageVector by lazy {
    callIcon("callSend", "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z")
}

private val CloseIcon: ImageVector by lazy {
    callIcon(
        "callClose",
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"
    )
}

private val PlayIcon: ImageVector by lazy {
    callIcon("callPlay", "M8,5v14l11,-7z")
}

/* ---- host ------------------------------------------------------------------------------ */

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
    if (CallManager.phase != CallPhase.Idle) {
        CallScreen()
    } else {
        // nothing shared during a call outlives it here; the transport keeps its own history
        LaunchedEffect(Unit) { CallChat.clear() }
    }
}

@Composable
private fun CallScreen() {
    val ctx = LocalContext.current
    val phase = CallManager.phase
    val gate = rememberMicGate()
    val scope = rememberCoroutineScope()

    var viewing by remember { mutableStateOf<CallItem?>(null) }
    var composing by remember { mutableStateOf(false) }

    BackHandler { }
    BackHandler(enabled = composing) { composing = false }
    BackHandler(enabled = viewing != null) { viewing = null }

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
    val waiting = phase == CallPhase.Calling || phase == CallPhase.Ringing || phase == CallPhase.Connecting
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

    // One voice level per side: the other person drives the top lights, you drive the bottom ones.
    // A muted mic does not move your lights.
    val peerE by animateFloatAsState(voiceLevel(live, CallManager.level), tween(160), label = "callPeerEnergy")
    val meE by animateFloatAsState(voiceLevel(live && !CallManager.muted, CallManager.myLevel), tween(160), label = "callMeEnergy")
    val energyNow by rememberUpdatedState(max(peerE, meE))

    // Motion clock: runs faster the louder anyone is, so colours drift calmly when quiet and move when speaking
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

    // The call is always colour on a deep base, so the system bars must always use light icons
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

    // System photo picker: no storage permission needed
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { CallChat.sendMedia(ctx, uri) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CallInk)
            .drawBehind { drawCallGlow(field, motion, pos.value, peerE, meE, spread) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Box(Modifier.padding(start = 24.dp, top = 18.dp)) { CallBadge(live = live) }
            Spacer(Modifier.height(26.dp))
            CallHeader(
                name = CallManager.peerName,
                status = status,
                live = live,
                pulsing = waiting,
                peerEnergy = { peerE }
            )

            // the shared moments float here, over the colour
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter
            ) {
                FloatingFeed(
                    onOpen = { viewing = it },
                    modifier = Modifier.padding(bottom = 14.dp)
                )
            }

            if (phase == CallPhase.Ringing) {
                AnswerOrb(
                    onDecline = { CallManager.hangup() },
                    onAccept = { gate { CallManager.accept() } }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(bottom = 16.dp)
                ) {
                    MutedHint(visible = CallManager.muted && live && !composing, onClick = { composing = true })
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .callGlass(RoundedCornerShape(32.dp), fill = 0.14f)
                        ) {
                            Crossfade(targetState = composing && live, label = "callDock") { typing ->
                                if (typing) {
                                    Composer(
                                        placeholder = if (CallManager.muted) "You're muted. Type instead…" else "Message",
                                        onSend = { CallChat.sendText(it) },
                                        onClose = { composing = false }
                                    )
                                } else {
                                    DockActions(
                                        muted = CallManager.muted,
                                        speaker = CallManager.speaker,
                                        canShare = live,
                                        onMute = { CallManager.toggleMute() },
                                        onSpeaker = { CallManager.toggleSpeaker() },
                                        onPhoto = {
                                            picker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                            )
                                        },
                                        onText = { composing = true }
                                    )
                                }
                            }
                        }
                        EndButton(onClick = { CallManager.hangup() })
                    }
                }
            }
        }

        val v = viewing
        if (v != null) {
            MediaViewer(item = v, status = status, onClose = { viewing = null })
        }
    }
}

/* ---- header ---------------------------------------------------------------------------- */

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
        CText("Encore voice call", fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = CallFont, color = Color.White)
    }
}

/** Big name on the left, avatar on the right. Deliberately not centred. */
@Composable
private fun CallHeader(name: String, status: String, live: Boolean, pulsing: Boolean, peerEnergy: () -> Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            CText(
                name,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = CallFont,
                letterSpacing = (-0.6).sp,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            StatusLine(status, live, pulsing)
        }
        Spacer(Modifier.width(16.dp))
        PeerAvatar(peerEnergy)
    }
}

@Composable
private fun StatusLine(text: String, live: Boolean, pulsing: Boolean) {
    val t = rememberInfiniteTransition(label = "callDot")
    val a by t.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "callDotAlpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    when {
                        live -> CallAccent
                        pulsing -> Color.White.copy(alpha = a)
                        else -> Color.White.copy(alpha = 0.55f)
                    }
                )
        )
        Spacer(Modifier.width(8.dp))
        CText(
            text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = CallFont,
            color = Color.White.copy(alpha = 0.82f),
            style = TextStyle(fontFeatureSettings = "tnum")
        )
    }
}

/** Avatar with a thin ring that brightens and thickens while the other person speaks. */
@Composable
private fun PeerAvatar(energy: () -> Float) {
    Box(
        modifier = Modifier
            .size(88.dp)
            .drawBehind {
                val e = energy()
                val stroke = (1.5f + 2.5f * e).dp.toPx()
                drawCircle(
                    color = Color.White.copy(alpha = 0.30f + 0.55f * e),
                    radius = size.minDimension / 2f - stroke / 2f,
                    style = Stroke(stroke)
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Avatar(CallManager.peerId, CallManager.peerName, 74.dp)
    }
}

/* ---- floating feed: text, photos, videos ----------------------------------------------- */

private const val FadeMs = 900

@Composable
private fun FloatingFeed(onOpen: (CallItem) -> Unit, modifier: Modifier = Modifier) {
    val visible = CallChat.items.filter { !it.dismissed }.takeLast(4)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        visible.forEach { item ->
            key(item.id) { FeedEntry(item, onOpen) }
        }
    }
}

@Composable
private fun FeedEntry(item: CallItem, onOpen: (CallItem) -> Unit) {
    val enter = remember { Animatable(0f) }
    var fading by remember { mutableStateOf(false) }
    val fade by animateFloatAsState(if (fading) 0f else 1f, tween(FadeMs), label = "feedFade")

    LaunchedEffect(item.id) {
        enter.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
    }
    LaunchedEffect(item.id) {
        delay(item.holdMs)
        while (item.state == CallItemState.Sending) delay(300) // never fade a card that is still moving
        if (item.state == CallItemState.Failed) delay(6_000)   // give the retry tap some time
        fading = true
        delay(FadeMs.toLong())
        item.dismissed = true
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val e = enter.value
                alpha = e.coerceIn(0f, 1f) * fade
                translationY = (1f - e) * 28.dp.toPx()
                val s = 0.9f + 0.1f * e
                scaleX = s
                scaleY = s
            },
        contentAlignment = if (item.mine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        if (item.kind == CallItemKind.Text) {
            TextBubble(item, onRetry = { CallChat.retry(item) })
        } else {
            MediaCard(item, onOpen = { onOpen(item) }, onRetry = { CallChat.retry(item) })
        }
    }
}

@Composable
private fun TextBubble(item: CallItem, onRetry: () -> Unit) {
    val r = 22.dp
    val tail = 7.dp
    val shape = RoundedCornerShape(
        topStart = r,
        topEnd = r,
        bottomStart = if (item.mine) r else tail,
        bottomEnd = if (item.mine) tail else r
    )
    val failed = item.mine && item.state == CallItemState.Failed
    Column(
        modifier = Modifier.widthIn(max = 280.dp),
        horizontalAlignment = if (item.mine) Alignment.End else Alignment.Start
    ) {
        CText(
            item.text,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontFamily = CallFont,
            color = Color.White,
            modifier = Modifier
                .callGlass(shape, fill = if (item.mine) 0.22f else 0.14f)
                .clickable(enabled = failed, onClick = onRetry)
                .padding(horizontal = 16.dp, vertical = 11.dp)
        )
        if (failed) {
            Spacer(Modifier.height(4.dp))
            CText("Not sent · tap to retry", fontSize = 12.sp, fontFamily = CallFont, color = Color(0xFFFFB3BF))
        }
    }
}

@Composable
private fun MediaCard(item: CallItem, onOpen: () -> Unit, onRetry: () -> Unit) {
    val p = item.preview
    val img = remember(p) { p?.asImageBitmap() }
    val ratio = if (p != null && p.height > 0) (p.width.toFloat() / p.height).coerceIn(0.75f, 1.5f) else 1f
    val shape = RoundedCornerShape(24.dp)
    val failed = item.state == CallItemState.Failed
    val sending = item.state == CallItemState.Sending

    Box(
        modifier = Modifier
            .width(216.dp)
            .aspectRatio(ratio)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.06f))),
                shape
            )
            .clickable {
                when {
                    failed && item.mine -> onRetry()
                    !failed -> onOpen()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = if (item.kind == CallItemKind.Video) "Video" else "Photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        when {
            sending -> Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.38f)),
                contentAlignment = Alignment.Center
            ) { ProgressRing(item.progress) }

            failed -> Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.50f)),
                contentAlignment = Alignment.Center
            ) {
                CText(
                    if (item.mine) "Not sent · tap to retry" else "Couldn't load",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = CallFont,
                    color = Color.White
                )
            }

            item.kind == CallItemKind.Video -> Box(
                modifier = Modifier
                    .size(54.dp)
                    .callGlass(CircleShape, fill = 0.22f),
                contentAlignment = Alignment.Center
            ) {
                Icon(PlayIcon, contentDescription = "Play video", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

/** Determinate when the transport reports progress, a gentle spinner when it does not. */
@Composable
private fun ProgressRing(progress: Float) {
    val t = rememberInfiniteTransition(label = "callRing")
    val spin by t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "callRingSpin"
    )
    val indeterminate = progress < 0.02f
    Canvas(Modifier.size(40.dp)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2f
        drawCircle(
            color = Color.White.copy(alpha = 0.25f),
            radius = (size.minDimension - stroke) / 2f,
            style = Stroke(stroke)
        )
        drawArc(
            color = Color.White,
            startAngle = if (indeterminate) spin - 90f else -90f,
            sweepAngle = if (indeterminate) 100f else 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

/* ---- full-screen viewer ---------------------------------------------------------------- */

@Composable
private fun MediaViewer(item: CallItem, status: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val full by produceState<android.graphics.Bitmap?>(initialValue = null, item.id, item.media) {
        val uri = item.media
        value = if (item.kind == CallItemKind.Photo && uri != null) CallChat.loadBitmap(ctx, uri, 2048) else null
    }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var sound by remember { mutableStateOf(false) } // videos start silent so they never fight the call audio
    LaunchedEffect(player, sound) {
        val vol = if (sound) 1f else 0f
        runCatching { player?.setVolume(vol, vol) }
    }

    val videoUri = if (item.kind == CallItemKind.Video) item.media else null
    val still = full ?: item.preview
    val stillImage = remember(still) { still?.asImageBitmap() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0101218))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (videoUri != null) sound = !sound else onClose()
            }
    ) {
        if (videoUri != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AndroidView(
                    modifier = Modifier.wrapContentSize(),
                    factory = { c ->
                        VideoView(c).apply {
                            setVideoURI(videoUri)
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                mp.setVolume(0f, 0f)
                                player = mp
                                start()
                            }
                        }
                    },
                    onRelease = { v ->
                        player = null
                        v.stopPlayback()
                    }
                )
            }
        } else if (stillImage != null) {
            Image(
                bitmap = stillImage,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .callGlass(RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(CallAccent))
                Spacer(Modifier.width(8.dp))
                CText(
                    "In call · $status",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = CallFont,
                    color = Color.White,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .callGlass(CircleShape, fill = 0.16f)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(CloseIcon, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }

        if (videoUri != null) {
            CText(
                if (sound) "Sound on · tap to mute" else "Sound off · tap for sound",
                fontSize = 13.sp,
                fontFamily = CallFont,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            )
        } else if (item.kind == CallItemKind.Video) {
            CText(
                "Video is still loading…",
                fontSize = 15.sp,
                fontFamily = CallFont,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/* ---- incoming: one orb, drag up to answer, down to decline ----------------------------- */

@Composable
private fun AnswerOrb(onDecline: () -> Unit, onAccept: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val trackW = 96.dp
    val trackH = 240.dp
    val orbSize = 76.dp
    val maxPx = with(density) { ((trackH - orbSize) / 2).toPx() }

    val off = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }

    val frac = (off.value / maxPx).coerceIn(-1f, 1f) // negative = up = answer
    val up = (-frac).coerceIn(0f, 1f)
    val down = frac.coerceIn(0f, 1f)
    val orbColor = if (frac < 0f) lerp(Color.White, CallAnswer, up) else lerp(Color.White, CallEnd, down)
    val iconColor = lerp(Color(0xFF14161B), Color.White, max(up, down))

    val drag = rememberDraggableState { delta ->
        scope.launch {
            val next = (off.value + delta).coerceIn(-maxPx, maxPx)
            off.snapTo(next)
            val past = abs(next) / maxPx >= TriggerFraction
            if (past && !armed) {
                armed = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            } else if (!past) {
                armed = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(trackW)
                .height(trackH)
                .callGlass(RoundedCornerShape(trackW / 2), fill = 0.10f),
            contentAlignment = Alignment.Center
        ) {
            CText(
                "Answer",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = CallFont,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp)
                    .alpha(0.85f - 0.6f * down)
            )
            CText(
                "Decline",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = CallFont,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .alpha(0.85f - 0.6f * up)
            )
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, off.value.roundToInt()) }
                    .size(orbSize)
                    .clip(CircleShape)
                    .background(orbColor)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = drag,
                        onDragStopped = {
                            val f = off.value / maxPx
                            armed = false
                            when {
                                f <= -TriggerFraction -> onAccept()
                                f >= TriggerFraction -> onDecline()
                            }
                            off.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow))
                        }
                    )
                    .semantics {
                        contentDescription = "Incoming call. Swipe up to answer, swipe down to decline."
                        customActions = listOf(
                            CustomAccessibilityAction("Answer") { onAccept(); true },
                            CustomAccessibilityAction("Decline") { onDecline(); true }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier
                        .size(32.dp)
                        .rotate(135f * down)
                )
            }
        }
    }
}

/* ---- in-call dock ---------------------------------------------------------------------- */

@Composable
private fun DockActions(
    muted: Boolean,
    speaker: Boolean,
    canShare: Boolean,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onPhoto: () -> Unit,
    onText: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockButton(if (muted) MicOffIcon else MicIcon, if (muted) "Unmute" else "Mute", on = muted, onClick = onMute)
        DockButton(SpeakerIcon, "Speaker", on = speaker, onClick = onSpeaker)
        Box(
            Modifier
                .width(1.dp)
                .height(24.dp)
                .background(Color.White.copy(alpha = 0.20f))
        )
        DockButton(PhotoIcon, "Share a photo or video", enabled = canShare, onClick = onPhoto)
        DockButton(ChatIcon, "Type a message", enabled = canShare, onClick = onText)
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    on: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .background(if (on) Color.White.copy(alpha = 0.94f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (on) Color(0xFF14161B) else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** Not a circle on purpose: a rounded square, so the dock and the end button read as two different things. */
@Composable
private fun EndButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(shape)
            .background(CallEnd)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)), shape)
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.04f))),
                shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Call,
            contentDescription = "End call",
            tint = Color.White,
            modifier = Modifier
                .size(28.dp)
                .rotate(135f)
        )
    }
}

/** Shown while muted: nudges people to type instead of leaving the call. */
@Composable
private fun MutedHint(visible: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 2 },
            exit = fadeOut(tween(150))
        ) {
            Row(
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .callGlass(RoundedCornerShape(50), fill = 0.14f)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(MicOffIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                CText(
                    "You're muted · tap to type",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = CallFont,
                    color = Color.White
                )
            }
        }
    }
}

/** The dock turns into a text field. The right button is Close while empty and Send once there is text. */
@Composable
private fun Composer(placeholder: String, onSend: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit() {
        val t = text.trim()
        if (t.isNotEmpty()) {
            onSend(t)
            text = ""
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it.take(500) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontFamily = CallFont),
            cursorBrush = SolidColor(CallAccent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        CText(
                            placeholder,
                            fontSize = 17.sp,
                            fontFamily = CallFont,
                            color = Color.White.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            }
        )
        Spacer(Modifier.width(8.dp))
        val hasText = text.isNotBlank()
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (hasText) CallAccent else Color.White.copy(alpha = 0.12f))
                .clickable { if (hasText) submit() else onClose() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (hasText) SendIcon else CloseIcon,
                contentDescription = if (hasText) "Send" else "Close",
                tint = if (hasText) Color(0xFF14161B) else Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/* ------------------------------------------------------------------------------------------
 * WIRING (4 places, none of them in this file)
 *
 * 1. Once at app start, give CallChat your network layer:
 *      CallChat.attach(object : CallChatTransport {
 *          override fun sendText(item: CallItem) { ... item.markSent() / item.markFailed() }
 *          override fun sendMedia(item: CallItem, source: Uri) {
 *              // send item.previewJpeg() first, then upload `source`, setting item.progress as it goes
 *          }
 *      })
 * 2. In the realtime handler for incoming call messages (main thread):
 *      CallChat.receiveText(ctx, id, text)
 *      val item = CallChat.receiveMedia(ctx, id, isVideo, previewBitmap)
 *      // then item.progress = ..., and finally item.markSent(localFileUri) or item.markFailed()
 *    Both calls show the heads-up banner by themselves when Encore is not on screen.
 * 3. Android 13+: ask for POST_NOTIFICATIONS somewhere in your onboarding, or banners are skipped.
 * 4. Dependencies: androidx.activity 1.6+ (photo picker) and androidx.core. Nothing else is added.
 *
 * Text uses androidx.compose.material3.Text (imported as CText) so letter spacing, font and tabular
 * digits work; the rest of the app's Text wrapper is untouched.
 * ---------------------------------------------------------------------------------------- */
