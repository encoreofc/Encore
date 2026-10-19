package com.encore.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

// ---------------------------------------------------------------------------
// Encore profile: every person has their own colour (the "aura"), taken from their photo.
// It glows behind the avatar and shines through the glass panels. One rule for the whole app.
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
    // read the colours here, in composable scope; the Canvas lambda is a DrawScope, not composable
    val ink = Ui.ink
    val soft = Ui.soft
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Notifications, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
        if (muted) {
            Canvas(Modifier.size(24.dp)) {
                val w = size.width
                val sw = w * 0.09f
                drawLine(
                    soft,
                    start = Offset(w * 0.2f, w * 0.14f),
                    end = Offset(w * 0.82f, w * 0.86f),
                    strokeWidth = sw * 2.6f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    ink,
                    start = Offset(w * 0.2f, w * 0.14f),
                    end = Offset(w * 0.82f, w * 0.86f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

/** One action under the name: a glass tile, 64dp tall. */
@Composable
private fun ActionTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .height(64.dp)
            .glassPanel(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(4.dp))
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

// ---------------------------------------------------------------------------
// The tabs under the info card: Gifts (always) and Media / Files / Voice when the chat has them.
// Sizes: strip 50dp (pill 36dp, corners 18dp, tab 28dp / corners 14dp, 15sp bold, 12dp tab
// padding, 8dp between tabs), media grid 3 columns with 2dp gaps, file / voice rows 56dp with the text 72dp
// from the card edge (name 16sp / title 15sp, second line 13sp), icon 40dp, voice button 44dp.
// ---------------------------------------------------------------------------

private const val TAB_GIFTS = "gifts"
private const val TAB_MEDIA = "media"
private const val TAB_FILES = "files"
private const val TAB_VOICE = "voice"
private const val PAGE = 60

private fun tabTitle(t: String) = when (t) {
    TAB_MEDIA -> "Media"
    TAB_FILES -> "Files"
    else -> "Voice"
}

private class SharedPage(
    val items: List<MsgItem>,
    val cursor: String?,
    val counts: Map<String, Int>,
    val hasMore: Boolean
)

/** One page of the chat's media / files / voice from the server (newest first); [before] is the oldest id already shown. */
private fun fetchShared(cid: String, kind: String, before: String?, limit: Int = PAGE): SharedPage {
    val q = "/v1/conversations/$cid/shared?kind=$kind&limit=$limit" + (if (before != null) "&before=$before" else "")
    val j = Api.call("GET", q)
    val arr = j.getJSONArray("messages")
    val raw = List(arr.length()) { parseItem(arr.getJSONObject(it)) }
    val c = j.optJSONObject("counts")
    return SharedPage(
        items = raw.filter { !it.msg.deleted && it.media != null && it.ttl == 0 },
        cursor = raw.lastOrNull()?.msg?.id,
        counts = mapOf(
            TAB_MEDIA to (c?.optInt("media") ?: 0),
            TAB_FILES to (c?.optInt("files") ?: 0),
            TAB_VOICE to (c?.optInt("voice") ?: 0)
        ),
        hasMore = j.optBoolean("has_more")
    )
}

// the last known background picture of each person, so a page does not jump when it opens again
private val coverKeyCache = HashMap<String, String?>()

private fun copyText(ctx: Context, text: String, done: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("label", text))
        // Android 13 and newer show their own "copied" note
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, done, Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
    }
}

private fun fileSizeText(b: Long): String = when {
    b >= 1048576L -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", b / 1024.0)
    else -> "$b B"
}

private fun voiceClock(ms: Long): String {
    val s = (ms / 1000).toInt()
    return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
}

@Composable
private fun CopyGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val sw = w * 0.08f
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.32f, w * 0.1f),
            size = Size(w * 0.56f, w * 0.62f),
            cornerRadius = CornerRadius(w * 0.08f),
            style = Stroke(width = sw)
        )
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.12f, w * 0.28f),
            size = Size(w * 0.56f, w * 0.62f),
            cornerRadius = CornerRadius(w * 0.08f),
            style = Stroke(width = sw)
        )
    }
}

