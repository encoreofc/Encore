package com.encore.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val searchHandler = Handler(Looper.getMainLooper())

private fun searchPeople(q: String): List<Peer> {
    val arr = Api.call("GET", "/v1/users/search?q=" + Uri.encode(q)).getJSONArray("users")
    return List(arr.length()) {
        val o = arr.getJSONObject(it)
        val key = if (o.isNull("avatar_key")) null else o.getString("avatar_key")
        Avatars.put(o.getString("id"), "u", key)
        Peer(
            o.getString("id"),
            o.getString("username"),
            o.optString("display_name", o.getString("username")),
            null,
            key
        )
    }
}

@Composable
private fun ResultRow(seed: String, name: String, sub: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(seed, name, 46.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(sub, fontSize = 13.sp, color = Ui.muted, maxLines = 1)
        }
    }
}

@Composable
fun SearchScreen(me: User, onBack: () -> Unit, onOpen: (Conv) -> Unit) {
    var q by remember { mutableStateOf("") }
    var chats by remember { mutableStateOf<List<Conv>>(emptyList()) }
    var people by remember { mutableStateOf<List<Peer>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val pendingRun = remember { arrayOfNulls<Runnable>(1) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: Exception) {
        }
        runIo({ Api.conversations() }) { r -> r.onSuccess { chats = it } }
    }

    fun schedule(text: String) {
        pendingRun[0]?.let { searchHandler.removeCallbacks(it) }
        val t = text.trim()
        if (t.length < 2) {
            people = emptyList()
            busy = false
            return
        }
        busy = true
        val run = Runnable {
            runIo({ searchPeople(t) }) { r ->
                if (q.trim() == t) {
                    busy = false
                    r.onSuccess {
                        people = it
                        problem = null
                    }.onFailure { problem = it.message }
                }
            }
        }
        pendingRun[0] = run
        searchHandler.postDelayed(run, 350)
    }

    fun openPerson(p: Peer) {
        runIo({ Api.startDm(p.username) }) { r ->
            r.onSuccess { onOpen(it) }.onFailure { problem = it.message }
        }
    }

    val needle = q.trim().lowercase()
    val chatHits = if (needle.isEmpty()) emptyList() else chats.filter { c ->
        val p = c.peer
        p != null && (p.displayName.lowercase().contains(needle) || p.username.lowercase().contains(needle))
    }
    val chatPeerIds = chatHits.mapNotNull { it.peer?.id }.toSet()
    val peopleHits = people.filter { it.id != me.id && it.id !in chatPeerIds }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ui.ink)
            }
            TextField(
                value = q,
                onValueChange = {
                    q = it
                    schedule(it)
                },
                placeholder = { Text("Search chats and people", color = Ui.muted) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Ui.soft,
                    unfocusedContainerColor = Ui.soft,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
            )
            if (q.isNotEmpty()) {
                IconButton(onClick = {
                    q = ""
                    schedule("")
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = Ui.muted)
                }
            }
        }

        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        if (needle.isEmpty()) {
            Text(
                "Search by name or username",
                color = Ui.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
            )
        } else if (chatHits.isEmpty() && peopleHits.isEmpty() && !busy && needle.length >= 2) {
            Text(
                "No results",
                color = Ui.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (chatHits.isNotEmpty()) {
                item {
                    Text(
                        "Chats",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ui.muted,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
                    )
                }
                items(chatHits, key = { "c" + it.id }) { c ->
                    val p = c.peer
                    ResultRow(p?.id ?: c.id, p?.displayName ?: "Unknown", "@" + (p?.username ?: "")) { onOpen(c) }
                }
            }
            if (peopleHits.isNotEmpty()) {
                item {
                    Text(
                        "People",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ui.muted,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
                    )
                }
                items(peopleHits, key = { "p" + it.id }) { p ->
                    ResultRow(p.id, p.displayName, "@" + p.username) { openPerson(p) }
                }
            }
        }
    }
}
