package com.encore.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first

// ---------------------------------------------------------------------------
// Encore profile. Light design: grey page, white cards, small grey section
// headings. Top: round photo, name, @username, four tiles. Then Bio, Chat,
// Shared Media, and the gifts at the bottom.
// ---------------------------------------------------------------------------

/**
 * Colours of this page. Light mode: grey page with white cards.
 * Dark mode keeps the app's own colours (Ui) and only adds a soft card tone.
 */
private object Pf {
    private val dark: Boolean
        @Composable get() = Ui.bg.luminance() < 0.5f

    val bg: Color
        @Composable get() = if (dark) Ui.bg else Color(0xFFEFEFEF)
    val card: Color
        @Composable get() = if (dark) lerp(Ui.bg, Ui.ink, 0.08f) else Color.White
    val sheet: Color
        @Composable get() = if (dark) Ui.bg else Color.White
    val soft: Color
        @Composable get() = if (dark) Ui.soft else Color(0xFFE7E7E9)
    val ink: Color
        @Composable get() = Ui.ink
    val muted: Color
        @Composable get() = Ui.muted
    val onInk: Color
        @Composable get() = Ui.onInk
    val line: Color
        @Composable get() = Ui.line
}

// change this to your real profile-link base when the website exists
private const val SHARE_BASE = "https://encore.app/"

private val StoryGold = listOf(
    Color(0xFFFFE27A),
    Color(0xFFFFC400),
    Color(0xFFB8860B),
    Color(0xFFFFC400),
    Color(0xFFFFE27A)
)

private const val TAB_MEDIA = "media"
private const val TAB_FILES = "files"
private const val TAB_VOICE = "voice"
private const val TAB_LINKS = "links"
private const val PAGE = 60

private fun tabTitle(t: String) = when (t) {
    TAB_MEDIA -> "Media"
    TAB_FILES -> "Files"
    TAB_VOICE -> "Voice"
    else -> "Links"
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

/** Saved on this device only for now. Move each one to the server when its API is ready. */
private object LocalPrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("encore_profile_local", Context.MODE_PRIVATE)
    fun getNote(ctx: Context, uid: String): String = sp(ctx).getString("note_$uid", "") ?: ""
    fun setNote(ctx: Context, uid: String, v: String) { sp(ctx).edit().putString("note_$uid", v).apply() }
    fun getTtl(ctx: Context, cid: String): Int = sp(ctx).getInt("ttl_$cid", 0)
    fun setTtl(ctx: Context, cid: String, v: Int) { sp(ctx).edit().putInt("ttl_$cid", v).apply() }
    fun getMuteUntil(ctx: Context, cid: String): Long = sp(ctx).getLong("muteuntil_$cid", 0L)
    fun setMuteUntil(ctx: Context, cid: String, v: Long) { sp(ctx).edit().putLong("muteuntil_$cid", v).apply() }
}

/** Raw exception text never reaches the screen. */
private fun friendly(e: Throwable?): String {
    val m = (e?.message ?: "").lowercase()
    val net = m.contains("unable to resolve") || m.contains("timeout") || m.contains("timed out") ||
        m.contains("failed to connect") || m.contains("network") || m.contains("connect")
    return if (net) "No connection. Check your internet and try again." else "Something went wrong. Please try again."
}

private fun ttlLabel(s: Int) = when (s) {
    86400 -> "24 hours"
    604800 -> "7 days"
    else -> "Off"
}

private fun copyText(ctx: Context, text: String, done: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("label", text))
        // Android 13 and newer show their own "copied" note
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, done, Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
    }
}

private fun shareProfile(ctx: Context, name: String, username: String) {
    try {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$name on Encore\n${SHARE_BASE}@$username")
        }
        ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
    }
}

private fun openLink(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
    }
}

private fun hostOf(url: String): String =
    try { Uri.parse(url).host?.removePrefix("www.") ?: url } catch (e: Exception) { url }

private fun fileSizeText(b: Long): String = when {
    b >= 1048576L -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
    b >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", b / 1024.0)
    else -> "$b B"
}

private fun voiceClock(ms: Long): String {
    val s = (ms / 1000).toInt()
    return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
}

// ---------------------------------------------------------------------------
// Server pages
// ---------------------------------------------------------------------------

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

private class LinkItem(val key: String, val url: String, val at: Long)
private class LinkPage(val items: List<LinkItem>, val cursor: String?, val hasMore: Boolean)

private val URL_RE = Regex("""https?://[^\s<>"']+""")

/**
 * Links of the chat. Expects the same endpoint with kind=links, returning "messages"
 * that carry "text" (or "body"), "id" and "created_at". Until the server has it, the tab shows an error / empty state.
 */
private fun fetchLinks(cid: String, before: String?): LinkPage {
    val q = "/v1/conversations/$cid/shared?kind=links&limit=$PAGE" + (if (before != null) "&before=$before" else "")
    val j = Api.call("GET", q)
    val arr = j.getJSONArray("messages")
    val out = ArrayList<LinkItem>()
    var lastId: String? = null
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val id = o.optString("id")
        lastId = id
        if (o.optBoolean("deleted")) continue
        val text = o.optString("text").ifEmpty { o.optString("body") }
        val at = o.optLong("created_at", 0L)
        URL_RE.findAll(text).map { it.value.trimEnd('.', ',', ')', '!', '?') }.distinct().forEachIndexed { n, u ->
            out.add(LinkItem("$id#$n", u, at))
        }
    }
    return LinkPage(out, lastId, j.optBoolean("has_more"))
}

// month groups (time gives a place to remember things by)
private sealed class Cell(val key: String) {
    class Head(val text: String, key: String) : Cell(key)
    class Entry(val item: MsgItem) : Cell("m:" + item.msg.id)
}

private fun groupByMonth(items: List<MsgItem>): List<Cell> {
    val fmt = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
    val out = ArrayList<Cell>()
    var last = ""
    var n = 0
    for (item in items) {
        val label = fmt.format(java.util.Date(item.msg.createdAt))
        if (label != last) {
            out.add(Cell.Head(label.uppercase(), "h:" + n++))
            last = label
        }
        out.add(Cell.Entry(item))
    }
    return out
}

