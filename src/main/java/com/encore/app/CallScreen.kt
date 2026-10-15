package com.encore.app

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val CallRed = Color(0xFFE5484D)
private val CallGreen = Color(0xFF2E9E5B)

// The same yellow the app already uses (chat-list badge, login button); move into Ui with the global theme pass.
private val CallAccent = Color(0xFFFFC400)

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))
            CallBadge(live = phase == CallPhase.Active)
            Spacer(Modifier.weight(1f))
            AvatarHalo(
                pulsing = phase == CallPhase.Calling || phase == CallPhase.Ringing || phase == CallPhase.Connecting
            ) {
                Avatar(CallManager.peerId, CallManager.peerName, 132.dp)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                CallManager.peerName,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ui.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(status, fontSize = 15.sp, color = Ui.muted)
            Spacer(Modifier.weight(1f))

            ControlSheet {
                if (phase == CallPhase.Ringing) {
                    RoundButton(CallRed, "Decline", hangup = true) { CallManager.hangup() }
                    RoundButton(CallGreen, "Accept", hangup = false) { gate { CallManager.accept() } }
                } else {
                    ToggleButton(if (CallManager.muted) MicOffIcon else MicIcon, "Mute", CallManager.muted) { CallManager.toggleMute() }
                    RoundButton(CallRed, "End", hangup = true) { CallManager.hangup() }
                    ToggleButton(SpeakerIcon, "Speaker", CallManager.speaker) { CallManager.toggleSpeaker() }
                }
            }
        }
    }
}

/** Small pill at the top: brand + live state. */
@Composable
private fun CallBadge(live: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Ui.soft)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (live) CallAccent else Ui.muted)
        )
        Spacer(Modifier.width(8.dp))
        Text("Encore voice call", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ui.ink)
    }
}

/** Avatar with a ring; while the call is ringing or connecting the ring pulses outwards. */
@Composable
private fun AvatarHalo(pulsing: Boolean, content: @Composable BoxScope.() -> Unit) {
    val halo: Modifier = if (pulsing) {
        val t = rememberInfiniteTransition(label = "callHalo")
        val p by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
            label = "callHaloPhase"
        )
        Modifier.drawBehind { drawPulse(p) }
    } else {
        Modifier.drawBehind { drawRing() }
    }
    Box(
        modifier = Modifier.size(236.dp).then(halo),
        contentAlignment = Alignment.Center,
        content = content
    )
}

private fun DrawScope.drawRing() {
    drawCircle(
        color = CallAccent.copy(alpha = 0.55f),
        radius = 66.dp.toPx() + 8.dp.toPx(),
        style = Stroke(width = 2.dp.toPx())
    )
}

private fun DrawScope.drawPulse(p: Float) {
    val r0 = 66.dp.toPx() + 8.dp.toPx()
    val r1 = size.minDimension / 2f
    drawRing()
    for (k in 0 until 3) {
        val f = (p + k / 3f) % 1f
        drawCircle(
            color = CallAccent.copy(alpha = 0.5f * (1f - f)),
            radius = r0 + (r1 - r0) * f,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

/** Bottom sheet holding the call controls (same sheet language as the rest of the app). */
@Composable
private fun ControlSheet(content: @Composable RowScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .background(Ui.soft)
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(Ui.muted.copy(alpha = 0.4f))
        )
        Spacer(Modifier.height(28.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
            content = content
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun RoundButton(color: Color, label: String, hangup: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Call,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.rotate(if (hangup) 135f else 0f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ui.ink)
    }
}

@Composable
private fun ToggleButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (on) Ui.ink else Ui.bg)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (on) Ui.onInk else Ui.ink,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ui.ink)
        Text(if (on) "On" else "Off", fontSize = 12.sp, color = Ui.muted)
    }
}
