package com.encore.app

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

private val toolsHandler = Handler(Looper.getMainLooper())

private fun searchMessages(cid: String, q: String): List<Msg> {
    val arr = Api.call("GET", "/v1/conversations/$cid/search?q=" + Uri.encode(q)).getJSONArray("messages")
    return List(arr.length()) { Api.parseMsg(arr.getJSONObject(it)) }
}

private fun fetchGallery(cid: String, kind: String): List<MsgItem> {
    val arr = Api.call("GET", "/v1/conversations/$cid/gallery?kind=$kind").getJSONArray("messages")
    return List(arr.length()) { parseItem(arr.getJSONObject(it)) }
}

@Composable
fun ChatSearchScreen(conv: Conv, onBack: () -> Unit, onJump: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Msg>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val pendingRun = remember { arrayOfNulls<Runnable>(1) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (e: Exception) {
        }
    }

    fun schedule(text: String) {
        pendingRun[0]?.let { toolsHandler.removeCallbacks(it) }
        val t = text.trim()
        if (t.length < 2) {
            results = emptyList()
            busy = false
            return
        }
        busy = true
        val run = Runnable {
            runIo({ searchMessages(conv.id, t) }) { r ->
                if (q.trim() == t) {
                    busy = false
                    r.onSuccess {
                        results = it
                        problem = null
                    }.onFailure { problem = it.message }
                }
            }
        }
        pendingRun[0] = run
        toolsHandler.postDelayed(run, 350)
    }

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
                placeholder = { Text("Search in this chat", color = Ui.muted) },
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
        if (q.trim().length < 2) {
            Text("Type at least 2 letters", color = Ui.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
        } else if (results.isEmpty() && !busy) {
            Text("No messages found", color = Ui.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(results, key = { it.id }) { m ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(m.id) }
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(m.body ?: "", fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(timeLabel(m.createdAt), fontSize = 12.sp, color = Ui.muted)
                }
                HorizontalDivider(color = Ui.line)
            }
        }
    }
}

@Composable
fun GalleryScreen(conv: Conv, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf("image") }
    var list by remember(kind) { mutableStateOf<List<MsgItem>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf<MsgItem?>(null) }
    var player by remember { mutableStateOf<MsgItem?>(null) }

    LaunchedEffect(kind) {
        val k = kind
        runIo({ fetchGallery(conv.id, k) }) { r ->
            r.onSuccess { if (k == kind) list = it }.onFailure { problem = it.message }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenBar("Media", onBack)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("image" to "Photos", "video" to "Videos", "file" to "Files").forEach { (k, label) ->
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (kind == k) Ui.onInk else Ui.ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (kind == k) Ui.ink else Ui.soft)
                        .clickable { kind = k }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        problem?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        val l = list
        if (l == null) {
            Text("Loading…", color = Ui.muted, modifier = Modifier.padding(20.dp))
        } else if (l.isEmpty()) {
            Text("Nothing here yet", color = Ui.muted, modifier = Modifier.padding(20.dp))
        } else if (kind == "image") {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f)
            ) {
                gridItems(l, key = { it.msg.id }) { x ->
                    val req = remember(x.msg.id) {
                        ImageRequest.Builder(ctx)
                            .data(Uploads.url(conv.id, x.msg.id))
                            .addHeader("Authorization", "Bearer ${Session.token}")
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = req,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(Ui.soft)
                            .clickable { viewer = x }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(l, key = { it.msg.id }) { x ->
                    if (kind == "video") {
                        VideoBlock(x, null) { player = x }
                    } else {
                        MediaBlock(conv.id, x, null, {}) {
                            x.media?.let { Uploads.download(ctx, conv.id, x.msg.id, it.name) }
                        }
                    }
                }
            }
        }
    }

    viewer?.let { v -> ImageViewer(conv.id, v, onClose = { viewer = null }) }
    player?.let { v -> VideoPlayerDialog(conv.id, v, onClose = { player = null }) }
}
