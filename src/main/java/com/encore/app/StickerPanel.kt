package com.encore.app

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ------------------------------------------------------------------ */
/* small local storage: recent stickers + last keyboard height          */
/* ------------------------------------------------------------------ */

object StickerPrefs {
    private fun p(c: Context) = c.getSharedPreferences("encore_stickers", Context.MODE_PRIVATE)

    fun recent(c: Context): List<String> =
        (p(c).getString("recent", "") ?: "").split(",").filter { it.isNotBlank() }

    fun push(c: Context, id: String) {
        val l = (listOf(id) + recent(c).filter { it != id }).take(24)
        p(c).edit().putString("recent", l.joinToString(",")).apply()
    }

    /** last measured keyboard height in dp; the panel opens at exactly this height */
    fun keyboardDp(c: Context): Float = p(c).getFloat("kb", 300f)
    fun setKeyboardDp(c: Context, v: Float) {
        p(c).edit().putFloat("kb", v).apply()
    }
}

/**
 * Kept for the whole app session so the panel opens instantly the second time.
 * Lists refresh quietly in the background every time the panel is opened.
 */
object StickerCache {
    var mine by mutableStateOf<List<Sticker>?>(null)
    var saved by mutableStateOf<List<Sticker>?>(null)
    var recentIds by mutableStateOf<List<String>>(emptyList())

    fun refresh(ctx: Context) {
        recentIds = StickerPrefs.recent(ctx)
        runIo({ BotApi.stickers("mine") }) { r ->
            r.onSuccess { mine = it }.onFailure { if (mine == null) mine = emptyList() }
        }
        runIo({ BotApi.stickers("saved") }) { r ->
            r.onSuccess { saved = it }.onFailure { if (saved == null) saved = emptyList() }
        }
    }

    fun recentStickers(): List<Sticker> {
        val all = ((mine ?: emptyList()) + (saved ?: emptyList())).associateBy { it.id }
        return recentIds.mapNotNull { all[it] }
    }
}

/** one place that decides what a message looks like in a reply quote, the reply bar, etc. */
fun msgPreview(kind: String?, body: String?): String =
    if (kind == "sticker") "Sticker" else (body ?: kindLabel(kind))

/* ------------------------------------------------------------------ */
/* icons (drawn, so no extra icon dependency is needed)                 */
/* ------------------------------------------------------------------ */

@Composable
fun StickerGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        val sw = 1.8.dp.toPx()
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // rounded square with a peeled bottom-right corner
        val body = Path().apply {
            moveTo(w * 0.22f, w * 0.1f)
            lineTo(w * 0.78f, w * 0.1f)
            quadraticTo(w * 0.9f, w * 0.1f, w * 0.9f, w * 0.22f)
            lineTo(w * 0.9f, w * 0.58f)
            lineTo(w * 0.58f, w * 0.9f)
            lineTo(w * 0.22f, w * 0.9f)
            quadraticTo(w * 0.1f, w * 0.9f, w * 0.1f, w * 0.78f)
            lineTo(w * 0.1f, w * 0.22f)
            quadraticTo(w * 0.1f, w * 0.1f, w * 0.22f, w * 0.1f)
            close()
        }
        drawPath(body, tint, style = st)
        // the fold
        val fold = Path().apply {
            moveTo(w * 0.9f, w * 0.58f)
            lineTo(w * 0.7f, w * 0.58f)
            quadraticTo(w * 0.58f, w * 0.58f, w * 0.58f, w * 0.7f)
            lineTo(w * 0.58f, w * 0.9f)
        }
        drawPath(fold, tint, style = st)
        // face
        drawCircle(tint, radius = sw * 0.62f, center = Offset(w * 0.36f, w * 0.38f))
        drawCircle(tint, radius = sw * 0.62f, center = Offset(w * 0.64f, w * 0.38f))
        val smile = Path().apply {
            moveTo(w * 0.32f, w * 0.55f)
            quadraticTo(w * 0.42f, w * 0.68f, w * 0.52f, w * 0.62f)
        }
        drawPath(smile, tint, style = st)
    }
}

@Composable
fun KeyboardGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        val sw = 1.8.dp.toPx()
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.07f, w * 0.2f),
            size = Size(w * 0.86f, w * 0.6f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.14f),
            style = Stroke(width = sw, join = StrokeJoin.Round)
        )
        val r = sw * 0.6f
        listOf(0.27f, 0.42f, 0.58f, 0.73f).forEach { x ->
            drawCircle(tint, r, Offset(w * x, w * 0.38f))
        }
        drawLine(tint, Offset(w * 0.3f, w * 0.62f), Offset(w * 0.7f, w * 0.62f), sw, StrokeCap.Round)
    }
}

/* ------------------------------------------------------------------ */
/* the panel itself                                                     */
/* ------------------------------------------------------------------ */

private val Tabs = listOf("Recent", "Mine", "Saved")

@Composable
private fun TabPill(label: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateFloatAsState(if (on) 1f else 0f, tween(180), label = "pill")
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) Ui.ink else Ui.soft)
            .graphicsLayer { alpha = 0.92f + 0.08f * bg }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (on) Ui.onInk else Ui.muted
        )
    }
}

@Composable
private fun StickerCell(s: Sticker, onSend: (Sticker) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) Ui.soft else Color.Transparent)
            .clickable(interactionSource = src, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSend(s)
            },
        contentAlignment = Alignment.Center
    ) {
        StickerView(s.id, s.kind, Modifier.fillMaxSize().padding(6.dp))
    }
}

@Composable
private fun StickerGrid(list: List<Sticker>?, empty: String, onSend: (Sticker) -> Unit) {
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading\u2026", color = Ui.muted, fontSize = 13.sp)
        }
        list.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(empty, color = Ui.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(list, key = { it.id }) { StickerCell(it, onSend) }
        }
    }
}

@Composable
fun StickerPanel(modifier: Modifier = Modifier, onSend: (Sticker) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { StickerCache.refresh(ctx) }

    val recent = StickerCache.recentStickers()
    var tab by rememberSaveable { mutableIntStateOf(if (StickerPrefs.recent(ctx).isNotEmpty()) 0 else 1) }

    Column(modifier.background(Ui.bg)) {
        HorizontalDivider(color = Ui.line)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tabs.forEachIndexed { i, t -> TabPill(t, tab == i) { tab = i } }
        }
        Crossfade(tab, animationSpec = tween(160), label = "tab", modifier = Modifier.weight(1f)) { t ->
            when (t) {
                0 -> StickerGrid(
                    if (StickerCache.mine == null && StickerCache.saved == null) null else recent,
                    "Stickers you send show up here.",
                    onSend
                )
                1 -> StickerGrid(
                    StickerCache.mine,
                    "Make a sticker with the Sticker Maker bot and it shows up here.",
                    onSend
                )
                else -> StickerGrid(
                    StickerCache.saved,
                    "Save a sticker from someone and it shows up here.",
                    onSend
                )
            }
        }
    }
}
