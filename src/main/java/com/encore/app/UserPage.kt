package com.encore.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

private val UserPageYellow = Color(0xFFFFC400)
private val UserPageOnYellow = Color(0xFF111111)

@Composable
private fun GalleryGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val sw = w * 0.08f
        // outer rounded frame
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.1f, w * 0.14f),
            size = Size(w * 0.8f, w * 0.72f),
            cornerRadius = CornerRadius(w * 0.12f),
            style = Stroke(width = sw)
        )
        // sun
        drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.34f, w * 0.36f))
        // mountains
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

// circle with a slash, drawn by hand like the gallery icon (no extra icon library needed)
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

@Composable
private fun Pill(
    label: String,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (dark) Ui.ink else Ui.soft)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            color = if (dark) Ui.onInk else Ui.ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** One action in the row under the name: icon on top, label under it, 74dp tall. */
@Composable
private fun ActionTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .height(74.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Ui.soft)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // measured from the reference row: icon 24dp starting 21.7dp from the top, label 11sp medium 4dp under it
        Spacer(Modifier.height(21.7.dp))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Ui.ink, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun PrefRow(
    label: String,
    onClick: () -> Unit,
    color: Color = Ui.ink,
    chevron: Boolean = true,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(label, color = color, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (chevron) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Ui.muted)
        }
    }
}

