package com.encore.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.json.JSONObject
import java.io.ByteArrayOutputStream

data class Story(
    val id: String,
    val kind: String,
    val caption: String?,
    val bg: Int,
    val createdAt: Long,
    val seen: Boolean,
    val views: Int
)

data class StoryGroup(val user: Peer, val mine: Boolean, val seenAll: Boolean, val stories: List<Story>)

data class Viewer(val user: Peer, val at: Long)

private val StoryColors = listOf(
    0xFF111111, 0xFF1F3A5F, 0xFF2E5339, 0xFF5B2A3C, 0xFF4A3B2A,
    0xFF3B2F63, 0xFF0F4C5C, 0xFF6B4226, 0xFF444B54, 0xFF7A3E1D
)

internal fun storyColor(i: Int): Color = Color(StoryColors[i.coerceIn(0, StoryColors.size - 1)])

private fun firstUnseen(g: StoryGroup): Int = g.stories.indexOfFirst { !it.seen }.let { if (it < 0) 0 else it }

internal fun prepareJpeg(ctx: Context, uri: Uri, maxSide: Int): Triple<ByteArray, Int, Int> {
    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = maxOf(w, h).toFloat() / maxSide
            if (scale > 1f) {
                decoder.setTargetSize((w / scale).toInt().coerceAtLeast(1), (h / scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > maxSide * 2) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            ?: throw ApiException("bad_file", "Can't read this picture")
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return Triple(out.toByteArray(), bmp.width, bmp.height)
}

object StoriesApi {
    private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.getString(k)

    private fun peer(u: JSONObject): Peer {
        val key = str(u, "avatar_key")
        Avatars.put(u.getString("id"), "u", key)
        return Peer(u.getString("id"), u.getString("username"), u.optString("display_name", u.getString("username")), null, key)
    }

    private fun story(o: JSONObject) = Story(
        o.getString("id"), o.getString("kind"), str(o, "caption"), o.optInt("bg"),
        o.getLong("created_at"), o.optBoolean("seen"), o.optInt("views")
    )

    fun feed(): List<StoryGroup> {
        val arr = Api.call("GET", "/v1/stories").getJSONArray("groups")
        return List(arr.length()) {
            val g = arr.getJSONObject(it)
            val st = g.getJSONArray("stories")
            StoryGroup(
                peer(g.getJSONObject("user")),
                g.optBoolean("mine"),
                g.optBoolean("seen_all"),
                List(st.length()) { i -> story(st.getJSONObject(i)) }
            )
        }
    }

    fun userGroup(p: Peer): StoryGroup? {
        val arr = Api.call("GET", "/v1/users/${p.id}/stories").getJSONArray("stories")
        if (arr.length() == 0) return null
        val l = List(arr.length()) { story(arr.getJSONObject(it)) }
        return StoryGroup(p, false, l.all { it.seen }, l)
    }

    fun view(id: String) {
        Api.call("POST", "/v1/stories/$id/view")
    }

    fun delete(id: String) {
        Api.call("DELETE", "/v1/stories/$id")
    }

    fun reply(id: String, text: String, reaction: Boolean) {
        Api.call("POST", "/v1/stories/$id/reply", JSONObject().put("text", text).put("reaction", reaction))
    }

    fun viewers(id: String): List<Viewer> {
        val arr = Api.call("GET", "/v1/stories/$id/viewers").getJSONArray("viewers")
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Viewer(peer(o), o.getLong("viewed_at"))
        }
    }

    fun postText(text: String, bg: Int) {
        Api.call("POST", "/v1/stories/text", JSONObject().put("text", text).put("bg", bg))
    }

    fun postImage(ctx: Context, uri: Uri, caption: String) {
        val (bytes, w, h) = prepareJpeg(ctx, uri, 1440)
        val q = "/v1/stories?w=$w&h=$h" + if (caption.isNotBlank()) "&caption=" + Uri.encode(caption.trim()) else ""
        Api.postBytes(q, bytes, "image/jpeg")
    }

    fun mediaUrl(id: String) = "$API_BASE/v1/stories/$id/media"
}

@Composable
fun StoriesRow(me: User, onOpen: (List<StoryGroup>, Int, Int) -> Unit, onAdd: () -> Unit) {
    var groups by remember { mutableStateOf<List<StoryGroup>>(emptyList()) }

    fun load() {
        runIo({ StoriesApi.feed() }) { r -> r.onSuccess { groups = it } }
    }

    Ticker(60000L, "stories-row") { load() }

    val mineIdx = groups.indexOfFirst { it.mine }
    val mine = groups.getOrNull(mineIdx)

    // nothing to show -> no space taken; adding a story lives in the top bar
    if (groups.isEmpty()) return

    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (mine != null) item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onOpen(groups, mineIdx, -1) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Box(modifier = Modifier.size(64.dp)) {
                    if (mine != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(2.dp, if (mine.seenAll) Ui.muted.copy(alpha = 0.5f) else Ui.ink, CircleShape)
                                .padding(4.dp)
                        ) {
                            Avatar(me.id, me.displayName.ifBlank { me.username }, 56.dp)
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Ui.ink)
                                .clickable { onAdd() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add story", tint = Ui.onInk, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Your story", fontSize = fs(13f), color = Ui.muted)
            }
        }
        items(groups.filter { !it.mine }, key = { it.user.id }) { g ->
            val idx = groups.indexOf(g)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onOpen(groups, idx, -1) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .border(2.dp, if (g.seenAll) Ui.muted.copy(alpha = 0.5f) else Ui.ink, CircleShape)
                        .padding(4.dp)
                ) {
                    Avatar(g.user.id, g.user.displayName, 56.dp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    g.user.displayName,
                    fontSize = fs(13f),
                    color = Ui.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 68.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryViewerScreen(me: User, groups: List<StoryGroup>, startGroup: Int, startStory: Int, onClose: () -> Unit) {
    if (groups.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val ctx = LocalContext.current
    val g0 = startGroup.coerceIn(0, groups.size - 1)
    var gi by remember { mutableStateOf(g0) }
    var si by remember { mutableStateOf(if (startStory >= 0) startStory else firstUnseen(groups[g0])) }
    var held by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showViewers by remember { mutableStateOf(false) }
    var viewers by remember { mutableStateOf<List<Viewer>?>(null) }
    var deleted by remember { mutableStateOf<Set<String>>(emptySet()) }

    val group = groups[gi]
    val list = group.stories.filter { it.id !in deleted }
    if (list.isEmpty()) {
        LaunchedEffect(gi) { onClose() }
        return
    }
    val cur = si.coerceIn(0, list.lastIndex)
    val story = list[cur]
    val mine = group.mine
    val paused = held || sheet || menu || confirmDelete || showViewers
    val anim = remember(story.id) { Animatable(0f) }

    fun nextGroup() {
        if (gi < groups.size - 1) {
            gi += 1
            si = firstUnseen(groups[gi])
        } else {
            onClose()
        }
    }

    fun next() {
        if (cur < list.lastIndex) si = cur + 1 else nextGroup()
    }

    fun prev() {
        if (cur > 0) {
            si = cur - 1
        } else if (gi > 0) {
            gi -= 1
            si = 0
        }
    }

    LaunchedEffect(story.id, paused) {
        if (!paused) {
            val left = ((1f - anim.value) * 5000f).toInt().coerceAtLeast(1)
            anim.animateTo(1f, tween(durationMillis = left, easing = LinearEasing))
            next()
        }
    }

    LaunchedEffect(story.id) {
        if (!mine) runIo({ StoriesApi.view(story.id) }) { }
    }

    val req = remember(story.id) {
        ImageRequest.Builder(ctx)
            .data(StoriesApi.mediaUrl(story.id))
            .addHeader("Authorization", "Bearer ${Session.token}")
            .crossfade(true)
            .build()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            list.forEachIndexed { i, _ ->
                val frac = if (i < cur) 1f else if (i == cur) anim.value else 0f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x55FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(frac)
                            .background(Color.White)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(group.user.id, group.user.displayName, 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (mine) "Your story" else group.user.displayName,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(timeLabel(story.createdAt), color = Color(0xB3FFFFFF), fontSize = 12.sp)
            }
            if (mine) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                confirmDelete = true
                            }
                        )
                    }
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1B1D21))
                .pointerInput(story.id) {
                    detectTapGestures(
                        onPress = {
                            held = true
                            tryAwaitRelease()
                            held = false
                        },
                        onTap = { o -> if (o.x < size.width / 3f) prev() else next() }
                    )
                }
        ) {
            if (story.kind == "text") {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(storyColor(story.bg)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        story.caption ?: "",
                        color = Color.White,
                        fontSize = 28.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(28.dp)
                    )
                }
            } else {
                AsyncImage(
                    model = req,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                val cap = story.caption
                if (!cap.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
                            .padding(horizontal = 16.dp, vertical = 20.dp)
                    ) {
                        Text(cap, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            if (mine) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x33FFFFFF))
                        .clickable {
                            showViewers = true
                            viewers = null
                            runIo({ StoriesApi.viewers(story.id) }) { r -> r.onSuccess { viewers = it } }
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        story.views.toString() + (if (story.views == 1) " view" else " views"),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White)
                        .clickable { sheet = true }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF111111), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reply", color = Color(0xFF111111), fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = Ui.bg) {
            var comment by remember { mutableStateOf("") }

            fun send(text: String, reaction: Boolean) {
                runIo({ StoriesApi.reply(story.id, text, reaction) }) { r ->
                    r.onSuccess {
                        sheet = false
                        Toast.makeText(ctx, "Sent", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(ctx, it.message ?: "Couldn't send", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Surface(color = Ui.soft, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        story.caption ?: "Story",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(14.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("Reply", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(EMOJIS) { e ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable { send(e, true) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(e, fontSize = 28.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = comment,
                        onValueChange = { comment = it },
                        placeholder = { Text("Comment", color = Ui.muted) },
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Ui.soft,
                            unfocusedContainerColor = Ui.soft,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { send(comment.trim(), false) }, enabled = comment.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Ui.ink)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showViewers) {
        ModalBottomSheet(onDismissRequest = { showViewers = false }, containerColor = Ui.bg) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text("Viewers", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                val v = viewers
                if (v == null) {
                    Text("Loading…", color = Ui.muted, modifier = Modifier.padding(vertical = 12.dp))
                } else if (v.isEmpty()) {
                    Text("No views yet", color = Ui.muted, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(v, key = { it.user.id }) { x ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Avatar(x.user.id, x.user.displayName, 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(x.user.displayName, modifier = Modifier.weight(1f))
                                Text(timeLabel(x.at), color = Ui.muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Ui.bg,
            title = { Text("Delete this story?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val id = story.id
                    runIo({ StoriesApi.delete(id) }) { r ->
                        r.onSuccess { deleted = deleted + id }.onFailure {
                            Toast.makeText(ctx, it.message ?: "Couldn't delete", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun StoryCreateScreen(onBack: () -> Unit, onPosted: () -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf<String?>(null) }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var bg by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = mode != null) { mode = null }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) {
            uri = u
            mode = "photo"
        }
    }

    fun post() {
        busy = true
        problem = null
        val m = mode
        val u = uri
        val c = caption
        val t = text
        val b = bg
        val app = ctx.applicationContext
        runIo({
            if (m == "photo" && u != null) StoriesApi.postImage(app, u, c) else StoriesApi.postText(t, b)
        }) { r ->
            busy = false
            r.onSuccess { onPosted() }.onFailure { problem = it.message }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        ScreenBar("New story", onBack = { if (mode != null) mode = null else onBack() })
        when (mode) {
            null -> Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                SettingRow("Photo", sub = "Pick a picture from your gallery", onClick = {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                HorizontalDivider(color = Ui.line)
                SettingRow("Text", sub = "Write something on a colored background", onClick = { mode = "text" })
            }
            "photo" -> Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Ui.soft)
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it.take(200) },
                    label = { Text("Caption (optional)") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { post() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Sharing…" else "Share story") }
                Spacer(Modifier.height(12.dp))
            }
            else -> Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(storyColor(bg)),
                    contentAlignment = Alignment.Center
                ) {
                    TextField(
                        value = text,
                        onValueChange = { text = it.take(300) },
                        placeholder = {
                            Text(
                                "Type something",
                                color = Color(0x99FFFFFF),
                                fontSize = 26.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        textStyle = TextStyle(color = Color.White, fontSize = 26.sp, textAlign = TextAlign.Center),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(StoryColors.size) { i ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(storyColor(i))
                                .then(if (i == bg) Modifier.border(2.dp, Ui.ink, CircleShape) else Modifier)
                                .clickable { bg = i }
                        )
                    }
                }
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { post() },
                    enabled = !busy && text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Sharing…" else "Share story") }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
