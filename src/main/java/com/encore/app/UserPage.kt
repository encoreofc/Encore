package com.encore.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

// ---------------------------------------------------------------------------
// Telegram's profile header, measured from ProfileActivity / ProfileActionsView
// (screen 360dp wide in the reference screenshot):
//   avatar 96dp, top = status bar + 56 (action bar) - 21 = status bar + 35dp
//   name 17.5dp * 1.12 = 19.6sp bold, centre 28dp under the avatar
//   action tiles: 54dp tall, 14dp from the screen edge, 7dp apart, corners 16dp,
//                 icon 24dp (7dp from the top), label 11sp bold, tile top 85dp under the avatar
//   info card: 12dp from the edge, corners 16dp, rows padded 18 / 10dp,
//              value 16sp, label 13sp, 5dp between them
//   gifts grid: tiles 12dp from the edge (GiftsSection adds 12.5dp - 7dp, so 6.5dp is given to it)
// ---------------------------------------------------------------------------

private val StoryGold = listOf(
    Color(0xFFFFE27A),
    Color(0xFFFFC400),
    Color(0xFFB8860B),
    Color(0xFFFFC400),
    Color(0xFFFFE27A)
)

@Composable
private fun GalleryGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val sw = w * 0.08f
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.1f, w * 0.14f),
            size = Size(w * 0.8f, w * 0.72f),
            cornerRadius = CornerRadius(w * 0.12f),
            style = Stroke(width = sw)
        )
        drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.34f, w * 0.36f))
        val mountain = Path().apply {
            moveTo(w * 0.12f, w * 0.72f)
            lineTo(w * 0.36f, w * 0.54f)
            lineTo(w * 0.54f, w * 0.68f)
            lineTo(w * 0.68f, w * 0.56f)
            lineTo(w * 0.88f, w * 0.72f)
        }
        drawPath(
            mountain,
            color,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

// circle with a slash, drawn by hand (no extra icon library needed)
@Composable
private fun BlockGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val sw = w * 0.09f
        drawCircle(
            color,
            radius = w * 0.38f,
            center = Offset(w / 2f, w / 2f),
            style = Stroke(width = sw)
        )
        drawLine(
            color,
            start = Offset(w * 0.24f, w * 0.24f),
            end = Offset(w * 0.76f, w * 0.76f),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
    }
}

/** The bell; when muted a slash is drawn across it with a small gap in the tile colour. */
@Composable
private fun MuteGlyph(muted: Boolean) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Notifications, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(24.dp))
        if (muted) {
            Canvas(Modifier.size(24.dp)) {
                val w = size.width
                val sw = w * 0.09f
                drawLine(
                    Ui.soft,
                    start = Offset(w * 0.2f, w * 0.14f),
                    end = Offset(w * 0.82f, w * 0.86f),
                    strokeWidth = sw * 2.6f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    Ui.ink,
                    start = Offset(w * 0.2f, w * 0.14f),
                    end = Offset(w * 0.82f, w * 0.86f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/** One action under the name: 54dp tall, icon 24dp starting 6.9dp from the top, label 11sp bold (top at 33.6dp). */
@Composable
private fun ActionTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Ui.soft)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(6.9.dp))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(2.7.dp))
        Text(
            label,
            color = Ui.ink,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** A row of the info card: the value on top (16sp), the small grey label under it (13sp). */
@Composable
private fun InfoRow(value: String, label: String, valueLineHeight: TextUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Text(value, color = Ui.ink, fontSize = 16.sp, lineHeight = valueLineHeight)
        Spacer(Modifier.height(5.dp))
        Text(label, color = Ui.muted, fontSize = 13.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit, color: Color = Ui.ink, icon: @Composable () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = color) },
        leadingIcon = icon,
        onClick = onClick
    )
}

