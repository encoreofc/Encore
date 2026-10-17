package com.encore.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Gifts: models, the backend calls, hand-drawn icons and the pieces
// the profile page uses.
// ---------------------------------------------------------------------------

private val GemYellow = Color(0xFFFFC400)
private val GemLight = Color(0xFFFFE27A)
private val GemDark = Color(0xFFE0A200)

/** One gift a person has received. Limited editions carry a serial and a total. */
data class GiftItem(
    val id: String,
    val name: String,
    val artUrl: String?,          // null -> a drawn gift icon is shown instead
    val gems: Int,                // price in Gems
    val fromId: String?,          // null when the sender chose to stay hidden
    val fromName: String?,
    val sentAt: Long,             // epoch millis
    val message: String? = null,
    val serial: Int? = null,      // "#72" of a limited edition
    val totalSupply: Int? = null  // "of 5,000"
)

data class GemTx(val id: String, val delta: Int, val balance: Int, val reason: String, val at: Long)
data class GemsInfo(val balance: Int, val history: List<GemTx>)

/** Talks to the Encore Worker: received gifts and the Gems wallet. All calls block, so run them inside runIo. */
object GiftsApi {
    // same Worker the rest of the app talks to; swap for the shared base URL constant if the app has one
    private const val HOST = "https://encore-api.jahidhasanofficial397.workers.dev"

    private fun JSONObject.text(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }

    private fun absolute(path: String?): String? = when {
        path == null -> null
        path.startsWith("http") -> path
        else -> HOST + path
    }

    fun parseGift(o: JSONObject): GiftItem {
        val from = o.optJSONObject("from")
        return GiftItem(
            id = o.getString("id"),
            name = o.optString("name", "Gift"),
            artUrl = absolute(o.text("art_url")),
            gems = o.optInt("gems"),
            fromId = from?.text("id"),
            fromName = from?.text("name"),
            sentAt = o.optLong("sent_at"),
            message = o.text("message"),
            serial = if (o.isNull("serial")) null else o.getInt("serial"),
            totalSupply = if (o.isNull("total_supply")) null else o.getInt("total_supply")
        )
    }

    /** Gifts on someone's profile. */
    fun received(userId: String): List<GiftItem> {
        val arr = Api.call("GET", "/v1/users/$userId/gifts").getJSONArray("gifts")
        return List(arr.length()) { parseGift(arr.getJSONObject(it)) }
    }

    /** My balance and recent changes. */
    fun gems(): GemsInfo {
        val o = Api.call("GET", "/v1/me/gems")
        val arr = o.getJSONArray("history")
        return GemsInfo(
            o.getInt("balance"),
            List(arr.length()) {
                val t = arr.getJSONObject(it)
                GemTx(t.getString("id"), t.getInt("delta"), t.getInt("balance"), t.optString("reason"), t.getLong("at"))
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Icons (drawn by hand, like the other glyphs on the page)
// ---------------------------------------------------------------------------

/** The Gems currency icon: a flat six-sided cut stone in the brand yellow. */
@Composable
fun GemGlyph(boxSize: Dp = 16.dp) {
    Canvas(Modifier.size(boxSize)) {
        val w = this.size.width
        val c = Offset(w / 2f, w / 2f)
        val r = w * 0.48f
        fun pt(deg: Double): Offset {
            val a = Math.toRadians(deg)
            return Offset(c.x + (r * Math.cos(a)).toFloat(), c.y + (r * Math.sin(a)).toFloat())
        }
        val top = pt(-90.0)
        val tr = pt(-30.0)
        val br = pt(30.0)
        val bot = pt(90.0)
        val bl = pt(150.0)
        val tl = pt(210.0)
        val body = Path().apply {
            moveTo(top.x, top.y)
            lineTo(tr.x, tr.y)
            lineTo(br.x, br.y)
            lineTo(bot.x, bot.y)
            lineTo(bl.x, bl.y)
            lineTo(tl.x, tl.y)
            close()
        }
        drawPath(body, GemYellow)
        // lighter facet on top
        val lit = Path().apply {
            moveTo(tl.x, tl.y)
            lineTo(top.x, top.y)
            lineTo(tr.x, tr.y)
            lineTo(c.x, c.y)
            close()
        }
        drawPath(lit, GemLight)
        // darker facet on the lower right
        val shade = Path().apply {
            moveTo(c.x, c.y)
            lineTo(tr.x, tr.y)
            lineTo(br.x, br.y)
            lineTo(bot.x, bot.y)
            close()
        }
        drawPath(shade, GemDark)
    }
}

/** A gift box with a bow, drawn as lines so it takes any colour. */
@Composable
fun GiftGlyph(color: Color, boxSize: Dp = 24.dp) {
    Canvas(Modifier.size(boxSize)) {
        val w = this.size.width
        val sw = w * 0.08f
        // lid
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.12f, w * 0.34f),
            size = Size(w * 0.76f, w * 0.16f),
            cornerRadius = CornerRadius(w * 0.04f),
            style = Stroke(width = sw)
        )
        // box
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.18f, w * 0.50f),
            size = Size(w * 0.64f, w * 0.36f),
            cornerRadius = CornerRadius(w * 0.04f),
            style = Stroke(width = sw)
        )
        // ribbon
        drawLine(
            color,
            start = Offset(w * 0.5f, w * 0.34f),
            end = Offset(w * 0.5f, w * 0.86f),
            strokeWidth = sw
        )
        // bow
        drawOval(
            color,
            topLeft = Offset(w * 0.26f, w * 0.14f),
            size = Size(w * 0.24f, w * 0.20f),
            style = Stroke(width = sw)
        )
        drawOval(
            color,
            topLeft = Offset(w * 0.50f, w * 0.14f),
            size = Size(w * 0.24f, w * 0.20f),
            style = Stroke(width = sw)
        )
    }
}

