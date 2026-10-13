package com.encore.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
private fun GalleryGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.1f, w * 0.18f),
            size = Size(w * 0.8f, w * 0.64f),
            cornerRadius = CornerRadius(w * 0.1f),
            style = Stroke(width = w * 0.08f)
        )
        drawCircle(color, radius = w * 0.07f, center = Offset(w * 0.34f, w * 0.38f))
    }
}

@Composable
private fun Pill(label: String, dark: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (dark) Ui.ink else Ui.soft)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (dark) Ui.onInk else Ui.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrefRow(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Ui.muted)
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
    onMedia: () -> Unit
) {
    val peer = conv.peer
    var bio by remember { mutableStateOf<String?>(null) }
    var location by remember { mutableStateOf<String?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(conv.muted) }
    var group by remember { mutableStateOf<StoryGroup?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmBlock by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }
    val soon = remember { SoonState() }

    LaunchedEffect(conv.id) {
        if (peer != null) {
            runIo({ Api.call("GET", "/v1/users/${peer.id}").getJSONObject("user") }) { r ->
                r.onSuccess { u ->
                    bio = if (u.isNull("bio")) null else u.getString("bio")
                    location = if (u.isNull("location")) null else u.getString("location")
                    blocked = u.optBoolean("blocked")
                }.onFailure { msg = it.message }
            }
            runIo({ StoriesApi.userGroup(peer) }) { r -> r.onSuccess { group = it } }
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
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Ui.ink)
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Avatar(peer?.id ?: conv.id, peer?.displayName ?: "?", 100.dp)
                Spacer(Modifier.height(12.dp))
                Text(peer?.displayName ?: "Unknown", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                val b = bio
                if (!b.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(b, fontSize = 14.sp, color = Ui.muted, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val loc = location
                    if (!loc.isNullOrBlank()) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = Ui.muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(loc, fontSize = 13.sp, color = Ui.muted)
                        Spacer(Modifier.width(16.dp))
                    }
                    if (peer != null) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Ui.muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("@" + peer.username, fontSize = 13.sp, color = Ui.muted)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Audio", true, { soon.show("Calls") }) {
                        Icon(Icons.Default.Call, contentDescription = null, tint = Ui.onInk, modifier = Modifier.size(18.dp))
                    }
                    Pill("Video", true, { soon.show("Video calls") }) { VideoIcon(Ui.onInk) }
                    Pill("Search", false, onSearch) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Ui.ink, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                    Text("No stories right now", color = Ui.muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Start))
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

                Spacer(Modifier.height(24.dp))
                Text("Preferences", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
                Spacer(Modifier.height(4.dp))
                PrefRow("Media", onMedia) { GalleryGlyph(Ui.ink) }
                HorizontalDivider(color = Ui.line)
                PrefRow("Starred Messages", { soon.show("Starred messages") }) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Ui.ink)
                }
                HorizontalDivider(color = Ui.line)
                SettingRow("Mute notifications") {
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
                        }
                    )
                }
                HorizontalDivider(color = Ui.line)
                SettingRow(
                    if (blocked) "Unblock user" else "Block user",
                    color = MaterialTheme.colorScheme.error,
                    onClick = {
                        if (blocked) {
                            val id = peer?.id ?: ""
                            runIo({ Api.block(id, false) }) { r ->
                                r.onSuccess { blocked = false }.onFailure { msg = it.message }
                            }
                        } else {
                            confirmBlock = true
                        }
                    }
                )
                HorizontalDivider(color = Ui.line)
                SettingRow("Report", color = MaterialTheme.colorScheme.error, onClick = { report = true })
                msg?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        SoonToast(soon, 40.dp)
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