/**
 * The round photo. 96dp like Telegram. With a story the photo is pulled in by 3.5dp (Telegram's story inset)
 * and a golden ring is drawn around it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroAvatar(
    id: String,
    name: String,
    url: String?,
    hasStory: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onBounds: (Rect) -> Unit
) {
    val inset = if (hasStory) 3.5.dp else 0.dp
    val photo = 96.dp - inset * 2
    Box(
        modifier = Modifier
            .size(96.dp)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(photo)
                .clip(CircleShape)
                .background(Ui.soft)
        ) {
            // the letters show until the photo is there, or when there is no photo
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Avatar(id, name, photo) }
            if (url != null) {
                val ctx = LocalContext.current
                val req = remember(url) {
                    ImageRequest.Builder(ctx)
                        .data(url)
                        .addHeader("Authorization", "Bearer ${Session.token}")
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = req,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (hasStory) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 2.33.dp.toPx()
                drawCircle(
                    brush = Brush.sweepGradient(colors = StoryGold, center = center),
                    radius = (size.minDimension - sw) / 2f,
                    style = Stroke(width = sw)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserPageScreen(
    me: User,
    conv: Conv,
    onBack: () -> Unit,
    onStories: (List<StoryGroup>, Int, Int) -> Unit,
    onSearch: () -> Unit,
    onMedia: () -> Unit,
    // true when this person is in your contacts
    isContact: Boolean = false,
    // called after a gift was sent from this page
    onSendGift: () -> Unit = {}
) {
    val peer = conv.peer
    // what was last known is used right away, so the page does not fill in piece by piece
    val cached = remember(conv.id) { peer?.let { ProfileCache.get(it.id) } }
    var bio by remember(conv.id) { mutableStateOf<String?>(cached?.bio) }
    var location by remember(conv.id) { mutableStateOf<String?>(cached?.location) }
    var blocked by remember(conv.id) { mutableStateOf(cached?.blocked ?: false) }
    var muted by remember { mutableStateOf(conv.muted) }
    var group by remember { mutableStateOf<StoryGroup?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmBlock by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }
    // the photo viewer: the address of the photo in use while it is open, and where the avatar sits on screen
    var photoUrl by remember { mutableStateOf<String?>(null) }
    var avatarBox by remember { mutableStateOf<Rect?>(null) }
    val soon = remember { SoonState() }
    val appCtx = LocalContext.current.applicationContext
    // gifts: null while loading, empty when the person has none
    var gifts by remember(conv.id) { mutableStateOf<List<GiftItem>?>(null) }
    var openGift by remember { mutableStateOf<GiftItem?>(null) }
    var shopOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // contacts: in your contacts when the caller says so or the saved list has them
    val inContacts = isContact || ContactsStore.has(peer?.id)
    var contactSheet by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val giftName = peer?.displayName?.trim()?.substringBefore(' ').orEmpty()

    LaunchedEffect(conv.id) {
        if (peer != null) {
            // 1) what we already know, instantly
            runIo({ Db.dao(appCtx).profileNow(peer.id) }) { r ->
                r.onSuccess { p ->
                    if (p != null) {
                        bio = p.bio
                        location = p.location
                        blocked = p.blocked
                        ProfileCache.put(p)
                    }
                }
            }
            // 2) refresh from the server and remember it
            runIo({ Api.call("GET", "/v1/users/${peer.id}").getJSONObject("user") }) { r ->
                r.onSuccess { u ->
                    bio = if (u.isNull("bio")) null else u.getString("bio")
                    location = if (u.isNull("location")) null else u.getString("location")
                    blocked = u.optBoolean("blocked")
                    val b0 = bio
                    val l0 = location
                    val k0 = blocked
                    val fresh = ProfileEntity(peer.id, b0, l0, k0)
                    ProfileCache.put(fresh)
                    runIo({ Db.dao(appCtx).putProfileNow(fresh) }) { }
                }.onFailure {
                    msg = it.message
                }
            }
            runIo({ StoriesApi.userGroup(peer) }) { r -> r.onSuccess { group = it } }
            runIo({ GiftsApi.received(peer.id) }) { r ->
                r.onSuccess { gifts = it }.onFailure { gifts = emptyList() }
            }
        }
    }

    fun toggleMute() {
        val on = !muted
        muted = on
        runIo({ Api.mute(conv.id, on) }) { r ->
            r.onFailure {
                muted = !on
                msg = it.message
            }
        }
    }

    fun blockTap() {
        if (blocked) {
            val id = peer?.id ?: ""
            runIo({ Api.block(id, false) }) { r ->
                r.onSuccess { blocked = false }.onFailure { msg = it.message }
            }
        } else {
            confirmBlock = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val avatarNow = peer?.let { Avatars.url(it.id) }
            val story = group

            // avatar: 35dp under the status bar (56dp action bar - 21dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 35.dp),
                contentAlignment = Alignment.Center
            ) {
                HeroAvatar(
                    id = peer?.id ?: conv.id,
                    name = peer?.displayName ?: "?",
                    url = avatarNow,
                    hasStory = story != null,
                    onClick = {
                        if (story != null) onStories(listOf(story), 0, -1)
                        else if (avatarNow != null) photoUrl = avatarNow
                    },
                    onLongClick = { if (avatarNow != null) photoUrl = avatarNow },
                    onBounds = { avatarBox = it }
                )
            }

            // name: centre 28dp under the avatar (top 16dp, line 24dp), 19.6sp bold
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    ContactsStore.nameFor(peer?.id, peer?.displayName ?: "Unknown"),
                    color = Ui.ink,
                    fontSize = 19.6.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Badge(peer?.id, 20.sp, name = peer?.displayName)
            }

            // tiles start 85dp under the avatar; the row keeps Telegram's own 8dp above the tiles
            Spacer(Modifier.height(37.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                ActionTile(if (muted) "Unmute" else "Mute", { toggleMute() }, Modifier.weight(1f)) { MuteGlyph(muted) }
                ActionTile("Audio", { soon.show("Calls") }, Modifier.weight(1f)) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(24.dp))
                }
                ActionTile("Video", { soon.show("Video calls") }, Modifier.weight(1f)) { VideoIcon(Ui.ink) }
                ActionTile("Search", onSearch, Modifier.weight(1f)) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(24.dp))
                }
            }

            // 12dp under the tiles + 6dp empty row
            Spacer(Modifier.height(18.dp))

            if (peer != null) {
                // the info card: bio, then username
                Column(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Ui.soft)
                ) {
                    val b = bio
                    if (!b.isNullOrBlank()) {
                        InfoRow(b, "Bio", 19.sp)
                    }
                    InfoRow("@" + peer.username, "Username", 20.sp)
                }
                Spacer(Modifier.height(14.5.dp))

                if (!inContacts) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Ui.soft)
                            .clickable { contactSheet = true }
                            .padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(18.5.dp))
                        Text("Add to contacts", color = Ui.ink, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(13.dp))
                }
            }

            msg?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // the gifts sit at the bottom of the profile like Telegram's tab; 6.5dp here puts the tiles 12dp from the edge
            Box(Modifier.fillMaxWidth().padding(horizontal = 6.5.dp)) {
                GiftsSection(gifts = gifts, onOpen = { openGift = it })
            }
        }

        // action bar: back arrow (button centre 28dp from the left) and the three dots (centre 29.5dp from the right)
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(start = 4.dp, end = 5.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ui.ink)
            }
            Spacer(Modifier.weight(1f))
            if (peer != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Ui.ink)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = Ui.bg,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        MenuRow("Media", { menuOpen = false; onMedia() }) { GalleryGlyph(Ui.ink) }
                        MenuRow("Starred Messages", { menuOpen = false; soon.show("Starred messages") }) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(24.dp))
                        }
                        if (!blocked) {
                            MenuRow("Send a gift", { menuOpen = false; shopOpen = true }) { GiftGlyph(Ui.ink, 24.dp) }
                        }
                        if (inContacts) {
                            MenuRow("Edit contact", { menuOpen = false; contactSheet = true }) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(24.dp))
                            }
                            MenuRow("Delete contact", { menuOpen = false; confirmRemove = true }, MaterialTheme.colorScheme.error) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                            }
                        } else {
                            MenuRow("Add to contacts", { menuOpen = false; contactSheet = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(24.dp))
                            }
                        }
                        HorizontalDivider(color = Ui.line)
                        MenuRow(if (blocked) "Unblock user" else "Block user", { menuOpen = false; blockTap() }) { BlockGlyph(Ui.ink) }
                        MenuRow("Report", { menuOpen = false; report = true }, MaterialTheme.colorScheme.error) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }

        SoonToast(soon, 40.dp)

        // the floating send button: always one tap away while looking at a profile
        if (peer != null && !blocked) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 11.dp)
            ) { GiftSendPill(giftName, onClick = { shopOpen = true }) }
        }

        val shownUrl = photoUrl
        if (shownUrl != null && peer != null) {
            ProfilePhotoViewer(peer.id, peer.username, peer.displayName, shownUrl, avatarBox) { photoUrl = null }
        }
    }

    val shownGift = openGift
    if (shownGift != null) {
        GiftDetailSheet(
            gift = shownGift,
            onDismiss = { openGift = null },
            onSendOne = {
                openGift = null
                shopOpen = true
            }
        )
    }

    if (shopOpen && peer != null) {
        GiftShopSheet(
            userId = peer.id,
            peerName = giftName,
            onDismiss = { shopOpen = false },
            onSent = {
                runIo({ GiftsApi.received(peer.id) }) { r -> r.onSuccess { gifts = it } }
                onSendGift()
            }
        )
    }

    if (contactSheet && peer != null) {
        ContactEditSheet(
            userId = peer.id,
            username = peer.username,
            displayName = peer.displayName,
            onDismiss = { contactSheet = false }
        )
    }

    if (confirmRemove && peer != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = Ui.bg,
            title = { Text("Delete ${ContactsStore.nameFor(peer.id, peer.displayName)}?") },
            text = { Text("They will be removed from your contacts. Your chat with them stays.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    ContactsStore.remove(peer.id) { r -> r.onFailure { msg = it.message } }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
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