// the last known background picture of each person, so a page does not jump when it opens again
private val coverKeyCache = HashMap<String, String?>()

// ---------------------------------------------------------------------------
// Glyphs (drawn by hand, no extra icon library)
// ---------------------------------------------------------------------------

@Composable
private fun GalleryGlyph(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
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
        drawPath(mountain, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun FilesGlyph(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.08f
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.2f, w * 0.1f),
            size = Size(w * 0.6f, w * 0.8f),
            cornerRadius = CornerRadius(w * 0.1f),
            style = Stroke(width = sw)
        )
        drawLine(color, Offset(w * 0.34f, w * 0.4f), Offset(w * 0.66f, w * 0.4f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.34f, w * 0.55f), Offset(w * 0.66f, w * 0.55f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.34f, w * 0.7f), Offset(w * 0.52f, w * 0.7f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun VoiceGlyph(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.1f
        val hs = floatArrayOf(0.25f, 0.55f, 0.85f, 0.55f, 0.25f)
        hs.forEachIndexed { i, h ->
            val x = w * (0.2f + 0.15f * i)
            drawLine(color, Offset(x, w * (0.5f - h / 2f)), Offset(x, w * (0.5f + h / 2f)), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun LinkGlyph(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.08f
        rotate(degrees = -45f, pivot = center) {
            drawRoundRect(color, Offset(w * 0.06f, w * 0.36f), Size(w * 0.5f, w * 0.28f), CornerRadius(w * 0.14f), style = Stroke(width = sw))
            drawRoundRect(color, Offset(w * 0.44f, w * 0.36f), Size(w * 0.5f, w * 0.28f), CornerRadius(w * 0.14f), style = Stroke(width = sw))
        }
    }
}

@Composable
private fun TimerGlyph(color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.08f
        drawCircle(color, radius = w * 0.34f, center = Offset(w / 2f, w * 0.57f), style = Stroke(width = sw))
        drawLine(color, Offset(w / 2f, w * 0.57f), Offset(w / 2f, w * 0.38f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w / 2f, w * 0.57f), Offset(w * 0.62f, w * 0.65f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.42f, w * 0.1f), Offset(w * 0.58f, w * 0.1f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

// circle with a slash, drawn by hand
@Composable
private fun BlockGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val sw = w * 0.09f
        drawCircle(color, radius = w * 0.38f, center = Offset(w / 2f, w / 2f), style = Stroke(width = sw))
        drawLine(color, Offset(w * 0.24f, w * 0.24f), Offset(w * 0.76f, w * 0.76f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun CopyGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val sw = w * 0.08f
        drawRoundRect(color, Offset(w * 0.32f, w * 0.1f), Size(w * 0.56f, w * 0.62f), CornerRadius(w * 0.08f), style = Stroke(width = sw))
        drawRoundRect(color, Offset(w * 0.12f, w * 0.28f), Size(w * 0.56f, w * 0.62f), CornerRadius(w * 0.08f), style = Stroke(width = sw))
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

/** The bell; when muted a slash is drawn across it with a small gap in the tile colour. */
@Composable
private fun MuteGlyph(muted: Boolean) {
    val ink = Pf.ink
    val soft = Pf.card
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Notifications, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp))
        if (muted) {
            Canvas(Modifier.size(24.dp)) {
                val w = size.width
                val sw = w * 0.09f
                drawLine(soft, Offset(w * 0.2f, w * 0.14f), Offset(w * 0.82f, w * 0.86f), strokeWidth = sw * 2.6f, cap = StrokeCap.Round)
                drawLine(ink, Offset(w * 0.2f, w * 0.14f), Offset(w * 0.82f, w * 0.86f), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Profile building blocks (same look as before)
// ---------------------------------------------------------------------------

/** One action under the name: a white tile, 64dp tall. */
@Composable
private fun ActionTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Pf.card)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = Pf.ink,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A small grey heading above a card (like Messenger's section titles), with an optional action on the right. */
@Composable
private fun SectionHead(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 30.dp, end = 26.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = Pf.muted,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            Text(
                action,
                color = Pf.ink,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/** The white rounded card a section sits on. */
@Composable
private fun PfCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Pf.card),
        content = content
    )
}

/** A row of a card: icon, label, optional value, arrow. */
@Composable
private fun GlassRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    divider: Boolean,
    labelColor: Color = Pf.ink,
    icon: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(18.5.dp))
            Text(label, color = labelColor, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!value.isNullOrEmpty()) {
                Text(value, color = Pf.muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                Spacer(Modifier.width(2.dp))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Pf.muted)
        }
        if (divider) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 58.dp)
                    .fillMaxWidth()
                    .height(Dp.Hairline)
                    .background(Pf.muted.copy(alpha = 0.2f))
            )
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit, color: Color = Pf.ink, icon: @Composable () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = color) },
        leadingIcon = icon,
        onClick = onClick
    )
}

/** A dialog with radio rows. */
@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Pf.sheet,
        title = { Text(title, color = Pf.ink) },
        text = {
            Column {
                if (hint != null) {
                    Text(hint, color = Pf.muted, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(8.dp))
                }
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(i) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = i == selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Pf.ink, unselectedColor = Pf.muted),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Text(label, color = Pf.ink, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Pf.ink) } }
    )
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
                .background(Pf.soft)
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
                    contentDescription = "Profile photo",
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

// ---------------------------------------------------------------------------
// The profile page
// ---------------------------------------------------------------------------

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
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    // what was last known is used right away, so the page does not fill in piece by piece
    val cached = remember(conv.id) { peer?.let { ProfileCache.get(it.id) } }
    var bio by remember(conv.id) { mutableStateOf<String?>(cached?.bio) }
    var location by remember(conv.id) { mutableStateOf<String?>(cached?.location) }
    var blocked by remember(conv.id) { mutableStateOf(cached?.blocked ?: false) }
    var muted by remember { mutableStateOf(conv.muted) }
    var group by remember { mutableStateOf<StoryGroup?>(null) }
    var toast by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var confirmBlock by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }
    // the photo viewer: the address of the photo in use while it is open, and where the avatar sits on screen
    var photoUrl by remember { mutableStateOf<String?>(null) }
    var avatarBox by remember { mutableStateOf<Rect?>(null) }
    val soon = remember { SoonState() }
    // gifts: null while loading, empty when the person has none
    var gifts by remember(conv.id) { mutableStateOf<List<GiftItem>?>(null) }
    var openGift by remember { mutableStateOf<GiftItem?>(null) }
    var shopOpen by remember { mutableStateOf(false) }
    // the username sheet: open or not, and when this person took the name (0 = the server did not say)
    var usernameSheet by remember { mutableStateOf(false) }
    var usernameSetAt by remember(conv.id) { mutableStateOf(0L) }
    var menuOpen by remember { mutableStateOf(false) }
    // contacts: in your contacts when the caller says so or the saved list has them
    val inContacts = isContact || ContactsStore.has(peer?.id)
    var contactSheet by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val giftName = peer?.displayName?.trim()?.substringBefore(' ').orEmpty()
    // the profile background picture
    var coverKey by remember(conv.id) { mutableStateOf(peer?.let { coverKeyCache[it.id] }) }
    // the All Media page (Media / Files / Voice / Links); null while closed, else the tab to open
    var allTab by remember(conv.id) { mutableStateOf<String?>(null) }
    var allCount by remember(conv.id) { mutableStateOf<Int?>(null) }
    // the first few photos / videos shown under "Shared Media"
    var peek by remember(conv.id) { mutableStateOf<List<MsgItem>?>(null) }
    var starredOpen by remember { mutableStateOf(false) }
    var bioMenu by remember { mutableStateOf(false) }
    var muteDialog by remember { mutableStateOf(false) }
    var ttlDialog by remember { mutableStateOf(false) }
    var noteDialog by remember { mutableStateOf(false) }
    var note by remember(conv.id) { mutableStateOf(peer?.let { LocalPrefs.getNote(appCtx, it.id) } ?: "") }
    var ttl by remember(conv.id) { mutableStateOf(LocalPrefs.getTtl(appCtx, conv.id)) }
    val scroll = rememberScrollState()

    fun say(t: String, error: Boolean = true) {
        toast = t to error
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(3500)
            toast = null
        }
    }

    LaunchedEffect(conv.id) {
        // a timed mute that has run out is lifted when the page opens
        if (muted) {
            val until = LocalPrefs.getMuteUntil(appCtx, conv.id)
            if (until > 0L && System.currentTimeMillis() > until) {
                muted = false
                LocalPrefs.setMuteUntil(appCtx, conv.id, 0L)
                runIo({ Api.mute(conv.id, false) }) { }
            }
        }
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
                    usernameSetAt = u.optLong("username_set_at", 0L)
                    val b0 = bio
                    val l0 = location
                    val k0 = blocked
                    val fresh = ProfileEntity(peer.id, b0, l0, k0)
                    ProfileCache.put(fresh)
                    runIo({ Db.dao(appCtx).putProfileNow(fresh) }) { }
                }.onFailure {
                    say(friendly(it))
                }
            }
            runIo({ StoriesApi.userGroup(peer) }) { r -> r.onSuccess { group = it } }
            runIo({ GiftsApi.received(peer.id) }) { r ->
                r.onSuccess { gifts = it }.onFailure { gifts = emptyList() }
            }
            // the total and the first few thumbnails: the All Media page loads its own pages when it is opened
            runIo({ fetchShared(conv.id, TAB_MEDIA, null, 6) }) { r ->
                r.onSuccess { p ->
                    allCount = p.counts.values.sum()
                    peek = p.items.take(3)
                }
            }
        }
    }

    fun unmute() {
        muted = false
        LocalPrefs.setMuteUntil(appCtx, conv.id, 0L)
        runIo({ Api.mute(conv.id, false) }) { r ->
            r.onFailure {
                muted = true
                say(friendly(it))
            }
        }
    }

    fun muteFor(ms: Long, label: String) {
        muted = true
        LocalPrefs.setMuteUntil(appCtx, conv.id, if (ms > 0L) System.currentTimeMillis() + ms else 0L)
        runIo({ Api.mute(conv.id, true) }) { r ->
            r.onSuccess {
                say(if (ms > 0L) "Muted for $label" else "Muted", false)
            }.onFailure {
                muted = false
                say(friendly(it))
            }
        }
    }

    fun blockTap() {
        if (blocked) {
            val id = peer?.id ?: ""
            runIo({ Api.block(id, false) }) { r ->
                r.onSuccess {
                    blocked = false
                    say("Unblocked", false)
                }.onFailure { say(friendly(it)) }
            }
        } else {
            confirmBlock = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Pf.bg)
    ) {
        // the background picture sits behind the top of the page and scrolls away with it
        val coverNow = coverKey
        if (coverNow != null && peer != null) {
            CoverBackdrop(coverUrl(peer.id, coverNow), Modifier.offset { IntOffset(0, -scroll.value) })
        }
        // soft round plates behind the back arrow and the three dots when a picture is behind them
        val chip = if (coverNow != null) Pf.bg.copy(alpha = 0.55f) else Color.Transparent
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val avatarNow = peer?.let { Avatars.url(it.id) }
            val story = group

            // the round photo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 40.dp),
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
                        else if (avatarNow != null) photoUrl = avatarNow
                    },
                    onLongClick = { if (avatarNow != null) photoUrl = avatarNow },
                    onBounds = { avatarBox = it }
                )
            }

            // name
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    ContactsStore.nameFor(peer?.id, peer?.displayName ?: "Unknown"),
                    color = Pf.ink,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    letterSpacing = (-0.3).sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Badge(peer?.id, 24.sp, name = peer?.displayName)
            }

            // the username sits where a phone number would be; tap opens its sheet, press and hold copies it
            if (peer != null) {
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { usernameSheet = true },
                            onLongClick = { copyText(appCtx, "@" + peer.username, "Username copied") }
                        )
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "@" + peer.username,
                        color = Pf.muted,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            // a blocked person is always one tap from being unblocked
            if (blocked && peer != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .padding(horizontal = 14.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Pf.card)
                        .padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("You blocked this user", color = Pf.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { blockTap() }) { Text("Unblock", color = Pf.ink, fontWeight = FontWeight.SemiBold) }
                }
            }

            // the four tiles
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionTile(if (muted) "Unmute" else "Mute", { if (muted) unmute() else muteDialog = true }, Modifier.weight(1f)) { MuteGlyph(muted) }
                ActionTile("Audio", { soon.show("Calls") }, Modifier.weight(1f)) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = Pf.ink, modifier = Modifier.size(24.dp))
                }
                ActionTile("Video", { soon.show("Video calls") }, Modifier.weight(1f)) { VideoIcon(Pf.ink) }
                ActionTile("Search", onSearch, Modifier.weight(1f)) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Pf.ink, modifier = Modifier.size(24.dp))
                }
            }

            if (peer != null) {
                // Bio: its own small section, only when there is one. Press and hold: a Copy button
                val b = bio
                if (!b.isNullOrBlank()) {
                    SectionHead("Bio")
                    PfCard {
                        Box(Modifier.combinedClickable(onClick = {}, onLongClick = { bioMenu = true })) {
                            Text(
                                b,
                                color = Pf.ink,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            )
                            DropdownMenu(
                                expanded = bioMenu,
                                onDismissRequest = { bioMenu = false },
                                containerColor = Pf.sheet,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                MenuRow("Copy", { bioMenu = false; copyText(appCtx, b, "Bio copied") }) { CopyGlyph(Pf.ink) }
                            }
                        }
                    }
                }

                val loc = location
                if (!loc.isNullOrBlank()) {
                    SectionHead("Location")
                    PfCard {
                        Text(
                            loc,
                            color = Pf.ink,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                    }
                }

                // chat settings and personal things
                SectionHead("Chat")
                PfCard {
                    if (!inContacts) {
                        GlassRow("Add to contacts", null, { contactSheet = true }, true) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Pf.ink, modifier = Modifier.size(24.dp))
                        }
                    }
                    GlassRow("Disappearing messages", ttlLabel(ttl), { ttlDialog = true }, true) { TimerGlyph(Pf.ink) }
                    GlassRow("Starred messages", null, { starredOpen = true }, true) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = Pf.ink, modifier = Modifier.size(24.dp))
                    }
                    GlassRow("Private note", if (note.isBlank()) "Add" else note.replace('\n', ' '), { noteDialog = true }, true) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Pf.ink, modifier = Modifier.size(24.dp))
                    }
                    GlassRow("Share profile", null, { shareProfile(ctx, ContactsStore.nameFor(peer.id, peer.displayName), peer.username) }, false) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Pf.ink, modifier = Modifier.size(24.dp))
                    }
                }

                // Shared Media: the first few thumbnails, "See All" opens the page with Media / Files / Voice / Links
                val total = allCount ?: 0
                if (total > 0) {
                    SectionHead("Shared Media", "See All") { allTab = TAB_MEDIA }
                    val pk = peek
                    PfCard {
                        if (!pk.isNullOrEmpty()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                pk.forEach { item ->
                                    Box(Modifier.weight(1f).aspectRatio(1.25f)) {
                                        MediaTile(conv.id, item) { allTab = TAB_MEDIA }
                                    }
                                }
                                // the tiles keep the same size when there are fewer than three
                                repeat(3 - pk.size) { Spacer(Modifier.weight(1f)) }
                            }
                        } else {
                            GlassRow("Files, voice and links", total.toString(), { allTab = TAB_MEDIA }, false) { FilesGlyph(Pf.ink) }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // gifts sit at the bottom of the profile
            // 6.5dp here puts the gift tiles 12dp from the edge
            Box(Modifier.fillMaxWidth().padding(horizontal = 6.5.dp)) {
                GiftsSection(gifts = gifts, onOpen = { openGift = it }, showHeader = true)
            }
            // free space under the gifts, so the floating button never covers them
            Spacer(Modifier.height(110.dp))
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Pf.ink)
            }
            Spacer(Modifier.weight(1f))
            if (peer != null) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.clip(CircleShape).background(chip)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Pf.ink)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = Pf.sheet,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        MenuRow("All Media", { menuOpen = false; allTab = TAB_MEDIA }) { GalleryGlyph(Pf.ink) }
                        MenuRow("Starred Messages", { menuOpen = false; starredOpen = true }) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = Pf.ink, modifier = Modifier.size(24.dp))
                        }
                        if (!blocked) {
                            MenuRow("Send a gift", { menuOpen = false; shopOpen = true }) { GiftGlyph(Pf.ink, 24.dp) }
                        }
                        if (inContacts) {
                            MenuRow("Edit contact", { menuOpen = false; contactSheet = true }) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = Pf.ink, modifier = Modifier.size(24.dp))
                            }
                            MenuRow("Delete contact", { menuOpen = false; confirmRemove = true }, MaterialTheme.colorScheme.error) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                            }
                        } else {
                            MenuRow("Add to contacts", { menuOpen = false; contactSheet = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Pf.ink, modifier = Modifier.size(24.dp))
                            }
                        }
                        HorizontalDivider(color = Pf.line)
                        MenuRow(if (blocked) "Unblock user" else "Block user", { menuOpen = false; blockTap() }) { BlockGlyph(Pf.ink) }
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

        // the All Media page and the Starred page cover the profile while they are open
        val tabNow = allTab
        if (tabNow != null && peer != null) {
            AllMediaScreen(
                cid = conv.id,
                meId = me.id,
                peerName = ContactsStore.nameFor(peer.id, peer.displayName),
                startTab = tabNow,
                onBack = { allTab = null }
            )
        }
        if (starredOpen) {
            StarredScreen(onBack = { starredOpen = false })
        }

        // the username sheet: dims the page and slides up over it
        if (usernameSheet && peer != null) {
            UsernameSheet(
                peer.id,
                peer.username,
                ContactsStore.nameFor(peer.id, peer.displayName),
                usernameSetAt
            ) { usernameSheet = false }
        }

        // one short message at the bottom, over everything
        toast?.let { (text, isError) ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 76.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isError) MaterialTheme.colorScheme.error else Pf.ink)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text,
                    color = if (isError) MaterialTheme.colorScheme.onError else Pf.onInk,
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }

    // ----- dialogs and sheets -----

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

    if (muteDialog) {
        val labels = listOf("1 hour", "8 hours", "1 week", "Always")
        val times = listOf(3_600_000L, 28_800_000L, 604_800_000L, 0L)
        ChoiceDialog(
            title = "Mute notifications",
            options = labels,
            selected = -1,
            onPick = { i ->
                muteDialog = false
                muteFor(times[i], labels[i].lowercase())
            },
            onDismiss = { muteDialog = false }
        )
    }

    if (ttlDialog) {
        val labels = listOf("Off", "24 hours", "7 days")
        val values = listOf(0, 86400, 604800)
        ChoiceDialog(
            title = "Disappearing messages",
            options = labels,
            selected = values.indexOf(ttl),
            hint = "New messages in this chat will disappear after the time you choose.",
            onPick = { i ->
                ttlDialog = false
                ttl = values[i]
                // TODO: send to the server; for now it is saved on this device only
                LocalPrefs.setTtl(appCtx, conv.id, values[i])
                say("Timer: ${labels[i]}", false)
            },
            onDismiss = { ttlDialog = false }
        )
    }

    if (noteDialog && peer != null) {
        var draft by remember { mutableStateOf(note) }
        AlertDialog(
            onDismissRequest = { noteDialog = false },
            containerColor = Pf.sheet,
            title = { Text("Private note", color = Pf.ink) },
            text = {
                Column {
                    Text("Only you can see this. How you met, a birthday, anything you want to remember.", color = Pf.muted, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.length <= 500) draft = it },
                        placeholder = { Text("Write here") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = draft.trim()
                    // TODO: move to the server when the API exists; for now this device only
                    LocalPrefs.setNote(appCtx, peer.id, v)
                    note = v
                    noteDialog = false
                }) { Text("Save", color = Pf.ink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { noteDialog = false }) { Text("Cancel", color = Pf.ink) } }
        )
    }

    if (confirmRemove && peer != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = Pf.sheet,
            title = { Text("Delete ${ContactsStore.nameFor(peer.id, peer.displayName)}?") },
            text = { Text("They will be removed from your contacts. Your chat with them stays.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    ContactsStore.remove(peer.id) { r -> r.onFailure { say(friendly(it)) } }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }

    if (confirmBlock && peer != null) {
        var alsoReport by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            containerColor = Pf.sheet,
            title = { Text("Block ${peer.displayName}?") },
            text = {
                Column {
                    Text("You won't be able to message each other. You can unblock at any time.")
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { alsoReport = !alsoReport },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = alsoReport,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = Pf.ink, checkmarkColor = Pf.onInk),
                            modifier = Modifier.padding(end = 10.dp, top = 8.dp, bottom = 8.dp)
                        )
                        Text("Also report this user")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    val rep = alsoReport
                    runIo({ Api.block(peer.id, true) }) { r ->
                        r.onSuccess {
                            blocked = true
                            if (rep) report = true else say("Blocked", false)
                        }.onFailure { say(friendly(it)) }
                    }
                }) { Text("Block", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text("Cancel") } }
        )
    }

    if (report && peer != null) {
        val reasons = listOf("Spam", "Harassment or bullying", "Fake account or impersonation", "Inappropriate content", "Something else")
        var pick by remember { mutableStateOf(-1) }
        var details by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { report = false },
            containerColor = Pf.sheet,
            title = { Text("Report user") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    reasons.forEachIndexed { i, label ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { pick = i },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = pick == i,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Pf.ink, unselectedColor = Pf.muted),
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            Text(label, fontSize = 15.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = details,
                        onValueChange = { if (it.length <= 500) details = it },
                        label = { Text("Details (optional)") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pick >= 0,
                    onClick = {
                        report = false
                        val text = reasons[pick] + (if (details.isNotBlank()) ": " + details.trim() else "")
                        runIo({ Api.report("user", peer.id, text) }) { res ->
                            res.onSuccess { say("Report sent. Thank you.", false) }.onFailure { say(friendly(it)) }
                        }
                    }
                ) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { report = false }) { Text("Cancel") } }
        )
    }
}

