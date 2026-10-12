package com.encore.app

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

private fun timeLabel(ms: Long): String {
    val recent = System.currentTimeMillis() - ms < 24L * 60 * 60 * 1000
    val pattern = if (recent) "HH:mm" else "dd MMM"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(ms))
}

private val avatarTones = listOf(0xFFE3E8EF, 0xFFEADFD5, 0xFFDDE7DF, 0xFFE9DDE6, 0xFFDDE5EA, 0xFFEDE6D2)

@Composable
fun Avatar(seed: String, name: String, size: Dp) {
    val tone = Color(avatarTones[Math.floorMod(seed.hashCode(), avatarTones.size)])
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(tone),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.take(1).uppercase(),
            color = Color(0xFF111111),
            fontWeight = FontWeight.Medium,
            fontSize = (size.value * 0.4f).sp
        )
    }
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
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
private fun StartChatDialog(onDismiss: () -> Unit, onStarted: (Conv) -> Unit) {
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
        containerColor = Ui.bg,
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
private fun ThemeDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ui.bg,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(me: User, onOpen: (Conv) -> Unit, onLogout: () -> Unit) {
    var chats by remember { mutableStateOf<List<Conv>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var startDialog by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val soon = remember { SoonState() }
    val dash = Ui.muted.copy(alpha = 0.7f)

    fun refresh() {
        runIo({ Api.conversations() }) { r ->
            r.onSuccess {
                chats = it
                loaded = true
            }.onFailure {
                if ((it as? ApiException)?.code == "unauthorized") onLogout()
            }
        }
    }

    Ticker(30000L, me.id) { refresh() }

    OnRealtime { e ->
        when (e.optString("type")) {
            "message", "read", "hello" -> refresh()
            else -> {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Encore",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { soon.show("Search") }) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Ui.ink)
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { soon.show("Stories") }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .drawBehind {
                                    drawCircle(
                                        color = dash,
                                        style = Stroke(
                                            width = 1.5.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                                        )
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Ui.muted)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Add story", fontSize = 12.sp, color = Ui.muted)
                    }
                }
                items(chats.filter { it.peer != null }.take(10), key = { it.id }) { c ->
                    val p = c.peer!!
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { soon.show("Stories") }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .border(2.dp, Ui.ink, CircleShape)
                                .padding(4.dp)
                        ) {
                            Avatar(p.id, p.displayName, 56.dp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            p.displayName,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 64.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chats", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Ui.ink)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Theme") },
                            onClick = {
                                menu = false
                                themeDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Log out") },
                            onClick = {
                                menu = false
                                onLogout()
                            }
                        )
                    }
                }
            }

            if (loaded && chats.isEmpty()) {
                Text(
                    "No chats yet. Tap New Chat to start one.",
                    color = Ui.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(chats, key = { it.id }) { c ->
                    val p = c.peer
                    val name = p?.displayName ?: "Unknown"
                    val last = c.last
                    val preview = when {
                        last == null -> "Say hello"
                        last.deleted -> "Message deleted"
                        last.preview != null -> (if (last.senderId == me.id) "You: " else "") + last.preview
                        else -> "Media"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(c) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(p?.id ?: c.id, name, 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                preview,
                                color = if (c.unread > 0) Ui.ink else Ui.muted,
                                fontWeight = if (c.unread > 0) FontWeight.Medium else FontWeight.Normal,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(timeLabel(last?.createdAt ?: c.updatedAt), color = Ui.muted, fontSize = 12.sp)
                            if (c.unread > 0) {
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Ui.ink)
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        if (c.unread > 99) "99+" else c.unread.toString(),
                                        color = Ui.onInk,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = Ui.line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(modifier = Modifier.padding(10.dp)) {
                    Icon(Icons.Default.Home, contentDescription = "Chats", tint = Ui.ink)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Ui.ink)
                        .clickable { sheet = true }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Ui.onInk)
                    Spacer(Modifier.width(6.dp))
                    Text("New Chat", color = Ui.onInk, fontWeight = FontWeight.Medium)
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { soon.show("Profile") }
                        .padding(10.dp)
                ) {
                    Icon(Icons.Default.Person, contentDescription = "Profile", tint = Ui.muted)
                }
            }
        }

        SoonToast(soon, 84.dp)
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = Ui.bg) {
            SheetRow(Icons.Default.Email, "New Chat", "Send a message to your contact") {
                sheet = false
                startDialog = true
            }
            SheetRow(Icons.Default.AccountCircle, "New Contact", "Add a contact to be able to send messages") {
                sheet = false
                soon.show("New contact")
            }
            SheetRow(Icons.Default.Face, "New Community", "Join the community around you") {
                sheet = false
                soon.show("Communities")
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { sheet = false },
                colors = ButtonDefaults.buttonColors(containerColor = Ui.soft, contentColor = Ui.ink),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) { Text("Cancel") }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (startDialog) {
        StartChatDialog(
            onDismiss = { startDialog = false },
            onStarted = {
                startDialog = false
                onOpen(it)
            }
        )
    }

    if (themeDialog) {
        ThemeDialog(onDismiss = { themeDialog = false })
    }
}
