package com.encore.app

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val CallRed = Color(0xFFE5484D)
private val CallGreen = Color(0xFF2E9E5B)

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
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(96.dp))
            Avatar(CallManager.peerId, CallManager.peerName, 132.dp)
            Spacer(Modifier.height(24.dp))
            Text(CallManager.peerName, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Ui.ink)
            Spacer(Modifier.height(8.dp))
            Text(status, fontSize = 15.sp, color = Ui.muted)
            Spacer(Modifier.weight(1f))

            if (phase == CallPhase.Ringing) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(CallRed, "Decline", hangup = true) { CallManager.hangup() }
                    RoundButton(CallGreen, "Accept", hangup = false) { gate { CallManager.accept() } }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top
                ) {
                    ToggleButton("Mute", CallManager.muted) { CallManager.toggleMute() }
                    RoundButton(CallRed, "End", hangup = true) { CallManager.hangup() }
                    ToggleButton("Speaker", CallManager.speaker) { CallManager.toggleSpeaker() }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun RoundButton(color: Color, label: String, hangup: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(68.dp)
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
        Text(label, fontSize = 13.sp, color = Ui.muted)
    }
}

@Composable
private fun ToggleButton(label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(if (on) Ui.ink else Ui.soft)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (on) Ui.onInk else Ui.ink)
        }
        Spacer(Modifier.height(8.dp))
        Text(if (on) "On" else "Off", fontSize = 13.sp, color = Ui.muted)
    }
}