/** The picture behind the top of a profile: softly blurred, fading into the page colour. */
@Composable
private fun CoverBackdrop(url: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bg = Pf.bg
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

// ---------------------------------------------------------------------------
// Starred messages (the page is ready; messages appear when starring exists in the chat)
// ---------------------------------------------------------------------------

@Composable
private fun StarredScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pf.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenBar("Starred messages", onBack)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Pf.soft),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Star, contentDescription = null, tint = Pf.muted, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.height(16.dp))
            Text("No starred messages", color = Pf.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Messages you star in this chat will be kept here.",
                color = Pf.muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ---------------------------------------------------------------------------
// All Media page: Media / Files / Voice / Links (new design for the tabs and lists)
// ---------------------------------------------------------------------------

private class SharedTabState(
    val items: List<MsgItem> = emptyList(),
    val cursor: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val more: Boolean = false,
    val error: String? = null
)

private class LinksState(
    val items: List<LinkItem> = emptyList(),
    val cursor: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val more: Boolean = false,
    val error: String? = null
)

/** Text tabs with a short line under the chosen one. */
@Composable
private fun UnderlineTabs(tabs: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            tabs.forEach { t ->
                val on = t == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clickable { onSelect(t) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tabTitle(t),
                        fontSize = 15.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) Pf.ink else Pf.muted,
                        maxLines = 1
                    )
                    if (on) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .width(28.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Pf.ink)
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(Dp.Hairline).background(Pf.muted.copy(alpha = 0.2f)))
    }
}