@Composable
private fun PlayGlyph(color: Color, boxSize: Dp, playing: Boolean = false) {
    Canvas(Modifier.size(boxSize)) {
        val w = size.width
        val h = size.height
        if (playing) {
            drawRect(color, Offset(w * 0.26f, h * 0.2f), Size(w * 0.17f, h * 0.6f))
            drawRect(color, Offset(w * 0.57f, h * 0.2f), Size(w * 0.17f, h * 0.6f))
        } else {
            val tri = Path().apply {
                moveTo(w * 0.3f, h * 0.15f)
                lineTo(w * 0.84f, h * 0.5f)
                lineTo(w * 0.3f, h * 0.85f)
                close()
            }
            drawPath(tri, color)
        }
    }
}

/** The pill with the three tabs of the All Media page, each with how many it holds. */
@Composable
private fun ProfileTabStrip(tabs: List<String>, selected: String, counts: Map<String, Int>, onSelect: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassSurface(18.dp) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEach { t ->
                    val on = t == selected
                    val tint = if (on) Ui.ink else Ui.muted
                    val n = counts[t] ?: 0
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) Ui.ink.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { onSelect(t) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            tabTitle(t) + if (n > 0) " $n" else "",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = tint
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaTile(cid: String, item: MsgItem, shape: RoundedCornerShape, onOpen: (MsgItem) -> Unit) {
    val isVideo = item.msg.kind == "video"
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(if (isVideo) Color(0xFF1B1D21) else Ui.soft)
            .clickable { onOpen(item) }
    ) {
        if (!isVideo) {
            val url = remember(item.msg.id) { Uploads.url(cid, item.msg.id) }
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
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center
            ) { PlayGlyph(Color.White, 18.dp) }
            // duration badge: 17dp tall, corners 4dp, 5dp from the left, 3dp from the bottom
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 5.dp, bottom = 3.dp)
                    .height(17.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x66000000))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlayGlyph(Color.White, 8.dp)
                val d = item.media?.dur ?: 0L
                if (d > 0) {
                    Spacer(Modifier.width(2.dp))
                    Text(durLabel(d), color = Color.White, fontSize = 12.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}

/** 3 columns, square cells; each cell leaves 2dp on its right and bottom (and on the left for the first column). */
@Composable
private fun SharedMediaGrid(cid: String, items: List<MsgItem>, onOpen: (MsgItem) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        items.chunked(3).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 3) {
                    val item = row.getOrNull(c)
                    if (item == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val top = r == 0
                        val shape = RoundedCornerShape(
                            topStart = if (top && c == 0) 18.dp else 1.dp,
                            topEnd = if (top && c == 2) 18.dp else 1.dp,
                            bottomEnd = 1.dp,
                            bottomStart = 1.dp
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(start = if (c == 0) 2.dp else 0.dp, end = 2.dp, bottom = 2.dp)
                        ) { MediaTile(cid, item, shape, onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBadge(name: String, modifier: Modifier = Modifier) {
    val ext = name.substringAfterLast('.', "").lowercase().take(4)
    val palette = listOf(Color(0xFFF2706A), Color(0xFF52A9EA), Color(0xFF5DBE7A), Color(0xFFF0A24E))
    val c = palette[(ext.hashCode() and 0x7fffffff) % palette.size]
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(c),
        contentAlignment = Alignment.Center
    ) {
        if (ext.isNotEmpty()) {
            Text(ext, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun RowDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(start = 72.dp)
            .fillMaxWidth()
            .height(Dp.Hairline)
            .background(Ui.muted.copy(alpha = 0.2f))
    )
}

/** Files: rows 56dp, icon 40dp (12dp in, 8dp down), name 16sp at 5dp, size and date 13sp at 30dp, both 72dp from the left. */
@Composable
private fun SharedFilesList(cid: String, items: List<MsgItem>) {
    val ctx = LocalContext.current
    val dateFmt = remember { java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault()) }
    val timeFmt = remember { android.text.format.DateFormat.getTimeFormat(ctx) }
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(24.dp))
    ) {
        items.forEachIndexed { i, item ->
            val m = item.media
            if (m != null) {
                val at = java.util.Date(item.msg.createdAt)
                val sub = fileSizeText(m.size) + ", " + dateFmt.format(at) + " at " + timeFmt.format(at)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable { Uploads.download(ctx, cid, item.msg.id, m.name) }
                ) {
                    FileBadge(m.name, Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
                    Text(
                        m.name,
                        color = Ui.ink,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 72.dp, end = 8.dp, top = 5.dp)
                    )
                    Text(
                        sub,
                        color = Ui.muted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 72.dp, end = 8.dp, top = 30.dp)
                    )
                    if (i < items.size - 1) RowDivider(Modifier.align(Alignment.BottomStart))
                }
            }
        }
    }
}

/** Voice: rows 56dp, round button 44dp (12dp in), title 15sp at 9dp, who and length 13sp at 29dp. */
@Composable
private fun SharedVoiceList(cid: String, items: List<MsgItem>, meId: String, peerName: String) {
    val ctx = LocalContext.current
    val dateFmt = remember { java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault()) }
    val timeFmt = remember { android.text.format.DateFormat.getTimeFormat(ctx) }
    val today = remember { dateFmt.format(java.util.Date()) }
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(24.dp))
    ) {
        items.forEachIndexed { i, item ->
            val m = item.media
            if (m != null) {
                val id = item.msg.id
                val playing = VoicePlayer.playingId == id
                val at = java.util.Date(item.msg.createdAt)
                val day = dateFmt.format(at)
                val title = (if (day == today) "" else "$day ") + "at " + timeFmt.format(at)
                val who = if (item.msg.senderId == meId) "You" else peerName
                val shownMs = if (playing) VoicePlayer.position.toLong() else m.dur
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable { VoicePlayer.toggle(ctx, cid, id, m.localUri) }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 12.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Ui.ink),
                        contentAlignment = Alignment.Center
                    ) { PlayGlyph(Ui.onInk, 24.dp, playing) }
                    Text(
                        title,
                        color = Ui.ink,
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 72.dp, end = 8.dp, top = 9.dp)
                    )
                    Text(
                        "$who \u2022 ${voiceClock(shownMs)}",
                        color = Ui.muted,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 72.dp, end = 8.dp, top = 29.dp)
                    )
                    if (i < items.size - 1) RowDivider(Modifier.align(Alignment.BottomStart))
                }
            }
        }
    }
}

