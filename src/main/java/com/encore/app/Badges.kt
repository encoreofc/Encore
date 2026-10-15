package com.encore.app

import android.os.Handler
import android.provider.Settings
import android.os.Looper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The server sends this for people who have no sticker badge. It is drawn as the Encore badge. */
const val STAR_BADGE = "\u2B50"

/** A sticker worn as a badge. until = when it ends (ms), null = no end. */
class StickerBadge(val id: String, val kind: String, val until: Long? = null)

// a badge whose time ran out counts as gone, the default badge comes back
private fun StickerBadge?.activeNow(): StickerBadge? =
    this?.takeIf { it.until == null || it.until > System.currentTimeMillis() }

/* ------------------------------------------------------------------ */
/* store: user id -> badge, only badge holders are in here             */
/* ------------------------------------------------------------------ */

object BadgeStore {
    val map = mutableStateMapOf<String, String>()
    val stickers = mutableStateMapOf<String, StickerBadge>()
    private val asked = HashMap<String, Long>()
    private val pending = LinkedHashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private const val FRESH_MS = 5 * 60 * 1000L

    // a name that scrolls into view asks again only when the last answer is a few minutes old
    fun want(id: String) {
        if (id.isBlank() || id in pending) return
        val t = asked[id]
        if (t != null && System.currentTimeMillis() - t < FRESH_MS) return
        pending.add(id)
        schedule()
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        handler.postDelayed({ flush() }, 150)
    }

    // many rows ask at once, so they go to the server together (max 50 per call)
    private fun flush() {
        scheduled = false
        val batch = pending.take(50)
        if (batch.isEmpty()) return
        pending.removeAll(batch.toSet())
        val now = System.currentTimeMillis()
        batch.forEach { asked[it] = now }
        runIo({ Api.call("GET", "/v1/badges?ids=" + batch.joinToString(",")) }) { r ->
            r.onSuccess { j ->
                val o = j.optJSONObject("badges")
                val s = j.optJSONObject("stickers")
                for (id in batch) {
                    if (o != null && o.has(id)) map[id] = o.getString(id) else map.remove(id)
                    val sb = s?.optJSONObject(id)
                    if (sb != null) {
                        stickers[id] = StickerBadge(
                            sb.getString("id"),
                            sb.getString("kind"),
                            if (sb.isNull("until")) null else sb.optLong("until")
                        )
                    } else {
                        stickers.remove(id)
                    }
                }
            }.onFailure { batch.forEach { asked.remove(it) } }
            if (pending.isNotEmpty()) schedule()
        }
    }

    /** A "badge" realtime event from the server (my badge changed on another device). */
    fun apply(e: JSONObject) {
        val uid = e.optString("user_id")
        if (uid.isBlank()) return
        val st = e.optJSONObject("sticker")
        if (st != null) {
            stickers[uid] = StickerBadge(
                st.getString("id"),
                st.getString("kind"),
                if (e.isNull("until")) null else e.optLong("until")
            )
        } else {
            stickers.remove(uid)
        }
    }
}

/* ------------------------------------------------------------------ */
/* drawing: the badge                                                  */
/* ------------------------------------------------------------------ */

/*
 * A scalloped seal with a check, flat and one color, like the verified marks people already
 * know. The yellow is the app's own yellow, the check is the app's ink. It is built once in a
 * 0..1 box and scaled, and nothing animates in a list, so a row costs almost nothing.
 */

