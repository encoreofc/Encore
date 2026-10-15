package com.encore.app

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** The server sends this for verified people who have no sticker badge. */
const val STAR_BADGE = "\u2B50"

/** A sticker worn as a badge. until = when it ends (ms), null = no end. */
class StickerBadge(val id: String, val kind: String, val until: Long? = null)

// a badge whose time ran out counts as gone, the star comes back
private fun StickerBadge?.activeNow(): StickerBadge? =
    this?.takeIf { it.until == null || it.until > System.currentTimeMillis() }

/* ------------------------------------------------------------------ */
/* store: user id -> badge, only verified people are in here           */
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
/* drawing                                                             */
/* ------------------------------------------------------------------ */

private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float, points: Int): Path {
    val p = Path()
    val n = points * 2
    for (i in 0 until n) {
        val r = if (i % 2 == 0) outer else inner
        val a = Math.PI * i / points - Math.PI / 2
        val x = cx + (r * Math.cos(a)).toFloat()
        val y = cy + (r * Math.sin(a)).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

/** Gold star with soft corners and a light sweep that passes over it every few seconds. */
@Composable
fun StarGlyph(dim: Dp, shimmer: Boolean = true) {
    val t = if (shimmer) {
        rememberInfiniteTransition(label = "shine").animateFloat(
            initialValue = -0.5f,
            targetValue = 1.5f,
            animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing)),
            label = "shineT"
        )
    } else null
    Canvas(Modifier.size(dim)) {
        val w = this.size.minDimension
        val path = starPath(w / 2f, w * 0.53f, w * 0.40f, w * 0.20f, 5)
        val body = Brush.linearGradient(
            listOf(Color(0xFFFFE98A), Color(0xFFFFB800), Color(0xFFD97B00)),
            start = Offset(w * 0.2f, 0f),
            end = Offset(w * 0.8f, w)
        )
        drawPath(path, body)
        drawPath(path, body, style = Stroke(width = w * 0.12f, join = StrokeJoin.Round))
        if (t != null) {
            clipPath(path) {
                val x = t.value * w
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.9f), Color.Transparent),
                        start = Offset(x - w * 0.2f, w * 0.1f),
                        end = Offset(x + w * 0.2f, w * 0.4f)
                    )
                )
            }
        }
    }
}

/** A sticker badge if the person wears one, otherwise the animated star. */
@Composable
fun BadgeGlyph(e: String, dim: Dp, shimmer: Boolean = true, sticker: StickerBadge? = null) {
    if (sticker != null && sticker.kind != "video") {
        StickerView(sticker.id, sticker.kind, Modifier.size(dim))
    } else if (e == STAR_BADGE) {
        StarGlyph(dim, shimmer)
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

private class Spark(val x: Float, val y: Float, val s: Float, val phase: Float)

private val SparkList = listOf(
    Spark(0.18f, 0.22f, 9f, 0.00f), Spark(0.80f, 0.18f, 12f, 0.30f),
    Spark(0.10f, 0.58f, 7f, 0.55f), Spark(0.90f, 0.55f, 8f, 0.80f),
    Spark(0.30f, 0.86f, 10f, 0.15f), Spark(0.72f, 0.84f, 7f, 0.65f),
    Spark(0.50f, 0.08f, 6f, 0.45f), Spark(0.24f, 0.40f, 5f, 0.90f),
    Spark(0.78f, 0.40f, 5f, 0.20f)
)

@Composable
private fun Sparkles(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "spark").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "sparkT"
    )
    val gold = Color(0xFFFFC52E)
    Canvas(modifier) {
        val glow = size.minDimension * 0.55f
        drawCircle(
            Brush.radialGradient(listOf(gold.copy(alpha = 0.28f), Color.Transparent), center = center, radius = glow),
            radius = glow,
            center = center
        )
        SparkList.forEach { k ->
            val a = ((kotlin.math.sin((t.value + k.phase) * 2.0 * Math.PI) + 1.0) / 2.0).toFloat()
            val r = k.s.dp.toPx() * (0.55f + 0.45f * a)
            drawPath(
                starPath(size.width * k.x, size.height * k.y, r, r * 0.28f, 4),
                gold.copy(alpha = 0.25f + 0.75f * a)
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* the badge next to a name + the sheet that opens when you tap it     */
/* ------------------------------------------------------------------ */

/** Put right after a name, inside a Row. Nothing is drawn for unverified people. */
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ui.bg) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
                Sparkles(Modifier.matchParentSize())
                BadgeGlyph(e, 112.dp, true, sticker)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (name != null) "$name is verified" else "Verified account",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (sticker == null) "Verified accounts carry the gold star next to their name. It shows in chats, groups and profiles."
                else "${name ?: "This account"} chose this sticker as their badge. Only verified accounts can show a badge next to their name.",
                fontSize = 14.sp,
                color = Ui.muted,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            Text(
                "Got it",
                color = Ui.onInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ui.ink)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 13.dp)
            )
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

// s == null is the default star
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

    // s == null puts the default star back; seconds == null means no end time
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
