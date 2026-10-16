package com.encore.app

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest

private val homeHandler = Handler(Looper.getMainLooper())

internal fun <T> runIo(work: () -> T, done: (Result<T>) -> Unit) {
    Thread {
        val r = runCatching(work)
        homeHandler.post { done(r) }
    }.start()
}

@Composable
internal fun Ticker(ms: Long, key: Any, action: () -> Unit) {
    val latest by rememberUpdatedState(action)
    DisposableEffect(key) {
        var alive = true
        val tick = object : Runnable {
            override fun run() {
                if (!alive) return
                latest()
                homeHandler.postDelayed(this, ms)
            }
        }
        homeHandler.post(tick)
        onDispose {
            alive = false
            homeHandler.removeCallbacks(tick)
        }
    }
}

class SoonState {
    var text by mutableStateOf<String?>(null)
    private val hide = Runnable { text = null }

    fun show(what: String) {
        text = what
        homeHandler.removeCallbacks(hide)
        homeHandler.postDelayed(hide, 1800)
    }
}

@Composable
fun BoxScope.SoonToast(state: SoonState, bottom: Dp) {
    state.text?.let {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = bottom)
                .clip(RoundedCornerShape(20.dp))
                .background(Ui.ink)
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text("$it · Coming soon", color = Ui.onInk, fontSize = 14.sp)
        }
    }
}

internal fun timeLabel(ms: Long): String {
    val recent = System.currentTimeMillis() - ms < 24L * 60 * 60 * 1000
    val pattern = if (recent) "HH:mm" else "dd MMM"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(ms))
}

private val avatarTones = listOf(0xFFE3E8EF, 0xFFEADFD5, 0xFFDDE7DF, 0xFFE9DDE6, 0xFFDDE5EA, 0xFFEDE6D2)

@Composable
fun Avatar(seed: String, name: String, size: Dp) {
    val tone = Color(avatarTones[Math.floorMod(seed.hashCode(), avatarTones.size)])
    val url = Avatars.url(seed)
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(tone),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            // the cache keys do not depend on the login token, so the same picture is reused from memory/disk
            // every time (no re-fetch, no fade-in). The letter is only drawn until the picture is ready.
            val req = remember(url) {
                ImageRequest.Builder(ctx)
                    .data(url)
                    .addHeader("Authorization", "Bearer ${Session.token}")
                    .memoryCacheKey(url)
                    .diskCacheKey(url)
                    .crossfade(false)
                    .build()
            }
            val painter = rememberAsyncImagePainter(model = req, contentScale = ContentScale.Crop)
            if (painter.state !is AsyncImagePainter.State.Success) {
                Letter(name, size)
            }
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Letter(name, size)
        }
    }
}

@Composable
private fun Letter(name: String, size: Dp) {
    Text(
        name.take(1).uppercase(),
        color = Color(0xFF111111),
        fontWeight = FontWeight.Medium,
        fontSize = (size.value * 0.4f).sp
    )
}

@Composable
internal fun SheetRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Ui.ink)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(sub, color = Ui.muted, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun StartChatDialog(onDismiss: () -> Unit, onStarted: (Conv) -> Unit) {
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun go() {
        busy = true
        problem = null
        val n = name
        runIo({ Api.startDm(n) }) { r ->
            busy = false
            r.onSuccess { onStarted(it) }.onFailure {
                problem = if (it is ApiException) it.message else "Something went wrong"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassDialogColor(),
        title = { Text("New chat") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Username") },
                    singleLine = true
                )
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { go() }, enabled = !busy && name.isNotBlank()) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun ThemeDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glassDialogColor(),
        title = { Text("Theme") },
        text = {
            Column {
                listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { ThemeStore.set(ctx, key) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = ThemeStore.mode == key, onClick = { ThemeStore.set(ctx, key) })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
