package com.encore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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

// text size is fixed in dp, so the phone font size setting does not change it
@Composable
internal fun fs(v: Float) = with(LocalDensity.current) { v.dp.toSp() }

// TODO: fill this in with your real group check (I haven't seen the Conv class)
private fun isGroupChat(c: Conv): Boolean = false

@Composable
private fun EncoreChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .background(if (selected) BadgeYellow.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) BadgeYellow else Ui.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = fs(14f),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Ui.ink else Ui.muted
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), fontSize = fs(13f), color = Ui.muted)
        }
    }
}

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
    var filter by remember { mutableStateOf(0) } // 0 All, 1 Unread, 2 Groups
    val chatsFlow = remember { Sync.convFlow() }
    val chatsState by chatsFlow.collectAsState(initial = null as List<Conv>?)
    val chats: List<Conv> = chatsState ?: emptyList()
    val loaded = chatsState != null
    val unreadCount = chats.count { it.unread > 0 }
    val shown: List<Conv> = when (filter) {
        1 -> chats.filter { it.unread > 0 }
        2 -> chats.filter { isGroupChat(it) }
        else -> chats
    }
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

    // story rings: who has stories right now
    LaunchedEffect(Unit) { StoryStore.refresh() }
    Ticker(60000L, "story-store") { StoryStore.refresh() }

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
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StoryAvatar(
                    me.id,
                    me.displayName.ifBlank { me.username },
                    40.dp,
                    onOpenStory = onStories,
                    mine = true,
                    onAdd = onAddStory
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Encore",
                    fontSize = fs(24f),
                    fontWeight = FontWeight.SemiBold,
                    color = Ui.ink,
                    modifier = Modifier.weight(1f)
                )
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

            // your own story sits in the header; other people's stories show as rings on their avatars

            // search pill (opens the same Search screen as before)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Ui.soft)
                    .clickable { onSearch() }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = Ui.muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text("Search chats and people", color = Ui.muted, fontSize = fs(16f))
            }

            // filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EncoreChip("All", null, filter == 0) { filter = 0 }
                EncoreChip("Unread", unreadCount, filter == 1) { filter = 1 }
                EncoreChip("Groups", null, filter == 2) { filter = 2 }
                EncoreChip("Bots", null, false) { onBots() }
            }
            Spacer(Modifier.height(4.dp))

            HorizontalDivider(thickness = 0.5.dp, color = Ui.line)

            if (loaded && shown.isEmpty()) {
                Text(
                    if (chats.isEmpty()) "No chats yet. Tap New Chat to start one." else "Nothing here",
                    color = Ui.muted,
                    fontSize = fs(16f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(shown, key = { it.id }) { c ->
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
                            .padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StoryAvatar(p?.id ?: c.id, name, 52.dp, onOpenStory = onStories)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    name,
                                    fontSize = fs(16f),
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
                                    fontSize = fs(14f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(timeLabel(last?.createdAt ?: c.updatedAt), color = Ui.muted, fontSize = fs(12f))
                            if (c.unread > 0) {
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (c.muted) Ui.muted else BadgeYellow)
                                        .padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (c.unread > 99) "99+" else c.unread.toString(),
                                        color = if (c.muted) Ui.onInk else Color(0xFF111111),
                                        fontSize = fs(12f),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 80.dp),
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

        // story "+" (moved here from the header), floats above the New Chat bar
        FloatingActionButton(
            onClick = onAddStory,
            containerColor = BadgeYellow,
            contentColor = Color(0xFF111111),
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 92.dp)
                .size(48.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add story")
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
