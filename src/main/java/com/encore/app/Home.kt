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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BadgeYellow = Color(0xFFFFC400)

// Telegram-style: text size is fixed in dp, so the phone's Font size setting can't shrink it
@Composable
internal fun fs(v: Float) = with(LocalDensity.current) { v.dp.toSp() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    me: User,
    onOpen: (Conv) -> Unit,
    onLogout: () -> Unit,
    onProfile: () -> Unit,
    onNewGroup: () -> Unit,
    onSearch: () -> Unit,
    onBots: () -> Unit,
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
            "badge" -> BadgeStore.apply(e)
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
                    .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Encore",
                    fontSize = fs(26f),
                    fontWeight = FontWeight.Bold,
                    color = Ui.ink,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onAddStory, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Default.AddCircle, contentDescription = "Add story", tint = Ui.ink, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = onSearch, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Ui.ink, modifier = Modifier.size(28.dp))
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Ui.ink, modifier = Modifier.size(28.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Settings", fontSize = fs(16f)) },
                            onClick = {
                                menu = false
                                onSettings()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Log out", fontSize = fs(16f)) },
                            onClick = {
                                menu = false
                                onLogout()
                            }
                        )
                    }
                }
            }

            StoriesRow(me, onStories, onAddStory)

            HorizontalDivider(thickness = 0.5.dp, color = Ui.line)

            if (loaded && chats.isEmpty()) {
                Text(
                    "No chats yet. Tap New Chat to start one.",
                    color = Ui.muted,
                    fontSize = fs(16f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(chats, key = { it.id }) { c ->
                    val p = c.peer
                    val name = p?.displayName ?: "Unknown"
                    val last = c.last
                    val mineLast = last != null && !last.deleted && last.kind != "system" && last.senderId == me.id
                    val preview = when {
                        last == null -> "Say hello"
                        last.deleted -> "Message deleted"
                        last.kind == "system" -> last.preview ?: ""
                        else -> last.preview ?: kindLabel(last.kind)
                    }
                    Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(c) }
                            .padding(start = 14.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(p?.id ?: c.id, name, 56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    name,
                                    fontSize = fs(17f),
                                    fontWeight = FontWeight.Medium,
                                    color = Ui.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Badge(p?.id, name = name)
                            }
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (mineLast) {
                                    Icon(
                                        Icons.Default.Done,
                                        contentDescription = null,
                                        tint = Ui.muted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    preview,
                                    color = Ui.muted,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = fs(16f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(timeLabel(last?.createdAt ?: c.updatedAt), color = Ui.muted, fontSize = fs(13f))
                            if (c.unread > 0) {
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(if (c.muted) Ui.muted else BadgeYellow)
                                        .padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (c.unread > 99) "99+" else c.unread.toString(),
                                        color = if (c.muted) Ui.onInk else Color(0xFF111111),
                                        fontSize = fs(13f),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 84.dp),
                        thickness = 0.5.dp,
                        color = Ui.line
                    )
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
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Ui.onInk)
                    Spacer(Modifier.width(6.dp))
                    Text("New Chat", color = Ui.onInk, fontSize = fs(16f), fontWeight = FontWeight.Medium)
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
            SheetRow(Icons.Default.Build, "Bots", "Find bots and make stickers") {
                sheet = false
                onBots()
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
