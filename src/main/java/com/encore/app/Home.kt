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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BadgeYellow = Color(0xFFFFC400)

// champagne gold for the glass look (chips and the story "+"); BadgeYellow above is untouched
private val GoldLight = Color(0xFFFFE08C)
private val Gold = Color(0xFFF2B136)
private val GoldDeep = Color(0xFFC98400)

// thin specular light along the top edge, the detail that makes a surface read as glass
private fun Modifier.topSheen(alpha: Float): Modifier = drawWithContent {
    drawContent()
    val inset = size.height * 0.45f
    drawLine(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = alpha), Color.Transparent)
        ),
        start = Offset(inset, 1.dp.toPx()),
        end = Offset(size.width - inset, 1.dp.toPx()),
        strokeWidth = 1.dp.toPx()
    )
}

// text size is fixed in dp, so the phone font size setting does not change it
@Composable
internal fun fs(v: Float) = with(LocalDensity.current) { v.dp.toSp() }

// TODO: fill this in with your real group check (I haven't seen the Conv class)
private fun isGroupChat(c: Conv): Boolean = false

@Composable
private fun EncoreChip(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    // selected: gold-infused glass (honey core, bright-to-deep gold rim); not selected: clean glass
    val goldCore = Brush.verticalGradient(
        listOf(Gold.copy(alpha = 0.40f), GoldDeep.copy(alpha = 0.18f))
    )
    val rim: Brush = if (selected) {
        Brush.linearGradient(listOf(GoldLight, GoldDeep.copy(alpha = 0.75f)))
    } else {
        SolidColor(Ui.line)
    }
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .glassSurface(shape)
            .then(if (selected) Modifier.background(goldCore) else Modifier)
            .border(1.dp, rim, shape)
            .topSheen(if (selected) 0.85f else 0.5f)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.5.dp),
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
            Text(count.toString(), fontSize = fs(11f), color = Ui.muted)
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
    // contacts: the full list opens over the chat list, "New Contact" opens the add sheet
    var contactsOpen by remember { mutableStateOf(false) }
    var addContact by remember { mutableStateOf(false) }
    val appCtx = LocalContext.current.applicationContext

    // a contact opens its chat; with no chat yet, the server makes sure it exists and then it opens
    fun openContact(id: String) {
        contactsOpen = false
        val conv = chats.firstOrNull { it.peer?.id == id }
        if (conv != null) {
            onOpen(conv)
        } else {
            runIo({
                val cid = ContactsApi.chatId(id)
                Api.conversations().firstOrNull { it.id == cid }
            }) { r ->
                r.onSuccess { found -> if (found != null) onOpen(found) }
                    .onFailure {
                        android.widget.Toast.makeText(appCtx, it.message ?: "Couldn't open the chat", android.widget.Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

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

    // contacts: what was saved on the phone shows at once, then the server list replaces it
    LaunchedEffect(me.id) {
        ContactsStore.attach(appCtx)
        ContactsStore.refresh()
    }

    Ticker(30000L, me.id) {
        refresh()
        checkAuth()
        ContactsStore.refresh()
    }

    OnRealtime { e ->
        when (e.optString("type")) {
            "message", "read", "hello" -> refresh()
            // the contact list changed on another device of yours
            "contacts" -> ContactsStore.refresh()
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
                    .height(56.dp)
                    .padding(start = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // your avatar shows up here only while you have a story; otherwise the header is just "Encore" as before
                if (StoryStore.mine() != null) {
                    StoryAvatar(
                        me.id,
                        me.displayName.ifBlank { me.username },
                        40.dp,
                        onOpenStory = onStories,
                        mine = true
                    )
                    Spacer(Modifier.width(12.dp))
                } else {
                    Spacer(Modifier.width(0.dp))
                }
                Text(
                    "Encore",
                    fontSize = fs(20f),
                    fontWeight = FontWeight.SemiBold,
                    color = Ui.ink,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Ui.ink, modifier = Modifier.size(24.dp))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Contacts", fontSize = fs(16f)) },
                            onClick = {
                                menu = false
                                contactsOpen = true
                            }
                        )
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
            // search field: 48dp box, 7dp side margin, 3dp inner padding, 20dp radius, 24dp icon, 15 text starting at 48dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 7.dp)
                    .padding(3.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .glassSurface(RoundedCornerShape(20.dp))
                        .clickable { onSearch() }
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Ui.muted, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Search chats and people", color = Ui.muted, fontSize = fs(15f))
                }
            }

            // filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .height(50.dp)
                    .padding(horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EncoreChip("All", null, filter == 0) { filter = 0 }
                EncoreChip("Unread", unreadCount, filter == 1) { filter = 1 }
                EncoreChip("Groups", null, filter == 2) { filter = 2 }
                EncoreChip("Bots", null, false) { onBots() }
            }
            HorizontalDivider(thickness = 0.5.dp, color = Ui.line)

            if (loaded && shown.isEmpty()) {
                Text(
                    if (chats.isEmpty()) "No chats yet. Tap New Chat to start one." else "Nothing here",
                    color = Ui.muted,
                    fontSize = fs(16f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                )
            }

            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 80.dp)) {
                items(shown, key = { it.id }) { c ->
                    val p = c.peer
                    // a contact shows under the name you gave them
                    val name = ContactsStore.nameFor(p?.id, p?.displayName ?: "Unknown")
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
                            .padding(start = 11.dp, end = 15.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StoryAvatar(p?.id ?: c.id, name, 52.dp, onOpenStory = onStories)
                        Spacer(Modifier.width(13.dp))
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
                                    fontSize = fs(15f),
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
                                        .defaultMinSize(minWidth = 20.67.dp, minHeight = 20.67.dp)
                                        .clip(RoundedCornerShape(10.33.dp))
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
                        modifier = Modifier.padding(start = 72.dp),
                        thickness = 0.5.dp,
                        color = Ui.line
                    )
                    }
                }
            }

        }

        // main tabs bar: floating pill 56dp tall, 28dp radius, 8dp margin, max width 328dp, 6dp shadow
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 328.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(6.dp, RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp))
                    .background(Ui.bg)
                    .glassSurface(RoundedCornerShape(28.dp), strong = true)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Home, contentDescription = "Chats", tint = Ui.ink, modifier = Modifier.size(24.dp))
                }
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Ui.ink)
                        .clickable { sheet = true }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Ui.onInk, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Chat", color = Ui.onInk, fontSize = fs(16f), fontWeight = FontWeight.Medium)
                }
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).clickable { onProfile() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person, contentDescription = "Profile", tint = Ui.muted, modifier = Modifier.size(24.dp))
                }
            }
        }

        // story "+" (moved here from the header), floats above the New Chat bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = 78.dp)
                .size(48.dp)
                .shadow(
                    14.dp,
                    CircleShape,
                    ambientColor = GoldDeep.copy(alpha = 0.40f),
                    spotColor = GoldDeep.copy(alpha = 0.55f)
                )
                .clip(CircleShape)
                // body: champagne gold, brighter at the top-left, deeper at the bottom-right
                .background(Brush.linearGradient(listOf(GoldLight, Gold, GoldDeep)))
                // glass layer: light catching the top half, soft depth at the bottom
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.45f),
                        0.5f to Color.Transparent,
                        1f to GoldDeep.copy(alpha = 0.30f)
                    )
                )
                // rim light
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.1f))),
                    CircleShape
                )
                .clickable(onClick = onAddStory),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add story", tint = Color(0xFF1A1200))
        }

        SoonToast(soon, 84.dp)

        if (contactsOpen) {
            ContactsScreen(
                onBack = { contactsOpen = false },
                onOpen = { openContact(it.id) },
                onAdd = { addContact = true }
            )
        }
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = glassSheetColor()) {
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
                addContact = true
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

    if (addContact) {
        AddContactSheet(onDismiss = { addContact = false })
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