@Composable
private fun StoryCard(s: Story, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .width(120.dp)
            .height(170.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (s.kind == "text") storyColor(s.bg) else Ui.soft)
            .clickable(onClick = onClick)
    ) {
        if (s.kind == "image") {
            val req = remember(s.id) {
                ImageRequest.Builder(ctx)
                    .data(StoriesApi.mediaUrl(s.id))
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
        val cap = s.caption ?: ""
        if (cap.isNotBlank()) {
            if (s.kind == "text") {
                Text(
                    cap,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(10.dp)
                )
            } else {
                Text(
                    cap,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color(0x66000000))
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun UserPageScreen(
    me: User,
    conv: Conv,
    onBack: () -> Unit,
    onStories: (List<StoryGroup>, Int, Int) -> Unit,
    onSearch: () -> Unit,
    onMedia: () -> Unit,
    // true when this person is in your contacts: then the gift button moves from the action row into the top-right menu
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
    var ready by remember(conv.id) { mutableStateOf(cached != null || peer == null) }
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

    // never leave the page waiting: after a short moment show whatever is known
    LaunchedEffect(conv.id) {
        kotlinx.coroutines.delay(1200)
        ready = true
    }

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
                        ready = true
                    }
                }
            }
            // 2) refresh from the server and remember it
            runIo({ Api.call("GET", "/v1/users/${peer.id}").getJSONObject("user") }) { r ->
                r.onSuccess { u ->
                    bio = if (u.isNull("bio")) null else u.getString("bio")
                    location = if (u.isNull("location")) null else u.getString("location")
                    blocked = u.optBoolean("blocked")
                    ready = true
                    val b0 = bio
                    val l0 = location
                    val k0 = blocked
                    val fresh = ProfileEntity(peer.id, b0, l0, k0)
                    ProfileCache.put(fresh)
                    runIo({ Db.dao(appCtx).putProfileNow(fresh) }) { }
                }.onFailure {
                    ready = true
                    msg = it.message
                }
            }
            runIo({ StoriesApi.userGroup(peer) }) { r -> r.onSuccess { group = it } }
            runIo({ GiftsApi.received(peer.id) }) { r ->
                r.onSuccess { gifts = it }.onFailure { gifts = emptyList() }
            }
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
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val avatarNow = peer?.let { Avatars.url(it.id) }
                // a round photo at the top
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .clip(CircleShape)
                            .background(Ui.soft)
                            .onGloballyPositioned { avatarBox = it.boundsInRoot() }
                            .then(if (avatarNow != null) Modifier.clickable { photoUrl = avatarNow } else Modifier)
                    ) {
                        // the letters show until the photo is there, or when there is no photo
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Avatar(peer?.id ?: conv.id, peer?.displayName ?: "?", 112.dp)
                        }
                        if (avatarNow != null) {
                            val heroCtx = LocalContext.current
                            val heroReq = remember(avatarNow) {
                                ImageRequest.Builder(heroCtx)
                                    .data(avatarNow)
                                    .addHeader("Authorization", "Bearer ${Session.token}")
                                    .crossfade(true)
                                    .build()
                            }
                            AsyncImage(
                                model = heroReq,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                // the name sits under the photo
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ContactsStore.nameFor(peer?.id, peer?.displayName ?: "Unknown"),
                            color = Ui.ink,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Badge(peer?.id, 20.sp, name = peer?.displayName)
                    }
                    if (peer != null) {
                        Text("@" + peer.username, color = Ui.muted, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                val b = bio
                if (!b.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(b, fontSize = 14.sp, color = Ui.muted, textAlign = TextAlign.Center)
                } else if (!ready) {
                    // keep the room of the bio line until we know whether there is one
                    Spacer(Modifier.height(24.dp))
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.alpha(if (ready) 1f else 0f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val loc = location
                    if (!loc.isNullOrBlank()) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = Ui.muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(loc, fontSize = 13.5.sp, color = Ui.muted)
                        Spacer(Modifier.width(16.dp))
                    }
                }
                Spacer(Modifier.height(20.dp))
                // the actions share one style so they read as a set; the gift button sits here unless this is a contact
                Row(
                    // 14dp from each screen edge: the page has 16dp, so this row reaches 2dp further out
                    modifier = Modifier.fillMaxWidth().layout { measurable, constraints ->
                        val extra = 2.dp.roundToPx()
                        val p = measurable.measure(
                            constraints.copy(minWidth = constraints.maxWidth + extra * 2, maxWidth = constraints.maxWidth + extra * 2)
                        )
                        layout(constraints.maxWidth, p.height) { p.place(-extra, 0) }
                    },
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    ActionTile("Audio", { soon.show("Calls") }, Modifier.weight(1f)) {
                        Icon(Icons.Default.Call, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(24.dp))
                    }
                    ActionTile("Video", { soon.show("Video calls") }, Modifier.weight(1f)) { VideoIcon(Ui.ink) }
                    ActionTile("Search", onSearch, Modifier.weight(1f)) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(24.dp))
                    }
                    if (peer != null && !blocked && !inContacts) {
                        ActionTile("Gift", { shopOpen = true }, Modifier.weight(1f)) { GiftGlyph(Ui.ink, 24.dp) }
                    }
                }

                Spacer(Modifier.height(28.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Story", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    val g = group
                    if (g != null) {
                        Text(
                            "View all story",
                            fontSize = 13.sp,
                            color = Ui.muted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onStories(listOf(g), 0, -1) }
                                .padding(4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                val g = group
                if (g == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Ui.soft)
                            .padding(horizontal = 16.dp, vertical = 18.dp)
                    ) {
                        Text("No stories right now", color = Ui.muted, fontSize = 14.sp)
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(g.stories.size) { i ->
                            StoryCard(g.stories[i]) { onStories(listOf(g), 0, i) }
                        }
                    }
                }

                Spacer(Modifier.height(28.dp))
                Text(
                    "Preferences",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(10.dp))
                // Media, Starred and Mute live in one rounded card with the same left edge
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Ui.soft)
                ) {
                    PrefRow("Media", onMedia) { GalleryGlyph(Ui.ink) }
                    HorizontalDivider(color = Ui.line, modifier = Modifier.padding(start = 54.dp))
                    PrefRow("Starred Messages", { soon.show("Starred messages") }) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(24.dp))
                    }
                    HorizontalDivider(color = Ui.line, modifier = Modifier.padding(start = 54.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Mute notifications", fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = muted,
                            onCheckedChange = { on ->
                                muted = on
                                runIo({ Api.mute(conv.id, on) }) { r ->
                                    r.onFailure {
                                        muted = !on
                                        msg = it.message
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = UserPageOnYellow,
                                checkedTrackColor = UserPageYellow,
                                checkedBorderColor = Color.Transparent,
                                uncheckedThumbColor = Ui.muted,
                                uncheckedTrackColor = Ui.bg,
                                uncheckedBorderColor = Ui.line
                            )
                        )
                    }
                }

                Spacer(Modifier.height(28.dp))
                // risky actions get their own card, with red icons
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Ui.soft)
                ) {
                    PrefRow(
                        if (blocked) "Unblock user" else "Block user",
                        onClick = {
                            if (blocked) {
                                val id = peer?.id ?: ""
                                runIo({ Api.block(id, false) }) { r ->
                                    r.onSuccess { blocked = false }.onFailure { msg = it.message }
                                }
                            } else {
                                confirmBlock = true
                            }
                        },
                        color = MaterialTheme.colorScheme.error,
                        chevron = false
                    ) { BlockGlyph(MaterialTheme.colorScheme.error) }
                    HorizontalDivider(color = Ui.line, modifier = Modifier.padding(start = 54.dp))
                    PrefRow(
                        "Report",
                        onClick = { report = true },
                        color = MaterialTheme.colorScheme.error,
                        chevron = false
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                msg?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                // the gifts sit at the very bottom of the profile, like a tab under the info
                Spacer(Modifier.height(20.dp))
                GiftsSection(gifts = gifts, onOpen = { openGift = it })
                }
            }
        }
        // top right, over the photo: the menu and the close button.
        // a contact's menu holds "Send a gift", "Edit contact" and "Delete contact"; anyone else's holds "Add to contacts"
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (peer != null && !blocked) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.background(Color(0x66000000), CircleShape)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Ui.bg) {
                        if (inContacts) {
                            DropdownMenuItem(
                                text = { Text("Send a gift") },
                                leadingIcon = { GiftGlyph(Ui.ink, 22.dp) },
                                onClick = {
                                    menuOpen = false
                                    shopOpen = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Edit contact") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(22.dp)) },
                                onClick = {
                                    menuOpen = false
                                    contactSheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete contact", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp)) },
                                onClick = {
                                    menuOpen = false
                                    confirmRemove = true
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Add to contacts") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(22.dp)) },
                                onClick = {
                                    menuOpen = false
                                    contactSheet = true
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            IconButton(
                onClick = onBack,
                modifier = Modifier.background(Color(0x66000000), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
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
