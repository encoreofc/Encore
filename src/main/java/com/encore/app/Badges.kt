package com.encore.app

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

class StickerBadge(val id: String, val kind: String)

/* ------------------------------------------------------------------ */
/* store: user id -> badge, only verified people are in here           */
/* ------------------------------------------------------------------ */

object BadgeStore {
    val map = mutableStateMapOf<String, String>()
    val stickers = mutableStateMapOf<String, StickerBadge>()
    private val asked = HashSet<String>()
    private val pending = LinkedHashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false

    fun want(id: String) {
        if (id.isBlank() || id in asked || id in pending) return
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
        asked.addAll(batch)
        runIo({ Api.call("GET", "/v1/badges?ids=" + batch.joinToString(",")) }) { r ->
            r.onSuccess { j ->
                val o = j.optJSONObject("badges")
                val s = j.optJSONObject("stickers")
                if (o != null) for (k in o.keys()) map[k] = o.getString(k)
                for (id in batch) {
                    val sb = s?.optJSONObject(id)
                    if (sb != null) stickers[id] = StickerBadge(sb.getString("id"), sb.getString("kind"))
                    else stickers.remove(id)
                }
            }.onFailure { asked.removeAll(batch.toSet()) }
            if (pending.isNotEmpty()) schedule()
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

/** A sticker badge if the person picked one, otherwise the animated star. */
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
    BadgeGlyph(e, dim, shimmer, BadgeStore.stickers[userId])
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
    val sticker = BadgeStore.stickers[userId]
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
/* picker: the star first, then your own and saved stickers            */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BadgePickerDialog(myId: String, onDismiss: () -> Unit) {
    val currentId = BadgeStore.stickers[myId]?.id
    var list by remember { mutableStateOf<List<Sticker>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runIo({
            (BotApi.stickers("mine") + BotApi.stickers("saved"))
                .filter { it.kind != "video" }
                .distinctBy { it.id }
        }) { r ->
            r.onSuccess { list = it }.onFailure { problem = it.message }
        }
    }

    // s == null puts the default star back
    fun save(s: Sticker?) {
        busy = true
        problem = null
        val body = JSONObject().also { if (s != null) it.put("sticker_id", s.id) }
        runIo({ Api.call("PUT", "/v1/me/badge", body) }) { r ->
            busy = false
            r.onSuccess { j ->
                val st = j.optJSONObject("sticker")
                if (st != null) BadgeStore.stickers[myId] = StickerBadge(st.getString("id"), st.getString("kind"))
                else BadgeStore.stickers.remove(myId)
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
            Text(
                "Shown next to your name in chats, groups and profiles.",
                fontSize = 13.sp,
                color = Ui.muted
            )
            Spacer(Modifier.height(16.dp))

            val l = list
            if (l == null && problem == null) {
                Text("Loading\u2026", color = Ui.muted, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                val cells: List<Sticker?> = listOf<Sticker?>(null) + (l ?: emptyList())
                cells.chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { s ->
                            val on = if (s == null) currentId == null else s.id == currentId
                            val shape = RoundedCornerShape(16.dp)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(shape)
                                    .background(if (on) Ui.soft else Color.Transparent)
                                    .then(if (on) Modifier.border(1.5.dp, Ui.ink, shape) else Modifier)
                                    .clickable(enabled = !busy) { save(s) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (s == null) StarGlyph(34.dp, shimmer = false)
                                else StickerView(s.id, s.kind, Modifier.fillMaxSize().padding(8.dp))
                            }
                        }
                        repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (l != null && l.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Make a sticker with the Sticker Maker bot, or save one from someone, and it shows up here.",
                        fontSize = 13.sp,
                        color = Ui.muted
                    )
                }
            }
            problem?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
