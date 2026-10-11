package com.encore.app

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ui = Handler(Looper.getMainLooper())

private fun <T> ioCall(work: () -> T, done: (Result<T>) -> Unit) {
    Thread {
        val r = runCatching(work)
        ui.post { done(r) }
    }.start()
}

@Composable
private fun Every(ms: Long, key: Any, action: () -> Unit) {
    val latest by rememberUpdatedState(action)
    DisposableEffect(key) {
        var alive = true
        val tick = object : Runnable {
            override fun run() {
                if (!alive) return
                latest()
                ui.postDelayed(this, ms)
            }
        }
        ui.post(tick)
        onDispose {
            alive = false
            ui.removeCallbacks(tick)
        }
    }
}

private fun message(e: Throwable): String =
    if (e is DbException) e.message ?: "Something went wrong"
    else "Can't reach the server. Check your connection."

@Composable
fun ChatApp(user: User, onLogout: () -> Unit) {
    var open by remember { mutableStateOf<Contact?>(null) }
    val chat = open
    if (chat == null) {
        ChatListScreen(user, onOpen = { open = it }, onLogout = onLogout)
    } else {
        BackHandler { open = null }
        ChatScreen(user, chat, onBack = { open = null })
    }
}

@Composable
private fun ChatList(user: User, onOpen: (Contact) -> Unit, onLogout: () -> Unit) {
    var chats by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        ioCall({ ChatDb.chats(user.id) }) { r ->
            r.onSuccess {
                chats = it
                loaded = true
            }
        }
    }

    fun add() {
        busy = true
        problem = null
        val name = newName
        ioCall({ ChatDb.addContact(user.id, name) }) { r ->
            busy = false
            r.onSuccess {
                newName = ""
                onOpen(it)
            }.onFailure { problem = message(it) }
        }
    }

    Every(5000L, user.id) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "@${user.username}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onLogout) { Text("Log out") }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Add by username") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { add() }, enabled = !busy && newName.isNotBlank()) { Text("Add") }
        }
        problem?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        if (loaded && chats.isEmpty()) {
            Text(
                "No chats yet. Add someone by username.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
        LazyColumn {
            items(chats, key = { it.id }) { c ->
                Text(
                    "@${c.username}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(c) }
                        .padding(vertical = 16.dp)
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ChatScreen(me: User, other: Contact, onBack: () -> Unit) {
    var msgs by remember { mutableStateOf<List<Message>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    fun load() {
        ioCall({ ChatDb.messages(me.id, other.id) }) { r ->
            r.onSuccess { msgs = it }
        }
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty()) return
        sending = true
        problem = null
        ioCall({
            ChatDb.send(me.id, other.id, body)
            ChatDb.messages(me.id, other.id)
        }) { r ->
            sending = false
            r.onSuccess {
                text = ""
                msgs = it
            }.onFailure { problem = message(it) }
        }
    }

    Every(3000L, other.id) { load() }

    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.scrollToItem(msgs.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("@${other.username}", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(msgs, key = { it.id }) { m ->
                val mine = m.senderId == me.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalAlignment = if (mine) Alignment.End else Alignment.Start
                ) {
                    Surface(
                        color = if (mine) MaterialTheme.colorScheme.primary else Color(0xFF2A2D31),
                        contentColor = if (mine) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.widthIn(max = 280.dp)
                    ) {
                        Text(m.body, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { send() }, enabled = !sending && text.isNotBlank()) { Text("Send") }
        }
    }
}