@Composable
private fun ShowMore(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text("Show more", color = Ui.muted, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

/**
 * The round photo, 112dp. With a story the photo is pulled in by 3.5dp and a golden ring is drawn around it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroAvatar(
    id: String,
    name: String,
    url: String?,
    isVideo: Boolean,
    hasStory: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onBounds: (Rect) -> Unit
) {
    val inset = if (hasStory) 3.5.dp else 0.dp
    val photo = 112.dp - inset * 2
    Box(
        modifier = Modifier
            .size(112.dp)
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
            if (url != null && isVideo) {
                // a profile video: muted, looping, round
                LoopingVideo(url, Modifier.fillMaxSize())
            } else if (url != null) {
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
    // the profile background picture and the All Media page
    var coverKey by remember(conv.id) { mutableStateOf(peer?.let { coverKeyCache[it.id] }) }
    var allMedia by remember(conv.id) { mutableStateOf(false) }
    var allCount by remember(conv.id) { mutableStateOf<Int?>(null) }
    var videoAvatar by remember { mutableStateOf<String?>(null) }
    var bioMenu by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

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
                    val ck = if (u.isNull("cover_key")) null else u.getString("cover_key")
                    coverKey = ck
                    coverKeyCache[peer.id] = ck
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
            // only the numbers: the All Media page loads its own pages when it is opened
            runIo({ fetchShared(conv.id, TAB_MEDIA, null, 1).counts.values.sum() }) { r ->
                r.onSuccess { allCount = it }
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
        val aura by rememberAura(peer?.id ?: conv.id, peer?.let { Avatars.url(it.id) })
        AuraBackground(aura, Modifier.fillMaxSize())
        // the background picture sits behind the top of the page and scrolls away with it
        val coverNow = coverKey
        if (coverNow != null && peer != null) {
            CoverBackdrop(coverUrl(peer.id, coverNow), Modifier.offset { IntOffset(0, -scroll.value) })
        }
        // soft round plates behind the back arrow and the three dots when a picture is behind them
        val chip = if (coverNow != null) Ui.bg.copy(alpha = 0.55f) else Color.Transparent
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val avatarNow = peer?.let { Avatars.url(it.id) }
            val story = group

            // avatar, with the person's colour glowing behind it
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 40.dp)
                    .drawBehind {
                        val r = 120.dp.toPx()
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(aura.copy(alpha = 0.16f), Color.Transparent),
                                center = center,
                                radius = r
                            ),
                            radius = r,
                            center = center
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                HeroAvatar(
                    id = peer?.id ?: conv.id,
                    name = peer?.displayName ?: "?",
                    url = avatarNow,
                    isVideo = peer != null && Avatars.isVideo(peer.id),
                    hasStory = story != null,
                    onClick = {
                        if (story != null) onStories(listOf(story), 0, -1)
                        else if (avatarNow != null) {
                            if (peer != null && Avatars.isVideo(peer.id)) videoAvatar = avatarNow else photoUrl = avatarNow
                        }
                    },
                    onLongClick = {
                        if (avatarNow != null) {
                            if (peer != null && Avatars.isVideo(peer.id)) videoAvatar = avatarNow else photoUrl = avatarNow
                        }
                    },
                    onBounds = { avatarBox = it }
                )
            }

            // name
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    ContactsStore.nameFor(peer?.id, peer?.displayName ?: "Unknown"),
                    color = Ui.ink,
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    letterSpacing = (-0.4).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Badge(peer?.id, 24.sp, name = peer?.displayName)
            }

            // glass tiles
            Spacer(Modifier.height(26.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                        .glassPanel(RoundedCornerShape(24.dp))
                ) {
                    val b = bio
                    if (!b.isNullOrBlank()) {
                        // press and hold: a Copy button
                        Box(Modifier.combinedClickable(onClick = {}, onLongClick = { bioMenu = true })) {
                            InfoRow(b, "Bio", 19.sp)
                            DropdownMenu(
                                expanded = bioMenu,
                                onDismissRequest = { bioMenu = false },
                                containerColor = Ui.bg,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                MenuRow("Copy", { bioMenu = false; copyText(appCtx, b, "Bio copied") }) { CopyGlyph(Ui.ink) }
                            }
                        }
                    }
                    // tap: the username is copied
                    Box(Modifier.clickable { copyText(appCtx, "@" + peer.username, "Username copied") }) {
                        InfoRow("@" + peer.username, "Username", 20.sp)
                    }
                }
                Spacer(Modifier.height(14.5.dp))

                if (!inContacts) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth()
                            .height(50.dp)
                            .glassPanel(RoundedCornerShape(24.dp))
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

                // All Media: opens the page with Media / Files / Voice
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth()
                        .height(50.dp)
                        .glassPanel(RoundedCornerShape(24.dp))
                        .clickable { allMedia = true }
                        .padding(start = 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GalleryGlyph(Ui.ink)
                    Spacer(Modifier.width(18.5.dp))
                    Text("All Media", color = Ui.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    val n = allCount
                    if (n != null && n > 0) {
                        Text(n.toString(), color = Ui.muted, fontSize = 15.sp)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Ui.muted)
                }
                Spacer(Modifier.height(13.dp))
            }

            msg?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // gifts sit at the bottom of the profile
            // 6.5dp here puts the gift tiles 12dp from the edge
            Box(Modifier.fillMaxWidth().padding(horizontal = 6.5.dp)) {
                GiftsSection(gifts = gifts, onOpen = { openGift = it }, showHeader = true)
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
                    .background(chip)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ui.ink)
            }
            Spacer(Modifier.weight(1f))
            if (peer != null) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.clip(CircleShape).background(chip)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Ui.ink)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = Ui.bg,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        MenuRow("All Media", { menuOpen = false; allMedia = true }) { GalleryGlyph(Ui.ink) }
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

        // the All Media page covers the profile while it is open
        if (allMedia && peer != null) {
            AllMediaScreen(
                cid = conv.id,
                meId = me.id,
                peerName = ContactsStore.nameFor(peer.id, peer.displayName),
                onBack = { allMedia = false }
            )
        }
    }

    videoAvatar?.let { AvatarVideoDialog(it) { videoAvatar = null } }

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

/** The picture behind the top of a profile: softly blurred, fading into the page colour. */
@Composable
private fun CoverBackdrop(url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bg = Ui.bg
    val req = remember(url) {
        ImageRequest.Builder(ctx)
            .data(url)
            .addHeader("Authorization", "Bearer ${Session.token}")
            .crossfade(true)
            .build()
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(340.dp)
            .clipToBounds()
    ) {
        AsyncImage(
            model = req,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(6.dp, BlurredEdgeTreatment.Rectangle)
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to bg.copy(alpha = 0.25f),
                        0.55f to bg.copy(alpha = 0.8f),
                        1f to bg
                    )
                )
        )
    }
}