// 8 rounded lobes around a circle
private val SealPath = Path().apply {
    val steps = 240
    for (i in 0..steps) {
        val a = 2.0 * Math.PI * i / steps
        val r = 0.42 * (1.0 + 0.07 * Math.cos(8.0 * a))
        val x = (0.5 + r * Math.cos(a)).toFloat()
        val y = (0.5 + r * Math.sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val SealCheck = Path().apply {
    moveTo(0.315f, 0.515f)
    lineTo(0.440f, 0.640f)
    lineTo(0.690f, 0.360f)
}

private val SealColor = Color(0xFFF5C400)
private val SealInk = Color(0xFF1A1A1A)
private val SealRim = Color(0x598A6200) // a fine edge so the shape holds on a white background

// glint: below 0 or above 1 = off, 0..1 = a soft light band crossing the badge once
private val NoGlint: () -> Float = { -1f }

private fun DrawScope.drawEncoreBadge(w: Float, glint: Float) {
    scale(scaleX = w, scaleY = w, pivot = Offset.Zero) {
        drawPath(SealPath, SealColor)
        drawPath(SealPath, SealRim, style = Stroke(width = 0.02f, join = StrokeJoin.Round))
        drawPath(
            SealCheck,
            SealInk,
            style = Stroke(width = 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        if (glint > 0f && glint < 1f) {
            clipPath(SealPath) {
                val x = -0.3f + 1.6f * glint
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                        start = Offset(x - 0.18f, 0f),
                        end = Offset(x + 0.18f, 0.36f)
                    ),
                    size = Size(1f, 1f)
                )
            }
        }
    }
}

@Composable
private fun EncoreBadge(dim: Dp, glint: () -> Float = NoGlint) {
    Canvas(Modifier.size(dim)) { drawEncoreBadge(size.minDimension, glint()) }
}

/** Kept so existing call sites still work. Draws the Encore badge. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun StarGlyph(dim: Dp, shimmer: Boolean = true) = EncoreBadge(dim)

/** A sticker badge if the person wears one, otherwise the Encore badge. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun BadgeGlyph(
    e: String,
    dim: Dp,
    shimmer: Boolean = true,
    sticker: StickerBadge? = null,
    glint: () -> Float = NoGlint
) {
    if (sticker != null && sticker.kind != "video") {
        StickerView(sticker.id, sticker.kind, Modifier.size(dim))
    } else if (e == STAR_BADGE) {
        EncoreBadge(dim, glint)
    } else {
        Box(Modifier.size(dim), contentAlignment = Alignment.Center) {
            Text(e, fontSize = with(LocalDensity.current) { (dim * 0.82f).toSp() })
        }
    }
}

/** Same as BadgeGlyph but looks the person up in the store (used in Settings). */
@Composable
fun BadgeOf(userId: String, dim: Dp, shimmer: Boolean = true) {
    val e = BadgeStore.map[userId] ?: STAR_BADGE
    BadgeGlyph(e, dim, shimmer, BadgeStore.stickers[userId].activeNow())
}

/* ------------------------------------------------------------------ */
/* the sparkles behind the badge in the sheet, like Telegram's: many     */
/* small twinkles scattered all around the badge                        */
/* ------------------------------------------------------------------ */

// a thin 4 point sparkle in a -0.5..0.5 box, centered on 0,0
private val Sparkle = Path().apply {
    val k = 0.09f
    moveTo(0f, -0.5f); lineTo(k, -k); lineTo(0.5f, 0f); lineTo(k, k)
    lineTo(0f, 0.5f); lineTo(-k, k); lineTo(-0.5f, 0f); lineTo(-k, -k)
    close()
}

// x, y = place inside the stage (-1..1 from the middle), size in dp, alpha, dot = a tiny round speck
private class Spark(val x: Float, val y: Float, val size: Float, val alpha: Float, val dot: Boolean)

// the same scatter every time (fixed seed): many small ones, a few bigger
private val Sparks: List<Spark> = run {
    val rnd = java.util.Random(11L)
    List(110) {
        val a = rnd.nextDouble() * 2.0 * Math.PI
        val d = Math.sqrt(rnd.nextDouble())
        val big = rnd.nextInt(8) == 0
        Spark(
            (d * Math.cos(a)).toFloat(),
            (d * Math.sin(a)).toFloat(),
            if (big) 9f + rnd.nextFloat() * 4f else 3.5f + rnd.nextFloat() * 4f,
            0.35f + rnd.nextFloat() * 0.65f,
            !big && rnd.nextInt(5) == 0
        )
    }
}

@Composable
private fun StarBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val rx = size.width * 0.47f
        val ry = size.height * 0.5f
        val hole = 62.dp.toPx() // the badge sits here
        for (sp in Sparks) {
            val dx = sp.x * rx
            val dy = sp.y * ry
            if (Math.hypot(dx.toDouble(), dy.toDouble()) < hole) continue
            val sz = sp.size.dp.toPx()
            val color = SealColor.copy(alpha = sp.alpha)
            if (sp.dot) {
                drawCircle(color, radius = sz * 0.2f, center = Offset(cx + dx, cy + dy))
            } else {
                translate(cx + dx, cy + dy) {
                    scale(scaleX = sz, scaleY = sz, pivot = Offset.Zero) {
                        drawPath(Sparkle, color)
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* who made a sticker badge + adding it to your own stickers           */
/* ------------------------------------------------------------------ */

private class StickerInfo(
    val ownerName: String?,
    val ownerUsername: String?,
    val mine: Boolean,
    val isPublic: Boolean,
    val saved: Boolean
) {
    // only a shared sticker can be kept; a private one is only visible because it is worn
    val canSave: Boolean get() = !mine && !saved && isPublic
    fun asSaved() = StickerInfo(ownerName, ownerUsername, mine, isPublic, true)
}

private fun parseStickerInfo(o: JSONObject?): StickerInfo? {
    if (o == null) return null
    val ow = o.optJSONObject("owner")
    return StickerInfo(
        ow?.optString("name")?.takeIf { it.isNotBlank() && it != "null" },
        if (ow == null || ow.isNull("username")) null else ow.optString("username").takeIf { it.isNotBlank() },
        o.optBoolean("mine"),
        o.optBoolean("public"),
        o.optBoolean("saved")
    )
}

/* ------------------------------------------------------------------ */
/* motion curves (Material 3 tokens): enter the screen / move on screen */
/* ------------------------------------------------------------------ */

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/* ------------------------------------------------------------------ */
/* the badge next to a name + the sheet that opens when you tap it     */
/* ------------------------------------------------------------------ */

/** Put right after a name, inside a Row. Nothing is drawn for people without a badge. */
@Composable
fun Badge(userId: String?, size: TextUnit = 14.sp, name: String? = null) {
    if (userId.isNullOrBlank()) return
    LaunchedEffect(userId) { BadgeStore.want(userId) }
    val e = BadgeStore.map[userId] ?: return
    val sticker = BadgeStore.stickers[userId].activeNow()
    var open by remember(userId) { mutableStateOf(false) }
    val dim = with(LocalDensity.current) { size.toDp() } * 1.2f
    Spacer(Modifier.width(5.dp))
    Box(Modifier.clip(CircleShape).clickable { open = true }) { BadgeGlyph(e, dim, true, sticker) }
    if (open) BadgeSheet(e, sticker, name) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BadgeSheet(e: String, sticker: StickerBadge?, name: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // if the phone has animations switched off, show the finished sheet at once
    val calm = remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val hero = remember { Animatable(if (calm) 1f else 0f) }
    val shine = remember { Animatable(0f) }
    val copy = remember { Animatable(if (calm) 1f else 0f) }
    var info by remember { mutableStateOf<StickerInfo?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addFailed by remember { mutableStateOf(false) }

    // who made it, and whether I can keep it (only for sticker badges)
    LaunchedEffect(sticker?.id) {
        val sid = sticker?.id ?: return@LaunchedEffect
        runIo({ Api.call("GET", "/v1/stickers/" + sid) }) { r ->
            r.onSuccess { j -> info = parseStickerInfo(j.optJSONObject("sticker")) }
        }
    }

    fun addToMine() {
        val sid = sticker?.id ?: return
        adding = true
        addFailed = false
        runIo({ Api.call("PUT", "/v1/stickers/" + sid + "/save") }) { r ->
            adding = false
            r.onSuccess { info = info?.asSaved() }.onFailure { addFailed = true }
        }
    }

    // three short steps, then it is still:
    // the badge settles in, the text follows, one soft glint crosses the badge
    LaunchedEffect(Unit) {
        if (calm) return@LaunchedEffect
        launch { hero.animateTo(1f, tween(400, easing = EmphasizedDecelerate)) }
        launch {
            delay(120)
            copy.animateTo(1f, tween(300, easing = StandardEasing))
        }
        launch {
            delay(480)
            shine.animateTo(1f, tween(900, easing = StandardEasing))
        }
    }

    val who = name ?: "This account"
    val detail = if (sticker != null) {
        "$who chose this sticker as their badge. Only Premium accounts can show a badge."
    } else {
        "Premium accounts show this badge next to their name in chats, groups and profiles."
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ui.bg,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // the stage: stars all around, the badge in the middle
            Box(Modifier.fillMaxWidth().height(236.dp), contentAlignment = Alignment.Center) {
                StarBackdrop(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = hero.value.coerceIn(0f, 1f) }
                )
                Box(
                    Modifier
                        .graphicsLayer {
                            val m = hero.value
                            scaleX = 0.9f + 0.1f * m
                            scaleY = 0.9f + 0.1f * m
                            alpha = m.coerceIn(0f, 1f)
                        }
                        .semantics { contentDescription = "Encore Premium badge" }
                ) {
                    BadgeGlyph(e, 120.dp, true, sticker, glint = { shine.value })
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .graphicsLayer {
                        alpha = copy.value
                        translationY = (1f - copy.value) * 8.dp.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Encore Premium",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "This account is an Encore Premium member.",
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = Ui.muted,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    detail,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = Ui.muted.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
                val i = info
                if (sticker != null && i != null) {
                    Spacer(Modifier.height(20.dp))
                    if (i.ownerName != null) {
                        Text(
                            "Made by " + i.ownerName + (i.ownerUsername?.let { "  \u00B7  @" + it } ?: ""),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    val shape = RoundedCornerShape(50)
                    when {
                        i.mine -> Text("This is one of your stickers", fontSize = 13.sp, color = Ui.muted)
                        i.saved -> Text("\u2713  Added to your stickers", fontSize = 14.sp, color = Ui.muted)
                        i.canSave -> Text(
                            if (adding) "Adding\u2026" else "Add to my stickers",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clip(shape)
                                .border(1.5.dp, Ui.ink, shape)
                                .clickable(enabled = !adding, role = Role.Button) { addToMine() }
                                .padding(horizontal = 22.dp, vertical = 11.dp)
                        )
                        else -> Text(
                            "This sticker is private, only its owner can share it.",
                            fontSize = 13.sp,
                            color = Ui.muted,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (addFailed) {
                        Spacer(Modifier.height(8.dp))
                        Text("Could not add it. Try again.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(36.dp))
                Text(
                    "OK",
                    color = Ui.onInk,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(CircleShape)
                        .background(Ui.ink)
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .wrapContentHeight(Alignment.CenterVertically)
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* picker (opened from Settings, verified accounts only)               */
/* ------------------------------------------------------------------ */

private val BadgeDurations = listOf(
    "1 hour" to 3600,
    "8 hours" to 28800,
    "1 day" to 86400,
    "1 week" to 604800,
    "1 month" to 2592000
)

private fun parseSticker(o: JSONObject) = Sticker(
    o.getString("id"),
    o.getString("kind"),
    o.optString("mime"),
    o.optInt("w"),
    o.optInt("h"),
    if (o.isNull("dur")) null else o.optInt("dur"),
    o.optBoolean("mine"),
    o.optBoolean("public"),
    o.optBoolean("saved")
)

private fun fetchStickers(path: String): List<Sticker> {
    val a = Api.call("GET", path).getJSONArray("stickers")
    return List(a.length()) { parseSticker(a.getJSONObject(it)) }
}

private fun leftLabel(ms: Long): String {
    val m = ms / 60000
    return when {
        m < 60 -> maxOf(m, 1L).toString() + " min"
        m < 60 * 24 -> (m / 60).toString() + " h"
        else -> (m / (60 * 24)).toString() + " d"
    }
}

@Composable
private fun PickerLabel(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text.uppercase(),
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ui.muted,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            Text(
                action,
                fontSize = 13.sp,
                color = Ui.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BadgeCell(
    s: Sticker?,
    on: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    onHold: (() -> Unit)?
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(if (on) Ui.soft else Color.Transparent)
            .then(if (on) Modifier.border(1.5.dp, Ui.ink, shape) else Modifier)
            .combinedClickable(enabled = enabled, onClick = onTap, onLongClick = onHold),
        contentAlignment = Alignment.Center
    ) {
        if (s == null) StarGlyph(34.dp, shimmer = false)
        else StickerView(s.id, s.kind, Modifier.fillMaxSize().padding(8.dp))
    }
}

// s == null is the default badge
@Composable
private fun BadgeGrid(
    cells: List<Sticker?>,
    currentId: String?,
    busy: Boolean,
    onPick: (Sticker?) -> Unit,
    onHold: (Sticker) -> Unit
) {
    cells.chunked(6).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { s ->
                val on = if (s == null) currentId == null else s.id == currentId
                BadgeCell(
                    s,
                    on,
                    !busy,
                    Modifier.weight(1f),
                    { onPick(s) },
                    if (s == null) null else ({ onHold(s) })
                )
            }
            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgePickerDialog(myId: String, onDismiss: () -> Unit) {
    val current = BadgeStore.stickers[myId].activeNow()
    var recent by remember { mutableStateOf<List<Sticker>?>(null) }
    var featured by remember { mutableStateOf<List<Sticker>?>(null) }
    var mine by remember { mutableStateOf<List<Sticker>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var holding by remember { mutableStateOf<Sticker?>(null) }

    LaunchedEffect(Unit) {
        runIo({ fetchStickers("/v1/me/badge/recent") }) { r ->
            r.onSuccess { recent = it }.onFailure { recent = emptyList() }
        }
        runIo({ fetchStickers("/v1/badges/featured") }) { r ->
            r.onSuccess { featured = it }.onFailure { featured = emptyList() }
        }
        runIo({
            (BotApi.stickers("mine") + BotApi.stickers("saved"))
                .filter { it.kind != "video" }
                .distinctBy { it.id }
        }) { r ->
            r.onSuccess { mine = it }.onFailure {
                mine = emptyList()
                problem = it.message
            }
        }
    }

    // s == null puts the default badge back; seconds == null means no end time
    fun save(s: Sticker?, seconds: Int?) {
        busy = true
        problem = null
        val body = JSONObject()
        if (s != null) {
            body.put("sticker_id", s.id)
            if (seconds != null) body.put("duration_s", seconds)
        }
        runIo({ Api.call("PUT", "/v1/me/badge", body) }) { r ->
            busy = false
            r.onSuccess { j ->
                val st = j.optJSONObject("sticker")
                if (st != null) {
                    BadgeStore.stickers[myId] = StickerBadge(
                        st.getString("id"),
                        st.getString("kind"),
                        if (j.isNull("until")) null else j.optLong("until")
                    )
                } else {
                    BadgeStore.stickers.remove(myId)
                }
                BadgeStore.map[myId] = STAR_BADGE
                onDismiss()
            }.onFailure { problem = it.message }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ui.bg) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("Your badge", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            val left = current?.until?.let { it - System.currentTimeMillis() }
            Text(
                if (left != null && left > 0) "Ends in " + leftLabel(left)
                else "Shown next to your name in chats, groups and profiles.",
                fontSize = 13.sp,
                color = Ui.muted
            )
            Spacer(Modifier.height(2.dp))
            Text("Tap a sticker to wear it. Hold it to wear it for a while.", fontSize = 12.sp, color = Ui.muted)

            val r = recent
            PickerLabel(
                "Recent",
                if (!r.isNullOrEmpty()) "Clear" else null
            ) {
                runIo({ Api.call("DELETE", "/v1/me/badge/recent") }) { res ->
                    res.onSuccess { recent = emptyList() }.onFailure { problem = it.message }
                }
            }
            if (r == null) {
                Text("Loading\u2026", color = Ui.muted, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                BadgeGrid(listOf<Sticker?>(null) + r, current?.id, busy, { save(it, null) }, { holding = it })
            }

            val f = featured
            if (!f.isNullOrEmpty()) {
                PickerLabel("Featured")
                BadgeGrid(f, current?.id, busy, { save(it, null) }, { holding = it })
            }

            PickerLabel("Your stickers")
            val m = mine
            if (m == null) {
                Text("Loading\u2026", color = Ui.muted, modifier = Modifier.padding(vertical = 8.dp))
            } else if (m.isEmpty()) {
                Text(
                    "Make a sticker with the Sticker Maker bot, or save one from someone, and it shows up here.",
                    fontSize = 13.sp,
                    color = Ui.muted
                )
            } else {
                BadgeGrid(m, current?.id, busy, { save(it, null) }, { holding = it })
            }

            problem?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    holding?.let { s ->
        AlertDialog(
            onDismissRequest = { holding = null },
            containerColor = Ui.bg,
            title = { Text("Wear it for how long?") },
            text = {
                Column {
                    BadgeDurations.forEach { (label, seconds) ->
                        Text(
                            label,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    holding = null
                                    save(s, seconds)
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { holding = null }) { Text("Cancel") } }
        )
    }
}
