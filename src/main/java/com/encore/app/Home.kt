package com.encore.app

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    me: User,
    onOpen: (Conv) -> Unit,
    onLogout: () -> Unit,
    onProfile: () -> Unit,
    onNewGroup: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onStories: (List<StoryGroup>, Int, Int) -> Unit,
    onAddStory: () -> Unit
) {
    // the chat list comes from the local database (kept up to date by Sync), so it shows instantly and works offline
    val chatsFlow = remember { Sync.convFlow() }
    val chatsState by chatsFlow.collectAsState(initial = null as List<Conv>?)
    val chats: List<Conv> = chatsState ?: emptyList()
    val loaded = chatsState != null
    var sheet by remember { mutableStateOf(false) }
    var startDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val soon = remember { SoonState() }

    fun refresh() {
        Sync.kick()
    }

    // keeps the old behaviour: an expired login sends the user back to the login screen
    fun checkAuth() {
        runIo({ Api.conversations() }) { r ->
            r.onFailure {
                if ((it as? ApiException)?.code == "unauthorized") onLogout()
            }
        }
    }

    Ticker(30000L, me.id) {
        refresh()
        checkAuth()
    }

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
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Ui.ink)
                }
            }

            StoriesRow(me, onStories, onAddStory)

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
                            text = { Text("Settings") },
                            onClick = {
                                menu = false
                                onSettings()
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
                        last.kind == "system" -> last.preview ?: ""
                        last.preview != null -> (if (last.senderId == me.id) "You: " else "") + last.preview
                        else -> (if (last.senderId == me.id) "You: " else "") + kindLabel(last.kind)
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
                                        .background(if (c.muted) Ui.muted else Ui.ink)
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
                        .clickable { onProfile() }
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
            SheetRow(Icons.Default.Face, "New Group", "Chat with several people at once") {
                sheet = false
                onNewGroup()
            }
            SheetRow(Icons.Default.AccountCircle, "New Contact", "Add a contact to be able to send messages") {
                sheet = false
                soon.show("New contact")
            }
            SheetRow(Icons.Default.Star, "New Community", "Join the community around you") {
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
}