@Composable
private fun MonthHead(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Pf.muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = modifier
    )
}

@Composable
private fun CenterSpinner() {
    Box(Modifier.fillMaxSize().padding(top = 64.dp), contentAlignment = Alignment.TopCenter) {
        CircularProgressIndicator(color = Pf.muted, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun ListFooter(loading: Boolean, error: String?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(color = Pf.muted, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        } else if (error != null) {
            Text(
                "Couldn't load. Tap to try again",
                color = Pf.muted,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun EmptyShared(tab: String, error: String?, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Pf.soft),
            contentAlignment = Alignment.Center
        ) {
            when (tab) {
                TAB_MEDIA -> GalleryGlyph(Pf.muted, 30.dp)
                TAB_FILES -> FilesGlyph(Pf.muted, 30.dp)
                TAB_VOICE -> VoiceGlyph(Pf.muted, 30.dp)
                else -> LinkGlyph(Pf.muted, 30.dp)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (error != null) {
            Text("Couldn't load", color = Pf.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(error, color = Pf.muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onRetry) { Text("Try again", color = Pf.ink, fontWeight = FontWeight.Bold) }
        } else {
            val (title, sub) = when (tab) {
                TAB_MEDIA -> "No photos or videos" to "Photos and videos you share in this chat will appear here."
                TAB_FILES -> "No files" to "Files you share in this chat will appear here."
                TAB_VOICE -> "No voice messages" to "Voice messages from this chat will appear here."
                else -> "No links" to "Links sent in this chat will appear here."
            }
            Text(title, color = Pf.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(sub, color = Pf.muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LazyListState.nearEnd(): Boolean {
    val s = this
    val v by remember(s) {
        derivedStateOf {
            val li = s.layoutInfo
            li.totalItemsCount > 0 && (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 6
        }
    }
    return v
}

@Composable
private fun LazyGridState.nearEnd(): Boolean {
    val s = this
    val v by remember(s) {
        derivedStateOf {
            val li = s.layoutInfo
            li.totalItemsCount > 0 && (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 9
        }
    }
    return v
}

@Composable
private fun MediaTile(cid: String, item: MsgItem, onOpen: (MsgItem) -> Unit) {
    val isVideo = item.msg.kind == "video"
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isVideo) Color(0xFF1B1D21) else Pf.soft)
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
                contentDescription = "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center
            ) { PlayGlyph(Color.White, 16.dp) }
            val d = item.media?.dur ?: 0L
            if (d > 0) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 5.dp, bottom = 5.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x80000000))
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(durLabel(d), color = Color.White, fontSize = 12.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}

/** 3 columns of square tiles with soft corners, grouped by month. The next page loads by itself near the end. */
@Composable
private fun SharedMediaGrid(cid: String, st: SharedTabState, onMore: () -> Unit, onOpen: (MsgItem) -> Unit) {
    val cells = remember(st.items) { groupByMonth(st.items) }
    val state = rememberLazyGridState()
    val near = state.nearEnd()
    val canMore = st.more && !st.loading && st.error == null
    LaunchedEffect(near, canMore) { if (near && canMore) onMore() }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(
            cells,
            key = { it.key },
            span = { c -> if (c is Cell.Head) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
        ) { c ->
            when (c) {
                is Cell.Head -> MonthHead(c.text, Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp))
                is Cell.Entry -> Box(Modifier.aspectRatio(1f)) { MediaTile(cid, c.item, onOpen) }
            }
        }
        item(key = "foot", span = { GridItemSpan(maxLineSpan) }) {
            ListFooter(st.loading, st.error, onMore)
        }
    }
}

@Composable
private fun FileBadge(name: String, modifier: Modifier = Modifier) {
    val ext = name.substringAfterLast('.', "").lowercase().take(4)
    val palette = listOf(Color(0xFFE5645E), Color(0xFF3F97E0), Color(0xFF45AE67), Color(0xFFE59A3C))
    val c = palette[(ext.hashCode() and 0x7fffffff) % palette.size]
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        if (ext.isNotEmpty()) {
            Text(ext, color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}

/** Files: plain rows on the page, grouped by month. */
@Composable
private fun SharedFilesList(cid: String, st: SharedTabState, onMore: () -> Unit) {
    val ctx = LocalContext.current
    val dateFmt = remember { java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()) }
    val timeFmt = remember { android.text.format.DateFormat.getTimeFormat(ctx) }
    val cells = remember(st.items) { groupByMonth(st.items) }
    val state = rememberLazyListState()
    val near = state.nearEnd()
    val canMore = st.more && !st.loading && st.error == null
    LaunchedEffect(near, canMore) { if (near && canMore) onMore() }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(cells, key = { it.key }) { c ->
            when (c) {
                is Cell.Head -> MonthHead(c.text, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                is Cell.Entry -> {
                    val item = c.item
                    val m = item.media
                    if (m != null) {
                        val at = java.util.Date(item.msg.createdAt)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { Uploads.download(ctx, cid, item.msg.id, m.name) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FileBadge(m.name)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.name, color = Pf.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    fileSizeText(m.size) + "  \u2022  " + dateFmt.format(at) + ", " + timeFmt.format(at),
                                    color = Pf.muted,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "foot") { ListFooter(st.loading, st.error, onMore) }
    }
}

/** A voice message drawn as a wave; the played part fills as it plays. The bars come from the message id, so they stay the same. */
@Composable
private fun Wave(id: String, progress: Float, modifier: Modifier) {
    val on = Pf.ink
    val off = Pf.muted.copy(alpha = 0.35f)
    Canvas(modifier) {
        val n = 34
        val gap = 3.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        var seed = id.hashCode()
        for (i in 0 until n) {
            seed = seed * 1103515245 + 12345
            val r = ((seed ushr 16) and 0x7fff) / 32767f
            val h = size.height * (0.25f + 0.75f * r)
            drawRoundRect(
                color = if (i / n.toFloat() < progress) on else off,
                topLeft = Offset(i * (bw + gap), (size.height - h) / 2f),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f)
            )
        }
    }
}

@Composable
private fun SharedVoiceList(cid: String, st: SharedTabState, meId: String, peerName: String, onMore: () -> Unit) {
    val ctx = LocalContext.current
    val dateFmt = remember { java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()) }
    val timeFmt = remember { android.text.format.DateFormat.getTimeFormat(ctx) }
    val cells = remember(st.items) { groupByMonth(st.items) }
    val state = rememberLazyListState()
    val near = state.nearEnd()
    val canMore = st.more && !st.loading && st.error == null
    LaunchedEffect(near, canMore) { if (near && canMore) onMore() }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(cells, key = { it.key }) { c ->
            when (c) {
                is Cell.Head -> MonthHead(c.text, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                is Cell.Entry -> {
                    val item = c.item
                    val m = item.media
                    if (m != null) {
                        val id = item.msg.id
                        val playing = VoicePlayer.playingId == id
                        val at = java.util.Date(item.msg.createdAt)
                        val who = if (item.msg.senderId == meId) "You" else peerName
                        val shownMs = if (playing) VoicePlayer.position.toLong() else m.dur
                        val prog = if (playing && m.dur > 0L) (VoicePlayer.position.toFloat() / m.dur.toFloat()).coerceIn(0f, 1f) else 0f
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { VoicePlayer.toggle(ctx, cid, id, m.localUri) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Pf.ink),
                                contentAlignment = Alignment.Center
                            ) { PlayGlyph(Pf.onInk, 22.dp, playing) }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Wave(id, prog, Modifier.fillMaxWidth().height(26.dp))
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$who  \u2022  ${voiceClock(shownMs)}", color = Pf.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(dateFmt.format(at) + ", " + timeFmt.format(at), color = Pf.muted, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "foot") { ListFooter(st.loading, st.error, onMore) }
    }
}

/** Links: the site on top, the full address under it. Tap opens it, press and hold copies it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SharedLinksList(st: LinksState, onMore: () -> Unit) {
    val ctx = LocalContext.current
    val dateFmt = remember { java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()) }
    val state = rememberLazyListState()
    val near = state.nearEnd()
    val canMore = st.more && !st.loading && st.error == null
    LaunchedEffect(near, canMore) { if (near && canMore) onMore() }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        items(st.items, key = { it.key }) { l ->
            val host = hostOf(l.url)
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { openLink(ctx, l.url) },
                        onLongClick = { copyText(ctx, l.url, "Link copied") }
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Pf.soft),
                    contentAlignment = Alignment.Center
                ) {
                    Text(host.firstOrNull()?.uppercase() ?: "L", color = Pf.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(host, color = Pf.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(l.url, color = Pf.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (l.at > 0L) {
                    Spacer(Modifier.width(10.dp))
                    Text(dateFmt.format(java.util.Date(l.at)), color = Pf.muted, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        item(key = "foot") { ListFooter(st.loading, st.error, onMore) }
    }
}

/** The All Media page: Media, Files, Voice and Links. Each tab loads its own pages. */
@Composable
private fun AllMediaScreen(cid: String, meId: String, peerName: String, startTab: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val tabs = remember { listOf(TAB_MEDIA, TAB_FILES, TAB_VOICE, TAB_LINKS) }
    var tab by remember { mutableStateOf(startTab) }
    val states = remember { mutableStateMapOf<String, SharedTabState>() }
    var links by remember { mutableStateOf<LinksState?>(null) }
    var viewImage by remember { mutableStateOf<MsgItem?>(null) }
    var viewVideo by remember { mutableStateOf<MsgItem?>(null) }

    fun load(t: String) {
        val cur = states[t] ?: SharedTabState()
        if (cur.loading) return
        states[t] = SharedTabState(cur.items, cur.cursor, cur.loaded, true, cur.more, null)
        runIo({ fetchShared(cid, t, cur.cursor) }) { r ->
            r.onSuccess { page ->
                states[t] = SharedTabState(
                    items = (cur.items + page.items).distinctBy { it.msg.id },
                    cursor = page.cursor ?: cur.cursor,
                    loaded = true,
                    loading = false,
                    more = page.hasMore,
                    error = null
                )
            }.onFailure {
                states[t] = SharedTabState(cur.items, cur.cursor, true, false, false, friendly(it))
            }
        }
    }

    fun loadLinks() {
        val cur = links ?: LinksState()
        if (cur.loading) return
        links = LinksState(cur.items, cur.cursor, cur.loaded, true, cur.more, null)
        runIo({ fetchLinks(cid, cur.cursor) }) { r ->
            r.onSuccess { p ->
                links = LinksState(
                    items = (cur.items + p.items).distinctBy { it.key },
                    cursor = p.cursor ?: cur.cursor,
                    loaded = true,
                    loading = false,
                    more = p.hasMore,
                    error = null
                )
            }.onFailure {
                links = LinksState(cur.items, cur.cursor, true, false, false, friendly(it))
            }
        }
    }

    LaunchedEffect(tab) {
        if (tab == TAB_LINKS) {
            val s = links
            if (s == null || (!s.loaded && !s.loading)) loadLinks()
        } else {
            val st = states[tab]
            if (st == null || (!st.loaded && !st.loading)) load(tab)
        }
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
            .background(Pf.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        ScreenBar("All Media", onBack)
        UnderlineTabs(tabs, tab) { tab = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == TAB_LINKS) {
                val s = links
                if (s == null || (!s.loaded && s.error == null)) {
                    CenterSpinner()
                } else if (s.items.isEmpty()) {
                    EmptyShared(TAB_LINKS, s.error) { loadLinks() }
                } else {
                    SharedLinksList(s) { loadLinks() }
                }
            } else {
                val st = states[tab]
                if (st == null || (!st.loaded && st.error == null)) {
                    CenterSpinner()
                } else if (st.items.isEmpty()) {
                    EmptyShared(tab, st.error) { load(tab) }
                } else {
                    when (tab) {
                        TAB_MEDIA -> SharedMediaGrid(cid, st, { load(TAB_MEDIA) }) {
                            if (it.msg.kind == "video") viewVideo = it else viewImage = it
                        }
                        TAB_FILES -> SharedFilesList(cid, st) { load(TAB_FILES) }
                        else -> SharedVoiceList(cid, st, meId, peerName) { load(TAB_VOICE) }
                    }
                }
            }
        }
    }

    viewImage?.let { ImageViewer(cid, it) { viewImage = null } }
    viewVideo?.let { VideoPlayerDialog(cid, it) { viewVideo = null } }
}

// ---------------------------------------------------------------------------
// Username sheet: tap on @username opens it
// ---------------------------------------------------------------------------

// sheet look and motion: white sheet, 24dp top corners, page behind dims to 20%, opens in 400 ms, closes in 250 ms
private val SheetOpenEase = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private val SheetCloseEase = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val SheetSnapEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val SheetBlue = Color(0xFF229AF0)

private fun usernameDate(ms: Long): String =
    java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ms))

@Composable
private fun UsernameInfoRow(label: String, divider: Boolean, value: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Pf.muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
            value()
        }
        if (divider) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 18.dp)
                    .fillMaxWidth()
                    .height(Dp.Hairline)
                    .background(Pf.muted.copy(alpha = 0.25f))
            )
        }
    }
}

@Composable
private fun UsernameSheet(
    userId: String,
    username: String,
    displayName: String,
    setAt: Long,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    // the sheet waits and leaves this far below its place: its own height + 10dp + the navigation bar
    val spare = with(density) { 10.dp.toPx() } + navBottom
    // a swipe down further than this (about 0.8 cm), or a fast one, closes the sheet
    val swipeLimit = with(density) { 50.dp.toPx() }

    val offset = remember { Animatable(0f) }
    val dim = remember { Animatable(0f) }
    var sheetH by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    // the @ turns once and settles; tapping it turns it again
    val spin = remember { Animatable(0f) }
    val pop = remember { Animatable(0.6f) }
    val tracker = remember { VelocityTracker() }
    val dragY = remember { floatArrayOf(0f) }

    LaunchedEffect(Unit) {
        val h = snapshotFlow { sheetH }.first { it > 0 }
        offset.snapTo(h + spare)
        ready = true
        delay(20)
        launch { dim.animateTo(1f, tween(400, easing = SheetOpenEase)) }
        launch {
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
        launch { spin.animateTo(360f, tween(900, easing = FastOutSlowInEasing)) }
        offset.animateTo(0f, tween(400, easing = SheetOpenEase))
    }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            launch { dim.animateTo(0f, tween(250, easing = SheetCloseEase)) }
            offset.animateTo(sheetH + spare, tween(250, easing = SheetCloseEase))
            onDismiss()
        }
    }

    BackHandler(onBack = { close() })

    Box(Modifier.fillMaxSize()) {
        // the page behind, dimmed; a tap on it closes the sheet
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black, alpha = 0.2f * dim.value) }
                .pointerInput(Unit) { detectTapGestures { close() } }
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .onSizeChanged { sheetH = it.height }
                .graphicsLayer {
                    translationY = offset.value
                    alpha = if (ready) 1f else 0f
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragY[0] = offset.value
                            tracker.resetTracking()
                        },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().y
                            val back = (dragY[0] < swipeLimit && v < 3500f) || (v < 0f && kotlin.math.abs(v) >= 3500f)
                            if (back) {
                                val ms = (250f * (dragY[0] / swipeLimit)).toInt().coerceAtLeast(1)
                                scope.launch { offset.animateTo(0f, tween(ms, easing = SheetSnapEase)) }
                            } else {
                                close()
                            }
                        },
                        onDragCancel = {
                            scope.launch { offset.animateTo(0f, tween(250, easing = SheetSnapEase)) }
                        },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            dragY[0] = (dragY[0] + dy).coerceAtLeast(0f)
                            tracker.addPosition(change.uptimeMillis, Offset(0f, dragY[0]))
                            val next = dragY[0]
                            scope.launch { offset.snapTo(next) }
                        }
                    )
                }
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Pf.sheet)
                .padding(top = 23.dp)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // the @: a solid blue circle, 80dp, 23dp from the top and 16dp above what follows, with a 45dp glyph
            Box(
                Modifier
                    .size(80.dp)
                    .graphicsLayer {
                        rotationZ = spin.value
                        scaleX = pop.value
                        scaleY = pop.value
                    }
                    .clip(CircleShape)
                    .background(SheetBlue)
                    .clickable {
                        scope.launch {
                            spin.snapTo(0f)
                            spin.animateTo(360f, tween(900, easing = FastOutSlowInEasing))
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("@", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "@$username",
                color = Pf.ink,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(20.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Pf.soft)
            ) {
                UsernameInfoRow("Owner", setAt > 0L) {
                    Avatar(userId, displayName, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.widthIn(max = 170.dp)) {
                        Text(
                            displayName,
                            color = Pf.ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Badge(userId, 16.sp, name = displayName)
                }
                // only shown when the server knows it; nothing is guessed
                if (setAt > 0L) {
                    UsernameInfoRow("Since", false) {
                        Text(
                            usernameDate(setAt),
                            color = Pf.ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Pf.soft)
                    .clickable { shareProfile(ctx, displayName, username) },
                contentAlignment = Alignment.Center
            ) {
                Text("Share link", color = Pf.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
