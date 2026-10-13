package com.encore.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ScreenBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ui.ink)
        }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SettingRow(
    title: String,
    color: androidx.compose.ui.graphics.Color = Ui.ink,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = color, fontSize = 16.sp)
            if (sub != null) Text(sub, color = Ui.muted, fontSize = 13.sp)
        }
        trailing()
    }
}

@Composable
internal fun rememberAvatarPicker(onPicked: (ByteArray, String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val p = Uploads.inspect(ctx, uri, "image")
                if (p.kind != "image") throw ApiException("bad_type", "Pick a JPG, PNG or WebP picture")
                if (p.size > 5L * 1024 * 1024) throw ApiException("too_large", "Picture is too large (max 5 MB)")
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw ApiException("bad_file", "Can't read this picture")
                onPicked(bytes, p.type)
            } catch (e: Exception) {
                onError(e.message ?: "Can't use this picture")
            }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun go() {
        busy = true
        problem = null
        val o = old
        val n = new
        runIo({ Api.changePassword(o, n) }) { r ->
            busy = false
            r.onSuccess { onDone() }.onFailure { problem = it.message }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui.bg,
        title = { Text("Change password") },
        text = {
            Column {
                OutlinedTextField(
                    value = old,
                    onValueChange = { old = it },
                    label = { Text("Current password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = new,
                    onValueChange = { new = it },
                    label = { Text("New password (8+ characters)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { go() }, enabled = !busy && old.isNotEmpty() && new.length >= 8) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ProfileScreen(me: User, onBack: () -> Unit, onLogout: () -> Unit) {
    var name by remember { mutableStateOf(me.displayName) }
    var bio by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var pw by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runIo({ Api.me() }) { r ->
            r.onSuccess {
                name = it.user.displayName
                bio = it.bio ?: ""
            }.onFailure {
                if ((it as? ApiException)?.code == "unauthorized") onLogout()
            }
        }
    }

    val pick = rememberAvatarPicker(
        onPicked = { bytes, type ->
            busy = true
            msg = null
            runIo({ Api.setAvatar(bytes, type) }) { r ->
                busy = false
                r.onFailure { msg = it.message }
            }
        },
        onError = { msg = it }
    )

    fun save() {
        busy = true
        msg = null
        val n = name
        val b = bio
        runIo({ Api.updateMe(n, b) }) { r ->
            busy = false
            r.onSuccess { msg = "Saved" }.onFailure { msg = it.message }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        ScreenBar("Profile", onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.clip(RoundedCornerShape(60.dp)).clickable { pick() }) {
                Avatar(me.id, name.ifBlank { me.username }, 112.dp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Change photo", color = Ui.muted, fontSize = 13.sp)
            Text("@" + me.username, color = Ui.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text("Bio") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { save() },
                enabled = !busy && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Please wait" else "Save") }
            msg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = if (it == "Saved") Ui.muted else MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Ui.line)
            SettingRow("Change password", onClick = { pw = true })
            HorizontalDivider(color = Ui.line)
            SettingRow("Log out", color = MaterialTheme.colorScheme.error, onClick = onLogout)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pw) {
        PasswordDialog(
            onDismiss = { pw = false },
            onDone = {
                pw = false
                msg = "Password changed"
            }
        )
    }
}

@Composable
fun UserInfoScreen(conv: Conv, onBack: () -> Unit) {
    val peer = conv.peer
    var info by remember { mutableStateOf<ProfileInfo?>(null) }
    var muted by remember { mutableStateOf(conv.muted) }
    var blocked by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmBlock by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }

    LaunchedEffect(conv.id) {
        if (peer != null) {
            runIo({ Api.userProfile(peer.id) }) { r ->
                r.onSuccess {
                    info = it
                    blocked = it.blocked
                }.onFailure { msg = it.message }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenBar("Info", onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Avatar(peer?.id ?: conv.id, peer?.displayName ?: "?", 112.dp)
            Spacer(Modifier.height(12.dp))
            Text(peer?.displayName ?: "Unknown", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (peer != null) Text("@" + peer.username, color = Ui.muted, fontSize = 14.sp)
            val bio = info?.bio
            if (!bio.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(bio, fontSize = 15.sp)
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Ui.line)
            SettingRow("Mute notifications") {
                Switch(
                    checked = muted,
                    onCheckedChange = { on ->
                        muted = on
                        runIo({ Api.mute(conv.id, on) }) { r ->
                            r.onFailure {
                                muted = !on
                                msg = it.message
                            }
                        }
                    }
                )
            }
            HorizontalDivider(color = Ui.line)
            SettingRow(
                if (blocked) "Unblock user" else "Block user",
                color = MaterialTheme.colorScheme.error,
                onClick = {
                    if (blocked) {
                        val id = peer?.id ?: ""
                        runIo({ Api.block(id, false) }) { r ->
                            r.onSuccess { blocked = false }.onFailure { msg = it.message }
                        }
                    } else {
                        confirmBlock = true
                    }
                }
            )
            HorizontalDivider(color = Ui.line)
            SettingRow("Report", color = MaterialTheme.colorScheme.error, onClick = { report = true })
            msg?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmBlock && peer != null) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            containerColor = Ui.bg,
            title = { Text("Block ${peer.displayName}?") },
            text = { Text("You won't be able to message each other until you unblock.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    runIo({ Api.block(peer.id, true) }) { r ->
                        r.onSuccess { blocked = true }.onFailure { msg = it.message }
                    }
                }) { Text("Block", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Cancel") } }
        )
    }

    if (report && peer != null) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { report = false },
            containerColor = Ui.bg,
            title = { Text("Report user") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("What's wrong?") },
                    maxLines = 4
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    report = false
                    val r = reason
                    runIo({ Api.report("user", peer.id, r) }) { res ->
                        res.onSuccess { msg = "Report sent" }.onFailure { msg = it.message }
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { report = false }) { Text("Cancel") } }
        )
    }
}
