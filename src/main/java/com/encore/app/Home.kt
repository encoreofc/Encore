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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val homeHandler = Handler(Looper.getMainLooper())

private fun <T> runIo(work: () -> T, done: (Result<T>) -> Unit) {
    Thread {
        val r = runCatching(work)
        homeHandler.post { done(r) }
    }.start()
}

@Composable
private fun Ticker(ms: Long, key: Any, action: () -> Unit) {
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

data class Preview(val contact: Contact, val last: String?, val time: Long?)

private var tablesReady = false

private fun loadPreviews(me: Long): List<Preview> {
    if (!tablesReady) {
        Db.query(
            "CREATE TABLE IF NOT EXISTS contacts (" +
                "owner_id INTEGER NOT NULL, contact_id INTEGER NOT NULL, " +
                "PRIMARY KEY (owner_id, contact_id))"
        )
        Db.query(
            "CREATE TABLE IF NOT EXISTS messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "sender_id INTEGER NOT NULL, receiver_id INTEGER NOT NULL, " +
                "body TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
        tablesReady = true
    }
    val between = "((m.sender_id = ? AND m.receiver_id = u.id) OR (m.sender_id = u.id AND m.receiver_id = ?))"
    val rows = Db.query(
        "SELECT u.id, u.username, " +
            "(SELECT m.body FROM messages m WHERE $between ORDER BY m.id DESC LIMIT 1), " +
            "(SELECT m.created_at FROM messages m WHERE $between ORDER BY m.id DESC LIMIT 1) " +
            "FROM users u WHERE u.id IN (SELECT contact_id FROM contacts WHERE owner_id = ?) " +
            "OR u.id IN (SELECT receiver_id FROM messages WHERE sender_id = ?) " +
            "OR u.id IN (SELECT sender_id FROM messages WHERE receiver_id = ?) " +
            "ORDER BY 4 DESC, u.username",
        me, me, me, me, me, me, me
    )
    return rows.map { Preview(Contact(it[0]!!.toLong(), it[1]!!), it[2], it[3]?.toLong()) }
}

private fun timeLabel(ms: Long): String {
    val recent = System.currentTimeMillis() - ms < 24L * 60 * 60 * 1000
    val pattern = if (recent) "HH:mm" else "dd MMM"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(ms))
}

private val avatarTones = listOf(0xFFE3E8EF, 0xFFEADFD5, 0xFFDDE7DF, 0xFFE9DDE6, 0xFFDDE5EA, 0xFFEDE6D2)

@Composable
private fun Avatar(c: Contact, size: Dp) {
    val tone = Color(avatarTones[(c.id % avatarTones.size).toInt()])
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(tone),
        contentAlignment = Alignment.Center
    ) {
        Text(
            c.username.take(1).uppercase(),
            color = Ui.ink,
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
private fun StartChatDialog(user: User, onDismiss: () -> Unit, onStarted: (Contact) -> Unit) {
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun go() {
        busy = true
        problem = null
        val n = name
        runIo({ ChatDb.addContact(user.id, n) }) { r ->
            busy = false
            r.onSuccess { onStarted(it) }.onFailure {
                problem = if (it is DbException) it.message
                else "Can't reach the server. Check your connection."
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(user: User, onOpen: (Contact) -> Unit, onLogout: () -> Unit) {
    var items by remember { mutableStateOf<List<Preview>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var soon by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var startDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val hideSoon = remember {
        object : Runnable {
            override fun run() {
                soon = null
            }
        }
    }

    fun showSoon(what: String) {
        soon = what
        homeHandler.removeCallbacks(hideSoon)
        homeHandler.postDelayed(hideSoon, 1800)
    }

    fun refresh() {
        runIo({ loadPreviews(user.id) }) { r ->
            r.onSuccess {
                items = it
                loaded = true
            }
        }
    }

    Ticker(5000L, user.id) { refresh() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
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
                IconButton(onClick = { showSoon("Search") }) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Ui.ink)
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { showSoon("Stories") }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .drawBehind {
                                    drawCircle(
                                        color = Ui.muted.copy(alpha = 0.7f),
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
                items(items.take(10), key = { it.contact.id }) { p ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { showSoon("Stories") }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .border(2.dp, Ui.ink, CircleShape)
                                .padding(4.dp)
                        ) {
                            Avatar(p.contact, 56.dp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            p.contact.username,
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
                            text = { Text("Log out") },
                            onClick = {
                                menu = false
                                onLogout()
                            }
                        )
                    }
                }
            }

            if (loaded && items.isEmpty()) {
                Text(
                    "No chats yet. Tap New Chat to start one.",
                    color = Ui.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(items, key = { it.contact.id }) { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(p.contact) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(p.contact, 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                p.contact.username,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                p.last ?: "Say hello",
                                color = Ui.muted,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        p.time?.let {
                            Spacer(Modifier.width(8.dp))
                            Text(timeLabel(it), color = Ui.muted, fontSize = 12.sp)
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
                Icon(Icons.Default.Home, contentDescription = "Chats", tint = Ui.ink)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Ui.ink)
                        .clickable { sheet = true }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("New Chat", color = Color.White, fontWeight = FontWeight.Medium)
                }
                Icon(
                    Icons.Default.Person,
                    contentDescription = "Profile",
                    tint = Ui.muted,
                    modifier = Modifier.clickable { showSoon("Profile") }
                )
            }
        }

        soon?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 84.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ui.ink)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text("$it · Coming soon", color = Color.White, fontSize = 14.sp)
            }
        }
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = Color.White) {
            SheetRow(Icons.Default.Email, "New Chat", "Send a message to your contact") {
                sheet = false
                startDialog = true
            }
            SheetRow(Icons.Default.AccountCircle, "New Contact", "Add a contact to be able to send messages") {
                sheet = false
                showSoon("New contact")
            }
            SheetRow(Icons.Default.Face, "New Community", "Join the community around you") {
                sheet = false
                showSoon("Communities")
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
            user,
            onDismiss = { startDialog = false },
            onStarted = {
                startDialog = false
                onOpen(it)
            }
        )
    }
}