private class SharedTabState(
    val items: List<MsgItem> = emptyList(),
    val cursor: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val more: Boolean = false,
    val error: String? = null
)

/** All Media: what the chat holds, in three tabs: Media (photos + videos), Files, Voice. Each tab loads its own pages. */
@Composable
private fun AllMediaScreen(cid: String, meId: String, peerName: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val tabs = remember { listOf(TAB_MEDIA, TAB_FILES, TAB_VOICE) }
    var tab by remember { mutableStateOf(TAB_MEDIA) }
    val states = remember { mutableStateMapOf<String, SharedTabState>() }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var viewImage by remember { mutableStateOf<MsgItem?>(null) }
    var viewVideo by remember { mutableStateOf<MsgItem?>(null) }

    fun load(t: String) {
        val cur = states[t] ?: SharedTabState()
        if (cur.loading) return
        states[t] = SharedTabState(cur.items, cur.cursor, cur.loaded, true, cur.more, null)
        runIo({ fetchShared(cid, t, cur.cursor) }) { r ->
            r.onSuccess { page ->
                counts = page.counts
                states[t] = SharedTabState(
                    items = (cur.items + page.items).distinctBy { it.msg.id },
                    cursor = page.cursor ?: cur.cursor,
                    loaded = true,
                    loading = false,
                    more = page.hasMore,
                    error = null
                )
            }.onFailure {
                states[t] = SharedTabState(cur.items, cur.cursor, true, false, false, it.message ?: "Couldn't load")
            }
        }
    }

    LaunchedEffect(tab) {
        val st = states[tab]
        if (st == null || (!st.loaded && !st.loading)) load(tab)
    }

    // a voice message started here stops when the page is left
    DisposableEffect(Unit) {
        onDispose {
            val pid = VoicePlayer.playingId
            if (pid != null && states[TAB_VOICE]?.items?.any { it.msg.id == pid } == true) VoicePlayer.stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ui.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenBar("All Media", onBack)
        ProfileTabStrip(tabs, tab, counts) { tab = it }
        Spacer(Modifier.height(4.dp))
        val st = states[tab]
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (st == null || (!st.loaded && st.error == null)) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Ui.muted, modifier = Modifier.size(28.dp))
                }
            } else if (st.items.isEmpty()) {
                Text(
                    st.error ?: when (tab) {
                        TAB_MEDIA -> "No photos or videos yet"
                        TAB_FILES -> "No files yet"
                        else -> "No voice messages yet"
                    },
                    color = if (st.error != null) MaterialTheme.colorScheme.error else Ui.muted,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 48.dp)
                )
                if (st.error != null) ShowMore { load(tab) }
            } else {
                when (tab) {
                    TAB_MEDIA -> SharedMediaGrid(cid, st.items) {
                        if (it.msg.kind == "video") viewVideo = it else viewImage = it
                    }
                    TAB_FILES -> SharedFilesList(cid, st.items)
                    else -> SharedVoiceList(cid, st.items, meId, peerName)
                }
                if (st.loading) {
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Ui.muted, modifier = Modifier.size(22.dp))
                    }
                } else if (st.more || st.error != null) {
                    ShowMore { load(tab) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    viewImage?.let { ImageViewer(cid, it) { viewImage = null } }
    viewVideo?.let { VideoPlayerDialog(cid, it) { viewVideo = null } }
}