// ---------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------

/** The dark rounded button used for the main gift actions. */
@Composable
fun GiftDarkButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Ui.ink)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            color = Ui.onInk,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The sender mark in a tile corner: their avatar, or a dark "?" when they stayed hidden. */
@Composable
private fun GiftSender(g: GiftItem, size: Dp) {
    if (g.fromId != null) {
        Avatar(g.fromId, g.fromName ?: "?", size)
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Ui.ink),
            contentAlignment = Alignment.Center
        ) {
            Text("?", color = Ui.onInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The picture of a gift: the real art when there is one (it needs the login header), a drawn gift box otherwise. */
@Composable
private fun GiftArt(g: GiftItem, size: Dp) {
    val art = g.artUrl
    if (art != null) {
        val ctx = LocalContext.current
        val req = remember(art) {
            ImageRequest.Builder(ctx)
                .data(art)
                .addHeader("Authorization", "Bearer ${Session.token}")
                .crossfade(true)
                .build()
        }
        AsyncImage(
            model = req,
            contentDescription = g.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size)
        )
    } else {
        GiftGlyph(Ui.muted, size)
    }
}

@Composable
private fun GiftTile(g: GiftItem, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Ui.soft)
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.align(Alignment.Center)) { GiftArt(g, 52.dp) }
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) { GiftSender(g, 22.dp) }
        val serial = g.serial
        if (serial != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ui.ink)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text("#$serial", color = Ui.onInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The parts the profile page uses
// ---------------------------------------------------------------------------

private const val GIFTS_PREVIEW = 6

/**
 * The Gifts block on a profile. [gifts] is null while loading, empty when there are none.
 * Shows two rows of three, with a switch to show everything.
 */
@Composable
fun GiftsSection(
    gifts: List<GiftItem>?,
    peerName: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpen: (GiftItem) -> Unit,
    onSend: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Gifts", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (!gifts.isNullOrEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Ui.soft)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("${gifts.size}", fontSize = 12.sp, color = Ui.muted, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.weight(1f))
            if (gifts != null && gifts.size > GIFTS_PREVIEW) {
                Text(
                    if (expanded) "Show less" else "View all gifts",
                    fontSize = 13.sp,
                    color = Ui.muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleExpanded)
                        .padding(4.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            gifts == null -> {
                // keeps the room of the block while it loads so the page does not jump
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Ui.soft)
                )
            }
            gifts.isEmpty() -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Ui.soft)
                        .clickable(onClick = onSend)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GiftGlyph(Ui.ink, 30.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("No gifts yet", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (peerName.isBlank()) "Be the first to send one" else "Be the first to send one to $peerName",
                            fontSize = 13.sp,
                            color = Ui.muted
                        )
                    }
                }
            }
            else -> {
                val shown = if (expanded) gifts else gifts.take(GIFTS_PREVIEW)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    shown.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            row.forEach { g -> GiftTile(g, Modifier.weight(1f)) { onOpen(g) } }
                            // an unfinished last row keeps the same tile size
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GiftInfoRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = Ui.muted, modifier = Modifier.width(72.dp))
        content()
    }
}

/** The sheet that opens when a gift is tapped: picture, who sent it, when, and what it is worth. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftDetailSheet(gift: GiftItem, onDismiss: () -> Unit, onSendOne: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val whenText = remember(gift.id) {
        java.text.SimpleDateFormat("d MMM yyyy 'at' h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(gift.sentAt))
    }
    val nf = remember { java.text.NumberFormat.getInstance() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Ui.bg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GiftArt(gift, 140.dp)
            Spacer(Modifier.height(12.dp))
            Text(gift.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            val serial = gift.serial
            if (serial != null) {
                val total = gift.totalSupply
                Spacer(Modifier.height(2.dp))
                Text(
                    if (total != null) "#${nf.format(serial)} of ${nf.format(total)}" else "#${nf.format(serial)}",
                    fontSize = 14.sp,
                    color = Ui.muted
                )
            }
            Spacer(Modifier.height(18.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Ui.soft)
            ) {
                GiftInfoRow("From") {
                    if (gift.fromId != null) {
                        GiftSender(gift, 24.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            gift.fromName ?: "Unknown",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text("Anonymous", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                HorizontalDivider(color = Ui.line)
                GiftInfoRow("Date") { Text(whenText, fontSize = 15.sp) }
                HorizontalDivider(color = Ui.line)
                GiftInfoRow("Value") {
                    GemGlyph(18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(nf.format(gift.gems), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                val note = gift.message
                if (!note.isNullOrBlank()) {
                    HorizontalDivider(color = Ui.line)
                    GiftInfoRow("Note") { Text(note, fontSize = 15.sp) }
                }
            }
            Spacer(Modifier.height(18.dp))
            GiftDarkButton("Send one too", onSendOne, Modifier.fillMaxWidth()) {
                GiftGlyph(Ui.onInk, 22.dp)
            }
        }
    }
}

/** The bar fixed at the bottom of the profile, so sending a gift is always one tap away. */
@Composable
fun SendGiftBar(name: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(Ui.bg)) {
        HorizontalDivider(color = Ui.line)
        GiftDarkButton(
            "Send a gift to $name",
            onClick,
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) { GiftGlyph(Ui.onInk, 22.dp) }
    }
}
